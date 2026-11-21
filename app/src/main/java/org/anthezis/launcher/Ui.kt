package org.anthezis.launcher

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View

/** AOS design tokens: Material solid surfaces, graphite base, green accent, translucent "glass" only for floating bars. */
class Palette(c: Context) {
    /** Theme choice stored by the user (0 auto, 1 light, 2 dark); auto follows the system, not an app-level override. */
    val dark: Boolean = when (c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getInt("theme", 0)) {
        1 -> false
        2 -> true
        else -> (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val style = AosStyles.find(AosStyles.selectedId(c))
    /**
     * Colours of the interface follow the scene as it really looks: a light theme whose day picture is dark (storm, deep sea,
     * jungle...) gets the light-on-dark colours, so text never sinks into the wallpaper. [dark] still picks the night or day scene.
     */
    val uiDark: Boolean = dark || (try { AosStyles.dayLuminance(style) < 0.42f } catch (_: Throwable) { false })
    val bg = Color.parseColor(if (uiDark) "#121417" else "#F1F3F2")
    val surface = Color.parseColor(if (uiDark) "#1C2024" else "#FFFFFF")
    val surfaceHigh = Color.parseColor(if (uiDark) "#262B31" else "#D9E0E5")
    val text = Color.parseColor(if (uiDark) "#E8EAED" else "#14181B")
    val sub = Color.parseColor(if (uiDark) "#9AA0A6" else "#5F6368")
    // Accent is tuned per theme so white text on it stays readable and it does not glare on dark surfaces.
    // The accent follows the chosen AOS wallpaper: Blue -> blue, Aurora -> violet; a custom photo keeps blue.
    // Dynamic Color: the system's Material You accent mixed with the theme's own, so every theme still looks like itself: the hue
    // meets halfway between the two (the short way round the colour wheel), brightness and saturation stay the theme's (they are
    // tuned for readability). A near-grey system palette (grey wallpaper, monochrome style) leaves the theme's colour as it is.
    private val themeAccent = Color.parseColor(if (uiDark) style.accentDark else style.accentLight)
    val accent = if (Fx.on(c, "dynamic_color")) dynamicAccent(c.getColor(if (uiDark) android.R.color.system_accent1_300 else android.R.color.system_accent1_600), themeAccent)
        else themeAccent

    private fun dynamicAccent(system: Int, theme: Int): Int {
        val s = FloatArray(3).also { Color.colorToHSV(system, it) }
        if (s[1] <= 0.3f) return theme
        val t = FloatArray(3).also { Color.colorToHSV(theme, it) }
        var d = s[0] - t[0]
        if (d > 180f) d -= 360f else if (d < -180f) d += 360f
        t[0] = (t[0] + d * 0.5f + 360f) % 360f
        return Color.HSVToColor(t)
    }
    // Text on the accent: white on dark accents, near-black on bright ones (yellow, mint, gold...) so it is always readable.
    val onAccent = if ((0.299 * Color.red(accent) + 0.587 * Color.green(accent) + 0.114 * Color.blue(accent)) / 255.0 > 0.62) Color.parseColor("#14181B") else Color.WHITE
    // Liquid glass: light, translucent fills with a bright rim; the rim is drawn by [glassShape].
    val glass = Color.parseColor(if (uiDark) "#80222830" else "#B3FFFFFF")
    /** Denser glass for panels that sit over busy content (Control Center). */
    val glassStrong = Color.parseColor(if (uiDark) "#C71B2026" else "#EBF8FAFB")
    /** Full-screen glass over the bare wallpaper (App Drawer). */
    val glassScreen = Color.parseColor(if (uiDark) "#B30E1114" else "#B3F1F3F2")
    val stroke = Color.parseColor(if (uiDark) "#40FFFFFF" else "#2E1B2530")
    /** Round glass behind dock icons. */
    val glassCircle = Color.parseColor(if (uiDark) "#33FFFFFF" else "#241B2530")
    /** Colour of the press-fill ripple on icons. */
    val pressFill = (accent and 0xFFFFFF) or 0x55000000
    val wallTop = Color.parseColor(if (uiDark) "#0E1114" else "#E6ECF8")
    val wallBottom = Color.parseColor(if (uiDark) "#14233F" else "#C7D7F5")
}

/** System Google Sans when the image ships it, otherwise the default sans (Roboto), at the given weight. */
fun appFont(weight: Int): Typeface =
    Typeface.create(Typeface.create("google-sans", Typeface.NORMAL), weight, false)

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.shape(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

/** Liquid glass panel: translucent top-lit gradient fill and a thin bright rim. Cheap: one GradientDrawable, no blur. */
fun Context.glassShape(p: Palette, radiusDp: Int, strong: Boolean = false) =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(lift(if (strong) p.glassStrong else p.glass, if (p.uiDark) 0.10f else 0.18f), if (strong) p.glassStrong else p.glass)).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(maxOf(1, dp(1)), p.stroke)
    }

/** Blends [c] towards white by [t], keeping its alpha. */
fun lift(c: Int, t: Float): Int {
    fun m(x: Int) = (x + (255 - x) * t).toInt()
    return Color.argb(Color.alpha(c), m(Color.red(c)), m(Color.green(c)), m(Color.blue(c)))
}

/** Shared motion constants: everything is short and decelerating so the UI feels instant. */
object Motion {
    // Scaled by the speed chosen in Settings > Effects.
    val FAST get() = Fx.dur(110L)
    val NORMAL get() = Fx.dur(160L)
    val easeOut = android.view.animation.DecelerateInterpolator(1.6f)
}

/**
 * Press effect for icons: the view dips slightly and an accent disc fills from the touch point (RippleDrawable, drawn by
 * the render thread, so it costs nothing on the UI thread). Clicks still go to the view's own listener.
 */
fun View.pressFx(p: Palette, round: Boolean = true) {
    val mask = GradientDrawable().apply { setColor(Color.BLACK); if (round) shape = GradientDrawable.OVAL else cornerRadius = 18f * resources.displayMetrics.density }
    val under = background
    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(p.pressFill), under, mask)
    isClickable = true
    val morph = Fx.on(context, "morph") && under is GradientDrawable
    val iconMorph = Fx.on(context, "icon_morph")
    val release = Fx.settle(context)
    setOnTouchListener { v, ev ->
        val icon = if (iconMorph) ((v as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.ImageView) else null
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(Motion.FAST).setInterpolator(Motion.easeOut).start()
                if (morph) morphShape(under as GradientDrawable, true)
                icon?.let { Fx.morphIcon(it, true) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(if (release is Fx.Spring) Fx.dur(380) else Motion.NORMAL).setInterpolator(release).start()
                if (morph) morphShape(under as GradientDrawable, false)
                icon?.let { Fx.morphIcon(it, false) }
            }
        }
        false
    }
}

