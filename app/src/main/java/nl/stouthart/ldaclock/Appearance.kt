package nl.stouthart.ldaclock

import android.annotation.SuppressLint
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo

/** The Appearance setting: 0 System default, 1 Light mode, 2 Dark mode. */
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

/**
 * Upright on a phone, free to turn on a tablet, from 600dp of smallest width, as the Astra: decided here, as a fixed
 * orientation in the manifest also holds on a tablet whose system doesn't set it aside, as REDMAGIC OS 11 does,
 * though Android 16 should from 600dp. Once, at the start: a turn recreates the screen, which asks again.
 */
@SuppressLint("SourceLockedOrientationActivity") // On purpose: on a phone only.
fun Activity.uprightOnPhone() {
    if (resources.configuration.smallestScreenWidthDp < 600) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
