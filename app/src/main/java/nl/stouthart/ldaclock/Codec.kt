// The int codec types are deprecated for BluetoothCodecType, which has no constructor: its objects
// cost a getSupportedCodecTypes() call, and it has two LDAC ids. The int path is what the stack logs.
@file:Suppress("DEPRECATION")

package nl.stouthart.ldaclock

import android.Manifest
import android.annotation.SuppressLint
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
const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
const val ACTION_ACTIVE_DEVICE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" // Hidden, as the others.

// A codec change comes as a burst of notices (a reconnect sends a dozen within 0.3 s): one look, this long after the
// last. On Samsung a look includes the encoder dump, which works inside the Bluetooth process, so never more often.
const val CODEC_QUIET_MS = 500L
// After the last playback notice, the screen and the tile read the encoder again if nothing plays by then: music takes
// a moment to go quiet.
const val FRESH_MS = 1500L
private const val STEP_TIMEOUT_MS = 5000L
private const val QUALITY_WAIT_MS = 2000L // The hardware path takes a quality-only change a moment later.
private const val QUALITY_POLL_MS = 100L

// Hidden from the SDK; reflection reaches them (BCC does the same). An object initializes on first use, not when the
// app starts, and a lookup that fails is null: on a firmware without them the lock says so, rather than crashing.
private object Hidden {
    val getCodecStatus = find("getCodecStatus", BluetoothDevice::class.java)
    val setCodecConfigPreference =
        find("setCodecConfigPreference", BluetoothDevice::class.java, BluetoothCodecConfig::class.java)
    val getActiveDevice = find("getActiveDevice") // BCC uses it too.
    val getBatteryLevel: Method? = try { // A pair's battery as it reports it, -1 when it doesn't.
        BluetoothDevice::class.java.getMethod("getBatteryLevel")
    } catch (e: Exception) {
        null
    }

    private fun find(name: String, vararg types: Class<*>): Method? = try {
        BluetoothA2dp::class.java.getMethod(name, *types)
    } catch (e: Exception) {
        null
    }
}

/** The app may read dumps: granted over adb, as the README says. */
fun Context.dumpable() = checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

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

/**
 * Detects the path; needs DUMP and LDAC as the current codec. Null when it can't tell for sure, as after a dump that
 * timed out or came back without its A2DP part: the answer is kept for the firmware build, so no guesses, and a lock
 * asks again after each restart (detectionHolds).
 */
fun Context.detectPath(): Path? {
    if (!dumpable()) return null
    val software = dumpsys("bluetooth_manager") { parseSoftware(it) } ?: return null
    if (software) return Path(false, 44100)
    // Behind a fixed 48 kHz mixer, LDAC at 44.1 kHz means a second resample (48 → 44.1) in the DSP. Unread, the next
    // lock asks again rather than keeping 44.1 kHz for good.
    val mixer = a2dpMixerHz() ?: return null
    return Path(true, if (mixer == 48000) 48000 else 44100)
}

/**
 * From a Bluetooth dump: true for software LDAC, which leaves an "A2DP LDAC State" block; false for hardware, which
 * leaves none. Null when the dump can't tell: it timed out, or ended without the adapter's A2DP status line, which
 * both the S25 and the Astra print ("A2dpOffloadEnabled: true"). Any "a2dp" won't do: the feature flags at the top
 * of every dump have it.
 */
fun parseSoftware(r: BufferedReader): Boolean? {
    var a2dp = false
    while (true) {
        val l = r.readLine() ?: break
        if (l == "A2DP LDAC State:") return true
        if ("DUMP TIMEOUT" in l) return null // dumpsys's own note when the service didn't finish.
        if (!a2dp && l.trim() == "A2dpOffloadEnabled: true") a2dp = true // "false": no offload, so no evidence of it.
    }
    return if (a2dp) false else null
}

/** The sample rate of the mixer thread feeding A2DP, from the audio_flinger dump (~10–16 ms). */
private fun a2dpMixerHz() = dumpsys("media.audio_flinger") { parseMixerHz(it) }

