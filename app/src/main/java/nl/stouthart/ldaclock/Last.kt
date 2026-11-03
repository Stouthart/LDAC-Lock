package nl.stouthart.ldaclock

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import java.util.concurrent.CopyOnWriteArraySet

/** How this phone encodes LDAC, detected once per firmware build (an OTA can change it). */
object Phone {
    private fun prefs(c: Context) = c.getSharedPreferences("phone", Context.MODE_PRIVATE)

    fun path(c: Context): Path? {
        val p = prefs(c)
        if (p.getString("build", null) != Build.FINGERPRINT) return null
        return Path(p.getBoolean("hardware", false), p.getInt("hz", 44100))
    }

    fun save(c: Context, path: Path) {
        prefs(c).edit()
            .putString("build", Build.FINGERPRINT)
            .putBoolean("hardware", path.hardware)
            .putInt("hz", path.hz)
            .apply()
    }
}

/** The last lock result, shared by the service, the tile and the screen. */
object Last {
    /** Run on the main thread after every save. */
    val listeners = CopyOnWriteArraySet<Runnable>()

    /** A one-off message (no lock attempted) for the tile and the screen; gone once either stops showing it. */
    @Volatile var notice: String? = null

    fun notice(c: Context, text: String) {
        notice = text
        notify(c)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("last", Context.MODE_PRIVATE)

    /** The last attempt locked: its history, which a reconnect afterwards doesn't change. */
    fun ok(c: Context) = prefs(c).getBoolean("ok", false)

    /** The lock still holds, as far as the tile knows: cleared by [expire]. */
    fun current(c: Context) = prefs(c).getBoolean("current", ok(c))

    fun text(c: Context): String? = prefs(c).getString("text", null)

    fun tile(c: Context): String? = prefs(c).getString("tile", null)

    fun at(c: Context) = prefs(c).getLong("at", 0)

    fun took(c: Context) = prefs(c).getLong("took", 0)

    /** The LDAC rate the last lock ended on. */
    fun hz(c: Context) = prefs(c).getInt("hz", 44100)

    /** The LDAC quality the last lock aimed at. */
    fun quality(c: Context) = prefs(c).getLong("q", Quality.HIGH)

    fun save(c: Context, ok: Boolean, text: String, tile: String, tookMs: Long = 0, hz: Int = 44100, q: Long = Quality.HIGH) {
        prefs(c).edit()
            .putBoolean("ok", ok)
            .putBoolean("current", ok)
            .putString("text", text)
            .putString("tile", tile)
            .putLong("at", System.currentTimeMillis())
            .putLong("took", tookMs)
            .putInt("hz", hz)
            .putLong("q", q)
            .apply()
        notice = null
        notify(c)
    }

    /** The lock lasts one connection; a reconnect resets the codec to the phone's default. The history stays. */
    fun expire(c: Context) {
        prefs(c).edit().putBoolean("current", false).putString("tile", c.getString(R.string.not_locked)).apply()
        notify(c)
    }

    private fun notify(c: Context) {
        TileService.requestListeningState(c, ComponentName(c, LockTile::class.java))
        c.mainExecutor.execute { listeners.forEach(Runnable::run) }
    }
}
