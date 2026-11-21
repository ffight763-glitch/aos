package org.anthezis.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.util.Date

private const val DEFAULT_PAGES = 3

/** Set by the gesture service on a swipe-up-and-hold: come home and open Recents. */
const val EXTRA_RECENTS = "aos_recents"
/** True while the AOS home is on screen: the gesture strip then lets the home open its own Control Center (only one opens). */
@Volatile var homeInFront = false

const val EXTRA_CC = "aos_cc"   // 0 = Control Center controls, 1 = notifications (from the top strip of the gesture service)

/** Horizontal pager: swipes freely, snaps to a page on release, reports the nearest page. */
/** Home desktops 1, 2, 3 stacked vertically: scrolls like a list but always settles on a whole page. */
internal class VPager(ctx: Context, private val onPage: (Int) -> Unit) : android.widget.ScrollView(ctx) {
    private var down = false
    private val snap = Runnable { if (!down && height > 0) { val t = Math.round(scrollY / height.toFloat()) * height; if (t != scrollY) smoothScrollTo(0, t) } }

    init { isVerticalScrollBarEnabled = false; overScrollMode = if (Fx.on(ctx, "elastic")) OVER_SCROLL_ALWAYS else OVER_SCROLL_NEVER; descendantFocusability = FOCUS_AFTER_DESCENDANTS }
    // A focused field (Notes widget) may take the keyboard, but never scrolls the desktops by itself.
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect?): Int = 0
    override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean) = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; removeCallbacks(snap) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { down = false; postDelayed(snap, 140) }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (height > 0) onPage(Math.round(t / height.toFloat()))
        removeCallbacks(snap); if (!down) postDelayed(snap, 140)
    }

    fun goTo(page: Int) = smoothScrollTo(0, page * height)

    /** Desktop to scroll back to once the pages have their new height (after the edit mode resized the pager). */
    private var pendingPage = -1

    /** Every desktop is exactly as tall as this pager (not the raw screen height), so pages and snapping always line up. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val page = if (oldh > 0) Math.round(scrollY / oldh.toFloat()) else 0
        (getChildAt(0) as? android.view.ViewGroup)?.let { col ->
            var changed = false
            for (i in 0 until col.childCount) col.getChildAt(i).layoutParams?.let { if (it.height != h) { it.height = h; changed = true } }
            // Scroll only after the column is laid out again: before that the old, shorter column clamps the scroll and the
            // desktop ends up shifted down (seen when leaving the edit mode on desktop 2).
            android.util.Log.d("AosVP", "size $oldh -> $h scrollY=$scrollY page=$page changed=$changed")
            // Never request a layout from inside this layout pass (it can be dropped: the pages stay at the old height, the next
            // desktop peeks in at the bottom and the scroll is clamped short). Ask for it right after.
            if (changed) { pendingPage = page; col.post { col.requestLayout() } }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // The size change and the first layout come in the same pass, while the column still has its old height (the scroll
        // would be clamped short and the desktop shows shifted). Wait for the pass in which the column is tall enough.
        val pg = pendingPage
        if (pg >= 0) android.util.Log.d("AosVP", "layout h=$height child=${getChildAt(0)?.height} pending=$pg scrollY=$scrollY")
        if (pg >= 0 && height > 0 && (getChildAt(0)?.height ?: 0) >= (pg + 1) * height) { pendingPage = -1; scrollTo(0, pg * height) }
    }
}

internal class Pager(ctx: Context, private val onPage: (Int) -> Unit) : HorizontalScrollView(ctx) {
    /** Keep the outer pager from stealing swipes (used by the app slider nested in a home page). */
    var lockParent = false
    /** Where a swipe from page `start` that would land on `target` really goes (desktops do not swipe into each other). */
    var redirect: ((Int, Int) -> Int)? = null

    init {
        isHorizontalScrollBarEnabled = false; overScrollMode = if (Fx.on(ctx, "elastic")) OVER_SCROLL_ALWAYS else OVER_SCROLL_NEVER
        // Fields inside the pages (the Feed translator, Notes) must be able to take focus and the keyboard,
        // but a focused child must never make the pager scroll on its own: see computeScrollDeltaToGetChildRectOnScreen.
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect?): Int = 0

    private var tracker: android.view.VelocityTracker? = null
    private var startPage = 0
    private var gx0 = 0f
    private var gy0 = 0f
    /** The gesture that just ended was mostly vertical (the desktops scroll up and down): it must never turn a horizontal page. */
    private var gestureVertical = false
    /** A nested scroller (the app slider) took this gesture: this pager must not turn a page when it ends. */
    private var childOwns = false

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        if (disallow) childOwns = true
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) childOwns = false
        if (lockParent && ev.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            tracker?.recycle(); tracker = android.view.VelocityTracker.obtain()
            gx0 = ev.rawX; gy0 = ev.rawY; gestureVertical = false
            if (width > 0) startPage = (scrollX + width / 2) / width
        }
        tracker?.addMovement(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            val dx = Math.abs(ev.rawX - gx0); val dy = Math.abs(ev.rawY - gy0)
            gestureVertical = dy > 24 * resources.displayMetrics.density && dy > dx * 1.2f
        }
        val r = super.dispatchTouchEvent(ev)
        // A vertical gesture taken over by the desktop column never reaches this pager's own touch handler, so a small sideways
        // nudge at its start would stay (the desktops sit a few pixels off). Settle on a whole page when any gesture ends.
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            // MotionEvent belongs to Android and may be recycled before a posted callback runs.
            settle(ev.actionMasked == MotionEvent.ACTION_CANCEL)
            tracker?.recycle()
            tracker = null
        }
        return r
    }

    private fun settle(cancelled: Boolean) {
        if (width <= 0) return
        val t = tracker
        t?.computeCurrentVelocity(1000)
        val vx = t?.xVelocity ?: 0f
        val d = scrollX - startPage * width
        val fast = 300 * resources.displayMetrics.density
        val step = when {
            cancelled || gestureVertical || childOwns -> 0
            vx < -fast -> 1
            vx > fast -> -1
            d > width * 0.12f -> 1
            d < -width * 0.12f -> -1
            else -> 0
        }
        val last = ((getChildAt(0)?.width ?: width) / width - 1).coerceAtLeast(0)
        val raw = (startPage + step).coerceIn(0, last)
        val target = (redirect?.invoke(startPage, raw) ?: raw).coerceIn(0, last) * width
        if (target != scrollX) smoothScrollTo(target, 0)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (width > 0) {
                // Quick flick or a short drag is enough to turn the page; no need to drag past the middle.
                val t = tracker
                t?.computeCurrentVelocity(1000)
                val vx = t?.xVelocity ?: 0f
                val d = scrollX - startPage * width
                val fast = 300 * resources.displayMetrics.density
                val step = when {
                    ev.actionMasked == MotionEvent.ACTION_CANCEL || gestureVertical || childOwns -> 0
                    vx < -fast -> 1
                    vx > fast -> -1
                    d > width * 0.12f -> 1
                    d < -width * 0.12f -> -1
                    else -> 0
                }
                val last = ((getChildAt(0)?.width ?: width) / width - 1).coerceAtLeast(0)
                val raw = (startPage + step).coerceIn(0, last)
                smoothScrollTo((redirect?.invoke(startPage, raw) ?: raw).coerceIn(0, last) * width, 0)
            }
            return true
        }
        return handled
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (width > 0) onPage((l + width / 2) / width)
        if (slideFx) applySlideFx()
        val range = (getChildAt(0)?.width ?: 0) - width
        if (range > 0) onScrollFraction?.invoke(l.toFloat() / range)
    }

    /** Page slide effect on/off (home pager only; the nested app slider stays plain). */
    var slideFx = false
    /** Scroll position 0..1 across all pages (drives the wallpaper parallax). */
    var onScrollFraction: ((Float) -> Unit)? = null
    private val pageMode by lazy { Fx.pageMode(context) }

    /** Page transition chosen in Settings > Effects (Slide, Cube 3D, Fade, Stack, Pinwheel); only transforms, drawn by the render thread. */
    private fun applySlideFx() {
        val strip = getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until strip.childCount) {
            val page = strip.getChildAt(i)
            Fx.pageTransform(page, ((page.left - scrollX).toFloat() / width).coerceIn(-1f, 1f), pageMode)
        }
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean) = false
}

/** Taskbar container: an upward swipe anywhere on it (also starting on an icon) fires [onSwipeUp]. */
private class SwipeUpBar(ctx: Context, private val onSwipeUp: () -> Unit) : LinearLayout(ctx) {
    private var downY = 0f
    private var fired = false
    /** Finger position over the dock (raw X), null when it lifts: drives the Liquid Dock. */
    var onFinger: ((Float?) -> Unit)? = null

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        onFinger?.invoke(if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL || fired) null else e.rawX)
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.rawY; fired = false }
            MotionEvent.ACTION_MOVE -> if (!fired && downY - e.rawY > 36 * resources.displayMetrics.density) {
                fired = true; onSwipeUp()
            }
        }
        return fired
    }

    // A touch that lands between the icons has no child to take it; the bar itself must still see the swipe.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.rawY; fired = false }
            MotionEvent.ACTION_MOVE -> if (!fired && downY - e.rawY > 36 * resources.displayMetrics.density) {
                fired = true; onSwipeUp()
            }
        }
        return true
    }
}

/** Recognise a hold even when an interactive widget (for example Notes) consumes the touch. */
private class HoldWidgetFrame(ctx: Context, private val onHold: () -> Unit) : FrameLayout(ctx) {
    var holdEnabled = true
    private var x0 = 0f
    private var y0 = 0f
    private var held = false
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private val hold = Runnable {
        held = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onHold()
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { held = false; x0 = ev.x; y0 = ev.y; removeCallbacks(hold); if (holdEnabled) postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> if (!held && (kotlin.math.abs(ev.x - x0) > slop || kotlin.math.abs(ev.y - y0) > slop)) removeCallbacks(hold)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(hold)
        }
        return super.dispatchTouchEvent(ev)
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = held
    override fun onTouchEvent(event: MotionEvent): Boolean = true
    override fun onDetachedFromWindow() { removeCallbacks(hold); super.onDetachedFromWindow() }
}

class HomeActivity : Activity() {
    private lateinit var drawer: View
    private lateinit var appDrawer: AppDrawer
    private lateinit var apps: List<ResolveInfo>
    private lateinit var p: Palette
    private lateinit var dots: LinearLayout
    private var shownPage = -1
    private var pagesCount = DEFAULT_PAGES
    private lateinit var pagesView: View
    private lateinit var homePager: Pager
    private var deskPager: VPager? = null   // the vertical stack of home desktops
    private val editBadges = mutableListOf<View>()
    private var stackPager: View? = null
    private var swipeInStack = false
    private lateinit var editSheet: View
    private lateinit var editBar: View
    private lateinit var controlCenter: ControlCenter
    private lateinit var recents: RecentsScreen
    private lateinit var chargingFx: ChargingFx
    private var editing = false
    private val showPageDots by lazy { getSharedPreferences("aos_setup", MODE_PRIVATE).getBoolean("show_page_dots", false) }
    private val showDesktopDots by lazy { getSharedPreferences("aos_setup", MODE_PRIVATE).getBoolean("show_desktop_dots", false) }

    /** The dock (with the status plate) slides away while the desktop is edited and on the Feed and Folders pages. */
    private fun dockForEditing(edit: Boolean) {
        if (!editing) for (f in resizeFrames) f.visibility = View.GONE   // resize frames close with edit mode
        if (!::taskbarView.isInitialized) return
        taskbarView.animate().cancel()
        if (edit) taskbarView.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(Motion.NORMAL).setInterpolator(Motion.easeOut)
            .withEndAction { if (editing || shownPage != feedOffset) taskbarView.visibility = View.INVISIBLE }.start()
        else { taskbarView.visibility = View.VISIBLE
            taskbarView.animate().alpha(1f).translationY(0f).setDuration(Motion.NORMAL).setInterpolator(Fx.settle(this)).start() }
    }
    private var feedEnabled = true
    private var stackEnabled = true
    private var tilesStyle = false
    /** Order of the horizontal sections (ADroid: set in the editor or AOS Settings > Pages): feed, desk (the desktops), folders. */
    private val pageOrder: List<String> get() = PageOrder.read(this).filter { (it != "feed" || feedEnabled) && (it != "folders" || foldersEnabled) }
    /** Logical page of the desktops = strip index of the first desktop (only the sections before it come first). */
    private val feedOffset get() = pageOrder.indexOf("desk").coerceAtLeast(0)
    private val feedPos get() = pageOrder.indexOf("feed")        // -1 when the Feed is off
    private val foldersPos get() = pageOrder.indexOf("folders")  // -1 when the Folders page is off
    // Dock & panels (AOS Settings > Desktop): read once per build of the Home, which rebuilds after a change.
    private val dockStyle get() = getSharedPreferences("aos_setup", MODE_PRIVATE).getString("dock_style", "glass") ?: "glass"
    private val dockButtons get() = getSharedPreferences("aos_setup", MODE_PRIVATE).getString("dock_buttons", "circle") ?: "circle"
    private val statusStyle get() = getSharedPreferences("aos_setup", MODE_PRIVATE).getString("status_plate", "full") ?: "full"
    private val panelStyle get() = getSharedPreferences("aos_setup", MODE_PRIVATE).getString("desk_panels", "none") ?: "none"

    /**
     * The card behind the clock and the app stack: none (straight on the wallpaper), glass or solid. Only vertical padding:
     * the app grid is sized to the full page width, so side padding would cut off its last column.
     */
    private fun deskPanel(v: View): View {
        when (panelStyle) {
            "glass" -> { v.background = glassShape(p, 26); v.setPadding(0, dp(10), 0, dp(10)) }
            "solid" -> { v.background = shape(p.surface, 26, p.stroke); v.setPadding(0, dp(10), 0, dp(10)) }
        }
        return v
    }
    private var foldersEnabled = true
    // Horizontal pages: Feed, the vertical stack of home desktops (one slot), Folders.
    private val totalPages get() = pageOrder.size
    private var splitActive = false
    private lateinit var splitIcon: ImageView

    private lateinit var topBarView: View
    private lateinit var calendarPanel: CalendarPanel
    private lateinit var taskbarView: View

    /** While the drawer is open only the wallpaper stays behind its glass, so nothing ghosts through. */
    private fun homeLayers(alpha: Float) {
        for (v in listOf(pagesView, topBarView, taskbarView)) v.animate().alpha(alpha).setDuration(Motion.FAST).setInterpolator(Motion.easeOut).start()
    }

    /** Frosted glass: blur what sits behind an open panel instead of letting it show through sharp. */
    private fun blurHome(on: Boolean) {
        // Blur & Glass off (or battery saver): panels just sit over the sharp home, nothing extra to render.
        val effect = if (on && Fx.on(this, "glass")) android.graphics.RenderEffect.createBlurEffect(18f, 18f, android.graphics.Shader.TileMode.CLAMP) else null
        for (v in listOf(pagesView, topBarView, taskbarView)) v.setRenderEffect(effect)
        wallpaperFx(on)
    }

    /** The wallpaper layer of the effects: Zoom & Fade and Blur Wallpaper while something covers the home. */
    private var wpFx: WallpaperFxDrawable? = null
    private var scenePageShift = 0f
    private var sceneTiltX = 0f
    private val tiltMotion by lazy { TiltMotion(this) { x, y ->
        wpFx?.setTilt(x, y)
        sceneTiltX = -x * dp(12)
        sceneFx?.apply {
            val motion = Fx.on(this@HomeActivity, "tilt")
            scaleX = if (motion) 1.06f else 1f; scaleY = scaleX
            translationX = scenePageShift + sceneTiltX
            translationY = -y * dp(18)
        }
    } }
    private fun wallpaperFx(covered: Boolean) {
        val d = wpFx ?: return
        val zoom = Fx.on(this, "zoom_fade"); val blur = Fx.on(this, "wp_blur")
        if (!zoom && !blur) return
        android.animation.ValueAnimator.ofFloat(if (covered) 0f else 1f, if (covered) 1f else 0f).apply {
            duration = Fx.dur(320); interpolator = Motion.easeOut
            addUpdateListener { val f = it.animatedValue as Float
                if (zoom) { d.zoom = 1f + 0.06f * f; d.dim = 0.5f * f }
                if (blur) d.blur = f }
        }.start()
    }

    private fun openDrawer() {
        sceneFx?.stop()   // the drawer covers the scene: no need to draw it
        homeLayers(0f)
        wallpaperFx(true)
        drawer.alpha = 0f; drawer.translationY = dp(40).toFloat(); drawer.visibility = View.VISIBLE
        drawer.animate().alpha(1f).translationY(0f).setDuration(if (Fx.on(this, "spring")) Fx.dur(420) else Motion.NORMAL).setInterpolator(Fx.settle(this)).start()
    }

    private fun closeDrawer() {
        sceneFx?.start()
        homeLayers(1f)
        wallpaperFx(false)
        drawer.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(Motion.FAST).setInterpolator(Motion.easeOut)
            .withEndAction { drawer.visibility = View.GONE }.start()
    }

    private val wallpapers = AosStyles.all.map { it.id to "AOS ${it.name}" }

    /** Keeps the system wallpaper on the AOS style that matches the current light/dark theme. */
    private fun applyWallpaper(force: Boolean = false) {
        val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val style = prefs.getString("wallpaper", AosStyles.DEFAULT).orEmpty()
        if (style == "custom") return
        // The animated theme draws the style itself; do not put a still picture over it.
        if (android.app.WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName) return
        val name = "wp_${style}_${if (p.dark) "night" else "day"}"
        val appliedKey = "$name:finish1"
        // A painted scene (Forest Owl, Blue, ...) wins over a bundled image of the same name; Aurora still uses its image.
        val painted = AosStyles.sceneBitmap(AosStyles.find(style), p.dark)
        val res = if (painted == null) resources.getIdentifier(name, "drawable", packageName) else 0
        // Cover the window with the chosen scene at once, so the stock wallpaper never flashes while the system one is being replaced.
        (painted ?: if (res != 0) android.graphics.BitmapFactory.decodeResource(resources, res) else null)
            ?.let { bmp ->
                // The effects layer (parallax, zoom, blur, cross-fade); a new picture fades in over the old one.
                wpFx?.swap(bmp, Fx.on(this, "wp_fade")) ?: window.setBackgroundDrawable(WallpaperFxDrawable(bmp).also {
                    it.parallax = Fx.on(this, "parallax"); it.tiltEnabled = Fx.on(this, "tilt"); wpFx = it
                })
            }
        if (!force && prefs.getString("wallpaper_applied", "") == appliedKey) return
        if (res == 0 && painted == null) return
        Thread {
            try {
                android.app.WallpaperManager.getInstance(this)
                    .setBitmap(painted ?: android.graphics.BitmapFactory.decodeResource(resources, res))
                prefs.edit().putString("wallpaper_applied", appliedKey).apply()
            } catch (_: Exception) { }
        }.start()
    }

