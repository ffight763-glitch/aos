package org.anthezis.launcher

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Date

/**
 * AOS lock screen in the chosen theme: the theme's scene (animated when "Animated theme" is on), the home clock with
 * date and weather, a music card with controls, notifications, a torch and a camera shortcut.
 * Android keeps the real keyguard (PIN, fingerprint) in the system: this screen sits on top of it the moment the
 * display turns on, and a slide to the right asks the system to unlock. Started by [LockScreen] when the screen turns on.
 * Turn it off with AOS Settings > Lock screen. Screen and lock events come from [LockScreen].
 */
class LockActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var list: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var media: LinearLayout
    private lateinit var status: TextView
    private var scene: SceneFx? = null
    private var wallpaperFx: WallpaperFxDrawable? = null
    private val tiltMotion by lazy { TiltMotion(this) { x, y ->
        wallpaperFx?.setTilt(x, y)
        scene?.apply {
            val motion = Fx.on(this@LockActivity, "tilt")
            scaleX = if (motion) 1.06f else 1f; scaleY = scaleX
            translationX = -x * dp(12); translationY = -y * dp(18)
        }
    } }
    private lateinit var unlockSlider: SlideToConfirm
    private lateinit var chargingFx: ChargingFx
    private var unlocking = false
    private var torchOn = false
    private var controller: MediaController? = null

    private fun text(t: String, sp: Float, c: Int, w: Int = 400) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(w)
    }

    private fun hideTopBar() {
        window.insetsController?.apply {
            hide(android.view.WindowInsets.Type.statusBars())
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Same two-column clock and information layout as the main desktop. */
    private fun buildClockWidget(): View {
        val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val hf = prefs.getFloat("widget_hf", 1f).coerceIn(0.6f, 1.8f)
        val wf = prefs.getFloat("widget_wf", 1f).coerceIn(0.5f, 1f)
        val halo = if (p.dark) 0xAA000000.toInt() else 0x99FFFFFF.toInt()
        val full = resources.displayMetrics.widthPixels - dp(32)
        val digitsPx = minOf(dp((96 * hf).toInt()).toFloat(), full * wf * 0.46f / 1.12f)
        fun digits(f24: String, f12: String, color: Int) = TextClock(this).apply {
            format24Hour = f24; format12Hour = f12; setTextColor(color); typeface = appFont(700)
            includeFontPadding = false; gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, digitsPx)
            setShadowLayer(dp(12).toFloat(), 0f, dp(2).toFloat(), halo)
        }
        val clock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(digits("HH", "h", p.text)); addView(digits("mm", "mm", p.accent))
        }
        ClockStyles.face(this, p)?.let { face ->
            clock.removeAllViews()
            clock.addView(face, LinearLayout.LayoutParams(-1, (digitsPx * 2.05f).toInt()))
        }
        fun chip(label: String) = text(label, 14f, p.text, 700).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            background = if (prefs.getString("desk_panels", "none") == "solid") shape(p.surfaceHigh, 16) else glassShape(p, 16)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        val now = Date()
        val information = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(text(DateFormat.format("EEEE", now).toString().replaceFirstChar { it.uppercase() },
                20f * hf.coerceIn(0.8f, 1.3f), p.text, 700).apply {
                gravity = Gravity.CENTER; maxLines = 1; setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), halo)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(text(DateFormat.format("d MMMM yyyy", now).toString(), 15f * hf.coerceIn(0.8f, 1.3f), p.text).apply {
                gravity = Gravity.CENTER; alpha = 0.85f; maxLines = 1; setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), halo)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            val weather = chip("🌡️  …")
            addView(weather, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            Weather.load(this@LockActivity) { value -> runOnUiThread { weather.text = value ?: "🌡️  —" } }
            getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.let { alarm ->
                addView(chip("⏰  " + DateFormat.format(if (DateFormat.is24HourFormat(this@LockActivity)) "EEE HH:mm" else "EEE h:mm a", alarm.triggerTime)),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(clock, LinearLayout.LayoutParams(0, -2, 0.92f))
            addView(View(this@LockActivity).apply { background = shape((p.accent and 0xFFFFFF) or 0x99000000.toInt(), 2) },
                LinearLayout.LayoutParams(dp(3), dp(170)).apply { marginStart = dp(6); marginEnd = dp(14) })
            addView(information, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return FrameLayout(this).apply { addView(box, FrameLayout.LayoutParams((full * wf).toInt(), -2, Gravity.CENTER_HORIZONTAL)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Fx.refresh(this)
        p = Palette(this)
        // Prepared while the screen is off (see [LockScreen]): it must not wake the screen up by itself.
        setShowWhenLocked(true)
        window.statusBarColor = 0; window.navigationBarColor = 0
        // The theme's own picture behind everything, the same one the Home uses.
        AosStyles.windowBackground(this, p.dark)?.let { drawable ->
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap != null) {
                wallpaperFx = WallpaperFxDrawable(bitmap).apply { parallax = false; tiltEnabled = Fx.on(this@LockActivity, "tilt") }
                window.setBackgroundDrawable(wallpaperFx)
            } else window.setBackgroundDrawable(drawable)
        }

        val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        media = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        status = text("", 14f, p.text, 700).apply {
            gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(16), 0, dp(16), 0); background = glassShape(p, 20, true)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(40), dp(16), 0)
            addView(ImageView(this@LockActivity).apply { setImageResource(R.drawable.ic_lock); setColorFilter(p.text) },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(12) })
            addView(buildClockWidget(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(media, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
            addView(android.widget.ScrollView(this@LockActivity).apply {
                isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list)
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        }

        // Shortcuts remain separate from the deliberate slide-to-unlock gesture.
        fun round(icon: Int, onClick: (ImageView) -> Unit) = ImageView(this).apply {
            setImageResource(icon); setColorFilter(p.text); setPadding(dp(15), dp(15), dp(15), dp(15))
            background = glassShape(p, 30); setOnClickListener { onClick(this) }; pressFx(p)
        }
        val shortcuts = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(round(R.drawable.ic_flashlight) { toggleTorch(it) }, LinearLayout.LayoutParams(dp(56), dp(56)))
            // The charging line sits on a glass pill so it reads over any scene.
            addView(FrameLayout(this@LockActivity).apply {
                addView(status, FrameLayout.LayoutParams(-2, dp(40), Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(round(R.drawable.ic_camera) { unlock { startActivity(Intent(this@LockActivity, CameraActivity::class.java)) } }, LinearLayout.LayoutParams(dp(56), dp(56)))
        }
        unlockSlider = SlideToConfirm(this, p, "slide to unlock →", { unlock() }, R.drawable.ic_lock)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), dp(28))
            addView(shortcuts, LinearLayout.LayoutParams(-1, dp(56)))
            addView(unlockSlider, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(16) })
        }

        val root = FrameLayout(this).apply {
            if (prefs.getBoolean("anim_theme", true)) {
                val st = AosStyles.selectedId(this@LockActivity)
                if (st != "custom") addView(SceneFx(this@LockActivity, st, p.dark).also { scene = it }, FrameLayout.LayoutParams(-1, -1))
            }
            // A soft shade at the bottom so the controls read on any scene.
            addView(View(this@LockActivity).apply {
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0, if (p.uiDark) 0x99000000.toInt() else 0x66FFFFFF))
            }, FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM))
            addView(content, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(184) })
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            chargingFx = ChargingFx(this@LockActivity, p)
            addView(chargingFx.view, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        hideTopBar()
        // Arrival: the clock block settles in from slightly below.
        content.alpha = 0f; content.translationY = dp(30).toFloat()
        content.animate().alpha(1f).translationY(0f).setDuration(Fx.dur(520)).setInterpolator(Fx.settle(this)).start()
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val rawLevel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val level = if (rawLevel >= 0 && scale > 0) (rawLevel * 100 / scale).coerceIn(0, 100) else 50
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            status.text = if (st == BatteryManager.BATTERY_STATUS_FULL) "Charged · 100%" else if (charging) "Charging · $level%" else "Slide right to unlock"
            status.setTextColor(if (charging) p.accent else p.text)
            val enabled = getSharedPreferences("aos_setup", MODE_PRIVATE).getBoolean("charging_fx", true)
            if (::chargingFx.isInitialized) chargingFx.apply(charging && enabled,
                charging && enabled && (st == BatteryManager.BATTERY_STATUS_FULL || level >= 100), level)
        }
    }

    override fun onResume() {
        super.onResume()
        unlocking = false
        unlockSlider.reset()
        hideTopBar()
        content.translationY = 0f; content.alpha = 1f
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery.onReceive(this, it) }
        if (getSystemService(android.os.PowerManager::class.java).isInteractive) { scene?.start(); tiltMotion.start() }
        refresh()
        NotifService.onLockChange = { runOnUiThread { refresh() } }
        bindMedia()
    }

    override fun onPause() {
        try { unregisterReceiver(battery) } catch (_: Exception) {}
        scene?.stop()
        tiltMotion.stop()
        if (::chargingFx.isInitialized) chargingFx.stop()
        NotifService.onLockChange = null
        controller?.unregisterCallback(mediaCallback); controller = null
        super.onPause()
    }

    /** The display turned on / off while this screen waits: run the animated scene only while it can be seen. */
    fun screenOn() {
        unlocking = false; unlockSlider.reset(); hideTopBar()
        content.translationY = 0f; content.alpha = 1f; scene?.start(); tiltMotion.start()
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery.onReceive(this, it) }
        refresh()
    }
    fun screenOff() {
        scene?.stop(); tiltMotion.stop(); unlockSlider.reset()
        if (::chargingFx.isInitialized) chargingFx.stop()
    }

    override fun onDestroy() {
        if (torchOn) try { getSystemService(android.hardware.camera2.CameraManager::class.java).let { cm -> cm.setTorchMode(cm.cameraIdList.first(), false) } } catch (_: Exception) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun toggleTorch(btn: ImageView) {
        try {
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
            btn.background = if (torchOn) shape(p.accent, 30) else glassShape(p, 30)
            btn.setColorFilter(if (torchOn) p.onAccent else p.text)
        } catch (_: Exception) {}
    }

    // ---- music card ----
    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) = paintMedia()
        override fun onPlaybackStateChanged(s: PlaybackState?) = paintMedia()
        override fun onSessionDestroyed() { controller = null; paintMedia() }
    }

    private fun bindMedia() {
        controller = try {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this, NotifService::class.java))
                .firstOrNull { it.metadata != null }
        } catch (_: SecurityException) { null }
        controller?.registerCallback(mediaCallback)
        paintMedia()
    }

    private fun paintMedia() {
        val c = controller
        val m = c?.metadata
        media.removeAllViews()
        if (c == null || m == null) { media.visibility = View.GONE; return }
        media.visibility = View.VISIBLE
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val art = m.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val appIcon = try { packageManager.getApplicationIcon(c.packageName) } catch (_: Exception) { null }
        fun ctrl(icon: Int, big: Boolean = false, act: () -> Unit) = ImageView(this).apply {
            setImageResource(icon); setColorFilter(if (big) p.onAccent else p.text)
            setPadding(dp(if (big) 13 else 10), dp(if (big) 13 else 10), dp(if (big) 13 else 10), dp(if (big) 13 else 10))
            background = if (big) circle(p.accent) else null
            setOnClickListener { act() }; pressFx(p)
        }
        media.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glassShape(p, 26, true); setPadding(dp(14), dp(14), dp(14), dp(10))
            addView(LinearLayout(this@LockActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@LockActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = shape(p.surfaceHigh, 14)
                    if (art != null) setImageBitmap(art) else setImageDrawable(appIcon)
                }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(LinearLayout(this@LockActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                    addView(text(m.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), 15f, p.text, 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(), 13f, p.sub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(this@LockActivity).apply { setImageDrawable(appIcon) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            })
            addView(LinearLayout(this@LockActivity).apply {
                gravity = Gravity.CENTER
                val t = c.transportControls
                addView(ctrl(R.drawable.ic_skip_prev) { t.skipToPrevious() }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(ctrl(if (playing) R.drawable.ic_pause else R.drawable.ic_play_plain, big = true) { if (playing) t.pause() else t.play() },
                    LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginStart = dp(24); marginEnd = dp(24) })
                addView(ctrl(R.drawable.ic_skip_next) { t.skipToNext() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        })
    }

    // ---- notifications ----
    private fun refresh() {
        list.removeAllViews()
        val svc = NotifService.instance ?: return
        val items = svc.activeNotifications.filter { !it.isOngoing && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }.take(4)
        for (sbn in items) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            val icon = try { packageManager.getApplicationIcon(sbn.packageName) } catch (_: Exception) { null }
            list.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 22, true); setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(ImageView(this@LockActivity).apply { setImageDrawable(icon?.let { AosIcons.themed(this@LockActivity, it, p, 34) }) }, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(LinearLayout(this@LockActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(title, 15f, p.text, 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(body, 13f, p.sub).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                // Opening a notification needs the phone unlocked first.
                setOnClickListener { unlock { try { sbn.notification.contentIntent?.send() } catch (_: Exception) {} } }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun unlock(after: () -> Unit = {}) {
        if (unlocking) return
        unlocking = true
        content.animate().translationY(-dp(160).toFloat()).alpha(0f).setDuration(Fx.dur(220)).start()
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { finish(); after(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { if (!isFinishing) finish(); after() }
            override fun onDismissCancelled() { unlocking = false; unlockSlider.reset(); content.animate().translationY(0f).alpha(1f).setDuration(Fx.dur(300)).start() }
            override fun onDismissError() { unlocking = false; unlockSlider.reset(); content.animate().translationY(0f).alpha(1f).setDuration(Fx.dur(300)).start() }
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* the lock screen stays until the phone is unlocked */ }

    companion object {
        @Volatile var instance: LockActivity? = null
    }
}

/**
 * Shows the AOS lock screen over the system keyguard when the display turns on while the phone is locked. Registered
 * by the notification listener (always running once notification access is given) and by the gesture service.
 */
/**
 * The one owner of the screen and lock events for AOS (Realme UI / Android 15-16, no root). Both system-bound services call
 * [register]; the accessibility service wins when it is on, because only it may open a screen from the background on these
 * Android versions (the notification listener is the fallback, and its start may be refused: then the system lock screen
 * simply stays). The system keyguard is never replaced: the AOS lock screen sits on top of it and a swipe asks the system to
 * unlock, so PIN, fingerprint and face unlock stay the system's own.
 */
object LockScreen {
    private var registeredIn: Context? = null
    private var viaAccessibility = false
    private var lastStart = 0L

    /** Told when the phone gets locked or unlocked (true = locked), e.g. so the gesture strips let the system have its touches. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun isLocked(c: Context) = c.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> {
                    val locked = isLocked(c)
                    for (l in listeners) l(locked)
                    LockActivity.instance?.screenOn()
                    val enabled = c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getBoolean("lock_screen", true)
                    val now = android.os.SystemClock.uptimeMillis()
                    // Only over a real keyguard, once per wake-up (both services used to start it, and a second start
                    // stacked a second screen).
                    if (enabled && locked && LockActivity.instance == null && now - lastStart > 1500) try {
                        lastStart = now
                        c.startActivity(Intent(c, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    } catch (_: Exception) {}
                }
                // Screen off: get the AOS lock screen ready now, so it is already there when the screen turns on
                // (no flash of the stock lock screen, and no blocked start from the background).
                Intent.ACTION_SCREEN_OFF -> {
                    for (l in listeners) l(true)
                    val enabled = c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getBoolean("lock_screen", true)
                    val ready = LockActivity.instance
                    if (ready != null) ready.screenOff()
                    else if (enabled) try {
                        lastStart = android.os.SystemClock.uptimeMillis()
                        c.startActivity(Intent(c, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    } catch (_: Exception) {}
                }
                // Unlocked by the system (PIN, fingerprint, face, Smart Lock): the AOS lock screen must not stay over the phone.
                Intent.ACTION_USER_PRESENT -> { LockActivity.instance?.let { if (!it.isFinishing) it.finish() }; for (l in listeners) l(false) }
            }
        }
    }

    /** [accessibility]: the caller is the accessibility service, which takes the events over from the notification listener. */
    @Synchronized fun register(c: Context, accessibility: Boolean = false) {
        if (registeredIn != null) {
            if (!accessibility || viaAccessibility) return
            try { registeredIn?.unregisterReceiver(receiver) } catch (_: Exception) {}
            registeredIn = null
        }
        c.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        registeredIn = c; viaAccessibility = accessibility
    }

    @Synchronized fun unregister(c: Context) {
        if (registeredIn !== c) return
        try { c.unregisterReceiver(receiver) } catch (_: Exception) {}
        registeredIn = null; viaAccessibility = false
        // The other service may still be running: let it pick the events up again.
        NotifService.instance?.let { if (it !== c) register(it) }
    }
}
