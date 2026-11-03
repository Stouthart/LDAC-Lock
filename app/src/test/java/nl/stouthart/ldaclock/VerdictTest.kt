package nl.stouthart.ldaclock

import org.junit.Assert.assertEquals
import org.junit.Test

/** A lock's state, as the service, the screen and the tile all tell it, from what each phone reports. */
class VerdictTest {
    private val high = Quality.HIGH
    private val mid = Quality.MID

    // The S25 after a lock: the report's quality reads 0, whatever the encoder runs.
    private val s25 = Reported(true, 44100, 24, 0)
    private val astra = Reported(true, 48000, 24, high)

    @Test
    fun encoderDecides() {
        assertEquals(Verdict.LOCKED, verdict(Encoder(44100, 24, "Stereo", "HIGH", 909), s25, 44100, high, false, true))
        assertEquals(Verdict.NOT_LOCKED, verdict(Encoder(44100, 24, "Stereo", "MID", 606), s25, 44100, high, false, true))
        assertEquals(Verdict.NOT_LOCKED, verdict(Encoder(44100, 24, "Stereo", "ABR", 909), s25, 44100, high, false, true))
        // The encoder is the truth there, also where the report's rate differs.
        assertEquals(Verdict.LOCKED, verdict(Encoder(48000, 24, "Stereo", "MID", 660), s25, 48000, mid, false, true))
    }

    @Test
    fun hardwareByItsReport() {
        assertEquals(Verdict.REPORTED, verdict(null, astra, 48000, high, true, true))
        assertEquals(Verdict.REPORTED, verdict(null, astra, 48000, high, true, false)) // Known from before DUMP went.
        assertEquals(Verdict.NOT_LOCKED, verdict(null, astra, 48000, mid, true, true))
        assertEquals(Verdict.NOT_LOCKED, verdict(null, astra, 44100, high, true, true))
        assertEquals(Verdict.NOT_LOCKED, verdict(null, Reported(true, 48000, 16, high), 48000, high, true, true))
    }

    @Test
    fun unreadNeverLocks() {
        // A match of rate and bits alone: the dump should have shown the encoder, or nothing could read it.
        assertEquals(Verdict.UNVERIFIED, verdict(null, s25, 44100, high, false, true))
        assertEquals(Verdict.NO_DUMP, verdict(null, s25, 44100, high, false, false))
        assertEquals(Verdict.NO_DUMP, verdict(null, astra, 48000, high, false, false)) // A path not known yet.
        // The format alone can still say no.
        assertEquals(Verdict.NOT_LOCKED, verdict(null, s25, 48000, high, false, false))
        assertEquals(Verdict.NOT_LOCKED, verdict(null, Reported(true, 44100, 32, 0), 44100, high, false, true))
    }

    @Test
    fun notLdac() {
        assertEquals(Verdict.NOT_LOCKED, verdict(null, null, 44100, high, false, true))
        assertEquals(Verdict.NOT_LOCKED, verdict(null, Reported(false, 44100, 16, 0), 44100, high, true, true))
        assertEquals(Verdict.NOT_LOCKED, verdict(Encoder(44100, 24, "Stereo", "HIGH", 909), null, 44100, high, false, true))
    }
}
