package nl.stouthart.ldaclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

private const val CHANNEL = "lock"
private const val PROXY_TIMEOUT_MS = 5000L

/**
 * Runs one hop in the foreground, so Android can't freeze the app halfway once the
 * Quick Settings panel closes. Started by the tile and the Lock button.
 */
class LockService : Service(), BluetoothProfile.ServiceListener {
    private val adapter by lazy { getSystemService(BluetoothManager::class.java).adapter }
    private val main = Handler(Looper.getMainLooper())
    private var busy = false // A start is being handled; later taps join it.
    private var ran = false // The proxy was used, or the watchdog gave up: one lock per start, never two.

    // Bluetooth turning off at the wrong moment, or a hung stack: never wait for the proxy forever.
    private val watchdog = Runnable {
        if (!ran) {
            ran = true
            Last.save(this, false, getString(R.string.bluetooth_timeout), getString(R.string.failed))
            stopSelf()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(getString(R.string.locking))
            .build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) { // Refused, e.g. Nearby devices revoked and no pairing.
            Log.w(TAG, "startForeground", e)
            Last.save(this, false, e.message ?: getString(R.string.failed), getString(R.string.failed))
            stopSelf()
            return START_NOT_STICKY
        }
        if (!busy) {
            busy = true
            // With Bluetooth off the proxy never connects, which would leave this service running for good.
            if (!adapter.isEnabled || !adapter.getProfileProxy(this, this, BluetoothProfile.A2DP)) {
                ran = true
                Last.save(this, false, getString(R.string.bluetooth_off), getString(R.string.failed))
                stopSelf()
            } else {
                main.postDelayed(watchdog, PROXY_TIMEOUT_MS)
            }
        }
        return START_NOT_STICKY
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        val a2dp = proxy as BluetoothA2dp
        // A Bluetooth restart reconnects the proxy; that must not start a second hop.
        if (ran) return adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp)
        ran = true
        main.removeCallbacks(watchdog)
        Thread {
            lock(a2dp)
            adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp)
            stopSelf()
        }.start()
    }

    override fun onServiceDisconnected(profile: Int) {}

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        super.onDestroy()
    }

    private fun lock(a2dp: BluetoothA2dp) {
        // The target a failure is saved with: the last lock's, until this one is planned; then its own, which a late
        // step may still have reached.
        var hz = Last.hz(this)
        var q = Last.quality(this)
        try {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Last.save(this, false, getString(R.string.no_bluetooth), getString(R.string.failed))
                return
            }
            val device = activeHeadphones(a2dp)
            if (device == null || !isPaired(device)) {
                val why = getString(if (device == null) R.string.no_device else R.string.not_paired)
                Last.save(this, false, why, getString(R.string.failed))
                return
            }
            val t0 = SystemClock.elapsedRealtimeNanos()
            val status = a2dp.codecStatus(device)
            // Detected once per boot and firmware, while LDAC is the current codec; later locks reuse it.
            var path = Phone.path(this, sameBoot = true)
            var detect = 0L
            if (path == null && status?.codecConfig?.isLdac == true) {
                path = detectPath()?.also { Phone.save(this, it) }
                detect = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
            }
            val hardware = path?.hardware == true
            // Unknown (no DUMP) could be a hardware phone, where a change during playback means silence. Asked before
            // the hop, and again right before its first change (Hop). Not between a detour's steps: one stopped halfway
            // would leave AAC on.
            if (path?.hardware != false && a2dp.isA2dpPlaying(device)) {
                Last.notice(this, getString(R.string.pause_first))
                return
            }
            val want = Quality.target(this)
            val plan = plan(status, path, Rate.target(this, path), want)
            if (plan == null) { // Nothing is changed: a detour that can't end on LDAC would leave a worse codec.
                Last.save(this, false, getString(R.string.no_ldac), getString(R.string.failed))
                return
            }
            hz = plan.hz
            q = want
            val steps = Hop(
                this, a2dp, device, plan.steps, resend = !hardware, readable = hardware, pauseRequired = path?.hardware != false,
            ).run()
            val t1 = SystemClock.elapsedRealtimeNanos()
            val e = if (hardware) null else ldacEncoder() // The DSP's encoder isn't in the dump.
            val took = (t1 - t0) / 1_000_000
            Log.i(
                TAG, "Hop ${device.address.takeLast(5)} $took ms (${if (hardware) "hardware" else "software"} ${plan.hz} Hz " +
                    "${Quality.name(q)}, detect $detect µs; $steps), dumpsys ${(SystemClock.elapsedRealtimeNanos() - t1) / 1_000_000} ms: $e",
            )
            // Still the headphones it locked: after a switch meanwhile, the dump's one encoder is another pair's.
            if (activeHeadphones(a2dp) != device) {
                return Last.save(this, false, getString(R.string.switched_text), getString(R.string.failed), took, plan.hz, q)
            }
            val c = a2dp.codecStatus(device)?.codecConfig
            if (e != null) LastRead.save(this, device.address, c.reported(), e) // For while music plays: see LastRead.
            when (verdict(e, c.reported(), plan.hz, q, hardware, dumpable())) {
                Verdict.LOCKED -> Last.save(this, true, e.toString(), getString(R.string.kbps, e?.kbps ?: 0), took, plan.hz, q)
                // The Astra: its report is all there is, and faithful there.
                Verdict.REPORTED -> Last.save(this, true, c?.label().orEmpty(), getString(R.string.locked), took, plan.hz, q)
                Verdict.NOT_LOCKED -> Last.save(
                    this, false, e?.toString() ?: c?.label().orEmpty(), e?.quality ?: getString(R.string.failed), took, plan.hz, q,
                )
                // A software encoder with DUMP granted should be in the dump: without it, the quality is unknown.
                Verdict.UNVERIFIED -> Last.save(
                    this, false, getString(R.string.unverified_text), getString(R.string.unverified), took, plan.hz, q,
                )
                Verdict.NO_DUMP -> Last.save(
                    this, false, getString(R.string.no_dump_text), getString(R.string.unverified), took, plan.hz, q,
                )
            }
        } catch (e: PlaybackStarted) {
            // As when it played at the tap: no change was made, so a notice, not a failure.
            Last.notice(this, getString(R.string.pause_first))
        } catch (e: SecurityException) {
            Log.w(TAG, "Lock", e)
            Last.save(this, false, getString(R.string.not_paired), getString(R.string.failed), 0, hz, q)
        } catch (e: Exception) {
            Log.w(TAG, "Lock", e)
            Last.save(this, false, e.message ?: getString(R.string.failed), getString(R.string.failed), 0, hz, q)
        }
    }
}
