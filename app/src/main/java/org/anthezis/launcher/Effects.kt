package org.anthezis.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.ImageView

/**
 * AOS visual effects: three groups (theme, wallpaper, desktop), each effect switchable, one speed for all of them.
 * Everything runs on property animations and RenderEffect, which the render thread draws at the panel's own rate
 * (60, 90 or 120 Hz). Heavy effects step aside in battery saver or when the system turns animations off.
 */
object Fx {
    class Effect(val id: String, val title: String, val hint: String, val def: Boolean = true, val heavy: Boolean = false)

    val theme = listOf(
        Effect("theme_fade", "Smooth light/dark switch", "The screen cross-fades instead of flashing when the theme changes"),
        Effect("dynamic_color", "Dynamic Color", "Accent follows the Material You colours of the system", def = false),
        Effect("glass", "Blur & Glass", "Frosted glass behind the drawer, Recents and panels", heavy = true),
        Effect("liquid_open", "Liquid Transition", "Apps open with a fluid reveal from their icon"),
        Effect("morph", "Shape & colour morph", "Buttons round off and light up smoothly when pressed"),
    )
    val wallpaper = listOf(
        Effect("tilt", "Motion depth", "The theme moves gently as you tilt the phone in any direction", heavy = true),
        Effect("parallax", "Parallax", "The wallpaper drifts slower than the pages", heavy = true),
        Effect("zoom_fade", "Zoom & Fade", "The wallpaper zooms and dims as the drawer or an app opens"),
        Effect("wp_blur", "Blur Wallpaper", "The wallpaper softens behind the drawer and panels", def = false, heavy = true),
        Effect("depth", "Depth Effect", "Animated scene layers move faster than the wallpaper", heavy = true),
        Effect("wp_fade", "Smooth wallpaper change", "A new wallpaper fades in over the old one"),
    )
    val desktop = listOf(
        Effect("elastic", "Elastic Scroll", "Pages and lists stretch at their ends"),
        Effect("icon_morph", "Icon Morph", "Icon tiles turn round under your finger"),
        Effect("spring", "Spring Animation", "Presses, drawer and icons settle with a light bounce"),
        Effect("liquid_dock", "Liquid Dock", "Dock icons swell around your finger", heavy = true),
        Effect("stagger", "Staggered Icons", "Icons arrive one after another when you come home"),
    )
    /** Page transitions of the home pager. */
    val pageModes = listOf("slide" to "Slide", "cube" to "Cube 3D", "fade" to "Fade", "stack" to "Stack", "pinwheel" to "Pinwheel")

