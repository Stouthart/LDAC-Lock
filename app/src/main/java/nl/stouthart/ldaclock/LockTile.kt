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
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    // While the panel is open: Bluetooth toggled (its own tile is usually right next to this one); or the headphones
    // (dis)connecting, another pair becoming the active one, or the codec changing by itself, as HIGH falling back to
    // MID, checked once their notices stopped.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Bluetooth on or off: as it shows now, and once it's on, whether the lock still holds. While it was off,
            // check() ignored the headphones disconnecting, so the saved lock could show again for no headphones.
            if (i.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                update()
                check()
                return
            }
            main.removeCallbacks(look)
            main.postDelayed(look, CODEC_QUIET_MS)
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val look = Runnable { check() }
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    // While the panel is open: any app's playback starting or stopping. Once nothing plays, the encoder a check skipped
    // during playback is read, as on the screen; a dump then can't make the audio skip.
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            main.removeCallbacks(fresh)
            main.postDelayed(fresh, FRESH_MS)
        }
    }
    private val fresh = Runnable { if (!audio.isMusicActive) check() }
    private var checking = false // One check at a time: a pull-down and a notice don't both dump. Main thread only.
    private var again = false // Asked for while one ran, too late for its read: one more once it's done. Main thread only.
    private var about = 0L // The lock a check is about, by when it was saved: one saved meanwhile isn't its to expire.
    private var checks = 0 // Counts the panel closing: a read still out after that is for no check any more.

    override fun onStartListening() {
        Last.listeners += refresh
        if (!registered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(ACTION_ACTIVE_DEVICE_CHANGED)
                addAction(ACTION_CODEC_CONFIG_CHANGED)
            }
            registerReceiver(receiver, filter, BLUETOOTH_PRIVILEGED, null, RECEIVER_EXPORTED)
            audio.registerAudioPlaybackCallback(playback, main)
            registered = true
        }
        update()
        check()
    }

    // Only a tile that claims a lock needs checking: one reported-config read, and on a software encoder its dump. Not
    // while a lock runs: its own steps change the codec too, and its result is the news.
    private fun check() {
        if (checking) {
            again = true
            return
        }
        if (Last.isLocking || !Last.current(this) || !adapter.isEnabled) return
        checking = true
        about = Last.at(this)
        t0 = SystemClock.elapsedRealtimeNanos()
        if (!adapter.getProfileProxy(this, this, A2DP)) done()
    }

    /** The check ended: one asked for meanwhile runs now. */
    private fun done() {
        checking = false
        if (again) {
            again = false
            check()
        }
    }

    /** No lock saved since [about] (the check's), and none running now: what the check read is still news. */
    private fun still(about: Long) = Last.at(this) == about && !Last.isLocking

    /** The check about [about] found the lock gone: so it is, unless another lock was saved since, or one runs now. */
    private fun expire(about: Long) {
        if (still(about)) Last.expire(this)
    }

    override fun onStopListening() {
        if (registered) {
            unregisterReceiver(receiver)
            audio.unregisterAudioPlaybackCallback(playback)
            registered = false
        }
        main.removeCallbacks(look)
        main.removeCallbacks(fresh)
        Last.listeners -= refresh
        Last.notice = null
        checking = false // A proxy that never answered doesn't stop the next pull-down's check.
        again = false
        checks++
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        val t1 = SystemClock.elapsedRealtimeNanos()
        val a2dp = proxy as BluetoothA2dp
        val d = try {
            pairedHeadphones(a2dp)
        } catch (e: SecurityException) {
            null
        }
        val c = try {
            d?.let { a2dp.codecStatus(it)?.codecConfig }
        } catch (e: SecurityException) {
            null
        }
        adapter.closeProfileProxy(A2DP, a2dp)
        if (!checking) return // A pull-down's proxy after onStopListening, or a second one: one check at a time.
        again = false // This read came after any notice so far: only one during the dump below needs another.
        val n = checks
        val a = about // Its own: a later check sets the field anew.
        val hz = Last.hz(this)
        val q = Last.quality(this)
        val hardware = Phone.path(this)?.hardware == true
        val dump = dumpable()
        val r = c.reported()
        val t2 = SystemClock.elapsedRealtimeNanos()
        Log.i(TAG, "Tile check: proxy ${(t1 - t0) / 1000} µs, read ${(t2 - t1) / 1000} µs")
        if (verdict(null, r, hz, q, hardware, dump) == Verdict.NOT_LOCKED) {
            expire(a)
            return done()
        }
        // Samsung's report can keep saying HIGH after its encoder went adaptive: its dump tells (~40 ms, off the main
        // thread, only while a lock is claimed). A failed read proves nothing, so it leaves the tile as it is.
        if (hardware || !dump) return done()
        // While music plays, a dump makes Samsung's audio skip: the encoder as last read stands if the report is the
        // same (none kept: the tile stays as it is). Once nothing plays, it's read again (see fresh).
        if (audio.isMusicActive) {
            val e = d?.let { LastRead.get(this, it.address, r) }
            if (e != null && verdict(e, r, hz, q, false, true) == Verdict.NOT_LOCKED) expire(a)
            return done()
        }
        Thread {
            val e = if (audio.isMusicActive) null else ldacEncoder() // Music that started since: no dump either.
            mainExecutor.execute {
                if (n != checks) return@execute // The panel closed meanwhile: the next pull-down checks anew.
                // A notice during the dump may be newer than what it read: the check it asked for reads anew instead.
                if (e == null || again) return@execute done()
                if (d != null && still(a)) LastRead.save(this, d.address, r, e) // Not over a newer lock's own read.
                if (verdict(e, r, hz, q, false, true) == Verdict.NOT_LOCKED) expire(a)
                done()
            }
        }.start()
    }

    override fun onServiceDisconnected(profile: Int) {}

    override fun onClick() {
        if (getSystemService(CompanionDeviceManager::class.java).myAssociations.isEmpty()) return openApp()
        Last.locking()
        try {
            startForegroundService(Intent(this, LockService::class.java))
        } catch (e: Exception) { // Android refused a background start: the app's Lock button still works.
            Log.w(TAG, "Tile start", e)
            Last.locked()
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
            val ok = Last.current(this)
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
