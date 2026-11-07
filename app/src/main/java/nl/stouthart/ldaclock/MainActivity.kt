package nl.stouthart.ldaclock

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.A2DP
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Date

private const val LOW_BATTERY = 15 // % and below in orange, as Android warns about its own battery.
private const val MEDIA_QUIET_MS = 300L // Playback notices come several to a change: one read, this long after the last.

private var View.shown: Boolean
    get() = visibility == View.VISIBLE
    set(v) {
        visibility = if (v) View.VISIBLE else View.GONE
    }

@SuppressLint("MissingPermission") // Only called from show(), after its BLUETOOTH_CONNECT check.
private fun BluetoothDevice.kind() = when (bluetoothClass?.deviceClass) {
    BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> R.string.headset
    BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> R.string.headphones
    BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
    BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> R.string.speaker
    else -> R.string.audio
}

/** The one screen: device card with the current codec, the Lock section, and the settings. */
class MainActivity : Activity(), BluetoothProfile.ServiceListener {
    private val adapter by lazy { getSystemService(BluetoothManager::class.java).adapter }
    private val cdm by lazy { getSystemService(CompanionDeviceManager::class.java) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val refresh = Runnable { show() }
    private lateinit var name: TextView
    private lateinit var meta: TextView
    private lateinit var panel: View
    private lateinit var codec: TextView
    private lateinit var hiRes: View
    private lateinit var play: ImageView
    private lateinit var spec: TextView
    private lateinit var note: TextView
    private lateinit var state: TextView
    private lateinit var pair: TextView
    private lateinit var summary: TextView
    private lateinit var rate: SeekBar
    private lateinit var rateValue: TextView
    private lateinit var qualities: Array<TextView>
    private lateinit var lock: TextView
    private lateinit var last: TextView
    private var a2dp: BluetoothA2dp? = null
    private var pending = false // A proxy request is out; Android answers once Bluetooth is on.
    private var started = false // Between onStart and onStop: a proxy that answers later is closed, not used.
    private var gen = 0 // Drops encoder reads overtaken by a newer show(), by Bluetooth going off, or by onStop.
    private var reading = false // An encoder read is out, one at a time; a show() meanwhile asks for another.
    private var reread = false
    private var config: BluetoothCodecConfig? = null // The reported config the next encoder read is shown with.
    private var readFor: String? = null // The headphones it's for, whose read is kept for while music plays.

    // Live while visible: Bluetooth on/off, headphones (dis)connecting, the active headset changing, the codec changing;
    // a battery change only redraws the device line, with no look at the codec.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> connect()
                ACTION_CODEC_CONFIG_CHANGED -> {
                    main.removeCallbacks(look)
                    main.postDelayed(look, CODEC_QUIET_MS)
                }
                ACTION_BATTERY_LEVEL_CHANGED -> if (meta.shown && checkSelfPermission(BLUETOOTH_CONNECT) == PERMISSION_GRANTED) {
                    a2dp?.let { activeHeadphones(it) }?.let { line(it) }
                }
                else -> show()
            }
        }
    }
    private val main = Handler(Looper.getMainLooper())

    // The codec changed by itself, as HIGH falling back to MID: one look once the notices stopped. Not during a lock,
    // whose own steps send them; its result shows when it's in.
    private val look = Runnable { if (started && !Last.isLocking) show(again = true) }

    // Any app's playback starting, pausing or stopping, while visible: the play button follows, once it settled.
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            main.removeCallbacks(media)
            main.postDelayed(media, MEDIA_QUIET_MS)
            main.removeCallbacks(fresh)
            main.postDelayed(fresh, FRESH_MS)
        }
    }
    private val media = Runnable { if (started) session() }

    // Playback stopped, or changed: the encoder read once nothing plays, as a dump then can't make the audio skip.
    // Later than the button: music takes a moment to go quiet.
    private val fresh = Runnable { if (started && !Last.isLocking && !audio.isMusicActive) show(again = true) }
    private var playing: Boolean? = null // What the play button shows: playing, waiting, or no app (no button).
    private var sessionReading = false // One read of the music app's state at a time; one more if asked meanwhile.
    private var sessionAgain = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        uprightOnPhone()
        setContentView(R.layout.activity_main)
        name = findViewById(R.id.name)
        meta = findViewById(R.id.meta)
        panel = findViewById(R.id.panel)
        codec = findViewById(R.id.codec)
        hiRes = findViewById(R.id.hi_res)
        play = findViewById(R.id.play)
        play.setOnClickListener {
            val p = playing ?: return@setOnClickListener
            // The key a headset's button sends: Android hands it to the app that plays, or that played last.
            val key = if (p) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            playButton(!p) // At once; the playback notice that follows confirms it.
        }
        spec = findViewById(R.id.spec)
        note = findViewById(R.id.note)
        state = findViewById(R.id.state)
        pair = findViewById(R.id.pair)
        summary = findViewById(R.id.summary)
        rate = findViewById(R.id.rate)
        rateValue = findViewById(R.id.rate_value)
        qualities = arrayOf(findViewById(R.id.quality_high), findViewById(R.id.quality_mid))
        lock = findViewById(R.id.lock)
        last = findViewById(R.id.last)
        findViewById<TextView>(R.id.copyright).text = getString(R.string.copyright, BuildConfig.VERSION_NAME)
        pair.setOnClickListener { pair() }
        lock.setOnClickListener {
            enable(lock, false)
            lock.setText(R.string.locking)
            Last.locking()
            startForegroundService(Intent(this, LockService::class.java))
        }
        // Saved at once; the next lock, from here or the tile, aims at it. Never a lock by itself.
        rate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                if (!user) return // showLast() set it.
                Rate.set(this@MainActivity, Rate.HZ[p])
                showLast()
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit

            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        for ((i, v) in qualities.withIndex()) v.setOnClickListener { // The same: saved, never a lock.
            Quality.set(this, Quality.ALL[i])
            showLast()
        }
        if (checkSelfPermission(BLUETOOTH_CONNECT) != PERMISSION_GRANTED) requestPermissions(arrayOf(BLUETOOTH_CONNECT), 0)
        appearance()
        fold()
    }

    /**
     * The App settings card opens and closes with a tap on its title row, the whole card while closed: closed at first,
     * then as it was left.
     */
    private fun fold() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val header: View = findViewById(R.id.settings_header)
        val body: View = findViewById(R.id.settings_body)
        val chevron: View = findViewById(R.id.settings_chevron)
        fun open(o: Boolean) {
            body.shown = o
            header.stateDescription = getString(if (o) R.string.expanded else R.string.collapsed)
        }
        open(prefs.getBoolean("open", false)) // Closed at first: the settings are rarely changed.
        chevron.rotation = if (body.shown) 180f else 0f
        val card = body.parent as ViewGroup
        var scroll: LayoutTransition.TransitionListener? = null // An open still to scroll into view.
        header.setOnClickListener {
            val o = !body.shown
            prefs.edit().putBoolean("open", o).apply()
            scroll?.let { card.layoutTransition.removeTransitionListener(it) }
            scroll = null
            open(o)
            chevron.animate().rotation(if (o) 180f else 0f).duration = 200
            if (o) scroll = into(card) { scroll === it }.also { card.layoutTransition.addTransitionListener(it) }
        }
    }

    /**
     * Scrolls all of [card] into view, with the margin below it, once it has grown: it's the last on the screen, so
     * it opens below the fold. Not before: while the card and the screen grow, animated, a scroll stops at their
     * height so far. [current]: the open is still the latest.
     */
    private fun into(card: ViewGroup, current: (LayoutTransition.TransitionListener) -> Boolean) =
        object : LayoutTransition.TransitionListener {
            private var done = false

            override fun startTransition(t: LayoutTransition, c: ViewGroup?, v: View?, type: Int) = Unit

            override fun endTransition(t: LayoutTransition, c: ViewGroup?, v: View?, type: Int) {
                if (done || type != LayoutTransition.CHANGE_APPEARING || t.isChangingLayout) return
                done = true
                card.post { t.removeTransitionListener(this) } // Not while it calls its listeners.
                if (!current(this)) return
                val gap = (16 * resources.displayMetrics.density).toInt()
                card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height + gap), false)
            }
        }

    /** System default, Light mode, Dark mode: Android recreates the screen in the new mode. */
    private fun appearance() {
        val choices = arrayOf<TextView>(findViewById(R.id.appearance_system), findViewById(R.id.appearance_light), findViewById(R.id.appearance_dark))
        fun mark() {
            val chosen = Appearance.get(this)
            for ((i, v) in choices.withIndex()) segment(v, i == chosen)
        }
        for ((i, v) in choices.withIndex()) v.setOnClickListener {
            if (i == Appearance.get(this)) return@setOnClickListener
            Appearance.set(this, i)
            mark() // When the mode stays the same, as System default while the system is in that mode, nothing recreates.
        }
        mark()
        // The labels at one size: the largest, from 14 sp down to 11, at which the longest fits its third of the row.
        choices[0].addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l == or - ol) return@addOnLayoutChangeListener // Only on a new width.
            val t = v as TextView
            val room = t.width - t.paddingLeft - t.paddingRight
            val paint = Paint(t.paint)
            var sp = 14f
            while (sp > 11f) {
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
                if (choices.all { paint.measureText(it.text.toString()) <= room }) break
                sp -= 0.5f
            }
            t.post { for (c in choices) c.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) } // Not inside the layout pass.
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        Last.listeners += refresh
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_ACTIVE_DEVICE_CHANGED)
            addAction(ACTION_CODEC_CONFIG_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        // Only a sender holding BLUETOOTH_PRIVILEGED (the Bluetooth stack) can trigger a refresh.
        registerReceiver(receiver, filter, BLUETOOTH_PRIVILEGED, null, RECEIVER_EXPORTED)
        audio.registerAudioPlaybackCallback(playback, main)
        showLast() // The Lock section needs no Bluetooth.
        connect()
        session()
    }

    override fun onStop() {
        started = false
        gen++
        unregisterReceiver(receiver)
        audio.unregisterAudioPlaybackCallback(playback)
        main.removeCallbacks(look)
        main.removeCallbacks(media)
        main.removeCallbacks(fresh)
        Last.listeners -= refresh
        Last.notice = null
        a2dp?.let { adapter.closeProfileProxy(A2DP, it) }
        a2dp = null
        pending = false
        super.onStop()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) = show()

    /** Gets the A2DP proxy while Bluetooth is on; while it's off, says so instead. */
    private fun connect() {
        if (!adapter.isEnabled) { // Also while turning on or off.
            a2dp?.let { adapter.closeProfileProxy(A2DP, it) }
            a2dp = null
            pending = false
            return empty(R.string.bluetooth_off)
        }
        if (a2dp != null) return show()
        if (!pending) pending = adapter.getProfileProxy(this, this, A2DP)
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        pending = false
        if (!started) return adapter.closeProfileProxy(A2DP, proxy) // Asked before onStop, answered after it.
        a2dp = proxy as BluetoothA2dp
        show()
    }

    override fun onServiceDisconnected(profile: Int) {
        a2dp = null
    }

    private fun segment(v: TextView, on: Boolean) {
        v.isSelected = on // TalkBack says "Selected" on the chosen one.
        v.setBackgroundResource(if (on) R.drawable.segment_on else R.drawable.segment)
        v.setTextColor(getColor(if (on) R.color.accent_text else R.color.text))
    }

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.5f
    }

    private fun state(icon: Int, color: Int, text: CharSequence) {
        val c = getColor(color)
        state.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        state.compoundDrawableTintList = ColorStateList.valueOf(c)
        state.setTextColor(c)
        state.text = text
        state.shown = true
    }

    private fun showLast() {
        val p = Phone.path(this)
        val hz = Rate.target(this, p) // Until the slider is moved, the phone's own, known after the first lock.
        rate.progress = Rate.HZ.indexOf(hz).coerceAtLeast(0)
        rateValue.text = khz(hz)
        rate.stateDescription = rateValue.text // TalkBack says "48 kHz", not "33 percent".
        val q = Quality.target(this)
        for ((i, v) in qualities.withIndex()) {
            v.text = getString(R.string.kbps, Quality.kbps(Quality.ALL[i], hz))
            segment(v, Quality.ALL[i] == q)
        }
        val s = if (p?.hardware == true) R.string.lock_summary_direct else R.string.lock_summary_hop
        summary.text = getString(s, khz(hz), Quality.name(q))
        // A notice, as Pause playback first, is a lock refused, and a failed lock a problem too: in the warning colour.
        // A lock that worked, in grey: the state line above already says Locked, in green.
        Last.notice?.let {
            last.text = it
            last.setTextColor(getColor(R.color.warning))
            last.shown = true
            return
        }
        val at = Last.at(this)
        last.shown = at > 0
        if (at == 0L) return
        val time = clock(at)
        val took = Last.took(this)
        last.setTextColor(getColor(if (Last.ok(this)) R.color.text2 else R.color.warning))
        last.text = if (Last.ok(this)) {
            getString(R.string.last_lock, time, if (took < 1000) "$took ms" else "%.1f s".format(took / 1000.0))
        } else {
            getString(R.string.last_failed, time, Last.text(this))
        }
    }

    /** Bluetooth off, no headphones, or no permission: only the message in the device card. */
    /** The device line: the headphones' kind in blue, then their battery as they report it, in orange when low. */
    private fun line(d: BluetoothDevice) {
        val kind = getString(d.kind())
        val level = d.battery()
        val battery = level?.let { getString(R.string.battery, it) }
        meta.text = SpannableString(listOfNotNull(kind, battery).joinToString(" · ")).apply {
            setSpan(ForegroundColorSpan(getColor(R.color.accent_text)), 0, kind.length, 0)
            if (battery != null && level <= LOW_BATTERY) {
                val at = kind.length + 3
                setSpan(ForegroundColorSpan(getColor(R.color.warning)), at, at + battery.length, 0)
            }
        }
        meta.shown = true
    }

    /**
     * Under the format, on RedMagic alone, whose mixer was measured at 48 kHz (the path keeps no other measured rate):
     * music at 44.1 kHz is resampled once, to that, in grey, as nothing changes it; LDAC at another rate adds a resample,
     * in orange, as Sample rate avoids it. Not counted: the music's own rate isn't known. In software (Samsung) the mixer
     * follows the codec: none.
     */
    private fun note(c: BluetoothCodecConfig) {
        val p = Phone.path(this)
        note.shown = c.isLdac && c.hz > 0 && p?.hardware == true && p.hz == 48000
        if (!note.shown) return
        val once = c.hz == 48000
        note.text = if (once) getString(R.string.resampled_once, khz(44100), khz(48000))
        else getString(R.string.resampled_ldac, khz(48000), khz(c.hz))
        note.setTextColor(getColor(if (once) R.color.text2 else R.color.warning))
    }

    private fun empty(message: Int) {
        gen++ // A read still out would show a codec under this message.
        config = null // Nor may a second read queued behind it.
        name.setText(message)
        // Nothing to lock, as with Bluetooth off, in the warning colour; a request (Allow Nearby devices) stays plain.
        name.setTextColor(getColor(if (message == R.string.no_bluetooth) R.color.text else R.color.warning))
        meta.shown = false
        panel.shown = false
        state.shown = false
        pair.shown = false
        enable(lock, false)
    }

    /** [again]: a look after a codec change, which leaves the state and format as they are until the new ones are in. */
    private fun show(again: Boolean = false) {
        val a = a2dp ?: return
        gen++
        config = null // Set again below for an encoder read; until then no read shows a codec from before.
        showLast()
        lock.setText(R.string.lock_now)
        if (checkSelfPermission(BLUETOOTH_CONNECT) != PERMISSION_GRANTED) return empty(R.string.no_bluetooth)
        val d = activeHeadphones(a) ?: return empty(R.string.no_device)

        name.text = d.name ?: d.address
        name.setTextColor(getColor(R.color.text))
        line(d)

        val paired = isPaired(d)
        pair.shown = !paired
        enable(pair, true)
        enable(lock, paired)
        panel.shown = paired
        if (!again) state.shown = false
        if (!paired) return

        val c = a.codecStatus(d)?.codecConfig ?: return
        codec.text = c.codecLabel
        hiRes.shown = c.isLdac
        if (!again || !c.isLdac) spec.text = specText(c.hz, c.bits, c.mode) // On LDAC, encoder() sets it.
        note(c)
        if (!c.isLdac) return state(R.drawable.ic_warning, R.color.warning, getString(R.string.not_locked)) // Codec is in the panel.

        // The DSP's encoder isn't in the dump, so a hardware path goes straight to the reported config.
        if (Phone.path(this)?.hardware == true) return encoder(null, c)
        // The reported config can't be trusted for quality; the encoder's state can (~40 ms, so off the main thread).
        config = c
        readFor = d.address
        if (!dumpable()) return encoder(null, c) // Not even tried without DUMP.
        // While music plays, a Bluetooth dump makes Samsung's audio skip: the encoder as last read stands while the
        // report is the same, else the report alone; it's read again once playback stops (see fresh).
        if (audio.isMusicActive) return encoder(LastRead.get(this, d.address, c.reported()), c, cached = true)
        read()
    }

    /** One encoder read at a time; a show() meanwhile gets one more read when this one is back, for its state. */
    private fun read() {
        if (reading) {
            reread = true
            return
        }
        reading = true
        val g = gen
        val at = readFor
        val r = config.reported()
        val lockAt = Last.at(this)
        Thread {
            val playing = audio.isMusicActive // Music that started since show(): no dump either.
            val e = if (playing) null else ldacEncoder()
            runOnUiThread {
                reading = false
                if (reread || playing) {
                    reread = false
                    // Not once the headphones or their codec are gone. Through show(), which knows about playback.
                    if (started && config != null) show(again = true)
                } else if (g == gen) {
                    // Kept only while nothing newer is: not after a lock meanwhile, which kept its own.
                    if (e != null && at != null && Last.at(this) == lockAt && !Last.isLocking) LastRead.save(this, at, r, e)
                    config?.let { encoder(e, it) }
                }
            }
        }.start()
    }

    /**
     * Whether a music app plays or waits, for the play button; none without one. With DUMP the app that gets the
     * keys, by its own state; without, only whether anything plays: a Play then might start an app nobody meant.
     */
    private fun session() {
        if (sessionReading) {
            sessionAgain = true
            return
        }
        sessionReading = true
        Thread {
            // With DUMP, always the state the music app reports to Android: the output can keep counting as playing for
            // a long while after a pause, as on the Astra. The media session's dump is small and quick, and safe while
            // music plays: it's Bluetooth's encoder dump that makes the audio skip. Without DUMP, the output alone.
            val p = mediaPlaying() ?: audio.isMusicActive.takeIf { it }
            runOnUiThread {
                sessionReading = false
                if (sessionAgain) {
                    sessionAgain = false
                    if (started) session()
                } else if (started) {
                    playButton(p)
                }
            }
        }.start()
    }

    private fun playButton(p: Boolean?) {
        playing = p
        play.shown = p != null
        if (p == null) return
        play.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
        play.contentDescription = getString(if (p) R.string.pause else R.string.play)
    }

    /** [cached]: music plays, so [e] is the encoder as last read, shown with when; or none for this report. */
    private fun encoder(e: Encoder?, c: BluetoothCodecConfig, cached: Boolean = false) {
        val hz = Last.hz(this)
        val q = Last.quality(this)
        val hardware = Phone.path(this)?.hardware == true
        spec.text = if (e == null) specText(c.hz, c.bits, c.mode) else specText(e.hz, e.bits, e.mode, e.kbps)
        val warn = R.drawable.ic_warning
        fun asOf(s: String) = if (cached) getString(R.string.checked_state, s, clock(LastRead.at(this))) else s
        when (verdict(e, c.reported(), hz, q, hardware, dumpable())) {
            Verdict.LOCKED -> state(R.drawable.ic_check, R.color.success, asOf(getString(R.string.locked_state, e?.quality)))
            // The Astra (offloaded LDAC): rate, bits and the reported quality, which its chip doesn't confirm.
            Verdict.REPORTED -> state(R.drawable.ic_check, R.color.text2, getString(R.string.locked_unreadable))
            Verdict.NOT_LOCKED -> state(
                warn, R.color.warning,
                if (e != null) asOf(getString(R.string.not_locked_state, e.quality)) else getString(R.string.not_locked), // The panel shows the rest.
            )
            // Music plays and the encoder wasn't read for this report: no fault, a wait (see fresh).
            Verdict.UNVERIFIED -> if (cached) {
                state(warn, R.color.text2, getString(R.string.playing_state))
            } else { // A software encoder belongs in the dump once DUMP is granted: a read that found none can't vouch for the lock.
                state(warn, R.color.warning, getString(R.string.unverified_state))
            }
            Verdict.NO_DUMP -> state(warn, R.color.warning, getString(R.string.no_dump_state))
        }
    }

    private fun clock(at: Long): String = DateFormat.getTimeFormat(this).format(Date(at))

    private fun pair() {
        val d = a2dp?.let { activeHeadphones(it) } ?: return
        enable(pair, false)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(d.address).build())
            .setSingleDevice(true)
            .build()
        cdm.associate(
            request, mainExecutor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(sender: IntentSender) =
                    startIntentSenderForResult(sender, 0, null, 0, 0, 0)

                override fun onAssociationCreated(association: AssociationInfo) = show()

                override fun onFailure(error: CharSequence?) {
                    enable(pair, true)
                    state(R.drawable.ic_warning, R.color.warning, error ?: getString(R.string.failed))
                }
            },
        )
    }
}
