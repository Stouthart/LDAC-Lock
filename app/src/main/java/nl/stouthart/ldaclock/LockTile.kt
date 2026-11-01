package nl.stouthart.ldaclock

import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.A2DP
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

// RedMagic's (nubia) round tiles hide the subtitle, so there the tile's name carries the status.
private val NAME_SHOWS_STATUS = Build.MANUFACTURER.equals("nubia", ignoreCase = true)

/** The Quick Settings tile: a tap locks; the subtitle (on nubia: the name) shows the last result. */
class LockTile : TileService(), BluetoothProfile.ServiceListener {
    private val adapter by lazy { getSystemService(BluetoothManager::class.java).adapter }
    private val refresh = Runnable { update() }
    private var t0 = 0L
    private var registered = false // Some vendors' panels may stop listening without having started.

    // Bluetooth toggled while the panel is open (its own tile is usually right next to this one).
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update()
    }

    override fun onStartListening() {
        Last.listeners += refresh
        if (!registered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            registerReceiver(receiver, filter, BLUETOOTH_PRIVILEGED, null, RECEIVER_EXPORTED)
            registered = true
        }
        update()
        // Only a tile that claims a lock needs checking: one reported-config read, no dumpsys.
        if (Last.ok(this) && adapter.isEnabled) {
            t0 = SystemClock.elapsedRealtimeNanos()
            adapter.getProfileProxy(this, this, A2DP)
        }
    }

    override fun onStopListening() {
        if (registered) {
            unregisterReceiver(receiver)
            registered = false
        }
        Last.listeners -= refresh
        Last.notice = null
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        val t1 = SystemClock.elapsedRealtimeNanos()
        val a2dp = proxy as BluetoothA2dp
        val c = try {
            pairedHeadphone(a2dp)?.let { a2dp.codecStatus(it)?.codecConfig }
        } catch (e: SecurityException) {
            null
        }
        adapter.closeProfileProxy(A2DP, a2dp)
        if (!c.lockedAt(Last.hz(this), quality = Phone.path(this)?.hardware == true)) Last.expire(this)
        val t2 = SystemClock.elapsedRealtimeNanos()
        Log.i(TAG, "Tile check: proxy ${(t1 - t0) / 1000} µs, read ${(t2 - t1) / 1000} µs")
    }

    override fun onServiceDisconnected(profile: Int) {}

    override fun onClick() {
        if (getSystemService(CompanionDeviceManager::class.java).myAssociations.isEmpty()) return openApp()
        try {
            startForegroundService(Intent(this, LockService::class.java))
        } catch (e: Exception) { // Android refused a background start: the app's Lock button still works.
            Log.w(TAG, "Tile start", e)
            return openApp()
        }
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            subtitle = getString(R.string.locking)
            name(R.string.locking)
            updateTile()
        }
    }

    private fun Tile.name(res: Int) {
        if (NAME_SHOWS_STATUS) label = getString(res)
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    }

    private fun update() {
        val t = qsTile ?: return
        if (!adapter.isEnabled) {
            // Greyed out, and the system ignores taps; the saved lock state stays as it is.
            t.state = Tile.STATE_UNAVAILABLE
            t.subtitle = getString(R.string.bluetooth_off)
            t.name(R.string.bluetooth_off)
        } else {
            val ok = Last.ok(this)
            t.state = if (ok) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            t.subtitle = Last.notice ?: Last.tile(this)
            t.name(
                when {
                    Last.notice != null -> R.string.tile_pause // The only notice: a tap refused during playback.
                    ok -> R.string.tile_locked
                    else -> R.string.app_name
                },
            )
        }
        t.updateTile()
    }
}
