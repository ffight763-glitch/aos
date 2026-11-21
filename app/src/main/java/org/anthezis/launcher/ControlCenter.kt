package org.anthezis.launcher

import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Volume-style bar made of separate segments. Touch or drag sets the value (0..1). */
class SegmentedBar(ctx: Context, private val p: Palette, private val segments: Int = 12,
                   private val onChange: (Float) -> Unit) : View(ctx) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val gap = 4 * d
        val w = (width - gap * (segments - 1)) / segments
        val r = 5 * d
        for (i in 0 until segments) {
            paint.color = if ((i + 0.5f) / segments <= value) p.accent else frostFill(p)
            val l = i * (w + gap)
            c.drawRoundRect(l, height * 0.18f, l + w, height * 0.82f, r, r, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent.requestDisallowInterceptTouchEvent(true)
            value = e.x / width
            onChange(value)
        }
        return true
    }
}

/** Tall rounded slider (brightness, volume): the fill rises from the bottom, the glyph sits at the foot. */
class VerticalSlider(ctx: Context, private val p: Palette, icon: Int, private val onChange: (Float) -> Unit) : View(ctx) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = ctx.getDrawable(icon)!!.mutate()
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val r = 24 * d
        val rect = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = frostFill(p); c.drawRoundRect(rect, r, r, paint)
        c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) })
        paint.color = p.accent; c.drawRect(0f, height * (1 - value), width.toFloat(), height.toFloat(), paint)
        c.restore()
        val s = (26 * d).toInt(); val bottom = height - (14 * d).toInt()
        glyph.setTint(if (value > 0.12f) p.onAccent else p.text)
        glyph.setBounds((width - s) / 2, bottom - s, (width + s) / 2, bottom); glyph.draw(c)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent.requestDisallowInterceptTouchEvent(true)
            value = 1f - e.y / height
            onChange(value)
        }
        return true
    }
}

/**
 * AOS Control Center. Replaces the stock quick settings panel for every entry point on AOS Home.
 * Toggles that Android lets a normal app flip (flashlight, volume, ringer) work directly; for radios the tile shows
 * the real state and opens the matching system screen, because only system apps may switch them silently.
 */
class ControlCenter(private val act: Activity, private val p: Palette, private val onOpenChanged: (Boolean) -> Unit = {}) {
    private class Tile(val label: String, val icon: Int, val isOn: () -> Boolean, val onClick: () -> Unit)