/** The A2DP mixer thread's rate in an audio_flinger dump; null without one, or with a rate it doesn't give. */
fun parseMixerHz(r: BufferedReader): Int? {
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
            mixer && l.startsWith("  Output devices:") && "BLUETOOTH_A2DP" in l -> return hz.takeIf { it > 0 }
        }
    }
    return null
}

/** Reads up to and including [line]; false if the output ends first. */
private fun skipTo(r: BufferedReader, line: String): Boolean {
    while (true) if ((r.readLine() ?: return false) == line) return true
}

/** The steps and the LDAC rate they end on. */
class Plan(val steps: List<BluetoothCodecConfig>, val hz: Int)

private fun rateBit(hz: Int) = when (hz) {
    48000 -> SAMPLE_RATE_48000
    88200 -> SAMPLE_RATE_88200
    96000 -> SAMPLE_RATE_96000
    else -> SAMPLE_RATE_44100
}

/**
 * The rate a lock aims at: [want], else the first of its fallbacks that [offered] says the headphones take — within
 * the family first (88.2 → 44.1, 96 → 48), then the other — else [want] after all.
 */
fun pickRate(want: Int, offered: (Int) -> Boolean): Int {
    val order = when (want) {
        96000 -> intArrayOf(96000, 48000, 44100)
        88200 -> intArrayOf(88200, 44100, 48000)
        48000 -> intArrayOf(48000, 44100)
        else -> intArrayOf(44100, 48000)
    }
    return order.firstOrNull(offered) ?: want
}

/**
 * Builds the steps to LDAC at [want] Hz and quality [q] from the phone's path and what the headphones can select.
 * Software or unknown: AAC → 32-bit → 24-bit. Hardware: one LDAC change. Null for headphones that don't offer LDAC,
 * or whose codecs can't be read: a detour via AAC or SBC would then leave them there, worse off than before.
 */
/** The detour's rate: the lowest its codec offers on these headphones, 44.1 kHz where offered; 0 for none. */
fun detourRate(mask: Int) = Integer.lowestOneBit(mask)

fun plan(s: BluetoothCodecStatus?, path: Path?, want: Int, q: Long): Plan? {
    val caps = s?.codecsSelectableCapabilities.orEmpty()
    val ldac = caps.firstOrNull { it.isLdac } ?: return null
    val hz = pickRate(want) { (ldac.sampleRate and rateBit(it)) != 0 }
    val rate = rateBit(hz)
    if (path?.hardware == true) return Plan(listOf(config(SOURCE_CODEC_TYPE_LDAC, rate, BITS_PER_SAMPLE_24, q)), hz)
    // A different codec forces a fresh stream; SBC is the fallback all headphones have. At a rate the headphones offer
    // for it: at another, the step waits for a rate that never comes, and the lock ends on that codec. Neither offered:
    // no detour, rather than one that can't finish.
    val via = caps.firstOrNull { it.codecType == SOURCE_CODEC_TYPE_AAC }
        ?: caps.firstOrNull { it.codecType == SOURCE_CODEC_TYPE_SBC } ?: return null
    val viaRate = detourRate(via.sampleRate).takeIf { it != 0 } ?: return null
    val steps = ArrayList<BluetoothCodecConfig>(3)
    steps += config(via.codecType, viaRate, BITS_PER_SAMPLE_16, 0)
    // The 32 → 24-bit change makes the S25's stack pass the quality on; skipped where 32-bit isn't offered.
    if ((ldac.bitsPerSample and BITS_PER_SAMPLE_32) != 0) {
        steps += config(SOURCE_CODEC_TYPE_LDAC, rate, BITS_PER_SAMPLE_32, q)
    }
    steps += config(SOURCE_CODEC_TYPE_LDAC, rate, BITS_PER_SAMPLE_24, q)
    return Plan(steps, hz)
}

/** Reflection wraps the real exception (a SecurityException when not paired); unwrap it. */
private fun call(m: Method, a2dp: BluetoothA2dp, vararg args: Any): Any? =
    try {
        m.invoke(a2dp, *args)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }

/** The headphones' battery in percent, as they report it to the phone; null when they don't, or it can't be read. */
fun BluetoothDevice.battery(): Int? = runCatching { Hidden.getBatteryLevel?.invoke(this) as Int? }.getOrNull()?.takeIf { it in 0..100 }

