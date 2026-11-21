package org.anthezis.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Prototype bridge for system features a normal app cannot reach: split-screen toggle plus Xiaomi-style full-screen
 * gestures, drawn as thin invisible touch strips over every app (accessibility overlay):
 *  - bottom edge, swipe up: Home; swipe up and hold: Recents
 *  - left/right edge, swipe inward: Back
 * In the AOS firmware these live in the system navigation code; this is the same behaviour for the prototype.
 */
class SplitService : AccessibilityService() {
    private val strips = mutableListOf<View>()

    /**
     * While the phone is locked every strip lets its touches through, so the system keeps its own unlock swipe, PIN pad and
     * shade on the keyguard; the strips come back the moment the phone is unlocked.
     */
    private val onLock: (Boolean) -> Unit = { locked -> android.os.Handler(android.os.Looper.getMainLooper()).post { setStripsTouchable(!locked) } }

    /** AOS navigation buttons over the stock 3-button bar, see [NavBar]. */
    private var navBar: NavBar? = null

    /** A theme or style change in the launcher repaints the buttons (only when their colours really changed). */
    private val onPrefs = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "aos_navbar" || key == "navbar_icons" || key == "home_style") { refreshGestures(); return@OnSharedPreferenceChangeListener }
        val bar = navBar ?: return@OnSharedPreferenceChangeListener
        val now = Palette(this).let { listOf(it.bg, it.text, it.accent, it.stroke) }
        if (now != bar.signature) refreshNavBar()
    }

    override fun onServiceConnected() {
        instance = this; refreshGestures()
        // The screen and lock events (and the AOS lock screen) are handled in one place, see [LockScreen].
        LockScreen.register(this, accessibility = true)
        LockScreen.listeners += onLock
        setStripsTouchable(!LockScreen.isLocked(this))
        getSharedPreferences("aos_setup", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onPrefs)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        getSharedPreferences("aos_setup", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(onPrefs)
        LockScreen.listeners -= onLock
        LockScreen.unregister(this)
        removeStrips(); navBar?.remove(); navBar = null; instance = null
        return super.onUnbind(intent)
    }

    /** Rotation, dark mode or font size: rebuild the strips and the buttons for the new screen. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshGestures()
    }

    private fun navBarOn() = getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getBoolean("aos_navbar", true)

    private fun refreshNavBar() {
        navBar?.remove(); navBar = null
        if (!navBarOn()) return
        navBar = NavBar(this).also { it.show(); it.setLocked(LockScreen.isLocked(this)) }
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(recents: Boolean) = home(recents)
    fun powerMenu() = powerMenu.show()
    fun controlCenter(panel: Int) {
        try {
            startActivity(Intent(this, ControlCenterActivity::class.java).putExtra(ControlCenterActivity.EXTRA_PANEL, panel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: Exception) { pendingCc = panel; home(recents = false) }
    }

    private fun setStripsTouchable(on: Boolean) {
        navBar?.setLocked(!on)
        val wm = getSystemService(WindowManager::class.java)
        for (v in strips) (v.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
            val flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (flags != lp.flags) { lp.flags = flags; try { wm.updateViewLayout(v, lp) } catch (_: Exception) {} }
        }
    }

    /**
     * The 3-button Recents key opens the stock Recents of the system launcher, which a normal launcher cannot replace.
     * As soon as that screen appears, go Home and show the AOS Recents instead.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val cls = event.className?.toString().orEmpty()
        if (event.packageName != packageName && cls.contains("RecentsActivity")) home(recents = true)
    }
    override fun onInterrupt() {}

    /** The volume keys are taken here, so the stock volume bar never appears; AOS draws its own slim panel instead. */
    private var volumeView: VolumePanel? = null

    // ---- Power button: a hold opens the AOS power menu instead of the stock one; a short press still locks the screen. ----
    private val powerMenu by lazy { PowerMenu(this) }
    private val powerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var powerHeld = false
    private var powerLong = false
    private val powerLongPress = Runnable { powerLong = true; powerMenu.show() }

    private fun handlePower(event: android.view.KeyEvent): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        // With the screen off the button must wake the phone: let the system have it.
        if (!getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getBoolean("power_menu", true) || !pm.isInteractive) return false
        when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> if (!powerHeld) { powerHeld = true; powerLong = false; powerHandler.postDelayed(powerLongPress, 550) }
            android.view.KeyEvent.ACTION_UP -> {
                powerHandler.removeCallbacks(powerLongPress)
                val wasLong = powerLong
                powerHeld = false; powerLong = false
                if (!wasLong) lockScreen()
            }
        }
        return true
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_POWER) return handlePower(event)
        val up = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        val down = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if ((!up && !down) || !getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getBoolean("volume_panel", true)) return false
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val audio = getSystemService(android.media.AudioManager::class.java)
            audio.adjustSuggestedStreamVolume(if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, 0)
            val stream = if (audio.mode == android.media.AudioManager.MODE_IN_CALL || audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION)
                android.media.AudioManager.STREAM_VOICE_CALL else android.media.AudioManager.STREAM_MUSIC
            (volumeView ?: VolumePanel(this).also { volumeView = it }).show(audio.getStreamVolume(stream).toFloat() / audio.getStreamMaxVolume(stream), stream)
        }
        return true
    }

    fun toggleSplit(): Boolean = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
    fun powerDialog(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    fun screenshot(): Boolean = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    private fun removeStrips() {
        val wm = getSystemService(WindowManager::class.java)
        for (v in strips) try { wm.removeView(v) } catch (_: Exception) {}
        strips.clear()
    }

    /** (Re)creates the gesture strips from the "gestures" preference. */
    fun refreshGestures() {
        removeStrips()
        refreshNavBar()
        if (!getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getBoolean("gestures", true)) return
        val d = resources.displayMetrics.density
        val wm = getSystemService(WindowManager::class.java)
        fun add(w: Int, h: Int, gravity: Int, v: View) {
            val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply {
                this.gravity = gravity
                // Reach the very edge of the screen, also behind the camera cutout, so the top strip really sits over the status bar.
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            try { wm.addView(v, lp); strips += v } catch (_: Exception) {}
        }
        // Full-width gesture bar along the bottom edge (taller than before, so it always catches the swipe before the
        // stock handle does), and tall back-swipe strips on both sides.
        // With the AOS buttons on, the bottom edge belongs to them (no swipe-up strip on top of the keys).
        if (!navBarOn()) add(-1, (30 * d).toInt(), Gravity.BOTTOM, Strip(bottom = true))
        // A strip along the very top: a swipe down opens the AOS Control Center (left half: notifications, right half: controls).
        add(-1, (34 * d).toInt(), Gravity.TOP, TopStrip())
        // Side strips stay clear of the top and bottom corners so they do not fight the status bar or the home strip.
        val sideH = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        add((18 * d).toInt(), sideH, Gravity.START or Gravity.CENTER_VERTICAL, Strip(bottom = false, fromLeft = true))
        add((18 * d).toInt(), sideH, Gravity.END or Gravity.CENTER_VERTICAL, Strip(bottom = false, fromLeft = false))
        if (LockScreen.isLocked(this)) setStripsTouchable(false)   // rebuilt while locked: stay out of the keyguard's way
    }

    private fun home(recents: Boolean) {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or (if (recents) Intent.FLAG_ACTIVITY_NO_ANIMATION else 0))
            .putExtra(EXTRA_RECENTS, recents))
    }

    /** Opens the AOS Home with its Control Center from any app: 1 = notifications (left half), 0 = controls (right half). */
    private inner class TopStrip : View(this@SplitService) {
        private val d = resources.displayMetrics.density
        private var x0 = 0f
        private var y0 = 0f
        private var done = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; done = false }
                MotionEvent.ACTION_MOVE -> if (!done && e.rawY - y0 > 40 * d && Math.abs(e.rawX - x0) < (e.rawY - y0)) {
                    done = true
                    val left = x0 < resources.displayMetrics.widthPixels / 2f
                    // On the AOS home the home's own top bar opens the panel: do not open a second one over it.
                    if (homeInFront) return true
                    // The Control Center opens right over the app (an accessibility service may start a screen from the
                    // background). If the system refuses, fall back to the Home, which opens it on resume (see [pendingCc]).
                    try {
                        startActivity(Intent(this@SplitService, ControlCenterActivity::class.java)
                            .putExtra(ControlCenterActivity.EXTRA_PANEL, if (left) 1 else 0)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    } catch (_: Exception) {
                        pendingCc = if (left) 1 else 0
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                }
            }
            return true
        }
    }

    private inner class Strip(private val bottom: Boolean, private val fromLeft: Boolean = true) : View(this@SplitService) {
        private val d = resources.displayMetrics.density
        private var x0 = 0f
        private var y0 = 0f
        private var done = false
        private var lastMove = 0L
        private var lastY = 0f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; done = false; lastMove = e.eventTime; lastY = y0 }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawY - lastY) > 3 * d) { lastMove = e.eventTime; lastY = e.rawY }
                    if (!bottom && !done) {
                        val dx = if (fromLeft) e.rawX - x0 else x0 - e.rawX
                        if (dx > 56 * d) { done = true; performGlobalAction(GLOBAL_ACTION_BACK) }
                    }
                }
                MotionEvent.ACTION_UP -> if (bottom && !done) {
                    if (y0 - e.rawY > 70 * d) {
                        done = true
                        // Stopped moving before lifting = "hold" -> Recents; a flick -> Home.
                        home(recents = e.eventTime - lastMove > 160)
                    }
                }
            }
            return true
        }
    }

    companion object {
        @Volatile var instance: SplitService? = null
        /** Set by the top strip: which Control Center panel the Home should open when it comes to the front (-1 = none). */
        @Volatile var pendingCc = -1
    }
}
