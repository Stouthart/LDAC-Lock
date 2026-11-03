package nl.stouthart.ldaclock

import android.content.Context

/** The Quality setting: LDAC's HIGH mode (990 kbps, 909 at 44.1 and 88.2 kHz), or MID (660/606) for a steadier link. */
object Quality {
    const val HIGH = 1000L // LDAC codecSpecific1: 1000 = HIGH, 1001 = MID … 1003 = adaptive.
    const val MID = 1001L
    val ALL = longArrayOf(HIGH, MID)

    private fun prefs(c: Context) = c.getSharedPreferences("quality", Context.MODE_PRIVATE)

    fun set(c: Context, q: Long) = prefs(c).edit().putLong("q", q).apply()

    fun target(c: Context) = if (prefs(c).getLong("q", HIGH) == MID) MID else HIGH

    /** As the encoder's dump names the mode. */
    fun name(q: Long) = if (q == MID) "MID" else "HIGH"

    /** The bitrate [q] runs at: the 44.1 kHz family gets 909 or 606 kbps, the 48 kHz family 990 or 660. */
    fun kbps(q: Long, hz: Int) = if (hz % 44100 == 0) (if (q == MID) 606 else 909) else (if (q == MID) 660 else 990)
}