/** The codec status, or null where it can't be read, as on a firmware without the hidden call. */
fun BluetoothA2dp.codecStatus(d: BluetoothDevice) =
    Hidden.getCodecStatus?.let { call(it, this, d) as BluetoothCodecStatus? }

private fun BluetoothCodecConfig?.matches(t: BluetoothCodecConfig?) = this != null && t != null &&
    codecType == t.codecType && sampleRate == t.sampleRate && bitsPerSample == t.bitsPerSample

/**
 * The headphones Android plays media to. Its answer stands, none included: with two pairs connected, the first isn't
 * necessarily the one. Only when it can't be asked is a pair taken, and then only the one and only.
 */
fun Context.activeHeadphones(a2dp: BluetoothA2dp): BluetoothDevice? {
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
    val m = Hidden.getActiveDevice ?: return a2dp.connectedDevices.singleOrNull()
    return try {
        m.invoke(a2dp) as BluetoothDevice?
    } catch (e: Exception) {
        a2dp.connectedDevices.singleOrNull()
    }
}

/** This app is paired with [d] as a companion device. */
fun Context.isPaired(d: BluetoothDevice) = getSystemService(CompanionDeviceManager::class.java).myAssociations
    .any { it.deviceMacAddress?.toString().equals(d.address, ignoreCase = true) }

/** The active headphones, if this app is paired with them. */
fun Context.pairedHeadphones(a2dp: BluetoothA2dp) = activeHeadphones(a2dp)?.takeIf { isPaired(it) }

/** A sample rate as every screen shows it: "44.1 kHz", "96 kHz", "22.05 kHz", exact and without trailing zeros. */
fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000} kHz" else "${hz / 1000.0} kHz"

/** "44.1 kHz 24-bit · Stereo · 909 kbps": the format as one unit, then the rest; kbps only when known. */
fun specText(hz: Int, bits: Int, mode: String, kbps: Int = 0) =
    if (kbps > 0) "${khz(hz)} $bits-bit · $mode · $kbps kbps" else "${khz(hz)} $bits-bit · $mode"

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

/** What the stack reports of the codec, for [verdict]: plain values, so it's tested off the phone. */
class Reported(val ldac: Boolean, val hz: Int, val bits: Int, val quality: Long)

fun BluetoothCodecConfig?.reported() = this?.let { Reported(isLdac, hz, bits, codecSpecific1) }

/**
 * A lock's state as the service, the screen and the tile all tell it: [verdict]'s answer. Plain numbers, as Quality's:
 * an enum's `when` costs a class in every file that asks.
 */
object Verdict {
    const val LOCKED = 0 // The encoder itself runs at the rate and quality.
    const val REPORTED = 1 // The hardware path's report matches, quality included: faithful there, unconfirmed by the chip.
    const val NOT_LOCKED = 2
    const val UNVERIFIED = 3 // A software encoder, or one not known yet, that the dump should have shown and didn't.
    const val NO_DUMP = 4 // Rate and bits match, but without DUMP nothing reads the encoder or tells which one it is.
}

/**
 * Whether a lock at [hz] and quality [q] holds: from the encoder [e] where the dump has it, from the report [r] on a
 * known [hardware] path, where it's faithful. Elsewhere the report's quality says nothing: on the S25 it can read 1000
 * while the encoder runs adaptive, so a match of rate and bits alone is never a lock. [dump]: DUMP is granted.
 */
fun verdict(e: Encoder?, r: Reported?, hz: Int, q: Long, hardware: Boolean, dump: Boolean): Int = when {
    r == null || !r.ldac -> Verdict.NOT_LOCKED
    hardware -> if (r.hz == hz && r.bits == 24 && r.quality == q) Verdict.REPORTED else Verdict.NOT_LOCKED
    e != null -> if (e.lockedAt(hz, q)) Verdict.LOCKED else Verdict.NOT_LOCKED
    r.hz != hz || r.bits != 24 -> Verdict.NOT_LOCKED // No encoder to ask, but the format alone says no.
    dump -> Verdict.UNVERIFIED
    else -> Verdict.NO_DUMP
}

/** Playback started between the check before a lock and its first change: nothing was changed. */
class PlaybackStarted : Exception()

