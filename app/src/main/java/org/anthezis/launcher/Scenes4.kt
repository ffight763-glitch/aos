package org.anthezis.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.sin

/** Bold two-colour AOS scenes (1080x2400): Inferno (red-orange), Hornet (black-yellow) and Royal (deep blue). */
internal object Scenes4 {
    private const val W = 1080
    private const val H = 2400

    private fun canvasWith(top: String, bottom: String): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), Color.parseColor(top), Color.parseColor(bottom), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        return bmp to c
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, rgb: Int, a: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(x, y, r, Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Color.argb(0, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
    }

    /** Inferno: a hot red-to-orange sky, a huge sun and layered flame ridges. */
    fun inferno(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#2A0606", "#C2300F") else canvasWith("#FFE2C2", "#FF6A2B")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 800f, 875f, 380f, if (dark) 0xFF7A1A else 0xFFB066, 150)   // sun in the gap between the clock and the icons
        p.color = Color.parseColor(if (dark) "#FFB347" else "#FFE08A")
        c.drawCircle(800f, 875f, 112f, p)
        for ((i, col) in listOf("#B3200C", "#8E1408", "#5E0C05").withIndex()) {
            val base = 1500f + i * 270f
            val path = Path().apply {
                moveTo(0f, H.toFloat()); lineTo(0f, base)
                var x = 0f
                while (x <= W) { lineTo(x, base + sin(x / W * 5 * PI + i * 1.3).toFloat() * (70f - i * 12f) + sin(x / W * 11 * PI).toFloat() * 24f); x += 10f }
                lineTo(W.toFloat(), H.toFloat()); close()
            }
            p.color = Color.parseColor(if (dark) col else listOf("#E24A1A", "#C2360F", "#8F2208")[i]); c.drawPath(path, p)
        }
        return bmp
    }

    /** Hornet: black and yellow, diagonal hazard stripes at the bottom and a honeycomb of faint hexagons. */
    fun hornet(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#0B0B0B", "#1A1500") else canvasWith("#FFF4B8", "#FFD83D")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Honeycomb.
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = if (dark) Color.argb(60, 255, 205, 0) else Color.argb(70, 20, 20, 20)
        val r = 70f
        var row = 0; var y = 40f
        while (y < 1700f) {
            var x = if (row % 2 == 0) 0f else r * 1.5f
            while (x < W + r) {
                val path = Path()
                for (k in 0 until 6) { val a = (PI / 3 * k).toFloat(); val px = x + r * kotlin.math.cos(a); val py = y + r * kotlin.math.sin(a); if (k == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                path.close(); c.drawPath(path, p); x += r * 3f
            }
            y += r * 0.87f; row++
        }
        p.style = Paint.Style.FILL
        glow(c, 540f, 900f, 620f, if (dark) 0xFFC800 else 0xFFFFFF, if (dark) 60 else 130)
        // Hazard band.
        p.color = if (dark) Color.parseColor("#FFC400") else Color.parseColor("#141414")
        c.save(); c.clipRect(0f, 2060f, W.toFloat(), 2200f)
        var sx = -300f
        while (sx < W + 300f) { c.drawPath(Path().apply { moveTo(sx, 2200f); lineTo(sx + 70f, 2060f); lineTo(sx + 150f, 2060f); lineTo(sx + 80f, 2200f); close() }, p); sx += 190f }
        c.restore()
        return bmp
    }

    /** Royal: deep blue with large soft circles; ripples run across it when animated. */
    fun royal(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#04103A", "#0F3FA8") else canvasWith("#DCE8FF", "#4C7DFF")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 820f, 700f, 640f, if (dark) 0x4D7BFF else 0xFFFFFF, if (dark) 120 else 150)
        glow(c, 200f, 1700f, 560f, if (dark) 0x1E4FD8 else 0xA8C2FF, 120)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        for (i in 1..7) { p.color = Color.argb(if (dark) 46 else 70, 255, 255, 255); c.drawCircle(540f, 1500f, 150f * i, p) }
        return bmp
    }
}
