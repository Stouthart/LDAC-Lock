package nl.stouthart.ldaclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/** The dump parsers, the rate fallback and the quality names, as the devices print and use them. */
class ParseTest {
    private fun dump(vararg lines: String) = BufferedReader(StringReader(lines.joinToString("\n")))

    // The S25's block, as `dumpsys bluetooth_manager` prints it while LDAC plays.
    private val ldacHigh = arrayOf(
        "A2DP State:",
        "A2DP LDAC State:",
        "  Config: Rate=44100 Bits=24 Mode=STEREO",
        "  LDAC quality mode                                       : HIGH",
        "  LDAC transmission bitrate (Kbps)                        : 909",
        "AVRCP State:",
    )

    @Test
    fun encoderAtHigh() {
        val e = parseLdac(dump(*ldacHigh))!!
        assertEquals(44100, e.hz)
        assertEquals(24, e.bits)
        assertEquals("Stereo", e.mode)
        assertEquals("HIGH", e.quality)
        assertEquals(909, e.kbps)
        assertTrue(e.lockedAt(44100, Quality.HIGH))
        assertFalse(e.lockedAt(44100, Quality.MID))
        assertFalse(e.lockedAt(48000, Quality.HIGH))
    }

    @Test
    fun encoderAtMidAndAdaptive() {
        val mid = parseLdac(
            dump("A2DP LDAC State:", "  Config: Rate=48000 Bits=24 Mode=STEREO", "  LDAC quality mode : MID",
                "  LDAC transmission bitrate (Kbps) : 660"),
        )!!
        assertTrue(mid.lockedAt(48000, Quality.MID))
        val abr = parseLdac(
            dump("A2DP LDAC State:", "  Config: Rate=44100 Bits=24 Mode=STEREO", "  LDAC quality mode : ABR",
                "  LDAC transmission bitrate (Kbps) : 330"),
        )!!
        assertFalse(abr.lockedAt(44100, Quality.HIGH))
        assertFalse(abr.lockedAt(44100, Quality.MID))
    }

    @Test
    fun encoderMissingOrCut() {
        assertNull(parseLdac(dump("A2DP State:", "AVRCP State:")))
        assertNull(parseLdac(dump())) // Hardware LDAC, or no DUMP: nothing to read.
        // The block ends before its bitrate line: no encoder rather than one with zeros.
        assertNull(parseLdac(dump("A2DP LDAC State:", "  Config: Rate=44100 Bits=24 Mode=STEREO", "AVRCP State:")))
        // A garbled number reads as 0, never a crash.
        assertEquals(0, parseLdac(dump("A2DP LDAC State:", "  LDAC transmission bitrate (Kbps) : x"))!!.kbps)
    }

    @Test
    fun specLine() {
        // The format as one unit, its bit depth with a hyphen, then the rest; as the other app writes a format.
        assertEquals("44.1 kHz 24-bit · Stereo · 909 kbps", specText(44100, 24, "Stereo", 909))
        assertEquals("96 kHz 24-bit · Stereo", specText(96000, 24, "Stereo"))
        assertEquals("88.2 kHz 32-bit · Mono · 909 kbps", specText(88200, 32, "Mono", 909))
    }

    @Test
    fun mediaSession() {
        // The Astra's dump, cut short: Spotify gets the keys; the phone's own call session comes first, the Bluetooth
        // stack's after it.
        val astra = arrayOf(
            "Global priority session is com.android.server.telecom/HeadsetMediaButton/1 (userId=0)",
            "  HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton/1 (userId=0)",
            "    state=null",
            "  Media button session is com.spotify.music/spotify-media-session/12 (userId=0)",
            "  Sessions Stack - have 2 sessions:",
            "    spotify-media-session com.spotify.music/spotify-media-session/12 (userId=0)",
            "      package=com.spotify.music",
            "      state=PlaybackState {state=PLAYING(3), position=289183, buffered position=0, speed=1.0}",
            "    BluetoothMediaBrowserService com.android.bluetooth/BluetoothMediaBrowserService/8 (userId=0)",
            "      state=PlaybackState {state=ERROR(7), position=0, buffered position=0, speed=0.0}",
        )
        assertEquals(true, parsePlaying(dump(*astra)))
        val paused = astra.copyOf().also { it[7] = "      state=PlaybackState {state=PAUSED(2), position=289183}" }
        assertEquals(false, parsePlaying(dump(*paused)))
        val stopped = astra.copyOf().also { it[7] = "      state=PlaybackState {state=STOPPED(1), position=0}" }
        assertEquals(false, parsePlaying(dump(*stopped)))
        val buffering = astra.copyOf().also { it[7] = "      state=PlaybackState {state=BUFFERING(6), position=0}" }
        assertEquals(true, parsePlaying(dump(*buffering)))
        // Nothing to control: no app takes the keys, its state is none or an error, or there's no state at all.
        assertNull(parsePlaying(dump("  Media button session is null", "  Sessions Stack - have 0 sessions:")))
        assertNull(parsePlaying(dump(*astra.copyOf().also { it[7] = "      state=PlaybackState {state=NONE(0)}" })))
        assertNull(parsePlaying(dump(*astra.copyOf().also { it[7] = "      state=null" })))
        assertNull(parsePlaying(dump(*astra.copyOfRange(0, 6))))
        assertNull(parsePlaying(dump("*** SERVICE 'media_session' DUMP TIMEOUT (2000ms) EXPIRED ***")))
    }