    private fun prefs(c: Context) = c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)
    private fun all() = theme + wallpaper + desktop

    // ---- every theme brings its own effects ----
    /** A set of effects that suits a family of themes: page transition, speed, effects turned on and off. */
    class Preset(val name: String, val page: String, val speed: Float, val on: Set<String>, val off: Set<String>)

    private val presets = mapOf(
        "classic" to Preset("Classic", "pinwheel", 1f, emptySet(), emptySet()),
        "minimal" to Preset("Minimal", "fade", 1.25f, setOf("theme_fade", "wp_fade", "morph"),
            setOf("parallax", "depth", "glass", "wp_blur", "liquid_dock", "stagger", "spring", "liquid_open")),
        "dreamy" to Preset("Dreamy", "stack", 0.85f, setOf("glass", "wp_blur", "parallax", "depth", "spring", "liquid_open", "wp_fade"), setOf("liquid_dock")),
        "nature" to Preset("Nature", "slide", 0.9f, setOf("parallax", "depth", "spring", "stagger", "elastic", "wp_fade"), setOf("liquid_dock", "wp_blur")),
        "cyber" to Preset("Cyber", "cube", 1.15f, setOf("liquid_dock", "icon_morph", "depth", "glass", "liquid_open", "morph"), setOf("spring", "wp_blur")),
        "energy" to Preset("Energy", "pinwheel", 1.2f, setOf("spring", "liquid_dock", "stagger", "elastic", "liquid_open"), setOf("wp_blur")),
        "calm" to Preset("Calm", "fade", 0.8f, setOf("parallax", "wp_fade", "theme_fade", "glass"), setOf("liquid_dock", "stagger")),
    )

    /** Which family a theme belongs to (new themes fall back to Classic). */
    fun family(style: String) = when {
        style.startsWith("flat_") || style == "graphite" -> "minimal"
        style.startsWith("bokeh_") || style in setOf("aurora", "twilight", "dawn", "sunrise", "rose") -> "dreamy"
        style in setOf("neon", "synth", "matrix", "circuit", "space", "x_blocks") -> "cyber"
        style in setOf("ember", "volcano", "inferno", "hornet", "royal") -> "energy"
        style in setOf("forest", "forestowl", "forest_peaks", "jungle", "meadow", "sakura", "alps", "desert") -> "nature"
        style in setOf("fog", "storm", "snow", "glacier", "lagoon") -> "calm"
        else -> "classic"
    }

    fun styleOf(c: Context) = prefs(c).getString("wallpaper", AosStyles.DEFAULT).orEmpty().ifEmpty { AosStyles.DEFAULT }
    fun preset(c: Context): Preset = presets[family(styleOf(c))] ?: presets.getValue("classic")
    /** On (default): effects, transition and speed follow the current theme, and changes are kept for that theme only. */
    fun perTheme(c: Context) = AosPlan.has(c, AosPlan.PER_THEME) && prefs(c).getBoolean("fx_per_theme", true)
    fun setPerTheme(c: Context, on: Boolean) { prefs(c).edit().putBoolean("fx_per_theme", on).putBoolean("aos_dirty", true).apply(); refresh(c) }
    private fun themeKey(c: Context, what: String) = "fx_${styleOf(c)}__$what"

    /** Forgets the user's changes for the current theme: its own preset again. */
    fun resetTheme(c: Context) {
        val sp = prefs(c)
        val pre = "fx_${styleOf(c)}__"
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith(pre) }.forEach { e.remove(it) }
        e.putBoolean("aos_dirty", true).apply()
        refresh(c)
    }

    /** Animation speed, 0.5x .. 2x. Read on every resume and cached so durations cost nothing. */
    @Volatile var speed = 1f; private set
    @Volatile private var lite = false
    @Volatile private var master = true

    fun refresh(c: Context) {
        val sp = prefs(c)
        speed = (if (perTheme(c)) sp.getFloat(themeKey(c, "speed"), preset(c).speed) else sp.getFloat("fx_speed", 1f)).coerceIn(0.5f, 2f)
        master = sp.getBoolean("fx_master", true)
        val pm = c.getSystemService(PowerManager::class.java)
        val scale = android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        lite = pm?.isPowerSaveMode == true || scale == 0f
    }

    fun masterOn(c: Context) = prefs(c).getBoolean("fx_master", true)
    fun setMaster(c: Context, on: Boolean) { prefs(c).edit().putBoolean("fx_master", on).putBoolean("aos_dirty", true).apply(); refresh(c) }
    /** Own choice for this theme, else the theme's preset, else the general setting. */
    fun stored(c: Context, id: String): Boolean {
        val sp = prefs(c)
        val def = all().firstOrNull { it.id == id }?.def ?: true
        if (!perTheme(c)) return sp.getBoolean("fx_$id", def)
        val key = themeKey(c, id)
        if (sp.contains(key)) return sp.getBoolean(key, def)
        val pr = preset(c)
        return when (id) { in pr.on -> true; in pr.off -> false; else -> sp.getBoolean("fx_$id", def) }
    }
    fun set(c: Context, id: String, on: Boolean) =
        prefs(c).edit().putBoolean(if (perTheme(c)) themeKey(c, id) else "fx_$id", on).putBoolean("aos_dirty", true).apply()

    /** Effect is wanted and allowed now (master switch, battery saver for heavy ones). */
    fun on(c: Context, id: String): Boolean {
        if (!master) return false
        val e = all().firstOrNull { it.id == id }
        if (!AosPlan.has(c, AosPlan.effectNeeds(id))) return false
        if (e?.heavy == true && lite) return false
        return stored(c, id)
    }

    fun pageMode(c: Context): String = pageModeRaw(c).let { if (AosPlan.has(c, AosPlan.pageNeeds(it))) it else "slide" }
    private fun pageModeRaw(c: Context): String = when {
        !master -> "slide"
        perTheme(c) -> prefs(c).getString(themeKey(c, "page"), null) ?: preset(c).page
        else -> prefs(c).getString("fx_page", "pinwheel") ?: "pinwheel"
    }
    fun setPageMode(c: Context, m: String) =
        prefs(c).edit().putString(if (perTheme(c)) themeKey(c, "page") else "fx_page", m).putBoolean("aos_dirty", true).apply()
    fun setSpeed(c: Context, s: Float) { prefs(c).edit().putFloat(if (perTheme(c)) themeKey(c, "speed") else "fx_speed", s).apply(); refresh(c) }

    fun dur(ms: Long): Long = (ms / speed).toLong().coerceAtLeast(1)

    /** Damped spring as an interpolator: overshoots a little and settles, the curve Material uses for "spring". */
    class Spring(private val bounce: Float = 0.32f) : Interpolator {
        override fun getInterpolation(t: Float): Float {
            if (t >= 1f) return 1f
            val zeta = 1f - bounce
            val w = 11f
            return (1 - Math.exp((-zeta * w * t).toDouble()) * Math.cos((w * Math.sqrt(1.0 - zeta * zeta) * t))).toFloat()
        }
    }
    val spring = Spring()
    private val ease = android.view.animation.DecelerateInterpolator(1.6f)
    fun settle(c: Context): Interpolator = if (on(c, "spring")) spring else ease

    /** Ask for the panel's fastest mode at the current resolution (90/120 Hz) while the launcher is in front, unless saving power. */
    fun preferHighRefresh(a: Activity) {
        val display = a.display ?: return
        val cur = display.mode
        val best = if (lite || !master) cur
            else display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: cur
        val lp = a.window.attributes
        if (lp.preferredDisplayModeId != best.modeId) { lp.preferredDisplayModeId = best.modeId; a.window.attributes = lp }
    }

    /**
     * Page transition for one page of a horizontal pager. [t] is the page's offset in pages: 0 = centred, -1 = one page to
     * the left, 1 = one to the right. Only transforms; the render thread draws them.
     */
    fun pageTransform(page: View, t: Float, mode: String) {
        val a = Math.abs(t).coerceAtMost(1f)
        val w = page.width.toFloat()
        page.rotation = 0f; page.rotationY = 0f; page.translationX = 0f; page.scaleX = 1f; page.scaleY = 1f; page.alpha = 1f
        when (mode) {
            "cube" -> {
                page.cameraDistance = w * 12f
                page.pivotX = if (t < 0) w else 0f; page.pivotY = page.height / 2f
                page.rotationY = 70f * t.coerceIn(-1f, 1f)
                page.alpha = (1f - 0.4f * a)
            }
            "fade" -> {
                page.translationX = -t * w   // pages stay in place and cross-fade
                page.alpha = (1f - a).coerceAtLeast(0f)
            }
            "stack" -> if (t > 0) {
                // The next page waits underneath, small and dim, and grows as the current one slides away.
                page.translationX = -t * w
                val s = 0.82f + 0.18f * (1f - a)
                page.scaleX = s; page.scaleY = s; page.alpha = 1f - a
            }
            "pinwheel" -> {
                page.pivotX = w / 2f; page.pivotY = page.height.toFloat()
                page.rotation = -9f * t; page.scaleX = 1f - 0.1f * a; page.scaleY = 1f - 0.1f * a
                page.alpha = (1f - 1.25f * a).coerceAtLeast(0f)
            }
        }
        // A page that has faded out must not catch taps meant for the current one.
        val vis = if (page.alpha <= 0.02f) View.INVISIBLE else View.VISIBLE
        if (page.visibility != vis) page.visibility = vis
    }

    /** Liquid Dock: icons near [rawX] swell and lift, the rest stay; null = finger left, everything springs back. */
    fun magnify(row: ViewGroup, rawX: Float?) {
        val loc = IntArray(2)
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            if (v.width == 0 || !v.isClickable) continue
            if (rawX == null) {
                v.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(dur(420)).setInterpolator(spring).start()
                continue
            }
            v.getLocationOnScreen(loc)
            val d = Math.abs(rawX - (loc[0] + v.width / 2f)) / (v.width * 2.2f)
            val s = 1f + 0.38f * (1f - d).coerceIn(0f, 1f)
            v.animate().cancel()
            v.scaleX = s; v.scaleY = s; v.translationY = -(s - 1f) * v.height * 0.7f
        }
    }

    /** Icon Morph: the tile under the icon rounds into a circle while pressed. Works on [AosIcons.themed] drawables. */
    fun morphIcon(iv: ImageView, pressed: Boolean) {
        val tile = ((iv.drawable as? LayerDrawable)?.getDrawable(0) as? GradientDrawable) ?: return
        val from = tile.cornerRadius
        val target = if (pressed) iv.width / 2f else (iv.getTag(R.id.fx_radius) as? Float ?: from)
        if (pressed && iv.getTag(R.id.fx_radius) == null) iv.setTag(R.id.fx_radius, from)
        ValueAnimator.ofFloat(from, target).apply {
            duration = dur(if (pressed) 160 else 320); interpolator = if (pressed) ease else spring
            addUpdateListener { tile.cornerRadius = it.animatedValue as Float; iv.invalidate() }
        }.start()
    }

    /** Staggered Icons: the given views arrive one after another with a spring. */
    fun stagger(views: List<View>) {
        views.forEachIndexed { i, v ->
            v.alpha = 0f; v.scaleX = 0.7f; v.scaleY = 0.7f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(dur(minOf(i, 16) * 24L)).setDuration(dur(380)).setInterpolator(spring)
                .withEndAction { v.animate().startDelay = 0 }.start()
        }
    }

    // ---- Smooth light/dark switch: a snapshot of the old screen is laid over the rebuilt one and fades away. ----
    private var snapshot: Bitmap? = null

    fun captureForRecreate(a: Activity) {
        if (!on(a, "theme_fade")) return
        val root = a.window.decorView
        if (root.width == 0) return
        snapshot = try {
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        } catch (_: Throwable) { null }
    }

    fun revealAfterRecreate(a: Activity) {
        val bmp = snapshot ?: return
        snapshot = null
        val decor = a.window.decorView as? ViewGroup ?: return
        val cover = ImageView(a).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        decor.addView(cover, ViewGroup.LayoutParams(-1, -1))
        cover.animate().alpha(0f).setStartDelay(60).setDuration(dur(420)).setInterpolator(ease)
            .withEndAction { decor.removeView(cover); bmp.recycle() }.start()
    }
}