/** Shape & colour morph: a pressed control rounds its corners further and its fill lightens, then eases back. */
private fun View.morphShape(g: GradientDrawable, pressed: Boolean) {
    val base = (getTag(R.id.fx_radius) as? Float) ?: g.cornerRadius.also { setTag(R.id.fx_radius, it) }
    val to = if (pressed) maxOf(base, height / 2f) else base
    android.animation.ValueAnimator.ofFloat(g.cornerRadius, to).apply {
        duration = Fx.dur(if (pressed) 140 else 300); interpolator = if (pressed) Motion.easeOut else Fx.settle(context)
        addUpdateListener { g.cornerRadius = it.animatedValue as Float; alpha = 1f - 0.12f * (it.animatedFraction.let { f -> if (pressed) f else 1f - f }) }
    }.start()
}

fun Context.circle(color: Int, strokeColor: Int? = null, strokeDp: Int = 2) =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

/** Frosted-white fill for Control Center controls: see-through, lighter than the panel. */
fun frostFill(p: Palette): Int = if (p.uiDark) 0x33FFFFFF else 0x1F1B2530

/** Liquid-glass panel: white, translucent, bright top rim fading down. */
fun Context.frostShape(p: Palette, radiusDp: Int) =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(if (p.uiDark) 0xF21A2029.toInt() else 0xF2FFFFFF.toInt(), if (p.uiDark) 0xE60E1218.toInt() else 0xD9FFFFFF.toInt())).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(maxOf(1, dp(1)), if (p.uiDark) 0x40FFFFFF else 0x401B2530)
    }
