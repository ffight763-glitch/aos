package org.anthezis.launcher

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build

/**
 * AOS icon theme: every app icon sits on the same rounded tile. Apps that ship a monochrome (themed) icon get it
 * tinted with the AOS accent; the rest keep their own artwork on the neutral tile.
 */
object AosIcons {
    /** Neutral tile tinted with the accent, so icon backgrounds follow the theme colour instead of plain grey. */
    private fun tile(p: Palette): Int {
        val f = if (p.dark) 0.26f else 0.16f
        fun mix(a: Int, b: Int) = (a + (b - a) * f).toInt()
        return android.graphics.Color.rgb(
            mix(android.graphics.Color.red(p.surfaceHigh), android.graphics.Color.red(p.accent)),
            mix(android.graphics.Color.green(p.surfaceHigh), android.graphics.Color.green(p.accent)),
            mix(android.graphics.Color.blue(p.surfaceHigh), android.graphics.Color.blue(p.accent)))
    }

    /** Greyscale, then multiplied by the accent: dark parts become deep accent, light parts bright accent. */
    private fun duotone(p: Palette): android.graphics.ColorMatrixColorFilter {
        val m = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        fun ch(v: Int) = (v / 255f * 1.2f).coerceAtMost(1f)
        m.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            ch(android.graphics.Color.red(p.accent)), 0f, 0f, 0f, 0f,
            0f, ch(android.graphics.Color.green(p.accent)), 0f, 0f, 0f,
            0f, 0f, ch(android.graphics.Color.blue(p.accent)), 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
        return android.graphics.ColorMatrixColorFilter(m)
    }

    /** The icon tile in the chosen shape (Manager, Style): squircle, circle, rounded square, square or teardrop. */
    private fun tileShape(ctx: Context, color: Int, sizeDp: Int, stroke: Int?, strokeDp: Int = 1): android.graphics.drawable.GradientDrawable {
        val mode = ctx.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getInt("icon_shape", 0)
        val f = when (mode) { 1 -> 0.5f; 2 -> 0.2f; 3 -> 0.06f; else -> 0.3f }
        val g = ctx.shape(color, (sizeDp * f).toInt(), stroke, strokeDp)
        if (mode == 4) {
            val r = ctx.dp((sizeDp * 0.5f).toInt()).toFloat(); val t = ctx.dp((sizeDp * 0.08f).toInt()).toFloat()
            g.cornerRadii = floatArrayOf(r, r, r, r, r, r, t, t)   // bottom-left corner stays sharp
        }
        return g
    }

    /**
     * Some apps (Privat24) ship their full-colour foreground as the "monochrome" layer: tinted, it turns into a solid blob.
     * A real themed glyph leaves most of the icon's safe zone transparent.
     */
    /** Answers per icon resource, so pages full of icons do not render each one again on every rebuild. */
    private val silhouette = java.util.WeakHashMap<Drawable.ConstantState, Boolean>()

    private fun isSilhouette(mono: Drawable): Boolean {
        val key = mono.constantState
        if (key != null) silhouette[key]?.let { return it }
        return measureSilhouette(mono).also { if (key != null) silhouette[key] = it }
    }

    private fun measureSilhouette(mono: Drawable): Boolean {
        val n = 54
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val d = mono.constantState?.newDrawable()?.mutate() ?: return true
        d.setBounds(0, 0, n, n); d.draw(android.graphics.Canvas(bmp))
        val inner = n / 6 until n - n / 6   // the 66/108 safe zone of an adaptive icon
        var opaque = 0; var total = 0; var colourful = 0
        val hsv = FloatArray(3)
        for (y in inner) for (x in inner) {
            total++
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.alpha(c) > 128) {
                opaque++
                android.graphics.Color.colorToHSV(c, hsv)
                if (hsv[1] > 0.3f && hsv[2] > 0.2f) colourful++
            }
        }
        bmp.recycle()
        // A themed glyph is plain (black/white/grey); a coloured layer is the app's real artwork reused.
        return opaque < total * 0.6f && colourful < opaque * 0.1f
    }

    /** Our own apps (org.anthezis.*) get the AOS outline style; every other app keeps its own original icon. */
    fun themed(ctx: Context, src: Drawable, p: Palette, sizeDp: Int, pkg: String? = null): Drawable {
        val ours = pkg?.startsWith("org.anthezis") == true
        if (src !is AdaptiveIconDrawable) {
            // Plain (non-adaptive) icon: its own artwork, inset on a tile.
            val art = src.constantState?.newDrawable()?.mutate() ?: src
            // Its own colours on the neutral tile: on the accent it would clash with (or vanish into) the artwork.
            return LayerDrawable(arrayOf(tileShape(ctx, tile(p), sizeDp, p.stroke), InsetDrawable(art, 0.16f)))
        }
        // Apps with a themed (monochrome) icon get the accent outline style; the rest keep their own picture.
        val mono = (if (ours && Build.VERSION.SDK_INT >= 33) src.monochrome else null)?.takeIf { isSilhouette(it) }
        return if (mono != null) {
            // Themed icon in the outline style: an accent rim around a faint accent tint, the silhouette in the accent itself,
            // so the glyph keeps its own look instead of sitting on a solid accent block.
            val fill = (p.accent and 0xFFFFFF) or (if (p.uiDark) 0x26000000 else 0x1F000000)
            LayerDrawable(arrayOf(tileShape(ctx, fill, sizeDp, p.accent, 2),
                InsetDrawable(mono.mutate().also { it.setTint(p.accent) }, -0.1f)))
        } else {
            // No monochrome art: the app's own picture with its own background (Brave, Gmail, banks...), no AOS tile behind
            // it; its artwork was drawn for that background, not for the accent.
            ownIcon(ctx, src, sizeDp)
        }
    }

    /** The app's own adaptive icon (background and foreground) cut to the chosen AOS tile shape. */
    private fun ownIcon(ctx: Context, src: AdaptiveIconDrawable, sizeDp: Int): Drawable {
        val px = ctx.dp(sizeDp).coerceAtLeast(48)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        tileShape(ctx, android.graphics.Color.BLACK, sizeDp, null).apply { setBounds(0, 0, px, px) }.draw(c)   // the mask
        c.saveLayer(0f, 0f, px.toFloat(), px.toFloat(), android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        })
        // Adaptive layers are 108 units with the middle 72 visible: draw them a quarter larger on every side.
        val e = px / 4
        for (layer in listOf(src.background, src.foreground)) layer?.constantState?.newDrawable()?.mutate()?.apply {
            setBounds(-e, -e, px + e, px + e); draw(c)
        }
        c.restore()
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
    }
}

/** User profile and spaces ("Space 0" is the main profile). Stored in the launcher until the firmware provides multi-user. */
object Profiles {
    private fun prefs(c: Context) = c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)

    fun userName(c: Context): String = prefs(c).getString("user_name", "").orEmpty().ifBlank { "User" }
    fun setUserName(c: Context, name: String) = prefs(c).edit().putString("user_name", name.trim()).apply()

    fun spaces(c: Context): List<String> =
        prefs(c).getString("spaces", "Main").orEmpty().split("\n").filter { it.isNotBlank() }

    fun addSpace(c: Context) {
        val next = spaces(c) + "Space ${spaces(c).size}"
        prefs(c).edit().putString("spaces", next.joinToString("\n")).apply()
    }

    fun active(c: Context): Int = prefs(c).getInt("space_active", 0).coerceIn(0, spaces(c).lastIndex)
    fun setActive(c: Context, i: Int) = prefs(c).edit().putInt("space_active", i).apply()
}
