package nl.stouthart.ldaclock

import android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_44100
import android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_48000
import android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_96000
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a saved encoder detection still holds, and the rate a lock's detour asks for. */
class DetectionTest {
    @Test
    fun detectionHolds() {
        assertTrue(detectionHolds("fp", "fp", 3, 3, sameBoot = true))
        assertFalse(detectionHolds("fp", "fp", 3, 4, sameBoot = true)) // A restart: offload may have been switched.
        assertTrue(detectionHolds("fp", "fp", 3, 4, sameBoot = false)) // The screen and the tile show it meanwhile.
        assertFalse(detectionHolds("old", "fp", 3, 3, sameBoot = false)) // A system update.
        assertFalse(detectionHolds("fp", "fp", -1, -1, sameBoot = true)) // No boot count: a lock detects every time.
        assertFalse(detectionHolds("fp", "fp", -1, 3, sameBoot = true)) // Saved before boots were kept.
        assertFalse(detectionHolds(null, "fp", -1, 3, sameBoot = false)) // Never detected.
    }

    @Test
    fun detourRate() {
        assertEquals(SAMPLE_RATE_44100, detourRate(SAMPLE_RATE_44100 or SAMPLE_RATE_48000)) // As before, where offered.
        assertEquals(SAMPLE_RATE_48000, detourRate(SAMPLE_RATE_48000 or SAMPLE_RATE_96000)) // AAC at 48 kHz only.
        assertEquals(0, detourRate(0)) // None: no detour.
    }
}