/** Runs the hop on the calling thread, which it blocks; throws if a step isn't confirmed in time. */
class Hop(
    private val ctx: Context,
    private val a2dp: BluetoothA2dp,
    private val device: BluetoothDevice,
    private val steps: List<BluetoothCodecConfig>,
    private val resend: Boolean, // Also send a step that already matches (its quality may not have landed).
    private val readable: Boolean, // The stack reports the quality faithfully (hardware): a step waits for it too.
    private val pauseRequired: Boolean, // No first change during playback: hardware silences, and unknown may be hardware.
) : BroadcastReceiver() {
    // A step's target and its latch, published as one: a late broadcast for the step before can't end this wait.
    private class Waiting(val target: BluetoothCodecConfig) {
        val done = CountDownLatch(1)
    }

    @Volatile private var waiting: Waiting? = null

    override fun onReceive(c: Context, i: Intent) {
        val w = waiting ?: return
        if (i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) == device &&
            i.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus::class.java)
                ?.codecConfig.matches(w.target)
        ) w.done.countDown()
    }

    /** Reads the reported quality until it's [t]'s, within QUALITY_WAIT_MS; false if it never got there. */
    private fun settled(t: BluetoothCodecConfig): Boolean {
        val end = SystemClock.elapsedRealtime() + QUALITY_WAIT_MS
        while (a2dp.codecStatus(device)?.codecConfig?.codecSpecific1 != t.codecSpecific1) {
            if (SystemClock.elapsedRealtime() >= end) return false
            Thread.sleep(QUALITY_POLL_MS)
        }
        return true
    }

    /** Returns one line for the log: each step's time in µs and what ended its wait. */
    @SuppressLint("MissingPermission") // Only run by LockService, after its BLUETOOTH_CONNECT check.
    fun run(): String {
        val set = Hidden.setCodecConfigPreference
            ?: throw IllegalStateException("This phone doesn't take codec changes")
        // Only a sender holding BLUETOOTH_PRIVILEGED (the Bluetooth stack) can end a wait.
        ctx.registerReceiver(
            this, IntentFilter(ACTION_CODEC_CONFIG_CHANGED), Manifest.permission.BLUETOOTH_PRIVILEGED, null,
            Context.RECEIVER_EXPORTED,
        )
        val s = StringBuilder()
        var changed = false // A change was sent: from then on the hop goes on, as one stopped halfway leaves AAC on.
        try {
            for ((n, t) in steps.withIndex()) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                val w = Waiting(t)
                waiting = w
                val cur = a2dp.codecStatus(device)?.codecConfig
                val already = cur.matches(t) // Codec, rate and bits: no broadcast will come, so don't wait for one.
                val same = already && cur?.codecSpecific1 == t.codecSpecific1
                if (!same || resend) {
                    // Not to a pair that stopped being the one playing: the rest of the hop changes nothing heard.
                    check(ctx.activeHeadphones(a2dp) == device) { ctx.getString(R.string.switched_text) }
                    // Asked again right before the first change sent, which needn't be the first step's (it may match
                    // already): music may have started since the check before the hop.
                    if (pauseRequired && !changed && a2dp.isA2dpPlaying(device)) throw PlaybackStarted()
                    call(set, a2dp, device, t)
                    changed = true
                }
                var how = when {
                    same && !resend -> "already, not sent"
                    // Only the quality changes, as HIGH → MID: no broadcast either; the report follows a moment later.
                    already && readable -> if (settled(t)) "quality" else "quality not seen"
                    already -> "already"
                    w.done.await(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS) -> "broadcast"
                    a2dp.codecStatus(device)?.codecConfig.matches(t) -> "status"
                    else -> throw IllegalStateException("Step ${n + 1} not confirmed")
                }
                // On the hardware path a new rate or bit depth lands first, its quality can follow: wait for that too.
                // At once when it's there already.
                if (readable && !already && !settled(t)) how += ", quality not seen"
                if (n > 0) s.append(", ")
                s.append(n + 1).append(": ").append((SystemClock.elapsedRealtimeNanos() - t0) / 1000).append(" µs ").append(how)
            }
        } finally {
            waiting = null
            ctx.unregisterReceiver(this)
        }
        return s.toString()
    }
}

