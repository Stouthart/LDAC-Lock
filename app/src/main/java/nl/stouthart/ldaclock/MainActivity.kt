package nl.stouthart.ldaclock

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_PRIVILEGED
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
import android.os.Bundle
import android.text.SpannableString
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import java.util.Date

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
    private val refresh = Runnable { show() }
    private lateinit var name: TextView
    private lateinit var meta: TextView
    private lateinit var panel: View
    private lateinit var codec: TextView
    private lateinit var hiRes: View
    private lateinit var spec: TextView
    private lateinit var state: TextView
    private lateinit var pair: TextView
    private lateinit var summary: TextView
    private lateinit var lock: TextView
    private lateinit var last: TextView
    private var a2dp: BluetoothA2dp? = null
    private var pending = false // A proxy request is out; Android answers once Bluetooth is on.
    private var gen = 0 // Drops encoder reads that a newer show() has overtaken.

    // Live while visible: Bluetooth on/off, a headphone (dis)connecting, the active headset changing.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothAdapter.ACTION_STATE_CHANGED) connect() else show()
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        name = findViewById(R.id.name)
        meta = findViewById(R.id.meta)
        panel = findViewById(R.id.panel)
        codec = findViewById(R.id.codec)
        hiRes = findViewById(R.id.hi_res)
        spec = findViewById(R.id.spec)
        state = findViewById(R.id.state)
        pair = findViewById(R.id.pair)
        summary = findViewById(R.id.summary)
        lock = findViewById(R.id.lock)
        last = findViewById(R.id.last)
        findViewById<TextView>(R.id.copyright).text = getString(R.string.copyright, BuildConfig.VERSION_NAME)
        pair.setOnClickListener { pair() }
        lock.setOnClickListener {
            enable(lock, false)
            lock.setText(R.string.locking)
            startForegroundService(Intent(this, LockService::class.java))
        }
        if (checkSelfPermission(BLUETOOTH_CONNECT) != PERMISSION_GRANTED) requestPermissions(arrayOf(BLUETOOTH_CONNECT), 0)
        appearance()
    }

    /** System default, Light mode, Dark mode: Android recreates the screen in the new mode. */
    private fun appearance() {
        val choices = arrayOf<TextView>(findViewById(R.id.appearance_system), findViewById(R.id.appearance_light), findViewById(R.id.appearance_dark))
        fun mark() {
            val chosen = Appearance.get(this)
            for ((i, v) in choices.withIndex()) {
                v.setBackgroundResource(if (i == chosen) R.drawable.segment_on else R.drawable.segment)
                v.setTextColor(getColor(if (i == chosen) R.color.on_accent else R.color.text))
            }
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
        Last.listeners += refresh
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_ACTIVE_DEVICE_CHANGED)
        }
        // Only a sender holding BLUETOOTH_PRIVILEGED (the Bluetooth stack) can trigger a refresh.
        registerReceiver(receiver, filter, BLUETOOTH_PRIVILEGED, null, RECEIVER_EXPORTED)
        showLast() // The Lock section needs no Bluetooth.
        connect()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
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
        a2dp = proxy as BluetoothA2dp
        show()
    }

    override fun onServiceDisconnected(profile: Int) {
        a2dp = null
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
        summary.text = if (p?.hardware == true) getString(R.string.lock_summary_direct, khz(p.hz))
        else getString(R.string.lock_summary_hop, khz(p?.hz ?: 44100))
        Last.notice?.let {
            last.text = it
            last.shown = true
            return
        }
        val at = Last.at(this)
        last.shown = at > 0
        if (at == 0L) return
        val time = DateFormat.getTimeFormat(this).format(Date(at))
        val took = Last.took(this)
        last.text = if (Last.ok(this)) {
            getString(R.string.last_lock, time, if (took < 1000) "$took ms" else "%.1f s".format(took / 1000.0))
        } else {
            getString(R.string.last_failed, time, Last.text(this))
        }
    }

    /** Bluetooth off, no headphone, or no permission: only the message in the device card. */
    private fun empty(message: Int) {
        name.setText(message)
        meta.shown = false
        panel.shown = false
        state.shown = false
        pair.shown = false
        enable(lock, false)
    }

    private fun show() {
        val a = a2dp ?: return
        val g = ++gen
        showLast()
        lock.setText(R.string.lock_now)
        if (checkSelfPermission(BLUETOOTH_CONNECT) != PERMISSION_GRANTED) return empty(R.string.no_bluetooth)
        val d = activeHeadphone(a) ?: return empty(R.string.no_device)

        val kind = getString(d.kind())
        name.text = d.name ?: d.address
        meta.text = SpannableString("$kind · ${d.address}").apply {
            setSpan(ForegroundColorSpan(getColor(R.color.accent)), 0, kind.length, 0)
        }
        meta.shown = true

        val paired = isPaired(d)
        pair.shown = !paired
        enable(pair, true)
        enable(lock, paired)
        panel.shown = paired
        state.shown = false
        if (!paired) return

        val c = a.codecStatus(d)?.codecConfig ?: return
        codec.text = c.codecLabel
        hiRes.shown = c.isLdac
        spec.text = specText(c.hz, c.bits, c.mode)
        if (!c.isLdac) return state(R.drawable.ic_warning, R.color.warning, getString(R.string.not_locked)) // Codec is in the panel.

        // The DSP's encoder isn't in the dump, so a hardware path goes straight to the reported config.
        if (Phone.path(this)?.hardware == true) return encoder(null, c)
        // The reported config can't be trusted for quality; the encoder's state can (~40 ms, so off the main thread).
        Thread {
            val e = ldacEncoder()
            runOnUiThread { if (g == gen) encoder(e, c) }
        }.start()
    }

    private fun encoder(e: Encoder?, c: BluetoothCodecConfig) {
        val hz = Last.hz(this)
        if (e == null) { // Astra (offloaded LDAC) or no DUMP: rate and bits only.
            return if (c.lockedAt(hz, quality = Phone.path(this)?.hardware == true)) {
                state(R.drawable.ic_check, R.color.text2, getString(R.string.locked_unreadable))
            } else {
                state(R.drawable.ic_warning, R.color.warning, getString(R.string.not_locked)) // The panel shows the rest.
            }
        }
        spec.text = specText(e.hz, e.bits, e.mode, e.kbps)
        if (e.lockedAt(hz)) state(R.drawable.ic_check, R.color.success, getString(R.string.locked_state, e.quality))
        else state(R.drawable.ic_warning, R.color.warning, getString(R.string.not_locked_state, e.quality))
    }

    private fun pair() {
        val d = a2dp?.let { activeHeadphone(it) } ?: return
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
