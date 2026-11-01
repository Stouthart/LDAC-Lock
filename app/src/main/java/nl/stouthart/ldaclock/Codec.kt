// The int codec types are deprecated for BluetoothCodecType, which has no constructor: its objects
// cost a getSupportedCodecTypes() call, and it has two LDAC ids. The int path is what the stack logs.
@file:Suppress("DEPRECATION")

package nl.stouthart.ldaclock

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecConfig.BITS_PER_SAMPLE_16
import android.bluetooth.BluetoothCodecConfig.BITS_PER_SAMPLE_24
import android.bluetooth.BluetoothCodecConfig.BITS_PER_SAMPLE_32
import android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_176400
import android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_192000
import android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_44100
import android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_48000
import android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_88200
import android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_96000
import android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC
import android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX
import android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD
import android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
import android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val TAG = "LDACLock"

// Hidden broadcasts of the A2DP stack.
private const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
const val ACTION_ACTIVE_DEVICE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
const val QUALITY_990 = 1000L // LDAC codecSpecific1: 1000 = 990 kbps … 1003 = adaptive.
private const val STEP_TIMEOUT_MS = 5000L

// Hidden from the SDK; reflection reaches them (BCC does the same).
private val getCodecStatus = BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java)
private val setCodecConfigPreference = BluetoothA2dp::class.java
    .getMethod("setCodecConfigPreference", BluetoothDevice::class.java, BluetoothCodecConfig::class.java)

private fun config(type: Int, rate: Int, bits: Int, quality: Long): BluetoothCodecConfig =
    BluetoothCodecConfig.Builder()
        .setCodecType(type)
        .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
        .setSampleRate(rate)
        .setBitsPerSample(bits)
        .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_STEREO)
        .setCodecSpecific1(quality)
        .build()

/**
 * How this phone encodes LDAC. Software (the Bluetooth stack: S25, Fold) needs the AAC hop and its mixer
 * follows the codec. Hardware (the audio DSP: Astra) goes silent on any codec change while a stream runs
 * (the HAL can't reconfigure the offload path live), so it's changed only while paused; its mixer may be
 * fixed at 48 kHz.
 */
class Path(val hardware: Boolean, val hz: Int)

/** Detects the path; needs DUMP and LDAC as the current codec. Null if it can't tell. */
fun Context.detectPath(): Path? {
    if (checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) return null
    // Software LDAC leaves an "A2DP LDAC State" block in the Bluetooth dump; hardware LDAC leaves none.
    if (dumpsys("bluetooth_manager") { r -> skipTo(r, "A2DP LDAC State:") } ?: return null) return Path(false, 44100)
    // Behind a fixed 48 kHz mixer, LDAC at 44.1 kHz means a second resample (48 → 44.1) in the DSP.
    return Path(true, if (a2dpMixerHz() == 48000) 48000 else 44100)
}

/** The sample rate of the mixer thread feeding A2DP, from the audio_flinger dump (~10–16 ms). */
private fun a2dpMixerHz() = dumpsys("media.audio_flinger") { r ->
    var mixer = false
    var hz = 0
    while (true) {
        val l = r.readLine() ?: break
        when {
            l.startsWith("Output thread ") -> {
                mixer = l.endsWith("(MIXER):")
                hz = 0
            }
            l.startsWith("  Sample rate: ") -> hz = l.substring(15).substringBefore(' ').toIntOrNull() ?: 0
            mixer && l.startsWith("  Output devices:") && "BLUETOOTH_A2DP" in l -> return@dumpsys hz
        }
    }
    null
}

/** Reads up to and including [line]; false if the output ends first. */
private fun skipTo(r: BufferedReader, line: String): Boolean {
    while (true) if ((r.readLine() ?: return false) == line) return true
}

/** The steps and the LDAC rate they end on. */
class Plan(val steps: List<BluetoothCodecConfig>, val hz: Int)

/**
 * Builds the steps from the phone's path and what the headphone can select.
 * Software or unknown: AAC → 32-bit → 24-bit at 44.1 kHz. Hardware: one LDAC change, at the mixer's rate.
 */