class Encoder(val hz: Int, val bits: Int, val mode: String, val quality: String, val kbps: Int) {
    // The mode, not the kbps: HIGH runs at 909 kbps at 44.1 kHz, MID at 606.
    fun lockedAt(hz: Int, q: Long) = this.hz == hz && bits == 24 && quality == Quality.name(q)

    override fun toString() = "LDAC · ${specText(hz, bits, mode, kbps)} · $quality"
}

/** Runs dumpsys for one service and hands its output to [read], which may stop early; null on any failure. */
private fun <T> dumpsys(service: String, read: (BufferedReader) -> T?): T? {
    val p = try {
        // stderr joins stdout, which is read: a full, unread stderr pipe could otherwise stall the dump.
        // -T: a service that hangs gives up after 2 s, not dumpsys's default 10 (the S25's whole dump takes ~0.35 s).
        ProcessBuilder("dumpsys", "-T", "2000", service).redirectErrorStream(true).start()
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
 * The LDAC encoder's real state from dumpsys, or null: without DUMP (granted over adb) not even tried.
 * Stops reading after the LDAC block: a full dump costs ~260 ms on the S25.
 * Null on the Astra too: its offloaded LDAC leaves no block in the dump.
 */
fun Context.ldacEncoder() = if (dumpable()) dumpsys("bluetooth_manager") { parseLdac(it) } else null

/** The encoder block of a Bluetooth dump, or null without one. */
fun parseLdac(r: BufferedReader): Encoder? {
    if (!skipTo(r, "A2DP LDAC State:")) return null
    var hz = 0
    var bits = 0
    var mode = "Stereo"
    var quality = "?"
    while (true) {
        val l = r.readLine() ?: break
        when {
            l.startsWith("  Config: ") -> { // "  Config: Rate=44100 Bits=24 Mode=STEREO"
                hz = l.substringAfter("Rate=").substringBefore(' ').toIntOrNull() ?: 0
                bits = l.substringAfter("Bits=").substringBefore(' ').toIntOrNull() ?: 0
                if (l.endsWith("MONO")) mode = "Mono"
            }
            l.startsWith("  LDAC quality mode") -> quality = l.substringAfter(": ").trim()
            l.startsWith("  LDAC transmission bitrate") ->
                return Encoder(hz, bits, mode, quality, l.substringAfter(": ").trim().toIntOrNull() ?: 0)
            !l.startsWith(" ") -> break
        }
    }
    return null
}

/**
 * Whether the app that gets the play and pause keys plays (true) or waits (false), as Spotify paused does; null
 * without such an app, or without DUMP. The media session service's dump, not Bluetooth's: 4–20 KB, ~20 ms, and it
 * stops at that app's state.
 */
fun Context.mediaPlaying() = if (dumpable()) dumpsys("media_session") { parsePlaying(it) } else null

/**
 * From a media session dump: the session named in "Media button session is <package>/<tag>/<n> (userId=0)", then its
 * own block's "state=PlaybackState {state=PLAYING(3), …". Buffering, seeking or skipping counts as playing; stopped
 * as waiting, as Play starts it again; none, an error or no state at all as nothing to control.
 */
fun parsePlaying(r: BufferedReader): Boolean? {
    var id = ""
    var mine = false
    while (true) {
        val l = r.readLine() ?: return null
        if ("DUMP TIMEOUT" in l) return null
        if (id.isEmpty()) {
            if (l.startsWith("  Media button session is ")) {
                id = l.removePrefix("  Media button session is ").substringBefore(" (userId")
                if ('/' !in id) return null // "null": no app takes the keys.
            }
            continue
        }
        val t = l.trim()
        if (t.endsWith(")") && " $id (userId" in t) mine = true // The block's first line: "<tag> <id> (userId=0)".
        else if (mine && t.startsWith("state=")) {
            return when (t.substringAfter("state=PlaybackState {state=", "").substringAfter('(').substringBefore(')').toIntOrNull()) {
                3, 4, 5, 6, 8, 9, 10, 11 -> true
                1, 2 -> false
                else -> null
            }
        }
    }
}