    @Test
    fun softwareOrHardware() {
        assertEquals(true, parseSoftware(dump(*ldacHigh)))
        // The Astra's dump: feature flags, the adapter's A2DP status, and no LDAC block.
        val astra = arrayOf("\t[■]: a2dp_cleanup_on_remove_device", "\t[ ]: a2dp_version14", "  A2dpOffloadEnabled: true", "  A2DP")
        assertEquals(false, parseSoftware(dump(*astra)))
        // No offload, and no LDAC block yet: software that hasn't started its encoder, not hardware.
        assertNull(parseSoftware(dump("\t[■]: a2dp_cleanup_on_remove_device", "  A2dpOffloadEnabled: false", "  A2DP")))
        // A field that only ends in the name isn't the adapter's status line.
        assertNull(parseSoftware(dump("    mA2dpOffloadEnabled: true")))
        // Neither: a dump that ran out of time, or one cut off after the flags, isn't evidence of hardware.
        assertNull(parseSoftware(dump(*astra.copyOfRange(0, 2))))
        assertNull(parseSoftware(dump("  A2dpOffloadEnabled: true", "*** SERVICE 'bluetooth_manager' DUMP TIMEOUT (2000ms) EXPIRED ***")))
        assertNull(parseSoftware(dump("Bluetooth Status", "  enabled: true")))
        assertNull(parseSoftware(dump()))
    }

    @Test
    fun mixerRate() {
        // The S25's format: other mixers first, the A2DP one later.
        val flinger = dump(
            "Output thread 0xb40000763219a4e0, name AudioOut_D, tid 3016, type 0 (MIXER):",
            "  Sample rate: 48000 Hz",
            "  Output devices:  (Empty device types)",
            "Output thread 0xb4000076317684e0, name AudioOut_15, tid 3067, type 1 (DIRECT):",
            "  Sample rate: 96000 Hz",
            "  Output devices: 0x80000080 (BLUETOOTH_A2DP)",
            "Output thread 0xb40000762a60e4e0, name AudioOut_1D, tid 3097, type 0 (MIXER):",
            "  Sample rate: 44100 Hz",
            "  Output devices: 0x80000080 (BLUETOOTH_A2DP)",
        )
        assertEquals(44100, parseMixerHz(flinger)) // The mixer's, not the direct thread's.
        assertNull(parseMixerHz(dump("Output thread 0x1, name AudioOut_D, tid 1, type 0 (MIXER):", "  Sample rate: 48000 Hz")))
        assertNull(
            parseMixerHz(
                dump("Output thread 0x1, name A, tid 1, type 0 (MIXER):", "  Output devices: 0x80 (BLUETOOTH_A2DP)"),
            ),
        ) // No rate given: unknown, not 0.
    }

    @Test
    fun rateFallsBackWithinItsFamily() {
        val all = setOf(44100, 48000, 88200, 96000)
        for (hz in all) assertEquals(hz, pickRate(hz) { it in all })
        val cd = setOf(44100, 48000) // Headphones without the high rates.
        assertEquals(44100, pickRate(88200) { it in cd })
        assertEquals(48000, pickRate(96000) { it in cd })
        assertEquals(48000, pickRate(88200) { it == 48000 }) // The other family as the last resort.
        assertEquals(44100, pickRate(96000) { it == 44100 })
        assertEquals(96000, pickRate(96000) { false }) // Nothing offered: the wanted rate after all.
    }

    @Test
    fun qualityNamesAndBitrates() {
        assertEquals("HIGH", Quality.name(Quality.HIGH))
        assertEquals("MID", Quality.name(Quality.MID))
        assertEquals(909, Quality.kbps(Quality.HIGH, 44100))
        assertEquals(909, Quality.kbps(Quality.HIGH, 88200))
        assertEquals(990, Quality.kbps(Quality.HIGH, 96000))
        assertEquals(606, Quality.kbps(Quality.MID, 44100))
        assertEquals(660, Quality.kbps(Quality.MID, 48000))
    }
}
