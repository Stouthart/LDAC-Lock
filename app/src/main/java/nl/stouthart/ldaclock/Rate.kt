package nl.stouthart.ldaclock

import android.content.Context

/** The Sample rate setting: the LDAC rate a lock aims at, in four steps; until it's moved, the phone's own. */
object Rate {
    val HZ = intArrayOf(44100, 48000, 88200, 96000)

    private fun prefs(c: Context) = c.getSharedPreferences("rate", Context.MODE_PRIVATE)

    fun set(c: Context, hz: Int) = prefs(c).edit().putInt("hz", hz).apply()

    /** The chosen rate, else the phone's: 44.1 kHz in software, the mixer's rate in hardware. */
    fun target(c: Context, path: Path?) = prefs(c).getInt("hz", 0).takeIf { it in HZ } ?: path?.hz ?: 44100
}
