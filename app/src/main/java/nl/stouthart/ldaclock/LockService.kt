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
        try {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Last.save(this, false, getString(R.string.no_bluetooth), getString(R.string.failed))
                return
            }
            val device = activeHeadphone(a2dp)
            if (device == null || !isPaired(device)) {
                val why = getString(if (device == null) R.string.no_device else R.string.not_paired)
                Last.save(this, false, why, getString(R.string.failed))
                return
            }
            val t0 = SystemClock.elapsedRealtimeNanos()
            val status = a2dp.codecStatus(device)
            // Detected once per firmware, while LDAC is the current codec; later locks reuse it.
            var path = Phone.path(this)
            var detect = 0L
            if (path == null && status?.codecConfig?.isLdac == true) {
                path = detectPath()?.also { Phone.save(this, it) }
                detect = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
            }
            val hardware = path?.hardware == true
            // Unknown (no DUMP) could be a hardware phone, where a change during playback means silence.
            if (path?.hardware != false && a2dp.isA2dpPlaying(device)) {
                Last.notice(this, getString(R.string.pause_first))
                return
            }
            val plan = plan(status, path)
            val steps = Hop(this, a2dp, device, plan.steps, resend = !hardware).run()
            val t1 = SystemClock.elapsedRealtimeNanos()
            val e = if (hardware) null else ldacEncoder() // The DSP's encoder isn't in the dump.
            val took = (t1 - t0) / 1_000_000
            Log.i(
                TAG, "Hop ${device.address.takeLast(5)} $took ms (${if (hardware) "hardware" else "software"} ${plan.hz} Hz, " +
                    "detect $detect µs; $steps), dumpsys ${(SystemClock.elapsedRealtimeNanos() - t1) / 1_000_000} ms: $e",
            )
            when {
                // No encoder block (Astra, or no DUMP): the reported config is all there is.
                e == null -> {
                    val c = a2dp.codecStatus(device)?.codecConfig
                    val ok = c.lockedAt(plan.hz, quality = hardware)
                    Last.save(
                        this, ok, c?.label().orEmpty(), getString(if (ok) R.string.locked else R.string.failed), took, plan.hz,
                    )
                }
                e.lockedAt(plan.hz) -> Last.save(this, true, e.toString(), getString(R.string.kbps, e.kbps), took, plan.hz)
                else -> Last.save(this, false, e.toString(), e.quality, took, plan.hz)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Lock", e)
            Last.save(this, false, getString(R.string.not_paired), getString(R.string.failed))
        } catch (e: Exception) {
            Log.w(TAG, "Lock", e)
            Last.save(this, false, e.message ?: getString(R.string.failed), getString(R.string.failed))
        }
    }
}