fun plan(s: BluetoothCodecStatus?, path: Path?): Plan {
    val caps = s?.codecsSelectableCapabilities.orEmpty()
    val ldac = caps.firstOrNull { it.isLdac }
    val want = if (path?.hz == 48000) SAMPLE_RATE_48000 else SAMPLE_RATE_44100
    // Fall back to the other of 44.1/48 kHz only if the headphone doesn't offer the wanted one.
    val rate = if (ldac == null || (ldac.sampleRate and want) != 0) want
    else if (want == SAMPLE_RATE_44100) SAMPLE_RATE_48000 else SAMPLE_RATE_44100
    val hz = if (rate == SAMPLE_RATE_44100) 44100 else 48000
    if (path?.hardware == true) return Plan(listOf(config(SOURCE_CODEC_TYPE_LDAC, rate, BITS_PER_SAMPLE_24, QUALITY_990)), hz)
    // A different codec forces a fresh stream; SBC is the fallback every headphone has.
    val via = if (caps.any { it.codecType == SOURCE_CODEC_TYPE_AAC }) SOURCE_CODEC_TYPE_AAC else SOURCE_CODEC_TYPE_SBC
    val steps = ArrayList<BluetoothCodecConfig>(3)
    steps += config(via, SAMPLE_RATE_44100, BITS_PER_SAMPLE_16, 0)
    // The 32 → 24-bit change makes the S25's stack pass the 990 quality on; skipped where 32-bit isn't offered.
    if (ldac == null || (ldac.bitsPerSample and BITS_PER_SAMPLE_32) != 0) {
        steps += config(SOURCE_CODEC_TYPE_LDAC, rate, BITS_PER_SAMPLE_32, QUALITY_990)
    }
    steps += config(SOURCE_CODEC_TYPE_LDAC, rate, BITS_PER_SAMPLE_24, QUALITY_990)
    return Plan(steps, hz)
}

/** Reflection wraps the real exception (a SecurityException when not paired); unwrap it. */
private fun call(m: Method, a2dp: BluetoothA2dp, vararg args: Any): Any? =
    try {
        m.invoke(a2dp, *args)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }

fun BluetoothA2dp.codecStatus(d: BluetoothDevice) = call(getCodecStatus, this, d) as BluetoothCodecStatus?

private fun BluetoothCodecConfig?.matches(t: BluetoothCodecConfig?) = this != null && t != null &&
    codecType == t.codecType && sampleRate == t.sampleRate && bitsPerSample == t.bitsPerSample

// Hidden as well; BCC uses it too. Null if this firmware hides it, then the first connected headphone is used.
private val getActiveDevice: Method? = try {
    BluetoothA2dp::class.java.getMethod("getActiveDevice")
} catch (e: Exception) {
    null
}

/** The headphone Android plays media to; with two connected, only that one matters. */
fun Context.activeHeadphone(a2dp: BluetoothA2dp): BluetoothDevice? {
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
    val active = try {
        getActiveDevice?.invoke(a2dp) as BluetoothDevice?
    } catch (e: Exception) {
        null
    }
    return active ?: a2dp.connectedDevices.firstOrNull()
}

/** This app is paired with [d] as a companion device. */
fun Context.isPaired(d: BluetoothDevice) = getSystemService(CompanionDeviceManager::class.java).myAssociations
    .any { it.deviceMacAddress?.toString().equals(d.address, ignoreCase = true) }

/** The active headphone, if this app is paired with it. */
fun Context.pairedHeadphone(a2dp: BluetoothA2dp) = activeHeadphone(a2dp)?.takeIf { isPaired(it) }

fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000} kHz" else "${hz / 1000.0} kHz"

/** "44.1 kHz · 24-bit · Stereo · 909 kbps", in BCC's order; kbps only when known. */
fun specText(hz: Int, bits: Int, mode: String, kbps: Int = 0) =
    if (kbps > 0) "${khz(hz)} · $bits-bit · $mode · $kbps kbps" else "${khz(hz)} · $bits-bit · $mode"

val BluetoothCodecConfig.isLdac get() = codecType == SOURCE_CODEC_TYPE_LDAC

val BluetoothCodecConfig.codecLabel
    get() = when (codecType) {
        SOURCE_CODEC_TYPE_SBC -> "SBC"
        SOURCE_CODEC_TYPE_AAC -> "AAC"
        SOURCE_CODEC_TYPE_APTX -> "aptX"
        SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
        SOURCE_CODEC_TYPE_LDAC -> "LDAC"
        else -> "Codec $codecType"
    }

val BluetoothCodecConfig.hz
    get() = when (sampleRate) {
        SAMPLE_RATE_44100 -> 44100
        SAMPLE_RATE_48000 -> 48000
        SAMPLE_RATE_88200 -> 88200
        SAMPLE_RATE_96000 -> 96000
        SAMPLE_RATE_176400 -> 176400
        SAMPLE_RATE_192000 -> 192000
        else -> 0
    }

val BluetoothCodecConfig.bits
    get() = when (bitsPerSample) {
        BITS_PER_SAMPLE_16 -> 16
        BITS_PER_SAMPLE_24 -> 24
        BITS_PER_SAMPLE_32 -> 32
        else -> 0
    }

val BluetoothCodecConfig.mode get() = if (channelMode == BluetoothCodecConfig.CHANNEL_MODE_MONO) "Mono" else "Stereo"

fun BluetoothCodecConfig.label() = "$codecLabel · ${specText(hz, bits, mode)}"