/**
 * The launcher's own wallpaper layer (window background): parallax drift, zoom & fade, a cheap blur and cross-fades
 * between pictures. The picture is drawn 12% wider than the screen so it can drift without showing edges.
 */
class WallpaperFxDrawable(private var bmp: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var old: Bitmap? = null
    private var oldAlpha = 0f
    private var blurred: Bitmap? = null
    /** 0..1 position across the pages. */
    var offset = 0.5f; set(v) { field = v; invalidateSelf() }
    var zoom = 1f; set(v) { field = v; invalidateSelf() }
    var dim = 0f; set(v) { field = v; invalidateSelf() }
    var blur = 0f; set(v) { field = v; invalidateSelf() }
    var parallax = true
    var tiltEnabled = false
    private var tiltX = 0f
    private var tiltY = 0f
    fun setTilt(x: Float, y: Float) { tiltX = x.coerceIn(-1f, 1f); tiltY = y.coerceIn(-1f, 1f); invalidateSelf() }

    /** Smooth wallpaper change: the old picture fades out over the new one. */
    fun swap(next: Bitmap, fade: Boolean) {
        if (next === bmp) return
        blurred = null
        if (!fade) { bmp = next; invalidateSelf(); return }
        old = bmp; bmp = next; oldAlpha = 1f
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = Fx.dur(600)
            addUpdateListener { oldAlpha = it.animatedValue as Float; invalidateSelf() }
            doOnEnd { old = null }
        }.start()
    }

    private fun ValueAnimator.doOnEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(a: android.animation.Animator) = f()
    })

    /** Box blur by down- and up-scaling: one small bitmap, made once, then just cross-faded. */
    private fun blurredBitmap(): Bitmap = blurred ?: Bitmap.createScaledBitmap(
        Bitmap.createScaledBitmap(bmp, maxOf(1, bmp.width / 16), maxOf(1, bmp.height / 16), true), bmp.width, bmp.height, true).also { blurred = it }

    private fun drawPicture(c: Canvas, b: Bitmap, alpha: Float) {
        val r = bounds
        val extra = if (parallax) 1.12f else 1f
        val overscan = if (tiltEnabled) 1.06f else 1f
        val s = maxOf(r.width() * extra / b.width, r.height().toFloat() / b.height) * zoom * overscan
        val w = b.width * s; val h = b.height * s
        val horizontal = if (parallax) {
            if (tiltEnabled) 0.08f + offset.coerceIn(0f, 1f) * 0.84f + tiltX * 0.08f else offset
        } else if (tiltEnabled) 0.5f + tiltX * 0.45f else 0.5f
        val x = r.left - (w - r.width()) * horizontal
        val y = r.top - (h - r.height()) * (if (tiltEnabled) 0.5f + tiltY * 0.45f else 0.5f)
        paint.alpha = (alpha * 255).toInt()
        c.save(); c.translate(x, y); c.scale(s, s); c.drawBitmap(b, 0f, 0f, paint); c.restore()
    }

    override fun draw(c: Canvas) {
        drawPicture(c, bmp, 1f)
        if (blur > 0.01f) drawPicture(c, blurredBitmap(), blur)
        old?.let { drawPicture(c, it, oldAlpha) }
        if (dim > 0.01f) c.drawColor(((dim * 140).toInt() shl 24))
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
