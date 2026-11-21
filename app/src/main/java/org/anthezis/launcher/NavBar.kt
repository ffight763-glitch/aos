package org.anthezis.launcher

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * AOS navigation buttons (Back, Home, Recents) drawn in the launcher's theme right over the stock 3-button bar of SystemUI.
 * The window has exactly the size of the system bar, so apps keep their layout; the stock buttons underneath stay covered.
 *  - Back: system Back; hold: the AOS power menu
 *  - Home: AOS Home; hold: Control Center
 *  - Recents: AOS Recents; hold: split screen
 * The bar hides itself when the system bar is hidden (full-screen video, games), in landscape (the stock bar moves to
 * the side there) and on the lock screen.
 */
class NavBar(private val svc: SplitService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val p = Palette(svc)
    private val d = svc.resources.displayMetrics.density
    private var systemBarVisible = true
    private var locked = false
    /** Colours this bar was drawn with: a theme change elsewhere rebuilds the bar only when they really changed. */
    val signature = listOf(p.bg, p.text, p.accent, p.stroke)

    private val barH: Int = svc.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        .takeIf { it != 0 }?.let { svc.resources.getDimensionPixelSize(it) } ?: (48 * d).toInt()

    private val portrait get() = svc.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    /** Windows Phone style Start: a black bar with white Back, Start and Search keys, like on the phone. */
    private val metro = svc.getSharedPreferences("aos_setup", android.content.Context.MODE_PRIVATE).getString("home_style", "android") == "tiles"

    private val root = FrameLayout(svc).apply {
        // Opaque, so the stock buttons underneath never show through; a hairline on top like the AOS dock.
        background = if (metro) android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK) else android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(p.bg),
            android.graphics.drawable.ColorDrawable(p.stroke))).apply { setLayerGravity(1, Gravity.TOP); setLayerHeight(1, maxOf(1, (0.7f * d).toInt())) }
    }

    /**
     * Insets only reach windows below the bar that provides them, and the accessibility overlay sits above the system bar,
     * so it never learns when an app hides it. A 1-pixel see-through app overlay sits below the system bar and reports it.
     * Without the "display over other apps" permission there is no probe and the buttons simply stay on.
     */
    private val probe: View? = if (android.provider.Settings.canDrawOverlays(svc)) View(svc).apply {
        setOnApplyWindowInsetsListener { v, ins -> readInsets(ins); v.onApplyWindowInsets(ins) }
    } else null

    /**
     * Hidden only when the system really reports its bar hidden. Some windows get no navigation-bar source at all (both
     * sizes 0): that says nothing, so the buttons stay on.
     */
    private fun readInsets(ins: WindowInsets) {
        val known = ins.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom > 0
        systemBarVisible = !known || ins.isVisible(WindowInsets.Type.navigationBars())
        update()
    }
    private val probeLp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSPARENT).apply { gravity = Gravity.TOP or Gravity.START; fitInsetsTypes = 0; title = "AOS NavProbe"; alpha = 0.8f } // untouchable overlays above 0.8 get clamped (and logged) by the system

    private val lp = WindowManager.LayoutParams(-1, barH, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.BOTTOM
        fitInsetsTypes = 0                       // sit on the system bar itself, not above it
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "AOS NavBar"
    }

    init {
        val row = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        fun key(icon: Int, desc: String, tap: () -> Unit, hold: () -> Unit) = row.addView(Key(icon, desc, tap, hold),
            LinearLayout.LayoutParams(0, -1, 1f))
        val back = { svc.back(); Unit }
        val home = { svc.goHome(recents = false) }
        val recents = { svc.goHome(recents = true) }
        val power = { svc.powerMenu() }
        val cc = { svc.controlCenter(0) }
        val split = { svc.toggleSplit(); Unit }
        // Style: dot Recents, bar Home, dot Back (the default); pref "navbar_icons" = classic Back, Home, Recents icons.
        if (metro) {
            // Back (hold: the open apps, like on Windows Phone), Start (hold: Control Center), Search (hold: power menu).
            val search = {
                val i = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try { svc.startActivity(i) } catch (_: Exception) {
                    try { svc.startActivity(android.content.Intent(android.content.Intent.ACTION_ASSIST).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
            }
            key(R.drawable.ic_metro_back, "Back", back, recents)
            key(R.drawable.ic_metro_start, "Start", home, cc)
            key(R.drawable.ic_search, "Search", search, power)
        } else if (svc.getSharedPreferences("aos_setup", android.content.Context.MODE_PRIVATE).getBoolean("navbar_icons", false)) {
            key(R.drawable.ic_chevron_left, "Back", back, power)
            key(R.drawable.ic_home, "Home", home, cc)
            key(R.drawable.ic_recents, "Recents", recents, split)
        } else {
            key(DOT, "Recents", recents, split)
            key(BAR, "Home", home, cc)
            key(DOT, "Back", back, power)
        }
        // The three keys sit in the middle 70% of the width, like the stock bar.
        root.addView(row, FrameLayout.LayoutParams((svc.resources.displayMetrics.widthPixels * 0.7f).toInt(), -1, Gravity.CENTER))
    }

    fun show() {
        try { wm.addView(root, lp) } catch (_: Exception) {}
        probe?.let { try { wm.addView(it, probeLp) } catch (_: Exception) {} }
        update()
    }
    fun remove() {
        try { wm.removeView(root) } catch (_: Exception) {}
        probe?.let { try { wm.removeView(it) } catch (_: Exception) {} }
    }
    fun setLocked(on: Boolean) { locked = on; update() }

    private fun update() {
        val on = systemBarVisible && portrait && !locked
        android.util.Log.d("AosNav", "bar=$systemBarVisible portrait=$portrait locked=$locked -> $on")
        root.visibility = if (on) View.VISIBLE else View.INVISIBLE
        val flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags != lp.flags) { lp.flags = flags; if (root.isAttachedToWindow) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {} }
    }

    /** One key: the icon in the theme's text colour, a soft accent circle while pressed, a tap and a hold action. */
    @SuppressLint("ViewConstructor")
    private inner class Key(icon: Int, desc: String, private val tap: () -> Unit, private val hold: () -> Unit) : FrameLayout(svc) {
        private val glow = View(svc).apply {
            background = svc.circle(android.graphics.Color.argb(if (p.uiDark) 70 else 50,
                android.graphics.Color.red(p.accent), android.graphics.Color.green(p.accent), android.graphics.Color.blue(p.accent)))
            alpha = 0f; scaleX = 0.6f; scaleY = 0.6f
        }
        private val img: View = when (icon) {
            // Dot and bar are plain shapes in the theme's text colour, no icon.
            // The dots are not drawn (only the bar shows), but their places still work as Recents / Back.
            DOT -> View(svc).apply { background = svc.circle(p.text); visibility = View.INVISIBLE }
            BAR -> View(svc).apply { background = svc.shape(p.text, 99) }
            else -> ImageView(svc).apply { setImageResource(icon); setColorFilter(if (metro) android.graphics.Color.WHITE else p.text) }
        }.apply { contentDescription = desc }
        private var held = false
        private val holdRun = Runnable { held = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); hold() }

        init {
            val s = (barH * 0.86f).toInt()
            addView(glow, LayoutParams(s, s, Gravity.CENTER))
            val (w, h) = when (icon) {
                // Sized in dp (the bar is only ~24dp tall in realme's gesture layout), capped by the bar height.
                DOT -> minOf((7 * d).toInt(), (barH * 0.4f).toInt()).let { it to it }
                BAR -> (38 * d).toInt() to minOf((3.5f * d).toInt(), (barH * 0.2f).toInt()).coerceAtLeast(3)
                else -> (barH * 0.46f).toInt().let { it to it }
            }
            addView(img, LayoutParams(w, h, Gravity.CENTER))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    held = false
                    glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(90).start()
                    img.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    postDelayed(holdRun, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(holdRun)
                    glow.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(160).start()
                    img.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    if (e.actionMasked == MotionEvent.ACTION_UP && !held) tap()
                }
            }
            return true
        }
    }

    private companion object {
        const val DOT = -1
        const val BAR = -2
    }
}