/**
 * The reported config matches the lock. Its quality is checked only where the stack reports it faithfully
 * ([quality]: the hardware path); on the S25 it can say 1000 while the encoder runs adaptive.
 */
fun BluetoothCodecConfig?.lockedAt(hz: Int, quality: Boolean) = this != null && isLdac && this.hz == hz &&
    bits == 24 && (!quality || codecSpecific1 == QUALITY_990)

/** Runs the hop on the calling thread, which it blocks; throws if a step isn't confirmed in time. */
class Hop(
    private val ctx: Context,
    private val a2dp: BluetoothA2dp,
    private val device: BluetoothDevice,
    private val steps: List<BluetoothCodecConfig>,
    private val resend: Boolean, // Also send a step that already matches (its quality may not have landed).
) : BroadcastReceiver() {
    @Volatile private var done = CountDownLatch(1)
    @Volatile private var target: BluetoothCodecConfig? = null

    override fun onReceive(c: Context, i: Intent) {
        if (i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) == device &&
            i.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus::class.java)
                ?.codecConfig.matches(target)
        ) done.countDown()
    }

    /** Returns one line for the log: each step's time in µs and what ended its wait. */
    fun run(): String {
        // Only a sender holding BLUETOOTH_PRIVILEGED (the Bluetooth stack) can end a wait.
        ctx.registerReceiver(
            this, IntentFilter(ACTION_CODEC_CONFIG_CHANGED), Manifest.permission.BLUETOOTH_PRIVILEGED, null,
            Context.RECEIVER_EXPORTED,
        )
        val s = StringBuilder()
        try {
            for ((n, t) in steps.withIndex()) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                done = CountDownLatch(1)
                target = t
                val cur = a2dp.codecStatus(device)?.codecConfig
                val already = cur.matches(t) // Codec, rate and bits: no broadcast will come, so don't wait.
                val same = already && cur?.codecSpecific1 == t.codecSpecific1
                if (!same || resend) call(setCodecConfigPreference, a2dp, device, t)
                val how = when {
                    same && !resend -> "already, not sent"
                    already -> "already"
                    done.await(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS) -> "broadcast"
                    a2dp.codecStatus(device)?.codecConfig.matches(t) -> "status"
                    else -> throw IllegalStateException("Step ${n + 1} not confirmed")
                }
                if (n > 0) s.append(", ")
                s.append(n + 1).append(": ").append((SystemClock.elapsedRealtimeNanos() - t0) / 1000).append(" µs ").append(how)
            }
        } finally {
            ctx.unregisterReceiver(this)
        }
        return s.toString()
    }
}

class Encoder(val hz: Int, val bits: Int, val mode: String, val quality: String, val kbps: Int) {
    // HIGH is the 990 setting; at 44.1 kHz it runs at 909 kbps, so check the mode, not the rate.
    fun lockedAt(hz: Int) = this.hz == hz && bits == 24 && quality == "HIGH"

    override fun toString() = "LDAC · ${specText(hz, bits, mode, kbps)} · $quality"
}

/** Runs dumpsys for one service and hands its output to [read], which may stop early; null on any failure. */
private fun <T> dumpsys(service: String, read: (BufferedReader) -> T?): T? {
    val p = try {
        // stderr joins stdout, which is read: a full, unread stderr pipe could otherwise stall the dump.
        ProcessBuilder("dumpsys", service).redirectErrorStream(true).start()
    } catch (e: Exception) {
        return null
    }
    return try {
        read(p.inputStream.bufferedReader())
    } catch (e: Exception) {
        Log.w(TAG, "dumpsys $service", e)
        null
    } finally {
        p.destroy() // Stops the dump once [read] has what it needs.
    }
}

/**
 * The LDAC encoder's real state from dumpsys (needs DUMP, granted over adb), or null.
 * Stops reading after the LDAC block: a full dump costs ~260 ms on the S25.
 * Null on the Astra too: its offloaded LDAC leaves no block in the dump.
 */
fun ldacEncoder() = dumpsys("bluetooth_manager") { r ->
    if (!skipTo(r, "A2DP LDAC State:")) return@dumpsys null
    var hz = 0
    var bits = 0
    var mode = "Stereo"
    var quality = "?"
    while (true) {
        val l = r.readLine() ?: break
        when {
            l.startsWith("  Config: ") -> { // "  Config: Rate=44100 Bits=24 Mode=STEREO"
                hz = l.substringAfter("Rate=").substringBefore(' ').toInt()
                bits = l.substringAfter("Bits=").substringBefore(' ').toInt()
                if (l.endsWith("MONO")) mode = "Mono"
            }
            l.startsWith("  LDAC quality mode") -> quality = l.substringAfter(": ")
            l.startsWith("  LDAC transmission bitrate") ->
                return@dumpsys Encoder(hz, bits, mode, quality, l.substringAfter(": ").trim().toInt())
            !l.startsWith(" ") -> break
        }
    }
    null
}