    private val camera = act.getSystemService(CameraManager::class.java)
    private val torchId: String? = camera.cameraIdList.firstOrNull {
        camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
    private var torchOn = false
    private val tileViews = mutableListOf<Pair<Tile, ImageView>>()
    private val audio = act.getSystemService(AudioManager::class.java)
    private val cr = act.contentResolver

    private fun dp(v: Int) = act.dp(v)
    private fun open(action: String) {
        try { act.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { act.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        close()
    }

    private val tiles = listOf(
        Tile("Wi-Fi", R.drawable.ic_wifi,
            { act.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }) { open(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) },
        Tile("Bluetooth", R.drawable.ic_bluetooth,
            { act.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }) { open(Settings.ACTION_BLUETOOTH_SETTINGS) },
        Tile("Mobile", R.drawable.ic_signal, { false }) { open(Settings.ACTION_NETWORK_OPERATOR_SETTINGS) },
        Tile("Airplane", R.drawable.ic_airplane,
            { Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }) { open(Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
        Tile("Location", R.drawable.ic_pin,
            { act.getSystemService(LocationManager::class.java).isLocationEnabled }) { open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
        Tile("Flashlight", R.drawable.ic_boost, { torchOn }) { toggleTorch() },
        Tile("Power saver", R.drawable.ic_battery,
            { act.getSystemService(PowerManager::class.java).isPowerSaveMode }) { open(Settings.ACTION_BATTERY_SAVER_SETTINGS) },
        Tile("Settings", R.drawable.ic_info, { false }) { act.startActivity(Intent(act, SettingsActivity::class.java)); close() },
        // second page
        Tile("Rotation", R.drawable.ic_star,
            { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }) { open(Settings.ACTION_DISPLAY_SETTINGS) },
        Tile("Do not disturb", R.drawable.ic_legal,
            { act.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL }) { open(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS) },
        Tile("Dark mode", R.drawable.ic_style,
            { p.dark }) { toggleDark() },
        // Features of the stock Realme panel. Radios open the system screen; screenshot and lock use the AOS service.
        Tile("Hotspot", R.drawable.ic_signal, { false }) { open("android.settings.TETHER_SETTINGS") },
        Tile("NFC", R.drawable.ic_language,
            { runCatching { android.nfc.NfcAdapter.getDefaultAdapter(act)?.isEnabled == true }.getOrDefault(false) }) { open(Settings.ACTION_NFC_SETTINGS) },
        Tile("Cast", R.drawable.ic_play, { false }) { open(Settings.ACTION_CAST_SETTINGS) },
        Tile("Data saver", R.drawable.ic_boost, { false }) { open("android.settings.DATA_SAVER_SETTINGS") },
        Tile("Screenshot", R.drawable.ic_split, { false }) { service { it.screenshot() } },
        Tile("Lock screen", R.drawable.ic_lock, { false }) { service { it.lockScreen() } },
        Tile("Camera", R.drawable.ic_star, { false }) {
            try { act.startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            close()
        },
        Tile("Wallpaper", R.drawable.ic_style, { false }) {
            try { act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) } catch (_: Exception) {}
            close()
        },
        Tile("Battery", R.drawable.ic_battery, { false }) { open(Settings.ACTION_BATTERY_SAVER_SETTINGS) },
    )

    /** AOS power menu. Android only lets the system itself shut down or reboot, so those two open its own dialog. */
    private fun powerMenu() {
        close()
        val dlg = android.app.Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        // iOS slider on top, Samsung / HyperOS round shortcut buttons below.
        fun round(icon: Int, label: String, tint: Int, onClick: () -> Unit) = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ImageView(act).apply {
                setImageResource(icon); setColorFilter(tint); setPadding(dp(18), dp(18), dp(18), dp(18)); background = act.circle(frostFill(p))
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(text(label, 12f, p.text).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
            setOnClickListener { dlg.dismiss(); onClick() }
        }
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = act.frostShape(p, 30); setPadding(dp(22), dp(22), dp(22), dp(20))
            addView(text("Power", 20f, p.text, true))
            addView(text("Slide to power off or restart", 12f, p.sub).apply { setPadding(0, dp(2), 0, dp(16)) })
            addView(SlideToConfirm(act, p, "slide to power off", { dlg.dismiss(); service { it.powerDialog() } }), LinearLayout.LayoutParams(-1, dp(60)))
            addView(LinearLayout(act).apply {
                gravity = Gravity.CENTER
                addView(round(R.drawable.ic_lock, "Lock", p.text) { service { it.lockScreen() } }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(round(R.drawable.ic_split, "Screenshot", p.text) { service { it.screenshot() } }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(round(R.drawable.ic_tools, "Settings", p.text) { act.startActivity(Intent(act, SettingsActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
            addView(text("Cancel", 15f, p.text, true).apply {
                gravity = Gravity.CENTER; background = act.shape(frostFill(p), 24); setOnClickListener { dlg.dismiss() }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(20) })
        }
        dlg.setContentView(FrameLayout(act).apply { setPadding(dp(24), 0, dp(24), 0); addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) })
        dlg.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setLayout(-1, -2); setDimAmount(0.55f) }
        dlg.show()
    }

    /** Screenshot and lock need the accessibility service; close the panel first so it is not in the picture. */
    private fun service(block: (SplitService) -> Unit) {
        val s = SplitService.instance
        if (s == null) {
            Toast.makeText(act, "Turn on AOS Split Screen in Accessibility first", Toast.LENGTH_LONG).show()
            act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); close(); return
        }
        close()
        view.postDelayed({ block(s) }, 400)
    }

    private fun toggleTorch() {
        val id = torchId
        if (id == null) { Toast.makeText(act, "This device has no flashlight", Toast.LENGTH_SHORT).show(); return }
        torchOn = !torchOn
        try { camera.setTorchMode(id, torchOn) } catch (_: Exception) { torchOn = !torchOn }
        refreshTiles()
    }

    private fun toggleDark() {
        act.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).edit().putInt("theme", if (p.dark) 1 else 2).apply()
        act.recreate()
    }

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    private fun roundButton(icon: Int, onClick: (ImageView) -> Unit) = ImageView(act).apply {
        setImageResource(icon); setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { onClick(this) }
    }

    private fun paintButton(b: ImageView, on: Boolean) {
        b.setColorFilter(if (on) p.onAccent else p.text)
        b.background = act.shape(if (on) p.accent else frostFill(p), 18)
    }

    /** Row: leading icon, segmented bar, mode button on the right. */
    private fun sliderRow(icon: Int, bar: SegmentedBar, mode: ImageView): View = LinearLayout(act).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(act).apply { setImageResource(icon); setColorFilter(p.sub) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(bar, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
        addView(mode, LinearLayout.LayoutParams(dp(42), dp(42)))
    }

    private val notifBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val notifScroll = android.widget.ScrollView(act).apply {
        isVerticalScrollBarEnabled = true
        addView(notifBox)
    }

    // No dimmed backdrop: the panel is a menu that opens under the top bar; this transparent layer only catches taps to close it.
    private val scrim = View(act).apply { setOnClickListener { close() } }

    private val card = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = act.frostShape(p, 30)
        setPadding(dp(16), dp(18), dp(16), dp(18))
        // One fixed size for both panels, so switching Controls/Notifications never makes the card jump.
        minimumHeight = (act.resources.displayMetrics.heightPixels * 0.56f).toInt()
    }

    val view: FrameLayout = FrameLayout(act).apply {
        visibility = View.GONE
        addView(scrim, FrameLayout.LayoutParams(-1, -1))
        addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(58), dp(12), 0) })   // right under the top bar
    }

    val isOpen get() = view.visibility == View.VISIBLE

    // ---- Windows Phone style (home style "tiles"): a black Action Center dropping from the top edge ----
    private val metro = act.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getString("home_style", "android") == "tiles"
    private var metroExpanded = false
    private val metroQuick = GridLayout(act).apply { columnCount = 4 }
    private val metroNotifs = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val metroExpand = text("Expand", 13f, android.graphics.Color.WHITE).apply {
        setPadding(dp(4), dp(10), dp(4), dp(10))
        setOnClickListener { metroExpanded = !metroExpanded; paintMetroQuick() }
    }
    private val metroCard: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(android.graphics.Color.argb(245, 0, 0, 0))
        setPadding(dp(10), dp(30), dp(10), dp(12))
        isClickable = true
        val head = LinearLayout(act).apply {
            gravity = Gravity.BOTTOM
            addView(android.widget.TextClock(act).apply {
                format12Hour = "h:mm"; format24Hour = "H:mm"; textSize = 40f; setTextColor(android.graphics.Color.WHITE); typeface = appFont(300)
            })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, dp(8))
                addView(android.widget.TextClock(act).apply {
                    format12Hour = "EEEE"; format24Hour = "EEEE"; textSize = 13f; setTextColor(android.graphics.Color.WHITE)
                })
                addView(android.widget.TextClock(act).apply {
                    format12Hour = "d MMMM"; format24Hour = "d MMMM"; textSize = 13f; setTextColor(android.graphics.Color.argb(170, 255, 255, 255))
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(act).apply { setImageResource(R.drawable.ic_info); setColorFilter(android.graphics.Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { act.startActivity(Intent(act, SettingsActivity::class.java)); close() } },
                LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        addView(metroQuick, LinearLayout.LayoutParams(-1, -2))
        addView(metroExpand, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        addView(View(act).apply { setBackgroundColor(android.graphics.Color.argb(60, 255, 255, 255)) }, LinearLayout.LayoutParams(-1, 1))
        addView(android.widget.ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(metroNotifs) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        // The grab line at the bottom: a tap (or anything below the panel) closes it.
        addView(View(act).apply { background = act.shape(android.graphics.Color.argb(150, 255, 255, 255), 2); setOnClickListener { close() } },
            LinearLayout.LayoutParams(dp(44), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        visibility = View.GONE
    }
    init { if (metro) view.addView(metroCard, FrameLayout.LayoutParams(-1, (act.resources.displayMetrics.heightPixels * 0.86f).toInt(), Gravity.TOP)) }

    private fun paintMetroQuick() {
        metroQuick.removeAllViews()
        val gap = dp(4)
        val w = (act.resources.displayMetrics.widthPixels - dp(20) - 3 * gap) / 4
        val shown = if (metroExpanded) tiles else tiles.take(4)
        for ((i, t) in shown.withIndex()) {
            val on = runCatching { t.isOn() }.getOrDefault(false)
            metroQuick.addView(FrameLayout(act).apply {
                background = android.graphics.drawable.ColorDrawable(if (on) p.accent else android.graphics.Color.rgb(38, 38, 38))
                addView(ImageView(act).apply { setImageResource(t.icon); setColorFilter(if (on) p.onAccent else android.graphics.Color.WHITE) },
                    FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
                addView(text(t.label, 11f, if (on) p.onAccent else android.graphics.Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(4), dp(6)) })
                setOnClickListener { t.onClick(); view.postDelayed({ if (isOpen) paintMetroQuick() }, 400) }
            }, GridLayout.LayoutParams().apply { width = w; height = w * 3 / 4; setMargins(0, 0, if (i % 4 == 3) 0 else gap, gap) })
        }
        metroExpand.text = if (metroExpanded) "Collapse" else "Expand"
    }

    private fun paintMetroNotifs() {
        metroNotifs.removeAllViews()
        val white = android.graphics.Color.WHITE; val dim = android.graphics.Color.argb(170, 255, 255, 255)
        val svc = NotifService.instance
        if (svc == null) {
            metroNotifs.addView(text("Allow notification access", 14f, white, true).apply {
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { try { act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}; close() }
            }); return
        }
        val list = svc.visibleNotifications()
        if (list.isEmpty()) { metroNotifs.addView(text("No new notifications", 15f, dim).apply { setPadding(0, dp(12), 0, 0) }); return }
        // Grouped by app like on the phone: the app name, then its notifications under it.
        for ((pkg, items) in list.groupBy { it.packageName }) {
            val appName = runCatching { act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            metroNotifs.addView(text(appName.uppercase(), 11f, p.accent, true).apply { setPadding(0, dp(12), 0, dp(2)) })
            for (sbn in items) {
                val ex = sbn.notification.extras
                val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().takeUnless { it.isNullOrBlank() } ?: appName
                val body = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
                metroNotifs.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(6))
                    addView(text(title, 15f, white).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    if (body.isNotBlank()) addView(text(body, 13f, dim).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    setOnClickListener {
                        try { sbn.notification.contentIntent?.send(); if (sbn.isClearable) svc.cancelNotification(sbn.key); close() }
                        catch (_: android.app.PendingIntent.CanceledException) { paintMetroNotifs() }
                    }
                })
            }
        }
        if (list.any { it.isClearable }) metroNotifs.addView(text("Clear all", 13f, white).apply {
            setPadding(0, dp(14), dp(4), dp(6)); gravity = Gravity.END
            setOnClickListener { list.filter { it.isClearable }.forEach { svc.cancelNotification(it.key) }; paintMetroNotifs() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * Where the panel opens. The AOS top bar sits above the dock, so by default the panel rises from the bottom and stands
     * just above it; pulling down from the top edge still drops it from the top. [bottomGap] = room to leave under the panel.
     */
    var bottomGap: () -> Int = { dp(24) + navInset() }
    private var fromBottom = true
    private fun navInset(): Int {
        val res = act.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (res != 0) act.resources.getDimensionPixelSize(res) else 0
    }
    private fun slide() = (if (fromBottom) dp(36) else -dp(28)).toFloat()

    private lateinit var tileDots: LinearLayout
    private lateinit var arrowLeft: View
    private lateinit var arrowRight: View
    private lateinit var tilePager: android.widget.ViewFlipper
    private var tilePage = -1
    private lateinit var brightnessBar: VerticalSlider
    private lateinit var volumeBar: VerticalSlider
    private val controlsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val modeTabs = mutableListOf<TextView>()

    /** 0 = Controls, 1 = Notifications (left side of the top bar opens this one, like HyperOS). */
    private fun setPanel(i: Int) {
        controlsBox.visibility = if (i == 0) View.VISIBLE else View.GONE
        notifScroll.visibility = if (i == 1) View.VISIBLE else View.GONE
        modeTabs.forEachIndexed { k, t ->
            t.setTextColor(if (k == i) p.onAccent else p.sub)
            t.background = act.shape(if (k == i) p.accent else frostFill(p), 17)
        }
        if (i == 1) refreshNotifs()
    }
    private lateinit var autoBtn: ImageView
    private lateinit var ringerBtn: ImageView

    private fun setTilePage(page: Int, pages: Int) {
        val selected = page.coerceIn(0, maxOf(0, pages - 1))
        if (selected == tilePage) return
        if (tilePage >= 0) {
            val direction = if (selected > tilePage) 1f else -1f
            val relative = android.view.animation.Animation.RELATIVE_TO_SELF
            tilePager.inAnimation = android.view.animation.TranslateAnimation(relative, direction, relative, 0f,
                relative, 0f, relative, 0f).apply { duration = 180; interpolator = Motion.easeOut }
            tilePager.outAnimation = android.view.animation.TranslateAnimation(relative, 0f, relative, -direction,
                relative, 0f, relative, 0f).apply { duration = 180; interpolator = Motion.easeOut }
        }
        tilePager.displayedChild = selected
        tilePage = selected
        tileDots.removeAllViews()
        for (i in 0 until pages) {
            tileDots.addView(View(act).apply { background = act.circle(if (i == selected) p.accent else (p.sub and 0xFFFFFF) or 0x66000000) },
                LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        arrowLeft.isEnabled = selected > 0
        arrowRight.isEnabled = selected < pages - 1
        // Both round buttons stay visible on every page (so the pair always frames the dots); only the chevron dims at an end.
        for (a in listOf(arrowLeft, arrowRight)) (a as ImageView).setColorFilter(if (a.isEnabled) p.text else (p.sub and 0xFFFFFF) or 0x66000000)
    }

    init {
        val head = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Control Center", 18f, p.text, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_power); setColorFilter(p.text); setPadding(dp(8), dp(8), dp(8), dp(8))
                background = act.circle(frostFill(p)); contentDescription = "Power"; setOnClickListener { powerMenu() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
            addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(p.text); setPadding(dp(8), dp(8), dp(8), dp(8))
                background = act.circle(frostFill(p)); setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        card.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Two panels like HyperOS and iOS: Controls (right side of the top bar) and Notifications (left side).
        val modeRow = LinearLayout(act).apply {
            gravity = Gravity.CENTER
            for ((i, name) in listOf("Controls", "Notifications").withIndex()) addView(text(name, 13f, p.sub, true).apply {
                gravity = Gravity.CENTER; setOnClickListener { setPanel(i) }; modeTabs += this
            }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        card.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Brightness and volume: tall rounded sliders.
        brightnessBar = VerticalSlider(act, p, R.drawable.ic_style) { v ->
            if (Settings.System.canWrite(act)) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (v * 255).toInt().coerceAtLeast(1))
            else askWriteSettings()
        }
        volumeBar = VerticalSlider(act, p, R.drawable.ic_play) { v ->
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt().coerceIn(0, max), 0)
        }
        // Auto-brightness and ringer buttons keep their logic (long-press the sliders' icons is not needed), they are not drawn.
        autoBtn = roundButton(R.drawable.ic_style) {
            if (Settings.System.canWrite(act)) {
                val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                refreshModes()
            } else askWriteSettings()
        }
        ringerBtn = roundButton(R.drawable.ic_play) {
            try {
                audio.ringerMode = if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_VIBRATE
                else AudioManager.RINGER_MODE_NORMAL
            } catch (_: SecurityException) {
                Toast.makeText(act, "Allow Do Not Disturb access to change the ringer", Toast.LENGTH_SHORT).show()
            }
            refreshModes()
        }

        // Connectivity card: Wi-Fi, Bluetooth, Mobile and Airplane as a 2x2 block.
        val clusterNames = listOf("Wi-Fi", "Bluetooth", "Mobile", "Airplane")
        val cluster = GridLayout(act).apply {
            columnCount = 2; rowCount = 2; background = act.shape(frostFill(p), 24); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        for (name in clusterNames) tiles.firstOrNull { it.label == name }?.let { t ->
            val icon = ImageView(act).apply { setImageResource(t.icon); setPadding(dp(14), dp(14), dp(14), dp(14)); setOnClickListener { t.onClick() } }
            tileViews.add(t to icon)
            cluster.addView(icon, GridLayout.LayoutParams().apply { width = dp(52); height = dp(52); setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        val topRow = LinearLayout(act).apply {
            addView(cluster, LinearLayout.LayoutParams(-2, dp(152)).apply { marginEnd = dp(10) })
            addView(brightnessBar, LinearLayout.LayoutParams(0, dp(152), 1f).apply { marginEnd = dp(10) })
            addView(volumeBar, LinearLayout.LayoutParams(0, dp(152), 1f))
        }

        // Fixed tile pages with a centred navigation row below, without vertical scrolling.
        val innerW = act.resources.displayMetrics.widthPixels - dp(24) - dp(32)
        val cellW = innerW / 4
        val pageH = dp(196)
        val pages = tiles.filter { it.label !in clusterNames }.chunked(8)
        tilePager = android.widget.ViewFlipper(act)
        for (chunk in pages) {
            val grid = GridLayout(act).apply { columnCount = 4 }
            for (t in chunk) {
                val icon = ImageView(act).apply { setImageResource(t.icon); setPadding(dp(15), dp(15), dp(15), dp(15)) }
                tileViews.add(t to icon)
                grid.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))
                    addView(text(t.label, 11f, p.text).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(6), 0, 0) })
                    setOnClickListener { t.onClick() }
                }, GridLayout.LayoutParams().apply { width = cellW; height = dp(96) })
            }
            tilePager.addView(grid, FrameLayout.LayoutParams(-1, pageH))
        }
        tileDots = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun arrow(delta: Int) = ImageView(act).apply {
            setImageResource(R.drawable.ic_chevron_up); setColorFilter(p.text)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            rotation = if (delta < 0) -90f else 90f
            background = act.shape(frostFill(p), 10)
            // Sits on the left / right edge of the tile grid, half in the card's margin, so it never covers an icon.
            translationX = dp(if (delta < 0) -6 else 6).toFloat()
            contentDescription = if (delta < 0) "Previous tile page" else "Next tile page"
            setOnClickListener { setTilePage(tilePage + delta, pages.size) }
        }
        arrowLeft = arrow(-1); arrowRight = arrow(1)
        controlsBox.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        // The arrows frame the tile grid (left and right, centred on its height); only the page dots stay below it.
        controlsBox.addView(FrameLayout(act).apply {
            clipChildren = false
            addView(tilePager, FrameLayout.LayoutParams(-1, pageH))
            addView(arrowLeft, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(arrowRight, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.END or Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(-1, pageH))
        controlsBox.addView(tileDots, LinearLayout.LayoutParams(-1, dp(28)))
        setTilePage(0, pages.size)
        card.addView(controlsBox, LinearLayout.LayoutParams(-1, -2))
        card.addView(notifScroll, LinearLayout.LayoutParams(-1, 0, 1f))   // the notifications take the card's free height
        setPanel(0)

        camera.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchId) { torchOn = enabled; refreshTiles() }
            }
        }, null)
    }

    /** Notification list under the sliders; needs the user's notification access, otherwise shows how to grant it. */
    fun refreshNotifications() { if (isOpen && notifScroll.visibility == View.VISIBLE) refreshNotifs() }

    private fun refreshNotifs() {
        notifBox.removeAllViews()
        val svc = NotifService.instance
        if (svc == null) {
            notifBox.addView(text("Allow notification access", 14f, p.onAccent, true).apply {
                gravity = Gravity.CENTER; background = act.shape(p.accent, 22)
                setOnClickListener {
                    try { act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
                    close()
                }
            }, LinearLayout.LayoutParams(-1, dp(44)))
            return
        }
        val list = svc.visibleNotifications()
        notifBox.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Notifications", 15f, p.sub, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (list.any { it.isClearable }) addView(text("Clear all", 13f, p.accent, true).apply {
                setPadding(dp(8), dp(4), dp(8), dp(4)); setOnClickListener { list.filter { it.isClearable }.forEach { svc.cancelNotification(it.key) }; refreshNotifs() }
            })
        })
        if (list.isEmpty()) notifBox.addView(text("No new notifications", 13f, p.sub).apply { setPadding(0, dp(8), 0, 0) })
        for (sbn in list) {
            val extras = sbn.notification.extras
            val appName = runCatching { act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().takeUnless { it.isNullOrBlank() } ?: appName
            val body = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().takeUnless { it.isNullOrBlank() }
                ?: extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.joinToString("\n").takeUnless { it.isNullOrBlank() }
                ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            val icon = try { act.packageManager.getApplicationIcon(sbn.packageName) } catch (_: Exception) { null }
            notifBox.addView(LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL; background = act.shape(frostFill(p), 16); setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(ImageView(act).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(title, 14f, p.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(body, 12f, p.sub).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                setOnClickListener {
                    val action = sbn.notification.contentIntent
                    if (action != null) try {
                        action.send()
                        if (sbn.isClearable && sbn.notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0) svc.cancelNotification(sbn.key)
                        close()
                    } catch (_: android.app.PendingIntent.CanceledException) { refreshNotifs() }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    private fun askWriteSettings() {
        Toast.makeText(act, "Allow AOS Home to change system settings", Toast.LENGTH_SHORT).show()
        act.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${act.packageName}")))
    }

    private fun refreshModes() {
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        paintButton(autoBtn, auto)
        paintButton(ringerBtn, audio.ringerMode != AudioManager.RINGER_MODE_NORMAL)
    }

    private fun refreshTiles() {
        for ((t, icon) in tileViews) {
            val on = runCatching { t.isOn() }.getOrDefault(false)
            icon.setColorFilter(if (on) p.onAccent else p.text)
            icon.background = act.shape(if (on) p.accent else frostFill(p), 18)
        }
    }

    fun show(panel: Int = 0, bottom: Boolean = true) {
        // Two sources can ask at the same moment (the top bar and the gesture strip): open only once.
        if (isOpen) { if (!metro) setPanel(panel); return }
        if (metro) {
            card.visibility = View.GONE
            paintMetroQuick(); paintMetroNotifs()
            metroCard.visibility = View.VISIBLE
            view.visibility = View.VISIBLE
            onOpenChanged(true)
            // Drops from the top edge like the Action Center.
            metroCard.translationY = -metroCard.layoutParams.height.toFloat()
            metroCard.animate().translationY(0f).setDuration(Fx.dur(240)).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            return
        }
        fromBottom = bottom
        // A fixed, tall card: from the bottom it fills the room above the dock (up to 72% of the screen), never squeezed.
        val screenH = act.resources.displayMetrics.heightPixels
        val gap = if (bottom) bottomGap() else 0
        val h = if (bottom) minOf((screenH * 0.72f).toInt(), screenH - gap - dp(56)) else (screenH * 0.62f).toInt()
        // Keep the two tile rows and the bottom navigation visible when opened from the top too.
        val available = screenH - gap - if (bottom) dp(24) else dp(82)
        val panelHeight = maxOf(h, minOf(dp(540), available)).coerceAtLeast(dp(360))
        card.layoutParams = FrameLayout.LayoutParams(-1, panelHeight, if (bottom) Gravity.BOTTOM else Gravity.TOP).apply {
            if (bottom) setMargins(dp(12), 0, dp(12), gap) else setMargins(dp(12), dp(58), dp(12), 0)
        }
        setPanel(panel)
        refreshTiles(); refreshModes(); refreshNotifs()
        brightnessBar.value = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        volumeBar.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        view.visibility = View.VISIBLE
        onOpenChanged(true)
        // Rises from the bar above the dock (or drops from the top edge): a short slide with a fade.
        card.alpha = 0f; card.translationY = slide()
        card.animate().alpha(1f).translationY(0f).setDuration(170).start()
    }

    fun close() {
        if (!isOpen) return
        onOpenChanged(false)
        if (metro) {
            metroCard.animate().translationY(-metroCard.height.toFloat()).setDuration(Fx.dur(180)).withEndAction { view.visibility = View.GONE }.start()
            return
        }
        card.animate().alpha(0f).translationY(slide()).setDuration(130).withEndAction { view.visibility = View.GONE }.start()
    }
}
