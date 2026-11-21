package org.anthezis.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.sin

/**
 * Soft flowing-wave wallpapers in the spirit of the smooth gradient wallpapers of early-2010s phones.
 * Original artwork drawn in code: layered translucent waves over a vertical gradient.
 */
internal object Waves {
    private const val W = 1080
    private const val H = 2400

    private fun draw(top: String, bottom: String, layers: List<Triple<String, Float, Float>>, dark: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), Color.parseColor(top), Color.parseColor(bottom), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null
        // Each layer: colour, baseline (0..1 of height), phase. Lower layers are denser.
        layers.forEachIndexed { i, (col, base, phase) ->
            val path = Path()
            val y0 = H * base
            path.moveTo(0f, H.toFloat())
            path.lineTo(0f, y0)
            var x = 0f
            while (x <= W) {
                val a = sin((x / W * 2 * PI + phase).toFloat()) * 120f + sin((x / W * 4 * PI + phase * 1.7).toFloat()) * 46f
                path.lineTo(x, y0 + a)
                x += 12f
            }
            path.lineTo(W.toFloat(), H.toFloat())
            path.close()
            paint.color = Color.parseColor(col)
            paint.alpha = (if (dark) 120 else 150) + i * 18
            c.drawPath(path, paint)
        }
        return bmp
    }

    /** Sunrise: pink, orange and violet waves. */
    fun sunrise(dark: Boolean): Bitmap = draw(
        if (dark) "#150B2B" else "#FFD3B8", if (dark) "#3A1046" else "#F7769F",
        listOf(Triple("#FF9E6B", 0.52f, 0.4f), Triple("#FF5F9E", 0.62f, 1.9f), Triple("#B04AD6", 0.72f, 3.1f), Triple("#5B2FB8", 0.83f, 4.4f)), dark)

    /** Lagoon: blue and teal waves. */
    fun lagoon(dark: Boolean): Bitmap = draw(
        if (dark) "#06182B" else "#C9ECFF", if (dark) "#0B3A57" else "#4FB4E8",
        listOf(Triple("#5CD6D0", 0.5f, 0.9f), Triple("#2F9BE0", 0.62f, 2.4f), Triple("#2563EB", 0.74f, 3.6f), Triple("#16348F", 0.85f, 5.0f)), dark)
}
