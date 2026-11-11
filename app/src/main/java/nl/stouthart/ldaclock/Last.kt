package nl.stouthart.ldaclock

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Whether a detection saved on firmware [savedBuild] in boot [savedBoot] still holds on [build] in boot [boot]: never
 * across a system update, which can change the encoder. With [sameBoot], as a lock asks, not across a restart either:
 * hardware offload may have been switched in Developer options, and a hardware path taken for software would skip the
 * check that nothing plays. No boot count (-1): a lock detects every time.
 */
fun detectionHolds(savedBuild: String?, build: String, savedBoot: Int, boot: Int, sameBoot: Boolean) =
    savedBuild == build && (!sameBoot || boot >= 0 && savedBoot == boot)

/** How this phone encodes LDAC, as last detected: per firmware build, and for a lock per boot too (detectionHolds). */
object Phone {
    private fun prefs(c: Context) = c.getSharedPreferences("phone", Context.MODE_PRIVATE)

    private fun boot(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** The saved path; with [sameBoot], as a lock needs it, only one detected since the phone started. */
    fun path(c: Context, sameBoot: Boolean = false): Path? {
        val p = prefs(c)
        if (!detectionHolds(p.getString("build", null), Build.FINGERPRINT, p.getInt("boot", -1), boot(c), sameBoot)) return null
        return Path(p.getBoolean("hardware", false), p.getInt("hz", 44100))
    }

    fun save(c: Context, path: Path) {
        prefs(c).edit()
            .putString("build", Build.FINGERPRINT)
            .putInt("boot", boot(c))
            .putBoolean("hardware", path.hardware)
            .putInt("hz", path.hz)
            .apply()
    }
}

/**
 * The encoder as last read, with what the stack reported then and when, for the headphones it was read for. While
 * music plays, a Bluetooth dump makes Samsung's audio skip, so the screen and the tile show this instead as long as
 * the report is the same, the screen with the time it was read; they read again once playback stops. A reconnect
 * reports another config (the S25 starts at 96 kHz, 32-bit), which no lock ends on, so it's read again then too.
 */
object LastRead {
    private fun prefs(c: Context) = c.getSharedPreferences("encoder", Context.MODE_PRIVATE)

    fun save(c: Context, device: String, r: Reported?, e: Encoder) {
        r ?: return
        prefs(c).edit().putString("device", device).putBoolean("ldac", r.ldac).putInt("rhz", r.hz).putInt("rbits", r.bits)
            .putLong("rq", r.quality).putInt("hz", e.hz).putInt("bits", e.bits).putString("mode", e.mode)
            .putString("quality", e.quality).putInt("kbps", e.kbps).putLong("at", System.currentTimeMillis()).apply()
    }

    /** When [get]'s encoder was read. */
    fun at(c: Context) = prefs(c).getLong("at", 0)

    /** The encoder as last read for [device], while the stack still reports [r] as it did then; else null. */
    fun get(c: Context, device: String, r: Reported?): Encoder? {
        val p = prefs(c)
        if (r == null || p.getString("device", null) != device || p.getBoolean("ldac", false) != r.ldac ||
            p.getInt("rhz", 0) != r.hz || p.getInt("rbits", 0) != r.bits || p.getLong("rq", -1) != r.quality ||
            p.getLong("at", 0) == 0L // Saved by a build that didn't keep the time.
        ) return null
        return Encoder(
            p.getInt("hz", 0), p.getInt("bits", 0), p.getString("mode", null) ?: "Stereo",
            p.getString("quality", null) ?: "?", p.getInt("kbps", 0),
        )
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
        lockAt = 0L
        notify(c)
    }

    // From a tap until its result is in, at most LOCK_MS should the service never report: a lock's own codec changes
    // aren't news to the screen and the tile.
    @Volatile private var lockAt = 0L
    private const val LOCK_MS = 30_000L

    fun locking() {
        lockAt = SystemClock.elapsedRealtime()
    }

    fun locked() {
        lockAt = 0L
    }

    val isLocking get() = lockAt != 0L && SystemClock.elapsedRealtime() - lockAt < LOCK_MS

    /** ms until a lock that never reported stops counting as running; 0 while none runs. */
    val lockLeft get() = if (isLocking) LOCK_MS - (SystemClock.elapsedRealtime() - lockAt) else 0L

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

    /**
     * Saves an attempt's result. [hz] and [q] are what the screen judges the codec by: a failure without a target of
     * its own, as with the headphones gone, keeps the last lock's, so a lock that still holds isn't called gone.
     */
    fun save(c: Context, ok: Boolean, text: String, tile: String, tookMs: Long = 0, hz: Int = Last.hz(c), q: Long = Last.quality(c)) {
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
        lockAt = 0L
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