    /** Wallpaper picker in the AOS style: tabs (Favourites, Top, the theme sets, Exclusive) and a vertical list of cards with previews and badges. */
    private fun pickWallpaper() {
        val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val current = prefs.getString("wallpaper", AosStyles.DEFAULT)
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabRow = LinearLayout(this)
        val tabNames = listOf("★ Favourites", "Top") + (1..7).map { "V$it" } + "Exclusive"
        fun favs(): Set<String> = prefs.getStringSet("fav_themes", emptySet()) ?: emptySet()
        fun toggleFav(id: String) {
            val s = favs().toMutableSet(); if (!s.add(id)) s.remove(id)
            prefs.edit().putStringSet("fav_themes", s).apply()
        }
        fun idsOf(tab: Int): List<String> = when (tab) {
            0 -> wallpapers.map { it.first }.filter { it in favs() }
            1 -> AosStyles.top
            else -> wallpapers.map { it.first }.filter { AosStyles.groupIndex(it) == tab - 2 }
        }
        var tab = if (current != null && current in favs()) 0 else AosStyles.groupIndex(current.orEmpty()) + 2
        fun badge(text: String, color: Int) = label(text, 10f, p.onAccent, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(7), dp(2), dp(7), dp(2)); background = shape(color, 8)
        }
        fun card(title: String, sub: String, selected: Boolean, preview: ImageView?, badges: List<Pair<String, Int>>, fav: Boolean?, onFav: () -> Unit, onClick: () -> Unit) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(8), dp(10))
            background = if (selected) shape(p.accent and 0x00FFFFFF or 0x33000000, 20, p.accent) else glassShape(p, 20)
            if (preview != null) addView(preview, LinearLayout.LayoutParams(dp(54), dp(96)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@HomeActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(title, 16f, p.text, true).apply { gravity = Gravity.START })
                    for ((bt, bc) in badges) addView(badge(bt, bc), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
                })
                addView(label(sub, 12f, p.sub).apply { gravity = Gravity.START })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) addView(label("✓", 18f, p.accent, true))
            if (fav != null) addView(label(if (fav) "★" else "☆", 24f, if (fav) 0xFFE0A800.toInt() else p.sub).apply {
                gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(6), 0); setOnClickListener { onFav() }
            })
            setOnClickListener { onClick() }; pressFx(p, round = false)
        }
        fun showTab(g: Int) {
            tab = g
            tabRow.removeAllViews()
            tabNames.forEachIndexed { i, n -> tabRow.addView(label(n, 14f, if (i == g) p.onAccent else p.text, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0)
                background = if (i == g) shape(p.accent, 19) else glassShape(p, 19, true)
                setOnClickListener { showTab(i) }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) }) }
            list.removeAllViews()
            val previews = ArrayList<Pair<ImageView, String>>()
            val ids = idsOf(g)
            if (ids.isEmpty()) list.addView(label(if (g == 0) "Nothing here yet. Tap ☆ on a theme to keep it here." else "No themes in this set.", 13f, p.sub).apply { gravity = Gravity.START; setPadding(dp(6), dp(8), 0, 0) })
            // A grid, three themes a row: the scene as a tall tile with its badges and the favourite star on it, the name under it.
            val colW = ((resources.displayMetrics.widthPixels * 0.92f).toInt() - dp(32) - dp(16)) / 3
            var row: LinearLayout? = null
            ids.forEachIndexed { i, id ->
                val name = AosStyles.find(id).name
                if (i % 3 == 0) row = LinearLayout(this).also { list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(p.surfaceHigh, 16); clipToOutline = true }
                previews += iv to id
                val badges = ArrayList<Pair<String, Int>>()
                if (id in AosStyles.top) badges += "TOP" to 0xFFE0A800.toInt()
                if (id in AosStyles.newest) badges += "NEW" to 0xFF2FB36B.toInt()
                if (AosStyles.isExclusive(id)) badges += "EXCL" to 0xFFB04AD6.toInt()
                val selected = id == current
                val fav = id in favs()
                val tile = FrameLayout(this).apply {
                    // The chosen theme gets an accent frame (drawn over the picture, so the picture keeps its rounded corners).
                    foreground = if (selected) shape(0, 16, p.accent, 3) else null
                    addView(iv, FrameLayout.LayoutParams(-1, -1))
                    addView(LinearLayout(this@HomeActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        for ((bt, bc) in badges) addView(badge(bt, bc), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(3) })
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
                    addView(label(if (fav) "★" else "☆", 20f, if (fav) 0xFFE0A800.toInt() else android.graphics.Color.WHITE).apply {
                        gravity = Gravity.CENTER; setShadowLayer(dp(3).toFloat(), 0f, 0f, 0x99000000.toInt())
                        setOnClickListener { toggleFav(id); showTab(g) }
                    }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END))
                    if (selected) addView(label("✓", 16f, p.onAccent, true).apply { gravity = Gravity.CENTER; background = circle(p.accent) },
                        FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
                }
                row!!.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(tile, LinearLayout.LayoutParams(-1, (colW * 1.7f).toInt()))
                    addView(label(name, 13f, if (selected) p.accent else p.text, true).apply {
                        gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0)
                    }, LinearLayout.LayoutParams(-1, -2))
                    setOnClickListener {
                        if (!AosPlan.check(this@HomeActivity, AosPlan.themeNeeds(id))) return@setOnClickListener
                        prefs.edit().putString("wallpaper", id).apply(); dlg.dismiss()
                        applyWallpaper(force = true); recreate()
                    }
                    pressFx(p, round = false)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = if (i % 3 == 0) 0 else dp(8) })
            }
            // Keep the last row's tiles the same width when it is not full.
            row?.let { r -> repeat((3 - ids.size % 3) % 3) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }) } }
            if (g == 2) list.addView(card("Choose from gallery…", "Your own picture", current == "custom", null, emptyList(), null, {}) {
                prefs.edit().putString("wallpaper", "custom").apply(); dlg.dismiss()
                try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) } catch (_: Exception) {}
            }, LinearLayout.LayoutParams(-1, -2))
            // Previews of this tab are painted on a background thread, one by one.
            Thread {
                for ((iv, id) in previews) {
                    val bmp = try { AosStyles.bitmap(AosStyles.find(id), p.dark)?.let { android.graphics.Bitmap.createScaledBitmap(it, 108, 240, true) } } catch (_: Exception) { null }
                    if (bmp != null && tab == g) runOnUiThread { iv.setImageBitmap(bmp) }
                }
            }.start()
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(p.surface, 28, p.stroke); setPadding(dp(16), dp(16), dp(16), dp(12))
            addView(label("Wallpaper & themes", 20f, p.text, true).apply { gravity = Gravity.START; setPadding(dp(4), 0, 0, dp(10)) })
            addView(android.widget.HorizontalScrollView(this@HomeActivity).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(android.widget.ScrollView(this@HomeActivity).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        showTab(tab)
        dlg.setContentView(panel)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), (resources.displayMetrics.heightPixels * 0.82f).toInt())
            setDimAmount(0.5f)
        }
        dlg.show()
    }

    private var managerSection = 1
    private lateinit var managerRail: LinearLayout
    private lateinit var managerPanel: LinearLayout
    private val managerSections = listOf("Pages" to R.drawable.ic_start, "Feed" to R.drawable.ic_legal,
        "Apps" to R.drawable.ic_folder, "Style" to R.drawable.ic_style, "Recents" to R.drawable.ic_history,
        "Updates" to R.drawable.ic_boost, "Widgets" to R.drawable.ic_start)

    /** Desktop Manager (long-press on the home screen): left sidebar of sections, current section on the right. */
    private fun enterEdit() {
        if (editing) return
        editing = true
        dockForEditing(true)
        refreshFolders?.invoke()
        chargingFx.setEditing(true)
        startWiggle()
        editBar.animate().cancel()
        editBar.animate().withEndAction(null)
        editBar.alpha = 0f; editBar.translationY = dp(20).toFloat(); editBar.visibility = View.VISIBLE
        editBar.animate().alpha(1f).translationY(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.easeOut).start()
        paintMini(); paintSlots()
        // One-time hint for the drag-and-drop zones.
        val hinted = getSharedPreferences("aos_setup", MODE_PRIVATE)
        if (!hinted.getBoolean("hint_drag", false)) {
            hinted.edit().putBoolean("hint_drag", true).apply()
            android.widget.Toast.makeText(this, "Hold an icon and drop it: left edge = move before, right edge = swap, middle = folder", android.widget.Toast.LENGTH_LONG).show()
        }
        paintLockPill()
        if (!layoutLocked) for (b in editBadges) { b.alpha = 0f; b.visibility = View.VISIBLE; b.animate().alpha(1f).setDuration(Motion.NORMAL).start() }
        // Reserve real layout space for editor controls, so drawing and touch targets stay aligned.
        miniHolder.animate().cancel()
        miniHolder.animate().withEndAction(null)
        miniHolder.alpha = 0f; miniHolder.visibility = View.VISIBLE
        miniHolder.animate().alpha(1f).setDuration(Motion.NORMAL).start()
        layoutForEditing()
        view_haptic()
    }

    private fun view_haptic() { editBar.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }

    private fun exitEdit() {
        if (!editing) return
        editing = false
        dockForEditing(shownPage != feedOffset)
        refreshFolders?.invoke()
        paintSlots()
        for (b in editBadges) { b.animate().cancel(); b.visibility = View.GONE }
        layoutForEditing()
        miniHolder.animate().cancel()
        miniHolder.animate().alpha(0f).setDuration(Motion.FAST).withEndAction { if (!editing) miniHolder.visibility = View.GONE }.start()
        closeManager()
        chargingFx.setEditing(false)
        stopWiggle()
        editBar.animate().cancel()
        editBar.animate().alpha(0f).setDuration(Motion.FAST).withEndAction { if (!editing) editBar.visibility = View.GONE }.start()
    }

    private fun layoutForEditing() {
        val page = shownPage.coerceIn(0, totalPages - 1)
        pagesView.animate().cancel(); pagesView.translationY = 0f
        stackPager?.let { it.animate().cancel(); it.translationY = 0f }
        pagesView.setPadding(0, if (editing) dp(56) else 0, 0, if (editing) dp(64) else 0)
        // The page chips of the editor replace both indicators while editing.
        // Both indicators are off unless switched on in AOS Settings > Pages.
        dots.visibility = if (editing || !showPageDots) View.GONE else View.VISIBLE
        paintVDots()
        homePager.post { homePager.scrollTo(stripIndex(page) * homePager.width, 0) }
    }

    /** Desktop Manager panel on top of the edit mode. */
    private fun openManager() {
        editSheet.alpha = 0f; editSheet.visibility = View.VISIBLE
        editSheet.animate().alpha(1f).setDuration(Motion.NORMAL).start()
        showManagerSection(managerSection)
    }

    private fun closeManager() {
        if (editSheet.visibility != View.VISIBLE) return
        editSheet.animate().alpha(0f).setDuration(Motion.FAST).withEndAction { editSheet.visibility = View.GONE }.start()
    }

    /** A double tap on the desktop opens edit mode (holding an icon then picks it up to move it). */
    private val doubleTap by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val inApps = stackPager?.let { v -> val bounds = android.graphics.Rect(); v.getGlobalVisibleRect(bounds) && bounds.contains(e.rawX.toInt(), e.rawY.toInt()) } ?: false
                // Not on the top bar: that area belongs to the Control Center swipe.
                if (!inApps && shownPage == feedOffset && e.rawY > dp(80) && e.rawY < taskbarView.top && !editing && drawer.visibility != View.VISIBLE && !recents.isOpen && !controlCenter.isOpen && editSheet.visibility != View.VISIBLE) enterEdit()
                return false
            }
        })
    }

    private val idleAnims = mutableListOf<android.animation.ObjectAnimator>()

    /** Optional idle motion for the icons on the desktop (Manager, Style): float, pulse or hop. */
    private fun applyIdleAnim(v: View) {
        val mode = getSharedPreferences("aos_setup", MODE_PRIVATE).getInt("icon_anim", 0)
        val a = when (mode) {
            1 -> android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, -dp(3).toFloat(), dp(3).toFloat()).apply {
                duration = 1800; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            2 -> android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f), android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)).apply {
                duration = 1600; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
            }
            3 -> android.animation.ObjectAnimator.ofPropertyValuesHolder(v, android.animation.PropertyValuesHolder.ofKeyframe(View.TRANSLATION_Y,
                android.animation.Keyframe.ofFloat(0f, 0f), android.animation.Keyframe.ofFloat(0.12f, -dp(10).toFloat()), android.animation.Keyframe.ofFloat(0.26f, 0f),
                android.animation.Keyframe.ofFloat(0.34f, -dp(4).toFloat()), android.animation.Keyframe.ofFloat(0.42f, 0f), android.animation.Keyframe.ofFloat(1f, 0f))).apply {
                duration = 2800; repeatCount = android.animation.ValueAnimator.INFINITE
            }
            else -> return
        }
        a.startDelay = (Math.random() * 1400).toLong(); a.start(); idleAnims += a
    }
    private val wiggleCells = mutableListOf<View>()
    private val wiggleAnims = mutableListOf<android.animation.ObjectAnimator>()

    /** iOS-style jiggle: each icon rocks +-1.2 degrees with its own phase. Pure view rotation, no layout work. */
    private fun startWiggle() {
        stopWiggle()
        for (v in wiggleCells) {
            wiggleAnims += android.animation.ObjectAnimator.ofFloat(v, View.ROTATION, -1.2f, 1.2f).apply {
                // Slow, soft rocking (it used to be +-2 degrees every 120 ms, which looked jittery).
                duration = 300; repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                startDelay = (Math.random() * 300).toLong(); start()
            }
        }
    }

    private fun stopWiggle() {
        for (a in wiggleAnims) a.cancel()
        wiggleAnims.clear()
        for (v in wiggleCells) v.rotation = 0f
    }

    /** Floating bar above the dock while editing: Manager (opens the desktop manager) and Done. */
    private fun buildEditBar(): View {
        fun pill(text: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            this.text = text; textSize = 14f; typeface = appFont(600); gravity = Gravity.CENTER
            setTextColor(if (primary) p.onAccent else p.text)
            background = if (primary) shape(p.accent, 22) else glassShape(p, 22, true)
            setPadding(dp(8), 0, dp(8), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { onClick() }; pressFx(p, round = false)
        }
        miniRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        // The mini desktops sit under the top bar (miniHolder); this bar keeps Manager and Done.
        miniHolder = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(12), 0, dp(12), 0)
            addView(HorizontalScrollView(this@HomeActivity).apply {
                isHorizontalScrollBarEnabled = false
                // Fill the width so the chips sit centred; with many pages the row is wider and scrolls as before.
                isFillViewport = true
                addView(miniRow, FrameLayout.LayoutParams(-2, -2))
            }, FrameLayout.LayoutParams(-1, -2))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = glassShape(p, 28, true); setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
            addView(LinearLayout(this@HomeActivity).apply {
                // The manager is part of AOS Settings now (Desktop section).
                lockPill = pill("🔓 Layout", false) {
                    getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putBoolean("layout_locked", !layoutLocked).apply(); paintLockPill()
                    for (b in editBadges) b.visibility = if (layoutLocked) View.GONE else View.VISIBLE
                    paintSlots()
                    refreshFolders?.invoke()
                }
                addView(lockPill, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
                addView(pill("Settings", false) { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java).putExtra("category", "desktop")) },
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
                addView(pill("Done", true) { exitEdit() }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private lateinit var miniRow: LinearLayout
    private lateinit var miniHolder: FrameLayout

    private fun setPages(n: Int) {
        val m = n.coerceIn(1, 5)
        if (m == pagesCount) return
        getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putInt("pages", m).putBoolean("reopen_edit", true).apply()
        recreate()
    }

    /** Mini desktops shown while editing: one small card per page (tap to go there) with page add / remove buttons. */
    private fun paintMini() {
        if (!::miniRow.isInitialized) return
        miniRow.removeAllViews()
        fun round(sym: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = sym; textSize = 22f; gravity = Gravity.CENTER; typeface = appFont(700); setTextColor(if (enabled) p.onAccent else p.sub)
            background = circle(if (enabled) p.accent else p.surfaceHigh); setOnClickListener { if (enabled) onClick() }
        }
        miniRow.addView(round("−", pagesCount > 1) { setPages(pagesCount - 1) }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(6) })
        val w = resources.displayMetrics.widthPixels
        // Page chips: slim pills (accent when it is the page you are on).
        fun card(name: String, on: Boolean, onLong: (() -> Unit)? = null, go: () -> Unit) = miniRow.addView(label(name, 13f, if (on) p.onAccent else p.text, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(11), 0, dp(11), 0)
            background = if (on) shape(p.accent, 19) else glassShape(p, 19, true)
            setOnClickListener { go() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
        fun section(id: String) = pageOrder.indexOf(id).let { pos ->
            // Tap Feed or Folders: a small menu says where it goes (open it, or move it one place along the Feed / Desktops / Folders order).
            val name = if (id == "feed") "Feed" else "Folders"
            card(name, shownPage == pos) {
                val items = mutableListOf<Pair<String, () -> Unit>>("Open" to { homePager.smoothScrollTo(stripIndex(pos) * w, 0) })
                if (pos > 0) items += "Move left" to { movePage(id, -1) }
                if (pos < totalPages - 1) items += "Move right" to { movePage(id, 1) }
                android.app.AlertDialog.Builder(this).setTitle(name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
            }
        }
        for (id in pageOrder.takeWhile { it != "desk" }) section(id)
        // Home desktops are stacked vertically: 1, 2, 3...
        for (n in 0 until pagesCount) card("${n + 1}", shownPage == feedOffset && vPage == n, onLong = {
            // Hold a page chip to swap it with the next page (the first page holds the app stack and stays put).
            if (n >= 1 && n + 1 < pagesCount) swapPages(n + 1, n + 2) else android.widget.Toast.makeText(this, "Page 1 and the last page cannot move this way", android.widget.Toast.LENGTH_SHORT).show()
        }) {
            goDesk(n)
        }
        for (id in pageOrder.dropWhile { it != "desk" }.drop(1)) section(id)
        miniRow.addView(round("+", pagesCount < 5) { setPages(pagesCount + 1) }, LinearLayout.LayoutParams(dp(38), dp(38)))
    }

    private fun buildEditSheet(): View {
        // Side navigation of the manager: a vertical rail on the left (scrolls down), one icon + label per section.
        managerRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            managerSections.forEachIndexed { i, (name, icon) ->
                if (i == 0) return@forEachIndexed   // Pages moved to the mini desktops of the edit mode
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    addView(ImageView(this@HomeActivity).apply { setImageResource(icon); tag = "i" }, LinearLayout.LayoutParams(dp(24), dp(24)))
                    addView(TextView(this@HomeActivity).apply {
                        text = name; textSize = 11f; typeface = appFont(600); gravity = Gravity.CENTER; tag = "t"
                    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                    setOnClickListener { showManagerSection(i) }
                }, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(4) })
            }
        }
        managerPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(p.text); setPadding(dp(8), dp(8), dp(8), dp(8))
            background = circle(p.surfaceHigh); setOnClickListener { closeManager() }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(close, LinearLayout.LayoutParams(dp(32), dp(32)))
        }
        // Outline mode: no dimming, the home screen and its border stay visible. The sheet is solid, with a left rail and
        // a content column that scrolls, so every section fits whatever the font size.
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(-1, dp(32)))
            addView(android.widget.ScrollView(this@HomeActivity).apply {
                isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(managerPanel)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = shape(p.surface, 28, p.stroke); isClickable = true
            elevation = dp(10).toFloat(); setPadding(dp(10), dp(12), dp(16), dp(10))
            addView(android.widget.ScrollView(this@HomeActivity).apply {
                isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(managerRail)
            }, LinearLayout.LayoutParams(dp(78), -1).apply { marginEnd = dp(10) })
            addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return FrameLayout(this).apply {
            visibility = View.GONE
            val h = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            addView(sheet, FrameLayout.LayoutParams(-1, h, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(24)) })
        }
    }

    private fun showManagerSection(i: Int) {
        managerSection = i
        for (k in 0 until managerRail.childCount) (managerRail.getChildAt(k) as LinearLayout).apply {
            val on = k + 1 == i
            val c = if (on) p.onAccent else p.sub
            background = shape(if (on) p.accent else 0x00000000, 18)
            (findViewWithTag<TextView>("t")).setTextColor(c); (findViewWithTag<ImageView>("i")).setColorFilter(c)
        }
        val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
        managerPanel.removeAllViews()
        fun title(t: String, sub: String) {
            managerPanel.addView(label(t, 20f, p.text, true).apply { gravity = Gravity.START; setPadding(0, dp(2), 0, dp(2)) })
            managerPanel.addView(label(sub, 13f, p.sub).apply { gravity = Gravity.START; setPadding(0, dp(4), 0, dp(12)) })
        }
        // Actions are cards in a vertical list on the right, like the App Drawer's list view.
        fun action(text: String, primary: Boolean = false, onClick: () -> Unit) {
            managerPanel.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(14), 0)
                background = shape(if (primary) p.accent else p.surfaceHigh, 20, if (primary) null else p.stroke)
                addView(TextView(this@HomeActivity).apply {
                    this.text = text; gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 15f; typeface = appFont(600); maxLines = 2
                    setTextColor(if (primary) p.onAccent else p.text)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@HomeActivity).apply {
                    this.text = "›"; textSize = 22f; setTextColor(if (primary) p.onAccent else p.sub)
                })
                setOnClickListener { onClick() }; pressFx(p, round = false)
            }, LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(8) })
        }
        when (i) {
            0 -> {
                title("Pages", "Home pages: $pagesCount")
                managerPanel.addView(LinearLayout(this).apply {
                    for (n in 1..pagesCount) addView(label("$n", 15f, p.text, true).apply {
                        background = shape(p.surfaceHigh, 14, p.stroke)
                    }, LinearLayout.LayoutParams(dp(52), dp(88)).apply { marginEnd = dp(8) })
                })
                action("Add page") { if (pagesCount < 5) { prefs.edit().putInt("pages", pagesCount + 1).apply(); recreate() } }
                action("Remove last page") { if (pagesCount > 1) { prefs.edit().putInt("pages", pagesCount - 1).apply(); recreate() } }
            }
            6 -> {
                title("Widgets", "Base widgets. Tap one to put it on desktop 2; or use the + slots while editing desktop 2, 3...")
                for ((id, name) in baseWidgets) action(name) { addWidgetToDesk(id) }
                action("Clear all widgets") {
                    val e = prefs.edit(); for (pg in 2..5) { e.putString("wlay_$pg", ""); for (s in 0 until 6) e.remove("wslot_${pg}_$s") }
                    e.putBoolean("reopen_edit", true).apply(); recreate()
                }
            }
            1 -> {
                title("Feed", "A page to the left of Home with your recent apps.")
                action(if (feedEnabled) "Feed: on" else "Feed: off", feedEnabled) { prefs.edit().putBoolean("feed", !feedEnabled).apply(); recreate() }
                action(if (foldersEnabled) "Folders page: on" else "Folders page: off", foldersEnabled) { prefs.edit().putBoolean("folders_page", !foldersEnabled).putBoolean("reopen_edit", true).apply(); recreate() }
            }
            2 -> {
                title("Apps", "The app drawer is part of the desktop. The base app stack sits on the first page.")
                action("Choose home apps", true) { chooseStackApps() }
                action("Grid: ${gridCols()} × ${gridRows()}") { cycleGrid() }
                action("Open app drawer") { exitEdit(); openDrawer() }
                action(if (stackEnabled) "App stack: on" else "App stack: off") { prefs.edit().putBoolean("stack", !stackEnabled).apply(); recreate() }
            }
            3 -> {
                val names = listOf("Auto", "Light", "Dark")
                title("Style", "Wallpaper and theme.")
                action("Wallpaper") { pickWallpaper() }
                val aOn = prefs.getBoolean("anim_theme", true)
                action(if (aOn) "Animated theme: on" else "Animated theme: off", aOn) {
                    prefs.edit().putBoolean("anim_theme", !aOn).putBoolean("reopen_edit", true).apply(); recreate()
                }
                val pOn = prefs.getBoolean("power_menu", true)
                action(if (pOn) "Power menu: on" else "Power menu: off", pOn) {
                    prefs.edit().putBoolean("power_menu", !pOn).putBoolean("reopen_edit", true).apply(); recreate()
                }
                val vOn = prefs.getBoolean("volume_panel", true)
                action(if (vOn) "Volume panel: on" else "Volume panel: off", vOn) {
                    prefs.edit().putBoolean("volume_panel", !vOn).putBoolean("reopen_edit", true).apply(); recreate()
                }
                val shapeNames = listOf("Squircle", "Circle", "Rounded", "Square", "Teardrop")
                val sh = prefs.getInt("icon_shape", 0).coerceIn(0, shapeNames.lastIndex)
                action("Icon shape: ${shapeNames[sh]}") {
                    prefs.edit().putInt("icon_shape", (sh + 1) % shapeNames.size).putBoolean("reopen_edit", true).apply(); recreate()
                }
                val animNames = listOf("Off", "Float", "Pulse", "Hop")
                val an = prefs.getInt("icon_anim", 0).coerceIn(0, animNames.lastIndex)
                action("Icon animation: ${animNames[an]}") {
                    prefs.edit().putInt("icon_anim", (an + 1) % animNames.size).putBoolean("reopen_edit", true).apply(); recreate()
                }
                action(if (tilesStyle) "Home style: Windows Phone tiles" else "Home style: ADroid", tilesStyle) {
                    prefs.edit().putString("home_style", if (tilesStyle) "android" else "tiles").apply(); recreate()
                }
                val lOn = prefs.getBoolean("lock_screen", true)
                action(if (lOn) "AOS lock screen: on" else "AOS lock screen: off", lOn) {
                    prefs.edit().putBoolean("lock_screen", !lOn).apply(); showManagerSection(3)
                }
                val gOn = prefs.getBoolean("gestures", true)
                action(if (gOn) "Gestures: on" else "Gestures: off", gOn) {
                    prefs.edit().putBoolean("gestures", !gOn).apply(); SplitService.instance?.refreshGestures(); showManagerSection(3)
                }
                action("Theme: ${names[prefs.getInt("theme", 0).coerceIn(0, 2)]}") {
                    prefs.edit().putInt("theme", (prefs.getInt("theme", 0) + 1) % 3).apply(); recreate()
                }
            }
            4 -> {
                val layouts = listOf("vertical" to "Grid: two cards per row, scroll down", "horizontal" to "Row: large cards, swipe sideways",
                    "list" to "List: one app per line")
                val current = prefs.getString("recents_layout", "vertical")
                title("Recents", "Layout of the recent apps screen. Swipe up from the bottom edge and hold to open it.")
                for ((id, name) in layouts) action(if (id == current) "\u2713  $name" else name, id == current) {
                    prefs.edit().putString("recents_layout", id).apply(); showManagerSection(4)
                }
                action("Open recents") { exitEdit(); recents.show() }
            }
            else -> {
                val (_, name) = Updater.currentVersion(this)
                title("Updates", "Version $name. Updates come from ${Updater.serverUrl(this)}.")
                action("Check for updates", true) { Updater.check(this, true) }
                action("Server: ${Updater.serverUrl(this)}") {
                    val input = android.widget.EditText(this).apply { setText(Updater.serverUrl(this@HomeActivity)); setSingleLine() }
                    android.app.AlertDialog.Builder(this).setTitle("Update server address").setView(input)
                        .setPositiveButton("Save") { _, _ -> Updater.setServerUrl(this, input.text.toString()); showManagerSection(5) }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }
    }

    /** Windows Phone style: flat accent tiles (two small side by side, then one wide), scrolling vertically in one slider. */
    // ---- Windows Phone style Start screen: a 4-column grid of small, medium and wide tiles, with live tiles ----

    /** Tile keys in order: an app key "pkg/cls" or a live tile "live:date" / "live:weather" / "live:clock". */
    private fun tileKeys(): List<String> {
        val sp = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val list = sp.getString("tiles", null)?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: run {
            val pinned = stackKeys().map { if (isFolder(it)) "folder:" + folderName(it) else it }
                .filter { it.startsWith("folder:") || appFor(it) != null }
                .ifEmpty { apps.take(18).map { stackKey(it) } }.toMutableList()
            pinned.add(0, "live:today")
            pinned.add(minOf(12, pinned.size), "live:clock")
            pinned
        }
        // Date and weather are one tile now ("live:today"); older layouts with the two separate tiles are merged.
        val at = list.indexOfFirst { it == "live:date" || it == "live:weather" }
        if (at >= 0) { list.removeAll { it == "live:date" || it == "live:weather" }; if ("live:today" !in list) list.add(minOf(at, list.size), "live:today") }
        // Folders appear on Start as soon as they exist (unless you unpinned them); gone folders drop out.
        val names = tileFolders().keys
        val hidden = sp.getStringSet("tiles_hidden_folders", emptySet()).orEmpty()
        list.removeAll { it.startsWith("folder:") && it.removePrefix("folder:") !in names }
        for (n in names) if ("folder:$n" !in list && n !in hidden) list.add(minOf(4, list.size), "folder:$n")
        return list
    }

    /** Your folders: the ones on the home stack and the ones made in the app drawer, with their apps. */
    private fun tileFolders(): LinkedHashMap<String, List<ResolveInfo>> {
        val out = LinkedHashMap<String, List<ResolveInfo>>()
        for (e in stackKeys().filter { isFolder(it) }) out[folderName(e)] = folderKeys(e).mapNotNull { appFor(it) }
        for ((name, pkgs) in Groups.all(this)) if (name !in out)
            out[name] = pkgs.mapNotNull { pkg -> apps.firstOrNull { it.activityInfo.packageName == pkg } }
        out.entries.removeAll { it.value.isEmpty() }
        return out
    }

    /** Flat one-colour glyph like on Windows Phone: the themed (monochrome) layer or the foreground of the icon, painted [ink]. */
    private fun metroGlyph(ri: ResolveInfo, ink: Int, sizePx: Int): View = ImageView(this).apply {
        val raw = ri.loadIcon(packageManager)
        val adaptive = raw as? android.graphics.drawable.AdaptiveIconDrawable
        val layer = adaptive?.let { (if (android.os.Build.VERSION.SDK_INT >= 33) it.monochrome else null) ?: it.foreground }
        val flat = layer ?: raw.takeIf { it is android.graphics.drawable.VectorDrawable }
        if (flat != null) {
            val d = (flat.constantState?.newDrawable(resources) ?: flat).mutate()
            d.colorFilter = android.graphics.PorterDuffColorFilter(ink, android.graphics.PorterDuff.Mode.SRC_IN)
            // An adaptive layer is drawn on a 108-unit canvas with the glyph in its middle: crop the empty margin away.
            setImageDrawable(if (layer != null) android.graphics.drawable.InsetDrawable(d, -0.25f) else d)
        } else setImageDrawable(AosIcons.themed(this@HomeActivity, raw, p, 44, ri.activityInfo.packageName))
        layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
    }

    /** The theme accent, darkened until white text reads well on it (a light accent would wash the white glyphs out). */
    private fun metroTileColor(): Int {
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(p.accent, hsv)
        fun lum(c: Int) = (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)) / 255.0
        var c = p.accent
        while (lum(c) > 0.5 && hsv[2] > 0.3f) { hsv[2] -= 0.05f; c = android.graphics.Color.HSVToColor(hsv) }
        return c
    }

    /** A folder opened from Start: its apps as small tiles under the folder name. */
    private fun openTileFolder(name: String, members: List<ResolveInfo>, tileColor: Int) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val gap = dp(5)
        val cell = (resources.displayMetrics.widthPixels - dp(16) - 3 * gap) / 4
        val grid = GridLayout(this).apply { columnCount = 4 }
        for ((i, ri) in members.withIndex()) grid.addView(FrameLayout(this).apply {
            background = android.graphics.drawable.ColorDrawable(tileColor)
            addView(metroGlyph(ri, android.graphics.Color.WHITE, cell * 46 / 100).apply { (layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(8) })
            addView(label(ri.loadLabel(packageManager).toString(), 10f, android.graphics.Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.START },
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(5), 0, dp(5), dp(3)) })
            setOnClickListener { dlg.dismiss(); launch(ri, from = getChildAt(0)) }
            pressFx(p, round = false)
            alpha = 0f; rotationX = -80f; pivotY = 0f
            animate().alpha(1f).rotationX(0f).setStartDelay((i / 4) * 40L).setDuration(Fx.dur(240)).start()
        }, GridLayout.LayoutParams().apply { width = cell; height = cell; setMargins(0, 0, if (i % 4 == 3) 0 else gap, gap) })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(70), dp(8), dp(40))
            setBackgroundColor(android.graphics.Color.argb(if (p.dark) 225 else 200, 0, 0, 0))
            addView(TextView(this@HomeActivity).apply { text = name.lowercase(); textSize = 34f; setTextColor(android.graphics.Color.WHITE); typeface = appFont(300) },
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(14); marginStart = dp(4) })
            addView(ScrollView(this@HomeActivity).apply { isVerticalScrollBarEnabled = false; addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnClickListener { dlg.dismiss() }
        }
        dlg.setContentView(body)
        dlg.window?.setLayout(-1, -1)
        dlg.show()
    }
    private fun saveTileKeys(keys: List<String>) =
        getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putString("tiles", keys.distinct().joinToString(",")).apply()

    /** 1 = small (1x1), 2 = medium (2x2), 4 = wide (4x2). */
    private fun tileSize(key: String, index: Int): Int {
        val saved = getSharedPreferences("aos_setup", MODE_PRIVATE).getInt("tile_size_$key", 0)
        if (saved in listOf(1, 2, 4)) return saved
        return when (key) {
            "live:today" -> 4; "live:clock" -> 2
            else -> if (key.startsWith("folder:")) 2 else listOf(2, 1, 1, 1, 1, 2, 1, 1, 2, 2, 1, 1, 1, 1, 4)[index % 15]
        }
    }

    private fun tileMenu(key: String, anchor: View) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val sp = getSharedPreferences("aos_setup", MODE_PRIVATE)
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Small"); menu.add(0, 2, 1, "Medium"); menu.add(0, 4, 2, "Wide")
            menu.add(0, 10, 3, "Unpin from Start")
            menu.add(0, 11, 4, "Move to top")
            menu.add(0, 12, 5, "Reset Start")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1, 2, 4 -> sp.edit().putInt("tile_size_$key", item.itemId).apply()
                    10 -> {
                        if (key.startsWith("folder:")) sp.edit().putStringSet("tiles_hidden_folders",
                            sp.getStringSet("tiles_hidden_folders", emptySet()).orEmpty() + key.removePrefix("folder:")).apply()
                        saveTileKeys(tileKeys().filter { it != key })
                    }
                    11 -> saveTileKeys(listOf(key) + tileKeys().filter { it != key })
                    12 -> { val e = sp.edit().remove("tiles").remove("tiles_hidden_folders"); sp.all.keys.filter { it.startsWith("tile_size_") }.forEach { e.remove(it) }; e.apply() }
                }
                recreate(); true
            }
        }.show()
    }

    private fun buildTiles(): View {
        val gap = dp(5)
        val full = resources.displayMetrics.widthPixels - dp(16)
        val cell = (full - 3 * gap) / 4
        val tileColor = metroTileColor()
        val ink = android.graphics.Color.WHITE   // Windows Phone tiles always have white glyphs and text
        val keys = tileKeys()
        val folders = tileFolders()
        val grid = FrameLayout(this)
        val occupied = ArrayList<BooleanArray>()
        fun free(r: Int, c: Int, w: Int, h: Int): Boolean {
            for (y in r until r + h) for (x in c until c + w) if (y < occupied.size && occupied[y][x]) return false
            return true
        }
        val placed = ArrayList<Triple<View, Int, Int>>()   // tile, row, column (for the entrance animation)
        keys.forEachIndexed { index, key ->
            val ri = if (key.startsWith("live:") || key.startsWith("folder:")) null else appFor(key) ?: return@forEachIndexed
            val size = tileSize(key, index)
            val w = if (size == 4) 4 else size; val h = if (size == 1) 1 else 2
            // First free spot, reading order: small tiles fill the holes beside bigger ones, like on the phone.
            var r = 0; var c = 0
            search@ while (true) { for (x in 0..4 - w) if (free(r, x, w, h)) { c = x; break@search }; r++ }
            while (occupied.size < r + h) occupied.add(BooleanArray(4))
            for (y in r until r + h) for (x in c until c + w) occupied[y][x] = true
            val tw = w * cell + (w - 1) * gap; val th = h * cell + (h - 1) * gap
            val tile = FrameLayout(this).apply {
                background = android.graphics.drawable.ColorDrawable(tileColor)
                pivotX = 0f
                setOnLongClickListener { if (editing) false else { tileMenu(key, this); true } }
                pressFx(p, round = false)
            }
            if (ri != null) {
                val icon = if (size == 1) cell * 52 / 100 else cell * 2 * 30 / 100
                tile.addView(metroGlyph(ri, ink, icon).apply { if (size != 1) (layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(8) })
                if (size != 1) tile.addView(label(ri.loadLabel(packageManager).toString(), 12f, ink).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.START },
                    FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, dp(8), dp(6)) })
                tile.setOnClickListener { if (!editing) launch(ri, from = tile.getChildAt(0)) else editTap(ri, tile) }
            } else if (key.startsWith("folder:")) {
                val name = key.removePrefix("folder:")
                val members = folders[name].orEmpty()
                // Folder tile: up to 4 (small) or 9 (bigger) app glyphs in a grid, the name in the corner.
                val n = if (size == 1) 2 else 3
                val mini = GridLayout(this).apply { columnCount = n * (if (size == 4) 2 else 1) }
                val slot = (if (size == 1) cell else 2 * cell) * 22 / 100
                for (m in members.take(n * n * (if (size == 4) 2 else 1))) mini.addView(metroGlyph(m, ink, slot),
                    GridLayout.LayoutParams().apply { width = slot; height = slot; setMargins(dp(3), dp(3), dp(3), dp(3)) })
                tile.addView(mini, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { if (size != 1) bottomMargin = dp(10) })
                if (size != 1) tile.addView(label(name, 12f, ink).apply { maxLines = 1; gravity = Gravity.START },
                    FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, dp(8), dp(6)) })
                tile.setOnClickListener { openTileFolder(name, members, tileColor) }
            } else when (key) {
                "live:today" -> {
                    // Calendar and weather in one live tile: the day on the left, the weather on the right.
                    val wd = label("", if (size == 1) 10f else 13f, ink).apply { gravity = Gravity.START; maxLines = 1 }
                    val day = TextView(this).apply { textSize = if (size == 1) 26f else 46f; setTextColor(ink); typeface = appFont(300); includeFontPadding = false }
                    fun paint() { val now = Date(); day.text = DateFormat.format("d", now); wd.text = DateFormat.format(if (size == 4) "EEEE, MMMM" else "EEEE", now) }
                    paint()
                    tile.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        val tick = object : Runnable { override fun run() { paint(); tile.postDelayed(this, 60_000) } }
                        override fun onViewAttachedToWindow(v: View) { tile.postDelayed(tick, 60_000) }
                        override fun onViewDetachedFromWindow(v: View) { tile.removeCallbacks(tick) }
                    })
                    val temp = label("…", if (size == 4) 30f else 15f, ink).apply { gravity = Gravity.END; maxLines = 1; typeface = appFont(300) }
                    val place = label("", 12f, ink).apply { gravity = Gravity.END; maxLines = 1; alpha = 0.9f }
                    val more = label("", 11f, ink).apply { gravity = Gravity.END; maxLines = 2; alpha = 0.85f }
                    tile.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(wd); addView(day) },
                        FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, dp(10), dp(8)) })
                    tile.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                        addView(temp); if (size == 4) { addView(place); addView(more) }
                    }, FrameLayout.LayoutParams(if (size == 4) tw / 2 else -2, -2, (if (size == 4) Gravity.BOTTOM else Gravity.TOP) or Gravity.END).apply { setMargins(dp(10), dp(8), dp(10), dp(8)) })
                    fun fill(text: String?) {
                        temp.text = text?.substringBefore("  ") ?: "—"
                        place.text = text?.substringAfter("  ", "").orEmpty()
                        more.text = Weather.detail(this).orEmpty().substringBefore("  ·  Wind")
                    }
                    Weather.load(this) { text -> runOnUiThread { fill(text) } }
                    tile.setOnClickListener { if (::calendarPanel.isInitialized) calendarPanel.show() }
                    tile.setOnLongClickListener { if (editing) false else { tileMenu(key, tile); true } }
                    temp.setOnClickListener {
                        if (!Weather.hasPermission(this)) requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 11)
                        Weather.invalidate(this); temp.text = "…"; Weather.load(this) { text -> runOnUiThread { fill(text) } }
                    }
                }
                "live:date" -> {
                    val day = TextView(this).apply { textSize = if (size == 1) 26f else 48f; setTextColor(ink); typeface = appFont(300); gravity = Gravity.START }
                    val wd = label("", if (size == 1) 10f else 14f, ink).apply { gravity = Gravity.START; maxLines = 1 }
                    fun paint() { val now = Date(); day.text = DateFormat.format("d", now); wd.text = DateFormat.format(if (size == 4) "EEEE, MMMM" else "EEEE", now) }
                    paint()
                    tile.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(wd); addView(day) },
                        FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, dp(10), dp(6)) })
                    tile.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        val tick = object : Runnable { override fun run() { paint(); tile.postDelayed(this, 60_000) } }
                        override fun onViewAttachedToWindow(v: View) { tile.postDelayed(tick, 60_000) }
                        override fun onViewDetachedFromWindow(v: View) { tile.removeCallbacks(tick) }
                    })
                    tile.setOnClickListener { if (::calendarPanel.isInitialized) calendarPanel.show() }
                }
                "live:clock" -> {
                    val clock = TextClock(this).apply {
                        format12Hour = "h:mm"; format24Hour = "H:mm"; textSize = if (size == 1) 22f else 40f
                        setTextColor(ink); typeface = appFont(300)
                    }
                    tile.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    if (size != 1) tile.addView(label("Alarms", 12f, ink).apply { gravity = Gravity.START },
                        FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, dp(8), dp(6)) })
                    tile.setOnClickListener { try { startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) } catch (_: Exception) {} }
                }
                "live:weather" -> {
                    val now = label("…", if (size == 1) 15f else 30f, ink).apply { gravity = Gravity.START; maxLines = 1; typeface = appFont(300) }
                    val more = label("Weather", if (size == 4) 12f else 11f, ink).apply { gravity = Gravity.START; maxLines = if (size == 4) 2 else 1; alpha = 0.9f }
                    tile.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(now); if (size != 1) addView(more) },
                        FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, dp(10), dp(8)) })
                    Weather.load(this) { text -> runOnUiThread {
                        now.text = text?.substringBefore("  ") ?: "—"
                        more.text = listOfNotNull(text?.substringAfter("  ", "")?.takeIf { it.isNotBlank() }, if (size == 4) Weather.detail(this) else null).joinToString("\n").ifBlank { "Weather" }
                    } }
                    tile.setOnClickListener {
                        if (!Weather.hasPermission(this)) requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 11)
                        Weather.invalidate(this); now.text = "…"
                        Weather.load(this) { text -> runOnUiThread { now.text = text?.substringBefore("  ") ?: "—" } }
                    }
                }
            }
            grid.addView(tile, FrameLayout.LayoutParams(tw, th).apply { leftMargin = c * (cell + gap); topMargin = r * (cell + gap) })
            placed.add(Triple(tile, r, c))
        }
        grid.minimumHeight = occupied.size * (cell + gap)
        // Arrow to the list of all apps, under the tiles (a swipe up anywhere does the same in the drawer).
        val arrow = TextView(this).apply {
            text = "→"; textSize = 22f; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER
            background = circle(android.graphics.Color.TRANSPARENT, android.graphics.Color.WHITE, 2)
            setOnClickListener { openDrawer() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(64))
            addView(grid, LinearLayout.LayoutParams(full, -2))
            addView(arrow, LinearLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.END; topMargin = dp(14); marginEnd = dp(6) })
        }
        // Turnstile entrance: the tiles swing in from the left edge, row by row.
        for ((v, r, c) in placed) {
            v.alpha = 0f; v.rotationY = -75f; v.cameraDistance = 8000 * resources.displayMetrics.density
            v.animate().alpha(1f).rotationY(0f).setStartDelay((r * 28 + c * 12).toLong().coerceAtMost(420)).setDuration(Fx.dur(260))
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
            addView(column)
        }
    }

    /** Base app stack: a swipeable slider, two rows per slide, centered. */
    private fun stackKey(ri: ResolveInfo) = ri.activityInfo.packageName + "/" + ri.activityInfo.name

    private fun stackKeys(): List<String> =
        getSharedPreferences("aos_setup", MODE_PRIVATE).getString("stack_apps", "").orEmpty().split(",").filter { it.isNotBlank() }

    private fun saveStackKeys(keys: List<String>) =
        getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putString("stack_apps", keys.joinToString(",")).apply()

    // A stack entry is either an app key "pkg/cls" or a folder "F:Name:key1;key2".
    private fun isFolder(e: String) = e.startsWith("F:")
    private fun folderName(e: String) = e.split(":", limit = 3).getOrElse(1) { "Folder" }
    private fun folderKeys(e: String) = e.split(":", limit = 3).getOrElse(2) { "" }.split(";").filter { it.isNotBlank() }
    private fun makeFolder(name: String, keys: List<String>) = "F:" + name.replace(Regex("[:;,]"), " ").ifBlank { "Folder" } + ":" + keys.joinToString(";")
    private fun appFor(key: String) = apps.firstOrNull { stackKey(it) == key }

    // ---- Edit mode polish: layout lock, remove/uninstall drop zone with Undo, page order, widget picker ----

    private val layoutLocked get() = getSharedPreferences("aos_setup", MODE_PRIVATE).getBoolean("layout_locked", false)
    private lateinit var lockPill: TextView
    private var undoBar: TextView? = null

    private fun paintLockPill() {
        lockPill.text = if (layoutLocked) "🔒 Locked" else "🔓 Layout"
    }

    /** Takes an icon or a folder off the home screen and offers Undo for a few seconds. */
    private fun removeEntry(key: String) {
        val sp = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val old = stackKeys()
        saveStackKeys(old.filter { it != key })
        sp.edit().putString("undo_stack", old.joinToString(",")).putLong("undo_at", System.currentTimeMillis()).putBoolean("reopen_edit", true).apply()
        recreate()
    }

    private fun showUndoIfAny(root: FrameLayout) {
        val sp = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val saved = sp.getString("undo_stack", null) ?: return
        if (System.currentTimeMillis() - sp.getLong("undo_at", 0) > 8000) { sp.edit().remove("undo_stack").apply(); return }
        val bar = label("Removed from the home screen  ·  UNDO", 14f, p.onAccent, true).apply {
            background = shape(p.accent, 24); setPadding(dp(22), dp(12), dp(22), dp(12))
            setOnClickListener {
                sp.edit().remove("undo_stack").putBoolean("reopen_edit", true).putString("stack_apps", saved).apply(); recreate()
            }
        }
        undoBar = bar
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(170) })
        bar.postDelayed({ sp.edit().remove("undo_stack").apply(); bar.animate().alpha(0f).setDuration(200).withEndAction { (bar.parent as? ViewGroup)?.removeView(bar) }.start() }, 6000)
    }

    /** Swaps the widget slots of two home pages (page numbers are 1-based; page 1 holds the app stack and never moves). */
    private fun swapPages(a: Int, b: Int) {
        if (a < 2 || b < 2) return
        val sp = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val e = sp.edit()
        val x = loadLayout(a).joinToString(";") { "${it.type},${it.c},${it.r},${it.w},${it.h}" }
        val y = loadLayout(b).joinToString(";") { "${it.type},${it.c},${it.r},${it.w},${it.h}" }
        e.putString("wlay_$a", y).putString("wlay_$b", x)
        e.putBoolean("reopen_edit", true).apply(); recreate()
    }

    /** Widget chooser (Samsung style): a search box and a card per widget with a live preview. */
    // ---- system widgets (any app's home-screen widget, hosted like on a stock launcher) ----
    private val widgetManager by lazy { android.appwidget.AppWidgetManager.getInstance(this) }
    private val widgetHost by lazy { android.appwidget.AppWidgetHost(this, 0xA05) }
    private var pendingWidget: ((String) -> Unit)? = null
    private var pendingWidgetId = -1
    private fun sysWidgetId(type: String) = if (type.startsWith("sys:")) type.removePrefix("sys:").toIntOrNull() else null

    /** Asks the system for the right to show [info] (a one-time dialog per app), then its own setup screen if it has one. */
    private fun addSystemWidget(info: android.appwidget.AppWidgetProviderInfo, onPick: (String) -> Unit) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id; pendingWidget = onPick
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.provider)) configureOrPlace(id)
        else try {
            startActivityForResult(Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider), 91)
        } catch (_: Exception) { dropPendingWidget() }
    }

    private fun configureOrPlace(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            try { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, 92, null); return } catch (_: Exception) { }
        }
        placePendingWidget(id)
    }

    private fun placePendingWidget(id: Int) { val cb = pendingWidget; pendingWidget = null; pendingWidgetId = -1; cb?.invoke("sys:$id") }
    private fun dropPendingWidget() {
        if (pendingWidgetId != -1) runCatching { widgetHost.deleteAppWidgetId(pendingWidgetId) }
        pendingWidget = null; pendingWidgetId = -1
    }

    /** A hosted system widget, told its real size on the grid so it lays itself out for it. */
    private fun systemWidgetView(id: Int, wc: Int, hc: Int): View {
        val info = widgetManager.getAppWidgetInfo(id)
            ?: return label("Widget not available", 12f, p.sub).apply { gravity = Gravity.CENTER }
        val hv = widgetHost.createView(this, id, info).apply { setPadding(0, 0, 0, 0) }
        val gap = dp(8)
        val cell = (resources.displayMetrics.widthPixels - dp(32) - gap * (gridCols - 1)) / gridCols
        val d = resources.displayMetrics.density
        val wDp = (wc * cell + (wc - 1) * gap) / d; val hDp = (hc * cell + (hc - 1) * gap) / d
        runCatching { hv.updateAppWidgetSize(android.os.Bundle(), listOf(android.util.SizeF(wDp, hDp))) }
        return hv
    }

    private fun showWidgetPicker(onPick: (String) -> Unit) {
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill(q: String) {
            list.removeAllViews()
            // Kyivstar first when "My Kyivstar" is installed: its own widgets (balance, gigabytes, minutes) live in an AOS frame.
            val ks = runCatching { widgetManager.installedProviders }.getOrDefault(emptyList()).filter { it.provider.packageName == "com.kyivstar.mykyivstar" }
            if (ks.isNotEmpty() && (q.isBlank() || listOf("kyivstar", "київстар", "киевстар", "balance", "баланс").any { it.contains(q.lowercase()) || q.lowercase().contains(it) })) {
                list.addView(label("Kyivstar", 15f, p.accent, true).apply { gravity = Gravity.START; setPadding(dp(4), 0, 0, dp(8)) })
                val icon = runCatching { packageManager.getApplicationIcon("com.kyivstar.mykyivstar") }.getOrNull()
                for (info in ks) {
                    val small = info.provider.className.contains("Small")
                    list.addView(LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); background = glassShape(p, 20, true)
                        addView(ImageView(this@HomeActivity).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(44), dp(44)))
                        addView(LinearLayout(this@HomeActivity).apply {
                            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0)
                            addView(label(if (small) "Kyivstar · Usage" else "Kyivstar · Balance", 15f, p.text, true).apply { gravity = Gravity.START; maxLines = 1 })
                            addView(label(if (small) "Gigabytes, minutes and SMS left" else "Balance and your tariff at a glance", 12f, p.sub).apply { gravity = Gravity.START; maxLines = 2 })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(label("Add", 13f, p.onAccent, true).apply { gravity = Gravity.CENTER; background = shape(p.accent, 16); setPadding(dp(14), dp(6), dp(14), dp(6)) })
                        setOnClickListener { dlg.dismiss(); addSystemWidget(info, onPick) }; pressFx(p, round = false)
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
            }
            for ((id, name) in baseWidgets.filter { it.second.contains(q, ignoreCase = true) }) {
                val preview = FrameLayout(this).apply { background = glassShape(p, 18); addView(widgetView(id), FrameLayout.LayoutParams(-1, -1)); isClickable = false }
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)); background = shape(p.surfaceHigh, 20, p.stroke)
                    addView(label("$name  ·  1 × 1", 15f, p.text, true).apply { gravity = Gravity.START; setPadding(0, 0, 0, dp(8)) })
                    addView(preview, LinearLayout.LayoutParams(dp(150), dp(120)))
                    setOnClickListener { dlg.dismiss(); onPick(id) }; pressFx(p, round = false)
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            val sys = runCatching { widgetManager.installedProviders }.getOrDefault(emptyList()).mapNotNull { info ->
                val name = runCatching { info.loadLabel(packageManager) }.getOrNull() ?: return@mapNotNull null
                val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault(info.provider.packageName)
                Triple(info, name, app)
            }.filter { (_, n, a) -> q.isBlank() || n.contains(q, true) || a.contains(q, true) }.sortedWith(compareBy({ it.third.lowercase() }, { it.second.lowercase() }))
            if (sys.isNotEmpty()) list.addView(label("System widgets", 15f, p.accent, true).apply { gravity = Gravity.START; setPadding(dp(4), dp(10), 0, dp(8)) })
            for ((info, name, app) in sys) list.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = shape(p.surfaceHigh, 20, p.stroke)
                addView(ImageView(this@HomeActivity).apply {
                    setImageDrawable(runCatching { packageManager.getApplicationIcon(info.provider.packageName) }.getOrNull())
                }, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                    addView(label(name, 14f, p.text, true).apply { gravity = Gravity.START; maxLines = 1 })
                    addView(label(app, 12f, p.sub).apply { gravity = Gravity.START; maxLines = 1 })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { dlg.dismiss(); addSystemWidget(info, onPick) }; pressFx(p, round = false)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val search = android.widget.EditText(this).apply {
            hint = "Search widgets"; setHintTextColor(p.sub); setTextColor(p.text); textSize = 15f; setSingleLine()
            background = shape(p.surfaceHigh, 18, p.stroke); setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { fill(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        fill("")
        dlg.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(p.surface, 28, p.stroke); setPadding(dp(16), dp(16), dp(16), dp(12))
            addView(label("Add a widget", 20f, p.text, true).apply { gravity = Gravity.START; setPadding(dp(4), 0, 0, dp(10)) })
            addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(android.widget.ScrollView(this@HomeActivity).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), (resources.displayMetrics.heightPixels * 0.78f).toInt())
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setDimAmount(0.5f)
        }
        dlg.show()
    }

    private fun removeFromStack(key: String) {
        saveStackKeys(stackKeys().mapNotNull { e ->
            if (isFolder(e)) { val ks = folderKeys(e) - key; if (ks.size >= 2) makeFolder(folderName(e), ks) else ks.firstOrNull() }
            else e.takeIf { it != key }
        })
    }

    private var deskMenu: android.widget.PopupWindow? = null

    /** Desktop picker centred at the former top bar; a pick changes the desktop in place. */
    private fun showDeskMenu() {
        deskMenu?.dismiss()
        val pop = android.widget.PopupWindow(this)
        deskMenu = pop
        val onDesk = shownPage == feedOffset
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glassShape(p, 24, true); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        for (i in 0 until pagesCount) {
            val on = onDesk && i == vPage
            col.addView(label(if (i == 0) "⌂   Main desktop" else "▢   Desktop ${i + 1}", 15f, if (on) p.onAccent else p.text, true).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0)
                background = if (on) shape(p.accent, 18) else null
                setOnClickListener { pop.dismiss(); if (onDesk) switchDesk(i) else goDesk(i) }
                pressFx(p, round = false)
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val menuWidth = minOf(dp(246), resources.displayMetrics.widthPixels - dp(24))
        val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        pop.contentView = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
        }
        pop.width = menuWidth
        pop.height = minOf(dp(16) + pagesCount * dp(48), maxHeight)
        pop.isFocusable = true; pop.isOutsideTouchable = true
        pop.setOnDismissListener { if (deskMenu === pop) deskMenu = null }
        pop.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); pop.elevation = dp(8).toFloat()
        val cameraInset = window.decorView.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
        pop.showAtLocation(window.decorView, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, cameraInset + dp(8))
    }

    /** The house button: the current desktop shrinks and fades, the next one appears in its place (no sideways scroll). */
    private fun switchDesk(n: Int) {
        if (!::homePager.isInitialized) return
        val vp = deskPager ?: return
        val col = vp.getChildAt(0) as? ViewGroup ?: return goDesk(n)
        val k = n.coerceIn(0, pagesCount - 1)
        val from = col.getChildAt(vPage.coerceIn(0, pagesCount - 1)); val to = col.getChildAt(k)
        if (shownPage != feedOffset || from == null || to == null || from === to) return goDesk(n)
        // A cube turn (vertical): the desktop you leave swings away around its edge, the next one swings in.
        val dir = if (k > vPage) 1 else -1
        val half = Fx.dur(170)
        val cam = 9000f * resources.displayMetrics.density
        fun pivot(v: View, atBottom: Boolean) { v.cameraDistance = cam; v.pivotX = v.width / 2f; v.pivotY = if (atBottom) v.height.toFloat() else 0f }
        from.animate().cancel(); to.animate().cancel()
        pivot(from, dir > 0)
        from.animate().rotationX(if (dir > 0) 90f else -90f).alpha(0.4f).setDuration(half).setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
            vp.scrollTo(0, k * vp.height)
            from.rotationX = 0f; from.alpha = 1f; from.pivotY = from.height / 2f
            pivot(to, dir < 0)
            to.rotationX = if (dir > 0) -90f else 90f; to.alpha = 0.4f
            to.animate().rotationX(0f).alpha(1f).setDuration(half).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                to.pivotY = to.height / 2f
            }.start()
        }.start()
    }

    /** Desktops sit side by side in the home strip (Feed | 1 | 2 | ... | Folders): go to desktop [n]. */
    private fun goDesk(n: Int) {
        if (!::homePager.isInitialized) return
        val k = n.coerceIn(0, pagesCount - 1)
        val vp = deskPager ?: return
        if (shownPage != feedOffset) {
            homePager.smoothScrollTo(feedOffset * resources.displayMetrics.widthPixels, 0)
            vp.post { vp.scrollTo(0, k * vp.height) }
        } else vp.goTo(k)
    }

    /** Pager position to the page the rest of Home thinks in: Feed, the desktops (one page; [vPage] says which) and Folders. */
    private fun onStripPage(raw: Int) = setDots(raw)

    /** Moves Feed or Folders one place along the section order (Feed / Desktops / Folders), then rebuilds Home in the editor. */
    private fun movePage(id: String, delta: Int) {
        // Only the sections that are on count as neighbours; a switched-off Feed or Folders keeps its slot in the saved order.
        val order = PageOrder.read(this).toMutableList()
        val live = order.filter { (it != "feed" || feedEnabled) && (it != "folders" || foldersEnabled) }
        val from = live.indexOf(id); val to = from + delta
        if (from < 0 || to !in live.indices) return
        order.remove(id); order.add(order.indexOf(live[to]) + if (delta > 0) 1 else 0, id)
        PageOrder.write(this, order)
        getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putBoolean("reopen_edit", true).apply()
        recreate()
    }

    /** The other way round: where a page (and, for the desktops, the current desktop) sits in the strip. */
    private fun stripIndex(logical: Int) = logical   // one strip child per section; the desktops share one (vertical) child

    /** One tap on an empty spot of a desktop turns it: the right half goes to the next desktop, the left half back. */
    private fun tapToTurn(page: View) {
        var tapX = 0f
        page.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) tapX = ev.x; false }
        page.setOnClickListener {
            if (editing) return@setOnClickListener
            if (pagesCount < 2) {
                // A single desktop: a tap on its right half opens Folders (swiping right is off on the main desktop).
                if (tapX > page.width / 2f && foldersPos >= 0) homePager.smoothScrollTo(stripIndex(foldersPos) * resources.displayMetrics.widthPixels, 0)
                return@setOnClickListener
            }
            switchDesk((vPage + if (tapX > page.width / 2f) 1 else -1).coerceIn(0, pagesCount - 1))   // cube turn, not a slide
        }
    }
    private lateinit var vDots: LinearLayout
    private var vPage = 0

    private fun paintVDots() {
        if (!::vDots.isInitialized) return
        vDots.removeAllViews()
        // Only on the desktops (not Feed or Folders) and only when there is more than one of them.
        vDots.visibility = if (showDesktopDots && pagesCount > 1 && !editing && shownPage == feedOffset) View.VISIBLE else View.GONE
        fun arrow(up: Boolean) = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right); rotation = if (up) -90f else 90f; setColorFilter(p.text)
            alpha = if ((up && vPage > 0) || (!up && vPage < pagesCount - 1)) 1f else 0.3f
            setOnClickListener { goDesk(vPage + if (up) -1 else 1) }
        }
        vDots.addView(arrow(true), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(2) })
        for (i in 0 until pagesCount) vDots.addView(View(this).apply {
            background = circle(if (i == vPage) p.accent else (p.sub and 0xFFFFFF) or 0x66000000)
        }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = dp(3); marginEnd = dp(3) })
        vDots.addView(arrow(false), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(2) })
    }

    private var sceneFx: SceneFx? = null
    private var dragged: ResolveInfo? = null
    private var dragX = 0f
    private var dragY = 0f

    private fun addToHome(ri: ResolveInfo) {
        val key = stackKey(ri)
        if (tilesStyle) {   // Start screen: pin as a tile
            if (key in tileKeys()) { android.widget.Toast.makeText(this, "Already on Start", android.widget.Toast.LENGTH_SHORT).show(); return }
            saveTileKeys(tileKeys() + key)
            android.widget.Toast.makeText(this, "${ri.loadLabel(packageManager)} pinned to Start", android.widget.Toast.LENGTH_SHORT).show()
            recreate(); return
        }
        val have = stackKeys().any { it == key || (isFolder(it) && key in folderKeys(it)) }
        if (have) { android.widget.Toast.makeText(this, "Already on the home screen", android.widget.Toast.LENGTH_SHORT).show(); return }
        saveStackKeys(stackKeys() + key)
        android.widget.Toast.makeText(this, "${ri.loadLabel(packageManager)} added to the home screen", android.widget.Toast.LENGTH_SHORT).show()
        recreate()
    }

    /** Drop of [src] on [dst] while editing. [zone]: 0 left edge = put before it, 1 middle = make or fill a folder, 2 right edge = swap places. */
    private fun onStackDrop(src: String, dst: String, zone: Int) {
        if (!editing || layoutLocked) return
        if (src == dst) return
        val list = stackKeys().toMutableList()
        if (zone == 2) {
            val a = list.indexOf(src); val b = list.indexOf(dst)
            if (a >= 0 && b >= 0) { list[a] = dst; list[b] = src; saveStackKeys(list) }
            getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putBoolean("reopen_edit", true).apply()
            recreate(); return
        }
        val before = zone == 0
        list.remove(src)
        val i = list.indexOf(dst)
        if (i < 0) return
        when {
            before || isFolder(src) -> list.add(i, src)
            isFolder(dst) -> list[i] = makeFolder(folderName(dst), folderKeys(dst) + src)
            else -> list[i] = makeFolder("Folder", listOf(dst, src))
        }
        saveStackKeys(list)
        getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putBoolean("reopen_edit", true).apply()
        recreate()
    }

    /** Folder popup: its apps, rename, and ungroup. */
    private fun openFolder(entry: String) {
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val cell = (resources.displayMetrics.widthPixels - dp(24) - dp(40)) / 4
        val grid = GridLayout(this).apply {
            columnCount = 4
            for (k in folderKeys(entry)) appFor(k)?.let { ri ->
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    addView(ImageView(this@HomeActivity).apply { setImageDrawable(AosIcons.themed(this@HomeActivity, ri.loadIcon(packageManager), p, 50, ri.activityInfo.packageName)) }, LinearLayout.LayoutParams(dp(50), dp(50)))
                    addView(label(ri.loadLabel(packageManager).toString(), 11f, p.text).apply { maxLines = 1; setPadding(0, dp(4), 0, 0) })
                    setOnClickListener { dlg.dismiss(); launch(ri, from = getChildAt(0)) }
                    setOnLongClickListener {
                        dlg.dismiss()
                        // Take the app out of the folder and put it back on the stack right after it.
                        val rest = folderKeys(entry) - k
                        val list = stackKeys().toMutableList(); val idx = list.indexOf(entry)
                        if (idx >= 0) {
                            if (rest.size >= 2) { list[idx] = makeFolder(folderName(entry), rest); list.add(idx + 1, k) }
                            else { list.removeAt(idx); list.addAll(idx, rest + k) }
                            saveStackKeys(list); recreate()
                        }
                        true
                    }
                    pressFx(p)
                }, GridLayout.LayoutParams().apply { width = cell; height = dp(86) })
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glassShape(p, 28, true); setPadding(dp(20), dp(20), dp(20), dp(16))
            addView(label(folderName(entry), 20f, p.text, true))
            addView(label("Long-press an app to take it out", 12f, p.sub).apply { setPadding(0, dp(2), 0, dp(12)) })
            addView(grid)
            addView(LinearLayout(this@HomeActivity).apply {
                addView(label("Rename", 14f, p.text, true).apply {
                    background = shape(p.surfaceHigh, 22); setOnClickListener {
                        dlg.dismiss()
                        val input = android.widget.EditText(this@HomeActivity).apply { setText(folderName(entry)); isSingleLine = true }
                        android.app.AlertDialog.Builder(this@HomeActivity).setTitle("Folder name").setView(input).setPositiveButton("Save") { _, _ ->
                            val list = stackKeys().toMutableList(); val i = list.indexOf(entry)
                            if (i >= 0) { list[i] = makeFolder(input.text.toString().trim(), folderKeys(entry)); saveStackKeys(list); recreate() }
                        }.setNegativeButton("Cancel", null).show()
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
                addView(label("Ungroup", 14f, 0xFFE5484D.toInt(), true).apply {
                    background = shape(p.surfaceHigh, 22); setOnClickListener {
                        dlg.dismiss()
                        val list = stackKeys().toMutableList(); val i = list.indexOf(entry)
                        if (i >= 0) { list.removeAt(i); list.addAll(i, folderKeys(entry)); saveStackKeys(list); recreate() }
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        dlg.setContentView(FrameLayout(this).apply { setPadding(dp(12), 0, dp(12), 0); addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) })
        dlg.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setLayout(-1, -2); setDimAmount(0.5f) }
        dlg.show()
    }

    /** The stack starts empty; the user picks which apps live on the home screen. */
    private fun chooseStackApps() {
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val current = stackKeys()
        val flat = current.flatMap { if (isFolder(it)) folderKeys(it) else listOf(it) }
        val checked = BooleanArray(apps.size) { stackKey(apps[it]) in flat }
        android.app.AlertDialog.Builder(this).setTitle("Apps on the home screen")
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Done") { _, _ ->
                val picked = apps.filterIndexed { i, _ -> checked[i] }.map { stackKey(it) }.toSet()
                // Keep the user's order and folders (minus unchecked apps); new apps go to the end.
                val kept = current.mapNotNull { e ->
                    if (isFolder(e)) { val ks = folderKeys(e).filter { it in picked }; if (ks.size >= 2) makeFolder(folderName(e), ks) else ks.firstOrNull() }
                    else e.takeIf { it in picked }
                }
                saveStackKeys(kept + picked.filter { k -> kept.none { it == k || (isFolder(it) && k in folderKeys(it)) } })
                recreate()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun gridCols() = getSharedPreferences("aos_setup", MODE_PRIVATE).getInt("grid_cols", 4).coerceIn(3, 5)
    private fun gridRows() = getSharedPreferences("aos_setup", MODE_PRIVATE).getInt("grid_rows", 2).coerceIn(2, 5)
    private fun cellDp(cols: Int) = when (cols) { 3 -> 90; 4 -> 82; else -> 76 }

    /** Manager > Apps > Grid: columns x visible rows of the home app grid, limited to what fits under the widget. */
    private fun cycleGrid() {
        val screenDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val options = listOf(3 to 2, 3 to 3, 4 to 2, 4 to 3, 4 to 4, 5 to 3, 5 to 4, 5 to 5)
            .filter { (c, r) -> r * cellDp(c) + 40 <= screenDp - 72 - 230 - 130 }.ifEmpty { listOf(4 to 2) }
        val next = options[(options.indexOf(gridCols() to gridRows()) + 1) % options.size]
        getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putInt("grid_cols", next.first).putInt("grid_rows", next.second).apply()
        recreate()
    }

    private fun buildAppStack(): View {
        val slideW = resources.displayMetrics.widthPixels - dp(32)
        val strip = LinearLayout(this)
        val cols = gridCols(); val rows = gridRows()
        val cellH = dp(cellDp(cols))
        val iconScale = listOf(0.9f, 1.0f, 1.1f)[getSharedPreferences("aos_setup", MODE_PRIVATE).getInt("icon_scale", 1).coerceIn(0, 2)]
        val iconDp = (when (cols) { 3 -> 56; 4 -> 48; else -> 42 } * iconScale).toInt()
        val entries = stackKeys().filter { isFolder(it) && folderKeys(it).any { k -> appFor(k) != null } || appFor(it) != null }
        if (entries.isEmpty()) {
            return LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(label("+  Add apps", 15f, p.text, true).apply {
                    background = glassShape(p, 24); setPadding(dp(22), dp(12), dp(22), dp(12))
                    setOnClickListener { chooseStackApps() }; pressFx(p, round = false)
                })
                setOnLongClickListener { enterEdit(); true }
            }
        }
        val perPage = cols * 3   // a slider: three rows of apps per slide
        for (chunk in entries.chunked(perPage)) {
            val grid = GridLayout(this).apply { columnCount = cols }
            for (entry in chunk) {
                val folder = isFolder(entry)
                val ri = if (folder) null else appFor(entry)
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    if (folder) {
                        // A folder: glass tile with up to four mini icons.
                        val mini = GridLayout(this@HomeActivity).apply { columnCount = 2; rowCount = 2; setPadding(dp(5), dp(5), dp(5), dp(5)) }
                        for (k in folderKeys(entry).take(4)) appFor(k)?.let { a ->
                            mini.addView(ImageView(this@HomeActivity).apply { setImageDrawable(AosIcons.themed(this@HomeActivity, a.loadIcon(packageManager), p, 20, a.activityInfo.packageName)) },
                                GridLayout.LayoutParams().apply { width = dp(20); height = dp(20); setMargins(dp(2), dp(2), dp(2), dp(2)) })
                        }
                        addView(FrameLayout(this@HomeActivity).apply { background = glassShape(p, 16, true); addView(mini, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) },
                            LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
                        addView(label(folderName(entry), 11f, p.text).onWallpaper().apply { maxLines = 1; setPadding(0, dp(5), 0, 0) })
                        setOnClickListener { openFolder(entry) }
                    } else if (ri != null) {
                        addView(ImageView(this@HomeActivity).apply {
                            setImageDrawable(AosIcons.themed(this@HomeActivity, ri.loadIcon(packageManager), p, iconDp, ri.activityInfo.packageName))
                        }, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
                        addView(label(ri.loadLabel(packageManager).toString(), 11f, p.text).onWallpaper().apply { maxLines = 1; setPadding(0, dp(5), 0, 0) })
                        setOnClickListener { if (!editing) launch(ri, from = getChildAt(0)) else editTap(ri, this) }
                    }
                    // Editing: long-press picks the cell up; drop it on the left edge of another to reorder, in the middle to make a folder.
                    setOnLongClickListener {
                        if (!editing) enterEdit()
                        if (layoutLocked) android.widget.Toast.makeText(this@HomeActivity, "Layout is locked", android.widget.Toast.LENGTH_SHORT).show()
                        else startDragAndDrop(android.content.ClipData.newPlainText("stack", entry), View.DragShadowBuilder(this), null, 0)
                        true
                    }
                    setOnDragListener { v, ev ->
                        when (ev.action) {
                            android.view.DragEvent.ACTION_DRAG_STARTED -> ev.clipDescription?.label?.toString() == "stack"
                            android.view.DragEvent.ACTION_DRAG_ENTERED -> { v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).start(); true }
                            android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> { v.animate().scaleX(1f).scaleY(1f).setDuration(90).start(); true }
                            android.view.DragEvent.ACTION_DROP -> {
                                val src = ev.clipData.getItemAt(0).text.toString()
                                onStackDrop(src, entry, zone = if (ev.x < v.width * 0.3f) 0 else if (ev.x > v.width * 0.7f) 2 else 1); true
                            }
                            else -> true
                        }
                    }
                    pressFx(p)
                }
                // Edit mode: an iOS-style "−" badge on every icon; tapping it asks "Delete?" with Yes / No (a folder asks to ungroup).
                val badge = TextView(this).apply {
                    text = "−"; textSize = 17f; typeface = appFont(700); gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
                    includeFontPadding = false
                    background = circle(0xE63A3F47.toInt()); visibility = if (editing) View.VISIBLE else View.GONE
                    contentDescription = "Delete"
                    setOnClickListener {
                        if (editing && !layoutLocked) { if (folder) confirmUngroup(entry) else if (ri != null) askRemove(ri, cell) }
                    }
                }
                editBadges += badge
                val holder = FrameLayout(this).apply {
                    addView(cell, FrameLayout.LayoutParams(-1, -1))
                    addView(badge, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(2), 0, 0) })
                }
                wiggleCells += holder   // the badge rocks together with its icon, like iOS
                applyIdleAnim(holder)
                grid.addView(holder, GridLayout.LayoutParams().apply { width = slideW / cols; height = cellH })
            }
            strip.addView(grid, LinearLayout.LayoutParams(slideW, -2))
        }
        // Widget: a centred header (arrows, title, page dots) above the slides.
        val pageCount = (entries.size + perPage - 1) / perPage
        val pageDots = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        lateinit var slider: Pager
        fun arrow(right: Boolean) = ImageView(this).apply {
            setImageResource(if (right) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left); setColorFilter(p.text)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener { if (slider.width > 0) slider.smoothScrollTo(((slider.scrollX / slider.width) + if (right) 1 else -1).coerceIn(0, pageCount - 1) * slider.width, 0) }
        }
        val prev = arrow(false); val next = arrow(true)
        var painted = -1
        fun paintPage(page: Int) {
            if (page == painted) return   // called on every scroll frame: rebuild only when the page really changes
            painted = page
            pageDots.removeAllViews()
            if (pageCount > 1) for (i in 0 until pageCount) pageDots.addView(View(this).apply {
                background = circle(if (i == page) p.accent else (p.sub and 0xFFFFFF) or 0x66000000)
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = dp(3); marginEnd = dp(3) })
            prev.alpha = if (page > 0) 1f else 0.3f; next.alpha = if (page < pageCount - 1) 1f else 0.3f
        }
        slider = Pager(this) { paintPage(it) }.apply { lockParent = true; addView(strip) }
        val header = LinearLayout(this).apply {
            // Title on the left, page arrows and dots on the right.
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(2), dp(6))
            addView(ImageView(this@HomeActivity).apply { setImageResource(R.drawable.ic_start); setColorFilter(p.accent) }, LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(label("Apps", 14f, p.text, true).onWallpaper(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(label("${entries.size}", 12f, p.sub), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(View(this@HomeActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(prev, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(pageDots, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) })
            addView(next, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        paintPage(0)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(slider, LinearLayout.LayoutParams(slideW, cellH * minOf(3, (entries.size + cols - 1) / cols)))
        }.also { stackPager = it }
    }

    private lateinit var island: DynamicIsland
    private var wasCharging: Boolean? = null
    private var wasFull = false
    private lateinit var batteryText: TextView
    private lateinit var wifiIcon: ImageView
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0) batteryText.text = "${level * 100 / scale}%"
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val full = status == BatteryManager.BATTERY_STATUS_FULL || (charging && level >= scale)
            // The charging effect can be switched off in AOS Settings > Panels.
            val fxOn = getSharedPreferences("aos_setup", MODE_PRIVATE).getBoolean("charging_fx", true)
            if (::chargingFx.isInitialized) chargingFx.apply(charging && fxOn, full && fxOn,
                if (level >= 0 && scale > 0) level * 100 / scale else 50)
            refreshStatus()
            if (::island.isInitialized && level >= 0) {
                val pct = level * 100 / scale
                if (full && !wasFull) island.show("Full  100%")
                else if (charging && wasCharging == false) island.show("Charging  $pct%")
                wasFull = full; wasCharging = charging
            }
        }
    }
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
            runOnUiThread { wifiIcon.alpha = if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1f else 0.3f; refreshStatus() }
        }
        override fun onLost(n: Network) { runOnUiThread { wifiIcon.alpha = 0.3f } }
    }

    /** Soft shadow under text drawn straight on the wallpaper (a light halo on light themes). */
    private fun TextView.onWallpaper(): TextView = apply {
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), if (p.dark) 0xCC000000.toInt() else 0xB3FFFFFF.toInt())
    }

    private fun label(t: String, sp: Float, c: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = sp; setTextColor(c); gravity = Gravity.CENTER
            typeface = appFont(if (bold) 700 else 400)
        }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res); setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fx.refresh(this)
        restoredPage = savedInstanceState?.getInt("aos_page")
        restoredDesktop = savedInstanceState?.getInt("aos_desktop", 0) ?: 0
        p = Palette(this)
        val setup = getSharedPreferences("aos_setup", MODE_PRIVATE)
        pagesCount = setup.getInt("pages", DEFAULT_PAGES).coerceIn(1, 5)
        feedEnabled = setup.getBoolean("feed", true)
        foldersEnabled = setup.getBoolean("folders_page", true)
        stackEnabled = setup.getBoolean("stack", true)
        tilesStyle = setup.getString("home_style", "android") == "tiles"
        apps = queryApps()
        val root = FrameLayout(this)
        applyWallpaper()
        appDrawer = AppDrawer(this, p, apps, { closeDrawer() }, { launch(it) }, { addToHome(it) }) { ri, v ->
            // Pick the app up straight out of the drawer: the drawer closes and the desktop is the drop target.
            dragged = ri
            v.startDragAndDrop(android.content.ClipData.newPlainText("drawer", stackKey(ri)), View.DragShadowBuilder(v), null, 0)
            closeDrawer()
        }
        root.setOnDragListener { _, ev ->
            if (ev.clipDescription?.label?.toString() != "drawer") return@setOnDragListener false
            when (ev.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> { dragX = ev.x; dragY = ev.y; true }
                android.view.DragEvent.ACTION_DROP -> {
                    val ri = dragged
                    if (ri != null) {
                        // Released without moving = it was a plain long-press: bring back the drawer with the app menu.
                        if (Math.hypot((ev.x - dragX).toDouble(), (ev.y - dragY).toDouble()) > dp(24)) addToHome(ri)
                        else { openDrawer(); appDrawer.showMenu(ri) }
                    }
                    true
                }
                else -> true
            }
        }
        drawer = appDrawer.view.also { it.visibility = View.GONE }
        // Moving parts of the chosen theme (stars, beam, petals...), over the wallpaper and behind everything else.
        // Windows Phone style has its own plain black Start background: no Android wallpaper or animated theme behind the tiles.
        if (tilesStyle) root.setBackgroundColor(android.graphics.Color.BLACK)
        else if (setup.getBoolean("anim_theme", true)) {
            val st = setup.getString("wallpaper", AosStyles.DEFAULT).orEmpty()
            if (st != "custom") root.addView(SceneFx(this, st, p.dark).also { sceneFx = it }, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(buildPages())
        topBarView = buildTopBar()
        taskbarView = buildTaskbar()
        root.addView(topBarView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(8), dp(12), 0) })
        if (!tilesStyle) root.addView(taskbarView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(28); marginStart = dp(12); marginEnd = dp(12) })   // air above the gesture line
        editSheet = buildEditSheet()
        editBar = buildEditBar()
        root.addView(editBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24); marginStart = dp(12); marginEnd = dp(12) })   // the dock hides while editing: the bar takes its place
        // The bottom bars keep clear of the system navigation: the gesture line needs 28dp, three buttons need their own height.
        fun liftBottomBars(nav: Int) {
            val lift = maxOf(dp(28), nav + dp(8))
            for ((v, base) in listOf(taskbarView to 0, editBar to -dp(4))) (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.bottomMargin != lift + base) { lp.bottomMargin = lift + base; v.layoutParams = lp }
            }
        }
        taskbarView.setOnApplyWindowInsetsListener { v, ins ->
            liftBottomBars(ins.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom); ins
        }
        showUndoIfAny(root)
        root.addView(miniHolder, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(62) })
        root.addView(editSheet, FrameLayout.LayoutParams(-1, -1))
        root.addView(drawer)
        recents = RecentsScreen(this, p, apps, { launch(it) }) { blurHome(it) }
        root.addView(recents.view)
        chargingFx = ChargingFx(this, p)
        root.addView(chargingFx.view, FrameLayout.LayoutParams(-1, -1))
        island = DynamicIsland(this, p)
        root.addView(island.view, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        controlCenter = ControlCenter(this, p)   // no blur or dimming behind it: the panel is a menu by the top bar
        // The top bar lives above the dock: the panel stands right above the bar and dock (or near the bottom edge if they are hidden).
        controlCenter.bottomGap = {
            val top = if (::taskbarView.isInitialized && taskbarView.visibility == View.VISIBLE && taskbarView.height > 0) taskbarView.top else 0
            if (top > 0) maxOf(dp(24), root.height - top + dp(10)) else dp(24)
        }
        root.addView(controlCenter.view)
        // The calendar opens from the time and date of the top bar, in the same panel as the Control Center.
        calendarPanel = CalendarPanel(this, p) { controlCenter.bottomGap() }
        root.addView(calendarPanel.view, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        Fx.revealAfterRecreate(this)
        // Fully transparent system bars: no colour and no automatic contrast scrim behind the clock or the nav handle.
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        window.insetsController?.setSystemBarsAppearance(
            if (p.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        // After a change made in edit mode (folders, pages) the screen is rebuilt and goes straight back to editing.
        if (setup.getBoolean("reopen_edit", false) || savedInstanceState?.getBoolean("aos_editing", false) == true) { setup.edit().remove("reopen_edit").apply(); root.post { enterEdit() } }
        // Ask once per run to be the default Home app, so the system opens AOS instead of the stock launcher.
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        if (!askedHome && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
            askedHome = true
            try { startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 7) } catch (_: Exception) {}
        }
        // Own top bar replaces the system status bar on the home screen; swipe from the top still reveals it.
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // A swipe from the very top edge is taken by the system and only reveals its status bar. Turn that reveal into
        // our Control Center and hide the system bar again, so the stock shade never opens from the home screen.
        var barShown = false
        root.setOnApplyWindowInsetsListener { v, insets ->
            val shown = insets.isVisible(WindowInsets.Type.statusBars())
            val now = android.os.SystemClock.uptimeMillis()
            // Edge swipes never reach our window (the system takes them), so only skip the cases we caused ourselves.
            if (shown && !barShown && now - resumedAt > 800 && now - swipeDownAt > 400 && !recents.isOpen && !editing &&
                drawer.visibility != View.VISIBLE) {
                if (!controlCenter.isOpen) controlCenter.show()
                root.post { window.insetsController?.hide(WindowInsets.Type.statusBars()) }
            }
            barShown = shown
            v.onApplyWindowInsets(insets)
        }
    }

    private var resumedAt = 0L
    private var restoredPage: Int? = null
    private var restoredDesktop = 0

    override fun onSaveInstanceState(outState: Bundle) {
        if (isChangingConfigurations) Fx.captureForRecreate(this)   // theme switch / rebuild: the old screen fades out over the new one
        outState.putInt("aos_page", shownPage.coerceAtLeast(0))
        outState.putInt("aos_desktop", vPage)
        outState.putBoolean("aos_editing", editing)
        super.onSaveInstanceState(outState)
    }

    private val replacedFileManagers = setOf("com.google.android.apps.nbu.files", "com.google.android.documentsui", "com.android.documentsui")

    private fun queryApps(): List<ResolveInfo> {
        val stock = setOfNotNull(
            getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage,
            android.provider.Telephony.Sms.getDefaultSmsPackage(this),
            packageManager.resolveActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0)?.activityInfo?.packageName
        ) - packageName
        return packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.name != HomeActivity::class.java.name }
            .filter { it.activityInfo.packageName !in stock || it.loadLabel(packageManager).toString().contains("ontact", true) }
            // AOS Files replaces the stock file managers (Files by Google, the system DocumentsUI entry).
            .filter { it.activityInfo.packageName !in replacedFileManagers }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    }

    private fun appComponents(list: List<ResolveInfo>) = list.map {
        android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name)
    }.toSet()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Replacement sends REMOVED before ADDED; wait until the new APK is ready.
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            recreate()
        }
    }

    private companion object { var askedHome = false }

    /** Coming back home always shows the first home page, like every launcher. */
    private fun resetPager() {
        if (!::homePager.isInitialized) return
        restoredPage?.let { page ->
            restoredPage = null
            vPage = restoredDesktop.coerceIn(0, pagesCount - 1)
            homePager.post { homePager.scrollTo(stripIndex(page.coerceIn(0, totalPages - 1)) * resources.displayMetrics.widthPixels, 0) }
            deskPager?.let { vp -> vp.post { vp.scrollTo(0, vPage * vp.height) } }
            return
        }
        if (editing) return
        homePager.post { homePager.scrollTo(feedOffset * resources.displayMetrics.widthPixels, 0) }
        deskPager?.let { vp -> vp.post { vp.scrollTo(0, 0) } }
        stackPager?.let { sp -> sp.post { sp.scrollTo(0, 0) } }
    }

    override fun onResume() {
        super.onResume()
        homeInFront = true
        val away = resumedAt != 0L
        resumedAt = android.os.SystemClock.uptimeMillis()
        Fx.refresh(this)
        Fx.preferHighRefresh(this)
        // Staggered Icons: coming back home, the app icons arrive one after another.
        if (away && Fx.on(this, "stagger")) wiggleCells.filter { it.isShown }.let { if (it.isNotEmpty()) Fx.stagger(it) }
        // AOS Settings changed a desktop option: rebuild the Home so it takes effect.
        getSharedPreferences("aos_setup", MODE_PRIVATE).let { sp ->
            if (sp.getBoolean("aos_dirty", false)) { sp.edit().putBoolean("aos_dirty", false).apply(); recreate(); return }
        }
        if (appComponents(queryApps()) != appComponents(apps)) { recreate(); return }
        sceneFx?.start()
        tiltMotion.start()
        if (editing) startWiggle()
        for (a in idleAnims) if (a.isPaused) a.resume() else if (!a.isStarted) a.start()
        resetPager()
        // The top strip of the gesture service asked for the Control Center (swipe down from the top edge in any app).
        val wantCc = SplitService.pendingCc
        if (wantCc >= 0) {
            SplitService.pendingCc = -1
            window.decorView.postDelayed({ if (::controlCenter.isInitialized && !controlCenter.isOpen) controlCenter.show(wantCc) }, 120)
        }
        refreshWeather()   // the location permission may have been granted in Settings while we were away
        removedPkg?.let { pkg ->
            removedPkg = null
            val gone = try { packageManager.getPackageInfo(pkg, 0); false } catch (_: Exception) { true }
            if (gone) recreate()
        }
    }

    private var removedPkg: String? = null
    private var lastTapPkg = ""
    private var lastTapAt = 0L

    /** Edit mode: a double tap on an app asks whether to remove it; a single tap just gives a small pop. */
    private fun editTap(ri: ResolveInfo, v: View) {
        if (layoutLocked) return
        val pkg = ri.activityInfo.packageName
        val now = android.os.SystemClock.uptimeMillis()
        v.animate().cancel()
        v.animate().scaleX(1.18f).scaleY(1.18f).setDuration(Motion.FAST)
            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.NORMAL).start() }.start()
        if (pkg == lastTapPkg && now - lastTapAt < 450) {
            lastTapAt = 0
            askRemove(ri, v)
        } else { lastTapPkg = pkg; lastTapAt = now }
    }

    private fun askRemove(ri: ResolveInfo, v: View) {
        val name = ri.loadLabel(packageManager).toString()
        val system = ri.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        val uninstall = {
            v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(Motion.NORMAL).withEndAction {
                removedPkg = ri.activityInfo.packageName
                try {
                    startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + ri.activityInfo.packageName)))
                } catch (_: Exception) { removedPkg = null }
                v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
            }.start()
        }
        // Like iOS: Delete App (user apps only) / Remove from Home Screen / Cancel.
        val hide = { removeFromStack(stackKey(ri)); getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putBoolean("reopen_edit", true).apply(); recreate() }
        val actions = listOfNotNull(
            if (system) null else DialogAction("Delete App", 2) { uninstall() },
            DialogAction("Remove from Home Screen", 1) { hide() },
            DialogAction("Cancel"))
        aosDialog(p, name,
            if (system) "It leaves the home screen and stays in the app drawer." else "Deleting removes the app and its data. Removing keeps it in the app drawer.",
            AosIcons.themed(this, ri.loadIcon(packageManager), p, 56, ri.activityInfo.packageName), *actions.toTypedArray())
    }

    /** Folder badge: Yes / No to dissolve the folder, the apps go back onto the stack. */
    private fun confirmUngroup(entry: String) {
        aosDialog(p, "Remove folder ${folderName(entry)}?", "Its apps go back to the home screen.", null,
            DialogAction("Yes", 1) {
                val list = stackKeys().toMutableList(); val i = list.indexOf(entry)
                if (i >= 0) { list.removeAt(i); list.addAll(i, folderKeys(entry)); saveStackKeys(list) }
                getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putBoolean("reopen_edit", true).apply(); recreate()
            }, DialogAction("No"))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (editing) exitEdit()
        // Asked from AOS Settings (Desktop section): open edit mode, the manager panel or the wallpaper picker.
        if (intent?.getBooleanExtra("aos_edit", false) == true) { window.decorView.post { enterEdit() }; return }
        if (intent?.getBooleanExtra("aos_manager", false) == true) { window.decorView.post { enterEdit(); openManager() }; return }
        if (intent?.getBooleanExtra("aos_pick_wallpaper", false) == true) { window.decorView.post { pickWallpaper() }; return }
        if (intent?.getBooleanExtra("aos_choose_apps", false) == true) { window.decorView.post { chooseStackApps() }; return }
        if (intent?.hasExtra(EXTRA_CC) == true) {
            val page = intent.getIntExtra(EXTRA_CC, 0)
            resetPager()
            window.decorView.post { if (!controlCenter.isOpen) controlCenter.show(page) }
        } else if (intent?.getBooleanExtra(EXTRA_RECENTS, false) == true) {
            // Replacing the stock Recents: no window transition and no fade, so only one frame of the stock screen shows.
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            recents.show(instant = true)
        }
        else {
            // The system Home button always brings back the main desktop (not a page restored after an update or recreate).
            restoredPage = null; vPage = 0
            resetPager()
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgetHost.startListening() }
        if (!::wifiIcon.isInitialized) return
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { batteryReceiver.onReceive(this, it) }
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, packages, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(packageReceiver, packages)
        val lastCheck = getSharedPreferences("aos_update", MODE_PRIVATE).getLong("checked_at", 0)
        if (System.currentTimeMillis() - lastCheck > 5 * 60 * 1000) Updater.check(this, false)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
        NotifService.onChange = { runOnUiThread {
            refreshStatus()
            if (::controlCenter.isInitialized) controlCenter.refreshNotifications()
        } }
        refreshStatus()
    }

    override fun onPause() { super.onPause(); homeInFront = false }

    override fun onStop() {
        super.onStop()
        deskMenu?.dismiss()
        tiltMotion.stop()
        runCatching { widgetHost.stopListening() }
        feedTranslator?.pause()
        sceneFx?.stop()
        stopWiggle()
        for (a in idleAnims) a.pause()
        try { unregisterReceiver(packageReceiver) } catch (_: IllegalArgumentException) {}
        if (!::wifiIcon.isInitialized) return
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        NotifService.onChange = null
        if (::chargingFx.isInitialized) chargingFx.stop()
        if (::island.isInitialized) island.stop()
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        feedTranslator?.close()
        stopWiggle()
        for (a in idleAnims) a.cancel()
        idleAnims.clear()
        super.onDestroy()
    }

    private fun expandPanel(settings: Boolean) {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod(if (settings) "expandSettingsPanel" else "expandNotificationsPanel").invoke(sb)
        } catch (_: Exception) { }
    }

    private lateinit var statusRow: LinearLayout

    /** Status items of the stock Realme bar that matter: notification icons, NFC, Bluetooth, alarm, DND, VPN, location, airplane. */
    private fun refreshStatus() {
        if (!::statusRow.isInitialized) return
        statusRow.removeAllViews()
        fun add(res: Int) = statusRow.addView(icon(res, p.text, 15).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) })
        // Icons of apps with pending notifications (needs notification access), at most three.
        NotifService.instance?.visibleNotifications()?.map { it.packageName }?.distinct()?.take(3)?.forEach { pkg ->
            val d = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null } ?: return@forEach
            statusRow.addView(ImageView(this).apply { setImageDrawable(AosIcons.themed(this@HomeActivity, d, p, 16)) },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) })
        }
        runCatching { if (android.nfc.NfcAdapter.getDefaultAdapter(this)?.isEnabled == true) add(R.drawable.ic_nfc) }
        runCatching { if (getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true) add(R.drawable.ic_bluetooth) }
        runCatching { if (getSystemService(android.app.AlarmManager::class.java).nextAlarmClock != null) add(R.drawable.ic_alarm) }
        runCatching {
            if (getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) add(R.drawable.ic_dnd)
        }
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add(R.drawable.ic_vpn)
        }
        runCatching { if (getSystemService(android.location.LocationManager::class.java).isLocationEnabled) add(R.drawable.ic_pin) }
        runCatching { if (Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) add(R.drawable.ic_airplane) }
    }

    /** AOS top bar: clock and date on the left (opens notifications), network/SIM/battery on the right (opens quick settings). */
    private fun buildTopBar(): View {
        val tm = getSystemService(TelephonyManager::class.java)
        val left = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextClock(this@HomeActivity).apply {
                format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 16f; setTextColor(p.text); typeface = appFont(700)
            })
            addView(label(DateFormat.format("EEE, d MMM", Date()).toString(), 13f, p.sub).apply { setPadding(dp(10), 0, 0, 0) })
        }
        wifiIcon = icon(R.drawable.ic_wifi, p.text, 18).apply { alpha = 0.3f }
        batteryText = label("", 13f, p.text, true)
        statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val right = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(statusRow)
            addView(wifiIcon.also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
            for (slot in 0..1) {
                val ready = tm.getSimState(slot) == TelephonyManager.SIM_STATE_READY
                addView(icon(R.drawable.ic_signal, p.text, 15).apply {
                    alpha = if (ready) 1f else 0.3f
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
                })
            }
            addView(icon(R.drawable.ic_battery, p.text, 18).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            })
            addView(batteryText)
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // Two glass pills (clock and date | status icons) with a gap for the camera cutout, the same material as the dock.
            // No plate behind the bar: clock and icons sit straight on the wallpaper.
            left.apply { setPadding(dp(14), 0, dp(10), 0) }
            right.apply { setPadding(dp(10), 0, dp(14), 0) }
            // Control Center opens only by pulling the bar down, never by tapping it. The bar keeps the whole gesture.
            var downY = 0f
            var downX = 0f
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = ev.rawY; downX = ev.rawX }
                    // Like HyperOS: pulling down on the left half opens notifications, on the right half the controls.
                    MotionEvent.ACTION_MOVE -> if (ev.rawY - downY > dp(36) && !controlCenter.isOpen)
                        controlCenter.show(if (downX < resources.displayMetrics.widthPixels / 2f) 1 else 0)
                }
                true
            }
            // The status plate above the dock shows all of this now: the bar stays only as the pull-down zone.
            left.visibility = View.INVISIBLE; right.visibility = View.INVISIBLE
            val gap = View(this@HomeActivity)
            addView(left, LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(gap, LinearLayout.LayoutParams(dp(8), 1))
            addView(right, LinearLayout.LayoutParams(-2, dp(36)))
            // Once the window insets are known, open the gap exactly under the camera hole and centre the pills on it.
            post {
                val cut = rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull { it.top <= 2 } ?: return@post
                val screenW = resources.displayMetrics.widthPixels
                val side = dp(12)                                   // margin of the bar inside the screen
                val gapW = cut.width() + dp(20)
                val cx = cut.exactCenterX().toInt()
                // The gap is centred exactly on the camera hole; each pill gets what is left on its side.
                left.getChildAt(1)?.visibility = View.GONE         // no room for the date beside the cutout; the widget shows it
                (statusRow.parent as? ViewGroup)?.removeView(statusRow)
                left.addView(statusRow, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })   // notification and radio icons live on the left
                // The right pill keeps Wi-Fi, the SIM bars and the battery percentage; the small battery icon makes way for them.
                right.getChildAt(right.childCount - 2)?.visibility = View.GONE
                // Air between the SIM bars and the battery percentage (they used to touch).
                batteryText.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(9) }
                left.layoutParams = LinearLayout.LayoutParams(cx - gapW / 2 - side, dp(36))
                gap.layoutParams = LinearLayout.LayoutParams(gapW, 1)
                right.layoutParams = LinearLayout.LayoutParams(screenW - side - (cx + gapW / 2), dp(36))
                (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.topMargin = (cut.exactCenterY() - dp(18)).toInt().coerceAtLeast(dp(4)); layoutParams = lp
                }
                requestLayout()
            }
        }
    }

    private fun buildPages(): View = FrameLayout(this).apply {
        val w = resources.displayMetrics.widthPixels
        pagesView = this
        val center = LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(72), dp(16), dp(200))   // room for the dock with its dots and the status plate above it
            if (tilesStyle) {
                // Start screen: the tiles take the whole page, from under the top bar down to the navigation bar.
                setPadding(0, dp(60), 0, 0)
                addView(buildTiles(), LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                addView(deskPanel(buildWidget()), LinearLayout.LayoutParams(-1, -2))
                addView(View(this@HomeActivity), LinearLayout.LayoutParams(-1, 0, 1f))
            }
            if (tilesStyle) Unit
            else if (stackEnabled) addView(deskPanel(resizableStack(buildAppStack())), LinearLayout.LayoutParams(-1, -2))
            setOnLongClickListener { enterEdit(); true }
        }
        val strip = LinearLayout(this@HomeActivity)
        vDots = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        // Sections in the user's order. Home desktops 1..N sit side by side in the "desk" slot: swipe, or tap an empty spot
        // (right half: next desktop, left half: the one before).
        for (id in pageOrder) when (id) {
            "feed" -> strip.addView(buildFeedPage(), LinearLayout.LayoutParams(w, -1))
            "folders" -> strip.addView(buildFoldersPage(), LinearLayout.LayoutParams(w, -1))
            else -> {
                // The desktops 1..N are stacked vertically in one pager (swipe up / down); the strip keeps Feed | desktops | Folders.
                tapToTurn(center)
                val column = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL }
                val screenH = resources.displayMetrics.heightPixels
                column.addView(center, LinearLayout.LayoutParams(-1, screenH))
                for (i in 2..(if (tilesStyle) 1 else pagesCount)) {   // Start is one long scrolling page
                    val page = FrameLayout(this@HomeActivity).apply { setOnLongClickListener { enterEdit(); true }; addView(buildSlotGrid(i)) }
                    tapToTurn(page)
                    column.addView(page, LinearLayout.LayoutParams(-1, screenH))
                }
                val vp = VPager(this@HomeActivity) { d ->
                    val k = d.coerceIn(0, pagesCount - 1)
                    if (k != vPage) { vPage = k; paintVDots(); if (editing) paintMini() }
                }
                vp.addView(column, FrameLayout.LayoutParams(-1, -2))
                deskPager = vp
                strip.addView(vp, LinearLayout.LayoutParams(w, -1))
            }
        }
        val pager = Pager(this@HomeActivity) { onStripPage(it) }
        // Parallax: the wallpaper drifts across a fraction of its width; Depth: the animated scene moves faster than it.
        val parallax = Fx.on(this@HomeActivity, "parallax"); val depth = Fx.on(this@HomeActivity, "depth")
        if (parallax || depth) pager.onScrollFraction = { f ->
            if (parallax) wpFx?.offset = f
            if (depth) {
                scenePageShift = (0.5f - f) * dp(48)
                sceneFx?.translationX = scenePageShift + sceneTiltX
            }
        }
        homePager = pager
        // Horizontal swipes only move between Feed, the desktops and Folders; the desktops themselves turn vertically.
        pager.isFillViewport = true
        pager.addView(strip, FrameLayout.LayoutParams(-2, -1))
        addView(pager, FrameLayout.LayoutParams(-1, -1))
        // Page dots (Feed | desktops | Folders) sit just under the app stack, the stack ends 200dp above the bottom.
        dots = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER }
        addView(dots, FrameLayout.LayoutParams(-2, dp(8), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(186) })
        if (!showPageDots) dots.visibility = View.GONE
        // Desktop indicator (only with more than one desktop): a slim glass pill under the top bar, up/down arrows at its ends.
        vDots.orientation = LinearLayout.HORIZONTAL
        vDots.background = glassShape(p, 12); vDots.setPadding(dp(4), 0, dp(4), 0)
        addView(vDots, FrameLayout.LayoutParams(-2, dp(22), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40) })
        paintVDots()
        pager.slideFx = true
        pager.post { pager.scrollTo(feedOffset * w, 0) }
        setDots(feedOffset)
    }

    /** Home widget: icon + clock, full date, weather slot and the active user's card. */
    private fun buildWidget(): View {
        // Big bold clock straight on the wallpaper (no card): hours in the text colour, minutes in the theme's accent,
        // with a soft shadow (dark on dark themes, a light glow on light ones) so it reads on any wallpaper.
        // Then the date and glass chips: weather, next alarm.
        val halo = if (p.dark) 0xAA000000.toInt() else 0x99FFFFFF.toInt()
        fun digits(f24: String, f12: String, color: Int) = TextClock(this).apply {
            format24Hour = f24; format12Hour = f12; setTextColor(color); typeface = appFont(700); includeFontPadding = false
            gravity = Gravity.CENTER; setShadowLayer(dp(12).toFloat(), 0f, dp(2).toFloat(), halo); maxLines = 1
        }
        val hours = digits("HH", "h", p.text)
        val colon = digits(":", ":", (p.text and 0xFFFFFF) or 0xB3000000.toInt())
        val minutes = digits("mm", "mm", p.accent)
        // A new minute slides in from above with a fade.
        minutes.addTextChangedListener(object : android.text.TextWatcher {
            private var last: String? = null
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString(); if (last != null && t != last && minutes.isAttachedToWindow) {
                    minutes.translationY = -minutes.height * 0.35f; minutes.alpha = 0f
                    minutes.animate().translationY(0f).alpha(1f).setDuration(Fx.dur(420)).setInterpolator(Fx.settle(this@HomeActivity)).start()
                }
                last = t
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // Two columns: the time on the left (hours over minutes), the day's information on the right as small plates.
        val clock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(hours); addView(minutes)
        }
        val alternateClock = ClockStyles.face(this, p)
        if (alternateClock != null) {
            clock.removeAllViews()
            clock.addView(alternateClock, LinearLayout.LayoutParams(-1, dp(190)))
        }
        colon.visibility = View.GONE
        val now = Date()
        val dayName = label(DateFormat.format("EEEE", now).toString().replaceFirstChar { it.uppercase() }, 20f, p.text, true).apply {
            gravity = Gravity.CENTER; maxLines = 1; setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), halo)
        }
        val date = label(DateFormat.format("d MMMM yyyy", now).toString(), 15f, p.text).apply {
            gravity = Gravity.CENTER; alpha = 0.85f; maxLines = 1; setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), halo)
        }
        // One plate per line of information, like bulletins.
        fun chip(t: String) = label(t, 14f, p.text, true).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            background = if (panelStyle == "solid") shape(p.surfaceHigh, 16) else glassShape(p, 16); setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun addChip(v: View) = chips.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // Weather: real data from Open-Meteo when the phone allows coarse location; tapping the chip asks for it.
        val weather = chip("🌡️  —").apply {
            // Tap: refresh now (and ask for the precise place once if the permission is still missing).
            setOnClickListener {
                if (!Weather.hasPermission(this@HomeActivity)) requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 11)
                Weather.invalidate(this@HomeActivity); text = "🌡️  …"; refreshWeather()
            }
        }
        weatherChip = weather
        addChip(weather)
        runCatching { getSystemService(android.app.AlarmManager::class.java).nextAlarmClock }.getOrNull()?.let { alarm ->
            addChip(chip("⏰  " + DateFormat.format(if (DateFormat.is24HourFormat(this)) "EEE HH:mm" else "EEE h:mm a", alarm.triggerTime)))
        }
        // The battery is its own widget (Add a widget > Battery), not part of the clock.
        refreshWeather()
        // Size set by the resize frame (like the grid widgets): width share of the screen and a size factor.
        val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
        var wf = prefs.getFloat("widget_wf", 1f); var hf = prefs.getFloat("widget_hf", 1f)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(dayName, LinearLayout.LayoutParams(-1, -2)); addView(date, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(clock, LinearLayout.LayoutParams(0, -2, 0.92f))   // the time gets almost half the width
            // A thin accent line between the time and the information.
            addView(View(this@HomeActivity).apply { background = shape((p.accent and 0xFFFFFF) or 0x99000000.toInt(), 2) },
                LinearLayout.LayoutParams(dp(3), dp(170)).apply { marginStart = dp(6); marginEnd = dp(14) })
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val full = resources.displayMetrics.widthPixels - dp(32)
        val wrap = FrameLayout(this)
        fun apply() {
            // Two lines of digits; the time column takes at most about 45% of the widget's width.
            val px = minOf(dp((96 * hf).toInt()).toFloat(), full * wf * 0.46f / 1.12f)
            for (t in listOf(hours, minutes)) t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
            alternateClock?.layoutParams = LinearLayout.LayoutParams(-1, (px * 2.05f).toInt())
            dayName.textSize = 20f * hf.coerceIn(0.8f, 1.3f)
            date.textSize = 15f * hf.coerceIn(0.8f, 1.3f)
            wrap.layoutParams = (wrap.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply { width = (full * wf).toInt() }
        }
        var w0 = 1f; var h0 = 1f
        val handles = resizeHandles(onStart = { w0 = wf; h0 = hf }, onEnd = { prefs.edit().putFloat("widget_wf", wf).putFloat("widget_hf", hf).apply() }) { ex, ey, dx, dy ->
            if (ex != 0) wf = (w0 + ex * 2f * dx / full).coerceIn(0.5f, 1f)
            if (ey != 0) hf = (h0 + ey * dy / dp(100)).coerceIn(0.6f, 1.8f)
            apply()
        }
        wrap.addView(box, FrameLayout.LayoutParams(-1, -2))
        wrap.addView(handles, FrameLayout.LayoutParams(-1, -1))
        wrap.post { apply() }
        makeMovable(wrap, "widget") { showHandles(handles) }
        return wrap
    }

    /** The resize frames of the home widgets (clock, app stack): hidden until the widget is held in edit mode. */
    private val resizeFrames = mutableListOf<View>()

    private fun showHandles(h: View) {
        if (!editing || layoutLocked) return
        val show = h.visibility != View.VISIBLE
        for (f in resizeFrames) f.visibility = View.GONE
        if (show) { h.alpha = 0f; h.visibility = View.VISIBLE; h.animate().alpha(1f).setDuration(Motion.NORMAL).start()
            h.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
    }

    /**
     * The same frame as the grid widgets: an accent outline with eight dots. Dragging an edge or corner reports which edge
     * (-1, 0, 1 on each axis) and how far the finger moved; a touch in the middle falls through to the widget below.
     */
    private fun resizeHandles(onStart: () -> Unit, onEnd: () -> Unit, onDrag: (Int, Int, Float, Float) -> Unit): View = object : View(this) {
        private val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var edgeX = 0; private var edgeY = 0
        private var sx = 0f; private var sy = 0f
        init { contentDescription = "Resize: drag an edge or corner" }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val inset = dp(5).toFloat(); val right = width - inset; val bottom = height - inset
            ink.color = p.accent; ink.style = android.graphics.Paint.Style.STROKE; ink.strokeWidth = dp(2).toFloat()
            canvas.drawRoundRect(inset, inset, right, bottom, dp(18).toFloat(), dp(18).toFloat(), ink)
            ink.style = android.graphics.Paint.Style.FILL
            for ((x, y) in listOf(inset to inset, right to inset, inset to bottom, right to bottom,
                width / 2f to inset, width / 2f to bottom, inset to height / 2f, right to height / 2f)) canvas.drawCircle(x, y, dp(5).toFloat(), ink)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeX = if (e.x < dp(28)) -1 else if (e.x > width - dp(28)) 1 else 0
                    edgeY = if (e.y < dp(28)) -1 else if (e.y > height - dp(28)) 1 else 0
                    if (edgeX == 0 && edgeY == 0) return false
                    sx = e.rawX; sy = e.rawY; onStart(); parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> onDrag(edgeX, edgeY, e.rawX - sx, e.rawY - sy)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onEnd()
            }
            return true
        }
    }.apply { visibility = View.GONE; resizeFrames += this }

    /** App stack with the same resize frame (hold its "Apps" header while editing): drag any edge to scale it, kept in prefs. */
    private fun resizableStack(stack: View): View {
        val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
        var sc = prefs.getFloat("stack_scale", 1f); var s0 = 1f
        val wrap = FrameLayout(this)
        fun apply() { stack.pivotX = stack.width / 2f; stack.pivotY = 0f; stack.scaleX = sc; stack.scaleY = sc }
        val handles = resizeHandles(onStart = { s0 = sc }, onEnd = { prefs.edit().putFloat("stack_scale", sc).apply() }) { ex, ey, dx, dy ->
            val h = wrap.height.coerceAtLeast(1).toFloat()
            val grow = (if (ex != 0) ex * 2f * dx / wrap.width.coerceAtLeast(1) else 0f) + (if (ey != 0) ey * dy / h else 0f)
            sc = (s0 + grow).coerceIn(0.7f, 1.15f); apply()
        }
        wrap.addView(stack, FrameLayout.LayoutParams(-1, -2))
        wrap.addView(handles, FrameLayout.LayoutParams(-1, -1))
        stack.post { apply() }
        // Hold the "Apps" header (the icons keep their own long-press for drag and drop).
        ((stack as? ViewGroup)?.getChildAt(0))?.setOnLongClickListener { if (editing) showHandles(handles) else enterEdit(); true }
        // While editing, the stack moves only by its "Apps" header (the icons keep swiping through the stack's pages).
        ((stack as? ViewGroup)?.getChildAt(0))?.let { header -> dragBy(header, wrap, "stack") }
        return wrap
    }

    /** Like [makeMovable], but only [handle] takes the drag and the whole [block] moves (the rest of the block keeps its touches). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun dragBy(handle: View, block: View, key: String) {
        val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
        block.translationX = prefs.getFloat("${key}_x", 0f); block.translationY = prefs.getFloat("${key}_y", 0f)
        var sx = 0f; var sy = 0f; var ox = 0f; var oy = 0f; var moved = false
        handle.setOnTouchListener { _, e ->
            if (!editing || layoutLocked) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = block.translationX; oy = block.translationY; moved = false; handle.parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && Math.hypot((e.rawX - sx).toDouble(), (e.rawY - sy).toDouble()) < dp(8)) return@setOnTouchListener true
                    moved = true
                    val container = block.parent as? ViewGroup ?: return@setOnTouchListener true
                    val minY = (container.paddingTop - block.top).toFloat()
                    val maxY = (container.height - container.paddingBottom - block.bottom).toFloat().coerceAtLeast(minY)
                    block.translationY = (oy + e.rawY - sy).coerceIn(minY, maxY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handle.parent?.requestDisallowInterceptTouchEvent(false)
                    if (moved) prefs.edit().putFloat("${key}_x", block.translationX).putFloat("${key}_y", block.translationY).apply()
                    else if (e.actionMasked == MotionEvent.ACTION_UP) handle.performLongClick()   // a tap on the header: the resize frame
                }
            }
            true
        }
    }

    /** In edit mode the block can be dragged anywhere; the offset is remembered. Double-tap while editing puts it back. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun makeMovable(v: View, key: String, onHold: (() -> Unit)? = null) {
        val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
        v.translationX = prefs.getFloat("${key}_x", 0f); v.translationY = prefs.getFloat("${key}_y", 0f)
        var sx = 0f; var sy = 0f; var ox = 0f; var oy = 0f
        val taps = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            // Holding the widget in place (no drag) opens its resize frame.
            override fun onLongPress(e: android.view.MotionEvent) {
                if (Math.abs(v.translationX - ox) < dp(6) && Math.abs(v.translationY - oy) < dp(6)) onHold?.invoke()
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                v.animate().translationX(0f).translationY(0f).setDuration(Motion.NORMAL).start()
                prefs.edit().remove("${key}_x").remove("${key}_y").apply(); return true
            }
        })
        v.setOnTouchListener { _, e ->
            if (!editing || layoutLocked) return@setOnTouchListener false
            taps.onTouchEvent(e)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = v.translationX; oy = v.translationY; v.parent?.requestDisallowInterceptTouchEvent(true) }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val container = v.parent as? ViewGroup
                    if (container != null) {
                        val minX = (container.paddingLeft - v.left).toFloat()
                        val maxX = (container.width - container.paddingRight - v.right).toFloat().coerceAtLeast(minX)
                        val minY = (container.paddingTop - v.top).toFloat()
                        val maxY = (container.height - container.paddingBottom - v.bottom).toFloat().coerceAtLeast(minY)
                        v.translationX = (ox + e.rawX - sx).coerceIn(minX, maxX)
                        v.translationY = (oy + e.rawY - sy).coerceIn(minY, maxY)
                    }
                }
                android.view.MotionEvent.ACTION_UP -> prefs.edit().putFloat("${key}_x", v.translationX).putFloat("${key}_y", v.translationY).apply()
            }
            true
        }
    }

    private var weatherChip: TextView? = null

    private fun refreshWeather() {
        val chip = weatherChip ?: return
        // Works with or without the location permission (without it the place comes from the IP address).
        Weather.load(this) { text -> runOnUiThread { chip.text = text ?: "🌡️  —" } }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        feedTranslator?.onPermissionsResult(requestCode, grantResults)
        if (requestCode == 11) refreshWeather()
    }

    /** Feed: the page left of Home. For now it lists recent apps; widgets will live here. */
    private fun buildFeedPage(): View {
        val recents = Recents.get(this).mapNotNull { pkg -> apps.firstOrNull { it.activityInfo.packageName == pkg } }.take(8)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glassShape(p, 28)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            if (recents.isEmpty()) {
                addView(label("Nothing yet. Apps you open appear here.", 13f, p.sub).apply { gravity = Gravity.START })
            } else {
                addView(GridLayout(this@HomeActivity).apply {
                    columnCount = 4
                    for (ri in recents) addView(LinearLayout(this@HomeActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                        addView(ImageView(this@HomeActivity).apply {
                            setImageDrawable(AosIcons.themed(this@HomeActivity, ri.loadIcon(packageManager), p, 48, ri.activityInfo.packageName))
                        }, LinearLayout.LayoutParams(dp(48), dp(48)))
                        addView(label(ri.loadLabel(packageManager).toString(), 11f, p.text).apply { maxLines = 1; setPadding(0, dp(4), 0, 0) })
                        setOnClickListener { launch(ri, from = getChildAt(0)) }
                        pressFx(p)
                    }, GridLayout.LayoutParams().apply { width = (resources.displayMetrics.widthPixels - dp(32) - dp(36)) / 4; height = dp(84) })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
        // Pill tabs at the top of the left page: News first, then Feed (recent apps).
        val content = FrameLayout(this)
        // A slim sidebar on the left: round icon buttons, one under the other (News, Recent apps); the content scrolls on the right.
        val tabRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // Header like the Folders page: the title with an action on the right (Recent: clear the list), the pills under it.
        val headerAction = ImageView(this).apply {
            setColorFilter(p.text); setPadding(dp(10), dp(10), dp(10), dp(10)); background = glassShape(p, 22, true)
            pressFx(p)
        }
        lateinit var showFeedTabRef: (Int) -> Unit
        fun showFeedTab(i: Int) {
            feedTab = i
            // The one action of the open tab, as an icon: News = choose sources, Recent = clear the list.
            when (i) {
                0 -> headerAction.apply {
                    visibility = View.VISIBLE; setImageResource(R.drawable.ic_list); contentDescription = "News sources"
                    setOnClickListener { newsSourcesDialog(p) { showFeedTabRef(0) } }
                }
                1 -> headerAction.apply {
                    visibility = if (recents.isNotEmpty()) View.VISIBLE else View.INVISIBLE
                    setImageResource(R.drawable.ic_close); contentDescription = "Clear recent apps"
                    setOnClickListener { Recents.clear(this@HomeActivity); recreate() }
                }
                else -> headerAction.visibility = View.INVISIBLE
            }
            if (i != 2) feedTranslator?.pause()
            if (i != 4) feedAi?.pause()
            tabRow.removeAllViews()
            // Round icon buttons, no text (the name is kept for TalkBack).
            // Display order: News, Device, Recent apps, Translate (the indices stay: 0 News, 1 Recent, 2 Translate, 3 Device).
            for ((k, pair) in listOf(0 to (R.drawable.ic_language to "News"), 3 to (R.drawable.ic_info to "About this phone"),
                    1 to (R.drawable.ic_history to "Recent apps"), 2 to (R.drawable.ic_translate to "Translate"), 4 to (R.drawable.ic_aos_mic to "AOS AI"))) {
                val on = k == i
                tabRow.addView(ImageView(this).apply {
                    setImageResource(pair.first); setColorFilter(if (on) p.onAccent else p.text); contentDescription = pair.second
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                    background = if (on) circle(p.accent) else glassShape(p, 22, true)
                    setOnClickListener { showFeedTab(k) }; pressFx(p)
                }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
            }
            content.removeAllViews()
            (card.parent as? ViewGroup)?.removeView(card)
            content.setPadding(0, 0, 0, if (i == 2 || i == 4) dp(100) else 0)
            val child = if (i == 2) buildTranslateCard() else if (i == 4) (feedAi ?: AiChatPanel(this, p).also { feedAi = it }).view() else ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(150))
                    addView(when (i) { 0 -> buildNews(); 3 -> buildDeviceInfo(); else -> card }, LinearLayout.LayoutParams(-1, -2))
                })
            }
            content.addView(child, FrameLayout.LayoutParams(-1, -1))
        }
        showFeedTabRef = { showFeedTab(it) }
        showFeedTab(feedTab)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // One slim toolbar: tab icons on the left, the tab's action icon on the right. No title.
            addView(LinearLayout(this@HomeActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(tabRow, LinearLayout.LayoutParams(0, -2, 1f))
                addView(headerAction, LinearLayout.LayoutParams(dp(44), dp(44)))
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(72), dp(16), dp(10)) })
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { marginStart = dp(16); marginEnd = dp(16) })
        }
    }

    /** "About this phone" in the left panel: model, Android, processor, memory, storage, battery, screen, uptime. */
    private fun buildDeviceInfo(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun gb(b: Long) = String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0)
        fun card(title: String, rows: List<Pair<String, String>>, bar: Float? = null) = col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glassShape(p, 24); setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(label(title, 15f, p.accent, true).apply { gravity = Gravity.START; setPadding(0, 0, 0, dp(6)) })
            if (bar != null) addView(object : View(this@HomeActivity) {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: android.graphics.Canvas) {
                    val r = height / 2f
                    paint.color = (p.text and 0x00FFFFFF) or 0x26000000; c.drawRoundRect(android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
                    paint.color = if (bar > 0.9f) 0xFFE5484D.toInt() else p.accent
                    c.drawRoundRect(android.graphics.RectF(0f, 0f, width * bar.coerceIn(0f, 1f), height.toFloat()), r, r, paint)
                }
            }, LinearLayout.LayoutParams(-1, dp(8)).apply { bottomMargin = dp(8) })
            for ((k, v) in rows) addView(LinearLayout(this@HomeActivity).apply {
                setPadding(0, dp(3), 0, dp(3))
                addView(label(k, 13f, p.sub).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(v, 13f, p.text, true).apply { gravity = Gravity.END })
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val b = android.os.Build.VERSION.SDK_INT
        card("Phone", listOf(
            "Model" to "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}",
            "Android" to "${android.os.Build.VERSION.RELEASE} (API $b)",
            "Security patch" to android.os.Build.VERSION.SECURITY_PATCH,
            "Build" to android.os.Build.DISPLAY.take(28)))
        val cores = Runtime.getRuntime().availableProcessors()
        val maxMhz = (0 until cores).mapNotNull { runCatching { java.io.File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 }.getOrNull() }.maxOrNull()
        card("Processor", listOfNotNull(
            "Chip" to (if (b >= 31) "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}" else android.os.Build.HARDWARE),
            "Cores" to "$cores",
            maxMhz?.let { "Max speed" to String.format(java.util.Locale.US, "%.2f GHz", it / 1000.0) },
            "ABI" to android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()))
        val mi = android.app.ActivityManager.MemoryInfo().also { getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        card("Memory (RAM)", listOf("Used" to gb(mi.totalMem - mi.availMem), "Free" to gb(mi.availMem), "Total" to gb(mi.totalMem)),
            (mi.totalMem - mi.availMem).toFloat() / mi.totalMem)
        val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        card("Storage", listOf("Used" to gb(st.totalBytes - st.availableBytes), "Free" to gb(st.availableBytes), "Total" to gb(st.totalBytes)),
            (st.totalBytes - st.availableBytes).toFloat() / st.totalBytes)
        val bi = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = getSystemService(android.os.BatteryManager::class.java)
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bi?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = when (bi?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 0)) { 2 -> "Good"; 3 -> "Overheat"; 4 -> "Dead"; 5 -> "Over voltage"; 7 -> "Cold"; else -> "—" }
        val cycles = if (b >= 34) bi?.getIntExtra(android.os.BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 } else null
        card("Battery", listOfNotNull(
            "Charge" to "$pct%",
            "State" to when (status) { android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"; android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"; else -> "On battery" },
            "Temperature" to String.format(java.util.Locale.US, "%.1f °C", (bi?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f),
            "Voltage" to String.format(java.util.Locale.US, "%.2f V", (bi?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f),
            "Health" to health,
            cycles?.let { "Charge cycles" to "$it" }), pct / 100f)
        val dm = resources.displayMetrics
        val hz = runCatching { display?.refreshRate }.getOrNull()
        val real = runCatching { getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds }.getOrNull()
        card("Screen", listOfNotNull(
            "Resolution" to "${real?.width() ?: dm.widthPixels} × ${real?.height() ?: dm.heightPixels}",
            "Density" to "${dm.densityDpi} dpi",
            hz?.let { "Refresh rate" to "${Math.round(it)} Hz" }))
        val up = android.os.SystemClock.elapsedRealtime() / 60000
        val tm = getSystemService(android.telephony.TelephonyManager::class.java)
        card("System", listOf(
            "Up since restart" to (if (up >= 1440) "${up / 1440} d ${(up % 1440) / 60} h" else "${up / 60} h ${up % 60} min"),
            "Network" to (runCatching { tm.networkOperatorName }.getOrNull().orEmpty().ifBlank { "—" }),
            "Apps" to "${apps.size}"))
        return col
    }

    private var feedTranslator: TranslatorPanel? = null
    private var feedAi: AiChatPanel? = null
    private var feedTranslateView: View? = null

    /** Open the working translator directly in the left panel. */
    private fun buildTranslateCard(): View {
        if (feedTranslateView == null) {
            val translator = TranslatorPanel(this)
            feedTranslator = translator
            feedTranslateView = translator.createView(true)
        }
        return feedTranslateView!!.also { (it.parent as? ViewGroup)?.removeView(it) }
    }

    // ---- Widgets: slots on the extra desktops (2, 3...). Empty slots show as dashed frames while editing. ----

    private val baseWidgets = listOf("weather" to "Weather", "battery" to "Battery", "calendar" to "Calendar", "notes" to "Notes", "clock" to "Clock", "roaming" to "Roaming data")
    private val slotPaints = mutableListOf<() -> Unit>()
    private val slotsPerPage = 6

    private fun paintSlots() { for (r in slotPaints) r() }

    // Widgets live on a 4 x 6 grid per extra desktop. Every widget has a size in cells (1x1 ... 4x3); empty cells show as dashed
    // frames while editing. A page is stored as "type,col,row,w,h;..." under wlay_<page>.

    private val gridCols = 4
    private val gridRows = 6

    private class Placed(var type: String, var c: Int, var r: Int, var w: Int, var h: Int)

    private fun loadLayout(page: Int): MutableList<Placed> {
        val sp = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val raw = sp.getString("wlay_$page", null)
        if (raw == null) {
            // First time on the grid: carry over the old two-column slots as 2x2 widgets.
            val out = mutableListOf<Placed>()
            for (i in 0 until 6) sp.getString("wslot_${page}_$i", null)?.let { out += Placed(it, (i % 2) * 2, (i / 2) * 2, 2, 2) }
            return out
        }
        return raw.split(";").filter { it.isNotBlank() }.mapNotNull { s ->
            val q = s.split(","); if (q.size < 5) null else Placed(q[0], q[1].toInt(), q[2].toInt(), q[3].toInt(), q[4].toInt())
        }.toMutableList()
    }

    private fun saveLayout(page: Int, list: List<Placed>) {
        getSharedPreferences("aos_setup", MODE_PRIVATE).edit()
            .putString("wlay_$page", list.joinToString(";") { "${it.type},${it.c},${it.r},${it.w},${it.h}" }).apply()
    }

    private fun fits(list: List<Placed>, c: Int, r: Int, w: Int, h: Int, ignore: Placed? = null): Boolean =
        c >= 0 && r >= 0 && c + w <= gridCols && r + h <= gridRows &&
            list.none { it !== ignore && c < it.c + it.w && it.c < c + w && r < it.r + it.h && it.r < r + h }

    /** Puts [type] (2 x 2) into the first free place of desktop 2 (adds the desktop if needed); false when it is full. */
    private fun addWidgetToDesk(type: String): Boolean {
        val list = loadLayout(2)
        for (r in 0..gridRows - 2) for (c in 0..gridCols - 2) if (fits(list, c, r, 2, 2)) {
            list += Placed(type, c, r, 2, 2); saveLayout(2, list)
            getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putInt("pages", maxOf(pagesCount, 2)).putBoolean("reopen_edit", true).apply()
            recreate(); return true
        }
        android.widget.Toast.makeText(this, "Desktop 2 is full", android.widget.Toast.LENGTH_SHORT).show(); return false
    }

    private fun buildSlotGrid(page: Int): View {
        val gap = dp(8)
        val cell = (resources.displayMetrics.widthPixels - dp(32) - gap * (gridCols - 1)) / gridCols
        val field = FrameLayout(this)
        val list = loadLayout(page)
        var selectedWidget: Placed? = null
        var painting = false
        fun px(n: Int) = n * (cell + gap)
        fun lp(c: Int, r: Int, w: Int, h: Int) = FrameLayout.LayoutParams(w * cell + (w - 1) * gap, h * cell + (h - 1) * gap).apply { leftMargin = px(c); topMargin = px(r) }

        fun paint() {
            if (painting) return
            painting = true
            try {
            if (!editing) selectedWidget = null
            field.removeAllViews()
            // Free cells: dashed frames while editing; a tap on one chooses a widget and a size for it.
            if (editing && !layoutLocked) for (r in 0 until gridRows) for (c in 0 until gridCols) if (fits(list, c, r, 1, 1)) {
                field.addView(FrameLayout(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat(); setColor(0x00000000); setStroke(maxOf(2, dp(1)), (p.accent and 0xFFFFFF) or 0x99000000.toInt(), dp(6).toFloat(), dp(5).toFloat())
                    }
                    addView(label("+", 20f, p.accent, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                    setOnClickListener {
                        showWidgetPicker { type ->
                            val size = if (fits(list, c, r, 2, 2)) 2 else 1
                            val placed = Placed(type, c, r, size, size)
                            list += placed; selectedWidget = placed; saveLayout(page, list); paint()
                        }
                    }
                }, lp(c, r, 1, 1))
            }
            for (w in list) {
                val frame = HoldWidgetFrame(this) {
                    if (!layoutLocked) {
                        selectedWidget = w
                        if (!editing) enterEdit() else paint()
                    }
                }.apply { background = glassShape(p, 22); holdEnabled = !layoutLocked && !editing }
                frame.addView(widgetView(w.type, w.w, w.h), FrameLayout.LayoutParams(-1, -1))
                if (editing && !layoutLocked) {
                    // While editing the widget itself ignores touches (so a Notes field cannot swallow them); the corner buttons work.
                    fun remove() {
                        list.remove(w); saveLayout(page, list)
                        sysWidgetId(w.type)?.let { runCatching { widgetHost.deleteAppWidgetId(it) } }
                        android.widget.Toast.makeText(this@HomeActivity, "Widget removed", android.widget.Toast.LENGTH_SHORT).show()
                        paint()
                    }
                    // Edit layer over the widget: tap = select (handles appear), drag = move it to free cells,
                    // drag an edge or a corner of the selected widget = resize. The ✕ is added after it, so it stays on top.
                    val isSel = selectedWidget === w
                    frame.addView(object : View(this) {
                        private val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                        private var edgeX = 0
                        private var edgeY = 0
                        private var startX = 0f
                        private var startY = 0f
                        private var moved = false
                        private var newC = w.c
                        private var newR = w.r
                        private var newW = w.w
                        private var newH = w.h
                        init { contentDescription = "Widget: tap to select, drag to move, drag an edge to resize"; isClickable = true }
                        override fun onDraw(canvas: android.graphics.Canvas) {
                            if (!isSel) return
                            val inset = dp(5).toFloat()
                            val right = width - inset; val bottom = height - inset
                            ink.color = p.accent; ink.style = android.graphics.Paint.Style.STROKE; ink.strokeWidth = dp(2).toFloat()
                            canvas.drawRoundRect(inset, inset, right, bottom, dp(18).toFloat(), dp(18).toFloat(), ink)
                            ink.style = android.graphics.Paint.Style.FILL
                            for ((x, y) in listOf(inset to inset, right to inset, inset to bottom, right to bottom,
                                width / 2f to inset, width / 2f to bottom, inset to height / 2f, right to height / 2f)) {
                                canvas.drawCircle(x, y, dp(5).toFloat(), ink)
                            }
                        }
                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    startX = event.rawX; startY = event.rawY; moved = false
                                    edgeX = if (!isSel) 0 else if (event.x < dp(26)) -1 else if (event.x > width - dp(26)) 1 else 0
                                    edgeY = if (!isSel) 0 else if (event.y < dp(26)) -1 else if (event.y > height - dp(26)) 1 else 0
                                    parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (!moved && Math.hypot((event.rawX - startX).toDouble(), (event.rawY - startY).toDouble()) < slop) return true
                                    moved = true
                                    val dx = Math.round((event.rawX - startX) / (cell + gap))
                                    val dy = Math.round((event.rawY - startY) / (cell + gap))
                                    if (edgeX == 0 && edgeY == 0) {
                                        // Move: the widget follows the finger cell by cell, only onto free cells.
                                        val c = (w.c + dx).coerceIn(0, gridCols - w.w)
                                        val r = (w.r + dy).coerceIn(0, gridRows - w.h)
                                        frame.translationZ = dp(8).toFloat(); frame.alpha = 0.85f
                                        if (fits(list, c, r, w.w, w.h, w)) {
                                            newC = c; newR = r; newW = w.w; newH = w.h
                                            frame.layoutParams = lp(c, r, w.w, w.h)
                                        }
                                    } else {
                                        val c = if (edgeX < 0) (w.c + dx).coerceIn(0, w.c + w.w - 1) else w.c
                                        val r = if (edgeY < 0) (w.r + dy).coerceIn(0, w.r + w.h - 1) else w.r
                                        val widthCells = if (edgeX < 0) w.c + w.w - c else if (edgeX > 0) (w.w + dx).coerceIn(1, gridCols - c) else w.w
                                        val heightCells = if (edgeY < 0) w.r + w.h - r else if (edgeY > 0) (w.h + dy).coerceIn(1, gridRows - r) else w.h
                                        if (fits(list, c, r, widthCells, heightCells, w)) {
                                            newC = c; newR = r; newW = widthCells; newH = heightCells
                                            frame.layoutParams = lp(c, r, widthCells, heightCells)
                                        }
                                    }
                                }
                                MotionEvent.ACTION_UP -> {
                                    parent?.requestDisallowInterceptTouchEvent(false)
                                    if (!moved) selectedWidget = if (isSel && edgeX == 0 && edgeY == 0) null else w
                                    else { w.c = newC; w.r = newR; w.w = newW; w.h = newH; saveLayout(page, list); selectedWidget = w }
                                    field.post { paint() }
                                }
                                MotionEvent.ACTION_CANCEL -> {
                                    parent?.requestDisallowInterceptTouchEvent(false)
                                    // Removing the old views also cancels their touch. Never rebuild recursively from that cancellation.
                                    if (!painting) field.post { paint() }
                                }
                            }
                            return true
                        }
                    }, FrameLayout.LayoutParams(-1, -1))
                    frame.addView(label("✕", 18f, 0xFFFFFFFF.toInt(), true).apply {
                        gravity = Gravity.CENTER; background = circle(0xF0E5484D.toInt())
                        contentDescription = "Remove widget"
                        setOnClickListener { remove() }
                    }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
                }
                field.addView(frame, lp(w.c, w.r, w.w, w.h))
            }
            } finally { painting = false }
        }
        slotPaints += ::paint
        paint()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(72), dp(16), dp(150))
            // Every extra desktop has a way back to the main one.
            addView(label("⌂   Main desktop", 14f, p.text, true).apply {
                gravity = Gravity.CENTER; background = glassShape(p, 18, true); setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { switchDesk(0) }; pressFx(p, round = false)
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { bottomMargin = dp(12) })
            addView(field, LinearLayout.LayoutParams(-1, gridRows * cell + (gridRows - 1) * gap))
            setOnLongClickListener { enterEdit(); true }
        }
        return android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }
    }

    /** The base widgets. The bigger the widget, the more it shows: w and h are its size in cells. */
    private fun widgetView(type: String, wc: Int = 1, hc: Int = 1): View {
        sysWidgetId(type)?.let { return systemWidgetView(it, wc, hc) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val wide = wc >= 2
        val big = wc >= 2 && hc >= 2
        when (type) {
            "weather" -> {
                // Big symbol and temperature, the place under them; wide and big ones add the details as small pills.
                val sym = label("", if (big) 44f else if (wide) 34f else 30f, p.text)
                val temp = label("…", if (big) 40f else if (wide) 32f else 24f, p.text, true)
                val place = label("Weather", 12f, p.sub).apply { maxLines = 1 }
                if (wide) box.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; addView(sym); addView(temp, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                }) else { box.addView(sym); box.addView(temp) }
                box.addView(place, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
                val pills = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                if (big) box.addView(pills, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                Weather.load(this) { text -> runOnUiThread {
                    val t = text ?: return@runOnUiThread run { temp.text = "—" }
                    sym.text = t.substringBefore(" ")
                    temp.text = t.substringAfter(" ").substringBefore("  ")
                    place.text = t.substringAfter("  ", "").ifBlank { "Weather" }
                    if (big) {
                        pills.removeAllViews()
                        for (row in Weather.detail(this).orEmpty().split("  ·  ").filter { it.isNotBlank() }.chunked(2)) pills.addView(LinearLayout(this).apply {
                            gravity = Gravity.CENTER
                            for (d in row) addView(label(d.trim(), if (wc >= 3) 11f else 10f, p.text).apply {
                                setPadding(dp(6), dp(3), dp(6), dp(3)); background = shape(p.glassCircle, 12); maxLines = 1
                            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), dp(3), dp(3), 0) })
                        })
                    }
                } }
            }
            "battery" -> {
                val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val pct = getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val status = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                val temp = (i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
                val health = when (i?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 0)) { 2 -> "Good"; 3 -> "Overheat"; 4 -> "Dead"; 5 -> "Over voltage"; 7 -> "Cold"; else -> "—" }
                val fill = if (charging) 0xFF34C759.toInt() else if (pct <= 20) 0xFFE5484D.toInt() else p.accent
                // The ring takes the whole face of the widget: it fills up to the charge when it appears, and while charging a
                // bright light runs round it. Percentage and state sit inside.
                val face = object : FrameLayout(this) {
                    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
                    private var shown = 0f
                    private var spin = 0f
                    private val anims = ArrayList<android.animation.ValueAnimator>()
                    init { setWillNotDraw(false) }
                    override fun onAttachedToWindow() {
                        super.onAttachedToWindow()
                        anims += android.animation.ValueAnimator.ofFloat(0f, pct / 100f).apply {
                            duration = 1100; interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                            addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
                        }
                        if (charging) anims += android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 2200; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
                            addUpdateListener { spin = it.animatedValue as Float; invalidate() }; start()
                        }
                    }
                    override fun onDetachedFromWindow() { anims.forEach { it.cancel() }; anims.clear(); super.onDetachedFromWindow() }
                    override fun onDraw(c: android.graphics.Canvas) {
                        val size = minOf(width, height).toFloat()
                        val sw = size * 0.08f; paint.strokeWidth = sw
                        val l = (width - size) / 2f + sw / 2f; val t = (height - size) / 2f + sw / 2f
                        val r = android.graphics.RectF(l, t, l + size - sw, t + size - sw)
                        paint.shader = null; paint.color = (p.text and 0x00FFFFFF) or 0x26000000; c.drawArc(r, 0f, 360f, false, paint)   // faint track in the text colour
                        paint.color = fill; c.drawArc(r, -90f, 360f * shown, false, paint)
                        if (charging && shown > 0.02f) {
                            // A short bright comet that runs along the filled part.
                            val head = -90f + 360f * shown * spin
                            paint.color = android.graphics.Color.WHITE
                            for (k in 0 until 6) { paint.alpha = 200 - k * 32; c.drawArc(r, head - k * 4f, 4f, false, paint) }
                            paint.alpha = 255
                        }
                    }
                }
                face.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    if (charging) addView(label("⚡", if (wc >= 3) 16f else 13f, 0xFF34C759.toInt()))
                    addView(label("$pct%", if (wc >= 3) 34f else if (wc >= 2) 26f else 18f, p.text, true).apply { includeFontPadding = false })
                    if (wc >= 2) addView(label(if (charging) "Charging" else "Battery", 11f, p.sub).apply { maxLines = 1 })
                    if (wc >= 2 && (hc >= 2 || wc >= 3)) addView(label("%.1f °C".format(temp) + if (wc >= 3) "  ·  $health" else "", 10f, p.sub).apply { maxLines = 1 })
                }, FrameLayout.LayoutParams(-1, -1))
                box.setPadding(dp(8), dp(8), dp(8), dp(8))
                box.addView(face, LinearLayout.LayoutParams(-1, -1))
            }
            "calendar" -> {
                val now = java.util.Calendar.getInstance()
                if (big) {
                    // A month: weekday header, then the days with today in the accent colour. Columns share the widget width.
                    box.gravity = Gravity.TOP
                    box.addView(label(DateFormat.format("LLLL yyyy", Date()).toString().replaceFirstChar { it.uppercase() }, 14f, p.text, true).apply { setPadding(0, 0, 0, dp(6)) })
                    val grid = GridLayout(this).apply { columnCount = 7 }
                    fun cell() = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = dp(20) }
                    val first = (now.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }
                    val offset = (first.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7   // Monday first
                    for ((k, d) in listOf("M", "T", "W", "T", "F", "S", "S").withIndex()) grid.addView(label(d, 10f, if (k >= 5) p.accent else p.sub, true).apply { gravity = Gravity.CENTER }, cell())
                    for (k in 0 until offset) grid.addView(View(this), cell())
                    val today = now.get(java.util.Calendar.DAY_OF_MONTH)
                    for (d in 1..now.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                        grid.addView(FrameLayout(this).apply {
                            addView(label("$d", 11f, if (d == today) p.onAccent else p.text, d == today).apply {
                                gravity = Gravity.CENTER; if (d == today) background = circle(p.accent)
                            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                        }, cell())
                    box.addView(grid, LinearLayout.LayoutParams(-1, -2))
                } else {
                    box.addView(label(DateFormat.format("EEEE", Date()).toString().replaceFirstChar { it.uppercase() }, 12f, p.accent, true))
                    box.addView(label(DateFormat.format("d", Date()).toString(), if (wide) 44f else 40f, p.text, true).apply { includeFontPadding = false })
                    box.addView(label(DateFormat.format(if (wide) "LLLL yyyy" else "LLLL", Date()).toString().replaceFirstChar { it.uppercase() }, 12f, p.sub))
                }
            }
            "roaming" -> {
                // Mobile data left in a roaming package: the size is typed once, the usage since then comes from the system.
                val sp = getSharedPreferences("aos_setup", MODE_PRIVATE)
                val limit = sp.getFloat("roam_gb", 0f)
                val tm = getSystemService(android.telephony.TelephonyManager::class.java)
                val roaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false)
                val op = runCatching { tm.networkOperatorName }.getOrNull().orEmpty()
                box.addView(label(if (roaming) "✈  Roaming" else "Mobile data", 11f, if (roaming) p.accent else p.sub, true).apply { maxLines = 1 })
                when {
                    limit <= 0f -> {
                        box.addView(label("Set package", if (wc >= 2) 18f else 14f, p.text, true).apply { setPadding(0, dp(6), 0, 0) })
                        box.addView(label("Tap to enter its GB", 11f, p.sub).apply { maxLines = 2 })
                    }
                    !usageAccess() -> {
                        box.addView(label("Allow usage access", 13f, p.text, true).apply { setPadding(0, dp(6), 0, 0); maxLines = 2 })
                        box.addView(label("Needed to count the data", 11f, p.sub).apply { maxLines = 2 })
                    }
                    else -> {
                        val used = mobileBytes(sp.getLong("roam_since", 0L)) / 1073741824.0
                        val left = (limit - used).coerceAtLeast(0.0)
                        val frac = (left / limit).toFloat().coerceIn(0f, 1f)
                        val col = if (frac < 0.1f) 0xFFE5484D.toInt() else p.accent
                        box.addView(label(String.format(java.util.Locale.US, if (left < 10) "%.1f GB" else "%.0f GB", left), if (wc >= 3) 34f else if (wc >= 2) 26f else 18f, p.text, true).apply { includeFontPadding = false; maxLines = 1; setPadding(0, dp(4), 0, 0) })
                        box.addView(label(String.format(java.util.Locale.US, "left of %.0f GB", limit), 11f, p.sub).apply { maxLines = 1 })
                        box.addView(object : View(this) {
                            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                            override fun onDraw(c: android.graphics.Canvas) {
                                val r = height / 2f
                                paint.color = (p.text and 0x00FFFFFF) or 0x26000000; c.drawRoundRect(android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
                                paint.color = col; c.drawRoundRect(android.graphics.RectF(0f, 0f, width * frac, height.toFloat()), r, r, paint)
                            }
                        }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8); bottomMargin = dp(4) })
                        if (wc >= 2) box.addView(label(String.format(java.util.Locale.US, "used %.2f GB", used) + if (op.isNotBlank()) "  ·  $op" else "", 10f, p.sub).apply { maxLines = 1 })
                    }
                }
                box.setOnClickListener { if (limit > 0f && !usageAccess()) openUsageAccess() else askRoamingPackage() }
            }
            "notes" -> {
                val prefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
                box.gravity = Gravity.TOP
                box.addView(label("✎  Notes", 12f, p.accent, true).apply { gravity = Gravity.START })
                box.addView(android.widget.EditText(this).apply {
                    hint = "Write something…"; setHintTextColor(p.sub); setTextColor(p.text); textSize = 14f; background = null
                    gravity = Gravity.TOP or Gravity.START; setPadding(0, dp(4), 0, 0)
                    setText(prefs.getString("wnote", ""))
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) { prefs.edit().putString("wnote", s?.toString().orEmpty()).apply() }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                }, LinearLayout.LayoutParams(-1, -1))
            }
            "clock" -> {
                // Hours in the text colour, minutes in the accent, like the big clock of the home widget.
                // Sized by the width in cells, always on one line (a 2-cell clock used to wrap its minutes).
                val size = if (wc >= 4) 56f else if (wc >= 3) 48f else if (wc >= 2) 36f else 26f
                box.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    addView(TextClock(this@HomeActivity).apply { format12Hour = "h"; format24Hour = "HH"; textSize = size; setTextColor(p.text); typeface = appFont(700); includeFontPadding = false; maxLines = 1 })
                    addView(label(":", size, p.sub).apply { includeFontPadding = false })
                    addView(TextClock(this@HomeActivity).apply { format12Hour = "mm"; format24Hour = "mm"; textSize = size; setTextColor(p.accent); typeface = appFont(700); includeFontPadding = false; maxLines = 1 })
                })
                if (wide) box.addView(label(DateFormat.format("EEEE, d MMMM", Date()).toString().replaceFirstChar { it.uppercase() }, 13f, p.sub))
                if (big) runCatching { getSystemService(android.app.AlarmManager::class.java).nextAlarmClock }.getOrNull()?.let { a ->
                    box.addView(label("⏰ " + DateFormat.format(if (DateFormat.is24HourFormat(this)) "EEE HH:mm" else "EEE h:mm a", a.triggerTime), 13f, p.accent, true).apply { setPadding(0, dp(6), 0, 0) })
                }
            }
        }
        return box
    }

    private var feedTab = 0   // 0 News, 1 Feed, 2 Folders

    // ---- Roaming widget ----

    private fun usageAccess(): Boolean = runCatching {
        getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun openUsageAccess() {
        try { startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) {}
    }

    /** Mobile data (all SIMs) sent and received since [since]. */
    private fun mobileBytes(since: Long): Long = runCatching {
        val b = getSystemService(android.app.usage.NetworkStatsManager::class.java)
            .querySummaryForDevice(android.net.ConnectivityManager.TYPE_MOBILE, null, since, System.currentTimeMillis())
        b.rxBytes + b.txBytes
    }.getOrDefault(0L)

    /** Size of the roaming package in GB; counting starts now (also used to start a new package). */
    private fun askRoamingPackage() {
        val sp = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val input = android.widget.EditText(this).apply {
            hint = "GB, for example 5"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            sp.getFloat("roam_gb", 0f).takeIf { it > 0f }?.let { setText(if (it % 1f == 0f) it.toInt().toString() else it.toString()) }
        }
        android.app.AlertDialog.Builder(this).setTitle("Roaming package").setMessage("How many GB does the package have? Counting starts now.")
            .setView(android.widget.FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(input) })
            .setPositiveButton("Start") { _, _ ->
                val gb = input.text.toString().replace(',', '.').toFloatOrNull() ?: return@setPositiveButton
                sp.edit().putFloat("roam_gb", gb).putLong("roam_since", System.currentTimeMillis()).apply()
                if (!usageAccess()) openUsageAccess()
                paintSlots()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- Locked folders: names kept in the setup prefs; opening or unlocking one asks for the phone's screen lock. ----
    private fun lockedFolders(): Set<String> = getSharedPreferences("aos_setup", MODE_PRIVATE).getStringSet("locked_folders", emptySet()) ?: emptySet()

    private fun setFolderLocked(name: String, on: Boolean) {
        val s = lockedFolders().toMutableSet(); if (on) s += name else s -= name
        getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putStringSet("locked_folders", s).apply()
    }

    private var afterCredential: (() -> Unit)? = null

    /** Runs [then] once the user has confirmed the screen lock (PIN, pattern or fingerprint); without a screen lock it just runs. */
    private fun askCredential(folder: String, then: () -> Unit) {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (!km.isDeviceSecure) {
            android.widget.Toast.makeText(this, "Set a screen lock in the phone settings to really protect folders", android.widget.Toast.LENGTH_LONG).show()
            then(); return
        }
        afterCredential = then
        @Suppress("DEPRECATION")
        // The folder is protected by the phone's own screen lock (PIN, pattern, fingerprint): no separate password to remember.
        startActivityForResult(km.createConfirmDeviceCredentialIntent("Folder “$folder”", "Confirm with your phone's screen lock to open or unlock this folder"), 77)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77) { val run = afterCredential; afterCredential = null; if (resultCode == RESULT_OK) run?.invoke() }
        if (requestCode == 91) { if (resultCode == RESULT_OK && pendingWidgetId != -1) configureOrPlace(pendingWidgetId) else dropPendingWidget() }
        if (requestCode == 92) { if (resultCode == RESULT_OK && pendingWidgetId != -1) placePendingWidget(pendingWidgetId) else dropPendingWidget() }
    }

    /**
     * Folders: its own desktop page on the right of the home pages (on by default). Folders are shown as square tiles with a
     * 2x2 preview, like widgets; tapping one opens its apps inside the same page. Collects the drawer groups and the home folders.
     */
    private var refreshFolders: (() -> Unit)? = null

    private fun buildFoldersPage(): View {
        val folderPrefs = getSharedPreferences("aos_setup", MODE_PRIVATE)
        val hiddenFolders = folderPrefs.getStringSet("hidden_auto_folders", emptySet()).orEmpty()
        val folders = LinkedHashMap<String, List<ResolveInfo>>()
        val autoNames = HashSet<String>()
        var fFilter = 0   // 0 All, 1 Mine, 2 Auto, 3 Locked
        for (e in stackKeys().filter { isFolder(it) }) folders[folderName(e)] = folderKeys(e).mapNotNull { appFor(it) }
        for ((name, pkgs) in Groups.all(this)) folders.getOrPut(name) { pkgs.mapNotNull { pkg -> apps.firstOrNull { it.activityInfo.packageName == pkg } } }
        // Fill the page: besides your own folders, apps are grouped automatically by category (Communication, Media, Tools...).
        for (cat in AppCategories.order) {
            val members = apps.filter { AppCategories.of(it) == cat && it.activityInfo.packageName != packageName }
            if (members.size >= 2 && cat !in folders && cat !in hiddenFolders) { folders[cat] = members; autoNames += cat }
        }
        val page = FrameLayout(this)
        val gap = dp(12)
        val tileW = (resources.displayMetrics.widthPixels - dp(32) - gap) / 2

        fun icon(ri: ResolveInfo, size: Int) = ImageView(this).apply { setImageDrawable(AosIcons.themed(this@HomeActivity, ri.loadIcon(packageManager), p, size, ri.activityInfo.packageName)) }

        lateinit var showAll: () -> Unit
        fun rebuildFolders() { recreate() }
        fun saveFolder(oldName: String?, newName: String, members: List<ResolveInfo>) {
            val entries = stackKeys().toMutableList()
            val index = entries.indexOfFirst { isFolder(it) && folderName(it) == oldName }
            if (index >= 0) {
                entries[index] = makeFolder(newName, members.map { stackKey(it) })
                saveStackKeys(entries)
            } else Groups.put(this, newName, members.map { it.activityInfo.packageName })
            if (oldName != null && oldName != newName) {
                Groups.delete(this, oldName)
                val hidden = hiddenFolders.toMutableSet().apply { if (oldName in autoNames) add(oldName); remove(newName) }
                val locked = lockedFolders().toMutableSet().apply { if (remove(oldName)) add(newName) }
                folderPrefs.edit().putStringSet("hidden_auto_folders", hidden).putStringSet("locked_folders", locked).apply()
            }
            rebuildFolders()
        }
        fun pickMembers(oldName: String?, name: String, members: List<ResolveInfo>) {
            val onHome = stackKeys().any { isFolder(it) && folderName(it) == oldName }
            val choices = if (onHome) apps else apps.distinctBy { it.activityInfo.packageName }
            val selected = choices.map { choice -> members.any { if (onHome) stackKey(it) == stackKey(choice) else it.activityInfo.packageName == choice.activityInfo.packageName } }.toBooleanArray()
            val dialog = android.app.AlertDialog.Builder(this).setTitle("Apps in $name")
                .setMultiChoiceItems(choices.map { it.loadLabel(packageManager).toString() }.toTypedArray(), selected) { _, i, checked -> selected[i] = checked }
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val picked = choices.filterIndexed { i, _ -> selected[i] }
                    if (picked.isEmpty()) android.widget.Toast.makeText(this, "Choose at least one app", android.widget.Toast.LENGTH_SHORT).show()
                    else { dialog.dismiss(); saveFolder(oldName, name, picked) }
                }
            }
            dialog.show()
        }
        fun nameFolder(oldName: String?, members: List<ResolveInfo>) {
            val input = android.widget.EditText(this).apply { isSingleLine = true; setText(oldName ?: ""); hint = "Folder name" }
            val dialog = android.app.AlertDialog.Builder(this).setTitle(if (oldName == null) "New folder" else "Rename folder")
                .setView(input).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = input.text.toString().trim()
                    if (name.isBlank() || name.any { it in ":;,\t\n\r" }) input.error = "Enter a name without : ; , or line breaks"
                    else if (name != oldName && folders.containsKey(name)) input.error = "A folder with this name already exists"
                    else { dialog.dismiss(); if (oldName == null) pickMembers(null, name, emptyList()) else saveFolder(oldName, name, members) }
                }
            }
            dialog.show()
        }
        fun editFolder(name: String, members: List<ResolveInfo>) {
            if (layoutLocked) { android.widget.Toast.makeText(this, "Unlock the layout to edit folders", android.widget.Toast.LENGTH_SHORT).show(); return }
            val open = {
                aosDialog(p, name, "Edit this folder", null,
                    DialogAction("Rename") { nameFolder(name, members) },
                    DialogAction("Choose apps") { pickMembers(name, name, members) },
                    DialogAction("Remove folder", 2) {
                        aosDialog(p, "Remove $name?", "Apps stay installed. Apps from a desktop folder return to the desktop.", null,
                            DialogAction("Remove", 2) {
                                saveStackKeys(stackKeys().flatMap { if (isFolder(it) && folderName(it) == name) folderKeys(it) else listOf(it) })
                                Groups.delete(this, name)
                                folderPrefs.edit().putStringSet("hidden_auto_folders", hiddenFolders + name).putStringSet("locked_folders", lockedFolders() - name).apply()
                                rebuildFolders()
                            }, DialogAction("Cancel"))
                    }, DialogAction("Cancel"))
            }
            if (name in lockedFolders()) askCredential(name) { open() } else open()
        }
        fun showOne(name: String, members: List<ResolveInfo>) {
            page.removeAllViews()
            val cell = (resources.displayMetrics.widthPixels - dp(32) - dp(36)) / 4
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(72), dp(16), dp(150))
                addView(LinearLayout(this@HomeActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("‹  Folders", 14f, p.text, true).apply {
                        background = glassShape(p, 20); setPadding(dp(16), dp(9), dp(16), dp(9)); setOnClickListener { showAll() }
                    })
                    addView(label(name, 20f, p.text, true).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
                })
                // Bento: the apps of the folder are tiles of different sizes (big 2x2, wide 2x1, small 1x1) that fit together.
                addView(GridLayout(this@HomeActivity).apply {
                    columnCount = 4
                    val pattern = listOf(2 to 2, 1 to 1, 1 to 1, 2 to 1, 1 to 1, 1 to 1, 2 to 1, 2 to 2, 1 to 1, 1 to 1)
                    val rowH = dp(92); val m = dp(4)
                    for ((i, ri) in members.withIndex()) {
                        val (cs, rs) = pattern[i % pattern.size]
                        val wide = cs == 2 && rs == 1
                        val big = cs == 2 && rs == 2
                        addView(LinearLayout(this@HomeActivity).apply {
                            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                            gravity = Gravity.CENTER; background = glassShape(p, 22); setPadding(dp(8), dp(8), dp(8), dp(8))
                            val isz = if (big) 84 else if (wide) 48 else 44
                            addView(icon(ri, isz), LinearLayout.LayoutParams(dp(isz), dp(isz)))
                            addView(label(ri.loadLabel(packageManager).toString(), if (big) 14f else 11f, p.text, big).apply {
                                maxLines = 1; setPadding(if (wide) dp(10) else 0, if (wide) 0 else dp(4), 0, 0)
                            })
                            setOnClickListener { launch(ri, from = getChildAt(0)) }
                            pressFx(p, round = false)
                        }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, rs), GridLayout.spec(GridLayout.UNDEFINED, cs)).apply {
                            width = cell * cs - m * 2; height = rowH * rs - m * 2; setMargins(m, m, m, m)
                        })
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            }
            page.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(box) })
        }
        showAll = {
            page.removeAllViews()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(72), dp(12), dp(150))
                // Header bar and pill filters, the same pattern as the Feed page.
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL; background = glassShape(p, 30, true); setPadding(dp(18), dp(12), dp(12), dp(12))
                    addView(LinearLayout(this@HomeActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(label("Folders", 22f, p.text, true).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
                        if (editing && !layoutLocked) addView(label("+", 22f, p.accent, true).apply {
                            contentDescription = "New folder"; setPadding(dp(14), dp(8), dp(14), dp(8)); setOnClickListener { nameFolder(null, emptyList()) }
                        })
                        addView(label(if (editing) "Done" else "Edit", 14f, p.accent, true).apply {
                            contentDescription = if (editing) "Finish editing folders" else "Edit folders"
                            setPadding(dp(14), dp(8), dp(14), dp(8)); setOnClickListener { if (editing) exitEdit() else enterEdit() }
                        })
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                    addView(android.widget.HorizontalScrollView(this@HomeActivity).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(LinearLayout(this@HomeActivity).apply {
                            for ((i, n) in listOf("All", "Mine", "Auto", "🔒 Locked").withIndex()) addView(label(n, 14f, if (i == fFilter) p.onAccent else p.text, true).apply {
                                gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
                                background = if (i == fFilter) shape(p.accent, 20) else glassShape(p, 20, true)
                                setOnClickListener { fFilter = i; showAll() }
                            }, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(8) })
                        })
                    })
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                if (folders.isEmpty()) addView(label("No folders yet. Hold an app on the desktop and drop it on another, or long-press an app in the drawer → Add to folder.", 13f, p.sub).apply { gravity = Gravity.START; setPadding(dp(4), 0, dp(4), 0) })
                // A list of folders, each an accordion: the row (folder tile, name, count) opens to show its apps right below.
                val shownFolders = folders.filter { (n, _) -> when (fFilter) { 1 -> n !in autoNames; 2 -> n in autoNames; 3 -> n in lockedFolders(); else -> true } }
                for ((name, members) in shownFolders) addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL; background = glassShape(p, 24)
                    val locked = name in lockedFolders()
                    val list = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(12), 0, dp(12), dp(10)) }
                    fun fill() {
                        if (list.childCount > 0) return
                        for (ri in members) list.addView(LinearLayout(this@HomeActivity).apply {
                            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0)
                            addView(icon(ri, 36), LinearLayout.LayoutParams(dp(36), dp(36)))
                            addView(label(ri.loadLabel(packageManager).toString(), 15f, p.text).apply { gravity = Gravity.START; maxLines = 1 },
                                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
                            setOnClickListener { launch(ri, from = getChildAt(0)) }
                            pressFx(p, round = false)
                        }, LinearLayout.LayoutParams(-1, dp(52)))
                    }
                    val chevron = ImageView(this@HomeActivity).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(p.sub) }
                    fun toggle() {
                        val open = list.visibility != View.VISIBLE
                        if (open) { fill(); list.alpha = 0f; list.visibility = View.VISIBLE; list.animate().alpha(1f).setDuration(Motion.NORMAL).start() }
                        else list.visibility = View.GONE
                        chevron.animate().rotation(if (open) 90f else 0f).setDuration(Motion.FAST).start()
                    }
                    val row = LinearLayout(this@HomeActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
                        // The folder's shortcut: an accent tile with its first app (or a padlock when locked).
                        addView(FrameLayout(this@HomeActivity).apply {
                            background = shape(p.accent, 14)
                            if (locked) addView(label("🔒", 20f, p.onAccent).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                            else members.firstOrNull()?.let { addView(icon(it, 34), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)) }
                        }, LinearLayout.LayoutParams(dp(44), dp(44)))
                        addView(LinearLayout(this@HomeActivity).apply {
                            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                            addView(label(name, 16f, p.text, true).apply { gravity = Gravity.START; maxLines = 1 })
                            addView(label(if (locked) "Locked · double-tap to unlock" else if (members.size == 1) "1 app" else "${members.size} apps", 12f, p.sub).apply { gravity = Gravity.START })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        if (editing) addView(label(if (layoutLocked) "Locked" else "Edit", 13f, p.accent, true).apply { setPadding(dp(10), 0, dp(10), 0) })
                        addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)))
                        // Tap opens or closes the folder (a locked one asks for the screen lock first); a double tap locks or unlocks it.
                        var lastTap = 0L
                        setOnClickListener {
                            if (editing) { editFolder(name, members); return@setOnClickListener }
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastTap < 320) {
                                lastTap = 0
                                if (locked) askCredential(name) { setFolderLocked(name, false); showAll() } else { setFolderLocked(name, true); showAll() }
                            } else {
                                lastTap = now
                                postDelayed({
                                    if (lastTap == now && !editing) { lastTap = 0; if (locked && list.visibility != View.VISIBLE) askCredential(name) { toggle() } else toggle() }
                                }, 330)
                            }
                        }
                        setOnLongClickListener { if (!editing) enterEdit(); editFolder(name, members); true }
                        pressFx(p, round = false)
                    }
                    addView(row, LinearLayout.LayoutParams(-1, dp(68)))
                    addView(list, LinearLayout.LayoutParams(-1, -2))
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
            page.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(box) })
        }
        refreshFolders = { showAll() }
        showAll()
        page.setOnLongClickListener { enterEdit(); true }
        return page
    }

    /** News block of the Feed: article cards from the chosen RSS sources, same glass look as the recent-apps card. */
    private fun buildNews(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = label("Loading news…", 13f, p.sub).apply { gravity = Gravity.START; setPadding(dp(4), dp(8), 0, 0) }
        box.addView(status); box.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        fun render(articles: List<News.Article>) {
            list.removeAllViews()
            status.visibility = if (articles.isEmpty()) View.VISIBLE else View.GONE
            if (articles.isEmpty()) status.text = "No news yet. Pick sources with the list button at the top."
            for (a in articles.take(25)) {
                val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(p.surfaceHigh, 14); clipToOutline = true }
                if (a.image.isNotBlank()) {
                    NetImages.load(a.image, thumb, dp(84))
                }
                list.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 22); setPadding(dp(12), dp(12), dp(12), dp(12))
                    addView(LinearLayout(this@HomeActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(a.source + (News.ago(a.time).let { if (it.isEmpty()) "" else "  ·  $it" }), 11f, p.accent, true).apply { gravity = Gravity.START })
                        addView(label(a.title, 15f, p.text, true).apply { gravity = Gravity.START; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (a.image.isNotBlank()) addView(thumb, LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginStart = dp(12) })
                    setOnClickListener {
                        startActivity(Intent(this@HomeActivity, BrowserActivity::class.java).setData(android.net.Uri.parse(a.link)))
                    }
                    pressFx(p, round = false)
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        News.load(this) { articles -> runOnUiThread { render(articles) } }
        return box
    }


    private var deskIcon: ImageView? = null

    private fun paintDesk() {
        val onFeed = feedEnabled && shownPage == feedPos
        // Active (accent circle, white glyph) while you are on the main desktop or the Feed; plain glass on the other pages.
        val active = onFeed || shownPage == feedOffset
        deskIcon?.setImageResource(if (onFeed) R.drawable.ic_legal else R.drawable.ic_home)
        // Active = an accent outline ring (no fill); inactive = plain glass.
        deskIcon?.setColorFilter(p.accent)
        (deskIcon?.parent as? View)?.background = when {
            dockButtons == "none" -> null
            dockButtons == "square" -> shape(p.glassCircle, 14, if (active) p.accent else null, 2)
            active -> android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(p.glassCircle); setStroke(maxOf(2, dp(2)), p.accent)
            }
            else -> circle(p.glassCircle)
        }
    }

    private fun setDots(page: Int) {
        if (page == shownPage) return
        if (!(feedEnabled && page == feedPos)) feedTranslator?.pause()
        shownPage = page
        if (!editing) dockForEditing(page != feedOffset)   // Feed and Folders pages have no dock or status plate
        paintDesk()
        paintVDots()
        if (editing) paintMini()
        dots.removeAllViews()
        for (i in 0 until totalPages) {
            val on = i == page
            dots.addView(View(this).apply { background = circle(if (on) p.accent else (p.sub and 0xFFFFFF) or 0x66000000) },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(5); marginEnd = dp(5) })
        }
    }

    /** Windows 11 style taskbar as a floating, fully rounded pill: centered icons, accent indicator under each app. */
    private fun buildTaskbar(): View {
        // The glass dock holds the icon row and, under it, the page dots.
        val dock = SwipeUpBar(this) { if (!editing && drawer.visibility != View.VISIBLE) openDrawer() }.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(7), dp(18), dp(6))
            // Dock style (AOS Settings > Desktop > Dock & panels): glass pill, solid, clear (icons only) or compact.
            background = when (dockStyle) {
                "solid" -> shape(p.surface, 34, p.stroke)
                "clear" -> null
                else -> glassShape(p, 34)
            }
        }
        val semi = dockStyle == "semicircle"
        val compact = dockStyle == "compact" || semi
        if (compact) dock.setPadding(dp(10), dp(6), dp(10), dp(6))
        val cellDp = if (semi) 42 else if (compact) 40 else 46
        val bar = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Eight fixed-size slots exceeded narrow screens and clipped the two end buttons.
                // Fit the actual buttons to the space offered by this dock, including compact gaps.
                if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                    val cells = (0 until childCount).map { getChildAt(it) }.filterIsInstance<FrameLayout>()
                    if (cells.isNotEmpty()) {
                        val gaps = if (compact) dp(4) * (cells.size - 1) else 0
                        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - gaps
                        val size = minOf(dp(cellDp), available / cells.size).coerceAtLeast(1)
                        for (cell in cells) {
                            cell.layoutParams.width = size; cell.layoutParams.height = size
                            cell.getChildAt(0)?.layoutParams?.let { icon ->
                                icon.width = (size - dp(6)).coerceAtLeast(1); icon.height = icon.width
                            }
                        }
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        if (Fx.on(this, "liquid_dock")) { dock.clipChildren = false; dock.clipToPadding = false; dock.onFinger = { x -> Fx.magnify(bar, x) } }
        dock.addView(bar, LinearLayout.LayoutParams(if (compact) -2 else -1, -2))
        fun slot(content: View, onClick: () -> Unit) {
            val cell = FrameLayout(this).apply {
                // Button shape: circle, rounded square or no plate at all.
                background = when (dockButtons) { "square" -> shape(p.glassCircle, 14); "none" -> null; else -> circle(p.glassCircle) }
                setOnClickListener { onClick() }
                pressFx(p)
            }
            cell.addView(content, FrameLayout.LayoutParams(dp(cellDp - 6), dp(cellDp - 6), Gravity.CENTER))
            // Full-width dock: flexible gaps between the icons spread them evenly across the bar; the compact dock packs them.
            if (bar.childCount > 0) bar.addView(View(this), if (compact) LinearLayout.LayoutParams(dp(4), 1) else LinearLayout.LayoutParams(0, 1, 1f))
            bar.addView(cell, LinearLayout.LayoutParams(dp(cellDp), dp(cellDp)))
        }
        slot(ImageView(this).apply { setImageResource(R.drawable.ic_start); setColorFilter(p.accent); setPadding(dp(2), dp(2), dp(2), dp(2)) }) {
            openDrawer()
        }
        // Desktop button: straight back to the main desktop (other desktops have their own way back, no picker menu).
        deskIcon = ImageView(this).apply { setColorFilter(p.accent); setPadding(dp(2), dp(2), dp(2), dp(2)) }
        paintDesk()
        slot(deskIcon!!) { if (shownPage == feedOffset) switchDesk(0) else goDesk(0) }
        paintDesk()   // the button's circle exists only now, so paint it again
        for (key in listOf("dialer", "messaging", "documentsui", "chrome", "camera")) {
            val own = key == "dialer" || key == "messaging" || key == "camera" || key == "documentsui"   // AOS Files replaces the system one
            val ri = apps.firstOrNull { it.activityInfo.packageName.contains(key, true) }
            if (!own && ri == null) continue
            lateinit var iv: ImageView
            iv = ImageView(this).apply {
                if (own) {
                    // Our own Phone and Messages apps, drawn as an accent tile with a white glyph.
                    val glyph = getDrawable(when (key) { "dialer" -> R.drawable.ic_phone; "camera" -> R.drawable.ic_camera; "documentsui" -> R.drawable.ic_folder; else -> R.drawable.ic_chat })!!
                        .mutate().also { it.setTint(p.onAccent) }
                    setImageDrawable(android.graphics.drawable.LayerDrawable(arrayOf(shape(p.accent, 10), android.graphics.drawable.InsetDrawable(glyph, 0.24f))))
                } else setImageDrawable(AosIcons.themed(this@HomeActivity, ri!!.loadIcon(packageManager), p, 34))
            }
            slot(iv) {
                when (key) {
                    "dialer" -> startActivity(Intent(this, PhoneActivity::class.java))
                    "messaging" -> startActivity(Intent(this, MessagesActivity::class.java))
                    "camera" -> startActivity(Intent(this, CameraActivity::class.java))
                    "documentsui" -> startActivity(Intent(this, FilesActivity::class.java))
                    else -> launch(ri!!, from = iv)
                }
            }
        }
        splitIcon = ImageView(this).apply { setColorFilter(p.accent); setPadding(dp(2), dp(2), dp(2), dp(2)) }
        updateSplitIcon()
        slot(splitIcon) { if (splitActive) exitSplit() else pickSplit() }
        // Semicircle dock: the same buttons, spread along the arc of a glass half-disc.
        var arc: SemiDock? = null
        if (semi) {
            arc = SemiDock(this, p, dp(cellDp)).apply { adopt(bar) }
            dock.removeAllViews(); dock.background = null; dock.setPadding(0, 0, 0, 0); dock.onFinger = null
            dock.addView(arc, LinearLayout.LayoutParams(dp(300), -2))
        }
        // The status plate floats just above the dock.
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
            // Status plate: full, mini (time and battery only, centred) or hidden. It takes the dock's form: as narrow as a compact
            // or semicircle dock (centred over it), and the same material as the dock (glass, solid or clear).
            if (statusStyle != "hidden" && arc != null) {
                // Semicircle dock: the top bar moves inside the half-disc, under the arc of apps (time, Wi-Fi, signal, battery).
                val plate = buildStatusPlate() as ViewGroup
                plate.background = null; plate.setPadding(dp(6), 0, dp(6), 0)
                (plate as? StatusPlate)?.onCalendar = { if (::calendarPanel.isInitialized) calendarPanel.show() }
                for (i in 1..2) plate.getChildAt(i)?.visibility = View.GONE                      // date and divider
                plate.getChildAt(5)?.visibility = View.GONE                                       // operator name
                if (statusStyle == "mini") for (i in 3..4) plate.getChildAt(i)?.visibility = View.GONE
                (plate.getChildAt(3)?.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(10)
                arc.setInner(plate)
            } else if (statusStyle != "hidden") {
                val plate = buildStatusPlate() as ViewGroup
                if (statusStyle == "mini") for (i in 1..5) plate.getChildAt(i)?.visibility = View.GONE
                when (dockStyle) {
                    "solid" -> plate.background = shape(p.surface, 20, p.stroke)
                    "clear" -> plate.background = null
                }
                (plate as? StatusPlate)?.onCalendar = { if (::calendarPanel.isInitialized) calendarPanel.show() }
                val narrow = statusStyle == "mini" || compact
                // In a narrow plate the operator name takes its own width instead of the leftover space (there is none).
                if (narrow) plate.getChildAt(5)?.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) }
                // Over a narrow dock the date and its divider make way, so the plate is as short as the dock.
                if (narrow) {
                    for (i in 1..2) plate.getChildAt(i)?.visibility = View.GONE
                    (plate.getChildAt(3)?.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(10)   // Wi-Fi off the clock
                }
                addView(plate, LinearLayout.LayoutParams(if (narrow) -2 else -1, dp(40)).apply { bottomMargin = dp(if (semi) 6 else 12) })
            }
            addView(dock, LinearLayout.LayoutParams(if (compact) -2 else -1, -2))
        }
    }

    /** Transparent glass plate above the dock: time, date, Wi-Fi, operator with signal bars and the battery percentage. */
    /** Live status plate above the dock (see [StatusPlate]); a tap opens the Control Center. */
    private fun buildStatusPlate(): View = StatusPlate(this, p).apply {
        setOnClickListener { if (::controlCenter.isInitialized && !controlCenter.isOpen) controlCenter.show(0) }
        pressFx(p, round = false)
    }

    private fun phoneGlyph() =getDrawable(R.drawable.ic_phone)!!.mutate().also { it.setTint(p.onAccent) }

    private fun updateSplitIcon() {
        splitIcon.setImageResource(if (splitActive) R.drawable.ic_close else R.drawable.ic_split)
    }

    /** Split screen: choose two apps; the first fills the top, the second is launched next to it. */
    private fun pickSplit() {
        val chosen = arrayOfNulls<ResolveInfo>(2)
        var active = 0
        val slots = Array(2) { LinearLayout(this) }
        val go = TextView(this)
        val dlg = android.app.Dialog(this)

        fun paint() {
            for (i in 0..1) slots[i].apply {
                removeAllViews()
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                background = shape(p.surfaceHigh, 16, if (i == active) p.accent else p.stroke)
                val ri = chosen[i]
                if (ri != null) {
                    addView(ImageView(this@HomeActivity).apply { setImageDrawable(AosIcons.themed(this@HomeActivity, ri.loadIcon(packageManager), p, 44, ri.activityInfo.packageName)) },
                        LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(label(ri.loadLabel(packageManager).toString(), 15f, p.text, true).apply { gravity = Gravity.START },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                } else {
                    addView(label(if (i == 0) "Top app: tap an app below" else "Bottom app: tap an app below", 14f, p.sub))
                }
            }
            val ready = chosen[0] != null && chosen[1] != null
            go.background = shape(if (ready) p.accent else p.surfaceHigh, 24)
            go.setTextColor(if (ready) p.onAccent else p.sub)
        }

        val cell = (resources.displayMetrics.widthPixels - dp(24) - dp(32)) / 4
        val grid = GridLayout(this).apply {
            columnCount = 4
            for (ri in apps) addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(ImageView(this@HomeActivity).apply { setImageDrawable(AosIcons.themed(this@HomeActivity, ri.loadIcon(packageManager), p, 46, ri.activityInfo.packageName)) },
                    LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(label(ri.loadLabel(packageManager).toString(), 11f, p.text).apply { maxLines = 1; setPadding(dp(2), dp(4), dp(2), 0) })
                setOnClickListener { chosen[active] = ri; if (active == 0) active = 1; paint() }
                pressFx(p)
            }, GridLayout.LayoutParams().apply { width = cell; height = dp(86) })
        }

        go.apply {
            text = "Start"; textSize = 15f; typeface = appFont(600); gravity = Gravity.CENTER
            setOnClickListener {
                val a = chosen[0]; val b = chosen[1]
                if (a != null && b != null) { dlg.dismiss(); startSplit(a, b) }
            }
        }
        val cancel = TextView(this).apply {
            text = "Cancel"; textSize = 15f; typeface = appFont(600); gravity = Gravity.CENTER; setTextColor(p.text)
            background = shape(p.surfaceHigh, 24); setOnClickListener { dlg.dismiss() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glassShape(p, 28, true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(label("Split screen", 20f, p.text, true).apply { gravity = Gravity.START })
            addView(label("Pick the top and the bottom app", 13f, p.sub).apply { gravity = Gravity.START; setPadding(0, dp(2), 0, dp(10)) })
            for (i in 0..1) {
                slots[i].setOnClickListener { active = i; paint() }
                addView(slots[i], LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(6) })
            }
            addView(android.widget.ScrollView(this@HomeActivity).apply { isVerticalScrollBarEnabled = false; addView(grid) },
                LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
            addView(LinearLayout(this@HomeActivity).apply {
                addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
                addView(go, LinearLayout.LayoutParams(0, dp(48), 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        paint()
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(FrameLayout(this).apply {
            setPadding(dp(12), dp(40), dp(12), dp(40))
            addView(panel, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.72f).toInt()))
        })
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(-1, -2); setDimAmount(0.5f)
        }
        dlg.show()
    }

    private fun startSplit(a: ResolveInfo, b: ResolveInfo) {
        val service = SplitService.instance
        if (service == null) {
            android.widget.Toast.makeText(this, "Turn on AOS Split Screen in Accessibility, then try again", android.widget.Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        splitActive = true; updateSplitIcon()
        val handler = android.os.Handler(mainLooper)
        launch(a)
        handler.postDelayed({ service.toggleSplit() }, 1400)
        handler.postDelayed({ launch(b, adjacent = true) }, 2800)
    }

    private fun exitSplit() {
        SplitService.instance?.toggleSplit()
        splitActive = false; updateSplitIcon()
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens an app; when [from] is given the window grows out of that icon (short scale-up, no full-screen fade). */
    private fun launch(ri: ResolveInfo, adjacent: Boolean = false, from: View? = null) {
        val opts = if (from != null && from.isAttachedToWindow) {
            if (Fx.on(this, "liquid_open")) {
                // Liquid Transition: the icon squashes like a drop, then the app is revealed out of it.
                from.animate().scaleX(1.18f).scaleY(0.86f).setDuration(Fx.dur(90)).withEndAction {
                    from.animate().scaleX(1f).scaleY(1f).setDuration(Fx.dur(420)).setInterpolator(Fx.spring).start()
                }.start()
                android.app.ActivityOptions.makeClipRevealAnimation(from, 0, 0, from.width, from.height).toBundle()
            } else android.app.ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        } else null
        if (Fx.on(this, "zoom_fade")) wpFx?.let { d ->
            d.zoom = 1.08f; d.dim = 0.4f
            window.decorView.postDelayed({ d.zoom = 1f; d.dim = 0f }, 600)   // back to normal by the time we return
        }
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (adjacent) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0)), opts)
            Recents.push(this, ri.activityInfo.packageName)
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "App is no longer available", android.widget.Toast.LENGTH_SHORT).show()
            recreate()
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(this, "Android blocked opening this app", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var swipeX0 = 0f
    private var swipeY0 = 0f
    private var swipeFired = false
    private var swipeDownAt = 0L

    /** A swipe up from anywhere on the lower part of the home screen opens the App Drawer (the Start button still works too). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::drawer.isInitialized || !::recents.isInitialized || !::controlCenter.isInitialized) return super.dispatchTouchEvent(ev)
        doubleTap.onTouchEvent(ev)
        when (ev.actionMasked) {
            // A swipe down on the open App Drawer (list already at the top) closes it.
            MotionEvent.ACTION_MOVE -> if (!swipeFired && drawer.visibility == View.VISIBLE && ::appDrawer.isInitialized && appDrawer.atTop()) {
                val down = ev.rawY - swipeY0
                if (down > dp(110) && down > Math.abs(ev.rawX - swipeX0) * 1.6f) {
                    swipeFired = true
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    closeDrawer()
                    return true
                }
            }
        }
        // A swipe down on the first home desktop (below the top-bar zone) pulls in the Feed on the left.
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && !swipeFired && feedEnabled && !editing && !tilesStyle && shownPage == feedOffset && vPage == 0 &&
            drawer.visibility != View.VISIBLE && !recents.isOpen && !controlCenter.isOpen &&
            editSheet.visibility != View.VISIBLE && swipeY0 > dp(130) && !swipeInStack) {
            val dy = ev.rawY - swipeY0
            if (dy > dp(90) && dy > Math.abs(ev.rawX - swipeX0) * 1.6f) {
                swipeFired = true
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel); cancel.recycle()
                homePager.smoothScrollTo(stripIndex(feedPos) * resources.displayMetrics.widthPixels, 0)
                return true
            }
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeX0 = ev.rawX; swipeY0 = ev.rawY; swipeFired = false; swipeDownAt = android.os.SystemClock.uptimeMillis()
                // A swipe that starts on the app stack scrolls the stack; it must not open the drawer.
                swipeInStack = stackPager?.let { v -> val r = android.graphics.Rect(); v.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt()) } ?: false
            }
            MotionEvent.ACTION_MOVE -> if (!swipeFired && !editing && !tilesStyle && !swipeInStack && !(feedEnabled && shownPage == feedPos) && drawer.visibility != View.VISIBLE && !recents.isOpen &&
                !controlCenter.isOpen && editSheet.visibility != View.VISIBLE) {
                val dy = swipeY0 - ev.rawY
                val dx = Math.abs(ev.rawX - swipeX0)
                // Desktops turn sideways now, so a swipe up from the lower part of any page opens the drawer.
                if (dy > dp(90) && dy > dx * 1.6f && pagesCount < 2 && swipeY0 > resources.displayMetrics.heightPixels * 0.35f) {   // with several desktops the dock bar opens the drawer; the page swipes up/down
                    swipeFired = true
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    openDrawer()
                    return true
                }
            }
        }
        if (swipeFired && ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (recents.isOpen) recents.hide() else if (::calendarPanel.isInitialized && calendarPanel.isOpen) calendarPanel.close() else if (controlCenter.isOpen) controlCenter.close() else if (editSheet.visibility == View.VISIBLE) closeManager() else if (editing) exitEdit() else if (drawer.visibility == View.VISIBLE) closeDrawer()
    }
}
