package nl.stouthart.ldaclock

import android.app.UiModeManager
import android.content.Context

/** The Appearance setting, as in UPnP Cast: 0 System default, 1 Light mode, 2 Dark mode. */
object Appearance {
    private fun prefs(c: Context) = c.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun get(c: Context) = prefs(c).getInt("mode", 0)

    /**
     * Android keeps the app's own night mode across restarts and applies it, recreating the screen; the preference
     * only remembers which choice to mark.
     */
    fun set(c: Context, a: Int) {
        prefs(c).edit().putInt("mode", a).apply()
        c.getSystemService(UiModeManager::class.java).setApplicationNightMode(
            when (a) {
                1 -> UiModeManager.MODE_NIGHT_NO
                2 -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO // Follows the system.
            },
        )
    }
}
