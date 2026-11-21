package org.anthezis.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.random.Random

/** Second set of painted AOS scenes (1080x2400): Neon City, Winter and Galaxy. Each has a night and a day version. */
internal object Scenes2 {
    private const val W = 1080
    private const val H = 2400

    private fun base(dark: Boolean, nightTop: String, nightBottom: String, dayTop: String, dayBottom: String): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), Color.parseColor(if (dark) nightTop else dayTop), Color.parseColor(if (dark) nightBottom else dayBottom), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        return bmp to c
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, rgb: Int, a: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(x, y, r, Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Color.argb(0, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
    }

    /** Neon City: layered skyline with lit windows and neon signs; the rain is animated on top. */
    fun neon(dark: Boolean): Bitmap {
        val (bmp, c) = base(dark, "#0A0620", "#2B0B4A", "#FBE7FF", "#C9B2FF")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(7)
        glow(c, 760f, 900f, 520f, if (dark) 0xFF4FD8 else 0xFFB0F0, if (dark) 110 else 120)
        glow(c, 280f, 1200f, 460f, if (dark) 0x42D6FF else 0xA8E8FF, if (dark) 90 else 110)
        // Three skyline layers, far to near.
        for ((layer, col) in listOf("#1B0F3D" to 1500f, "#130A2E" to 1700f, "#0B0620" to 1900f).withIndex().map { (i, v) -> i to v }) {
            val shade = if (dark) col.first else listOf("#B7A4E8", "#9C86D8", "#7F68C4")[layer]
            p.color = Color.parseColor(shade)
            var x = -20f
            while (x < W) {
                val w = 90f + rnd.nextInt(90); val h = col.second - 260f * (2 - layer) * 0.0f - rnd.nextInt(520) - layer * 40f
                c.drawRect(x, h, x + w, H.toFloat(), p)
                // Windows.
                if (dark) {
                    p.color = Color.argb(200, 255, 220, 140)
                    var wy = h + 24f
                    while (wy < H - 60f) {
                        var wx = x + 14f
                        while (wx < x + w - 18f) { if (rnd.nextInt(5) == 0) c.drawRect(wx, wy, wx + 8f, wy + 12f, p); wx += 22f }
                        wy += 34f
                    }
                    p.color = Color.parseColor(shade)
                }
                x += w + 6f
            }
        }
        // Neon signs.
        for ((sx, sy, rgb) in listOf(Triple(220f, 1560f, 0xFF3DDC), Triple(620f, 1470f, 0x3DE0FF), Triple(880f, 1640f, 0xFFD23D))) {
            glow(c, sx, sy, 110f, rgb, 150)
            p.color = Color.argb(255, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            c.drawRoundRect(RectF(sx - 54f, sy - 8f, sx + 54f, sy + 8f), 8f, 8f, p)
        }
        // Wet street reflection.
        p.shader = LinearGradient(0f, 2150f, 0f, H.toFloat(), Color.argb(0, 255, 120, 240), Color.argb(if (dark) 90 else 60, 255, 120, 240), Shader.TileMode.CLAMP)
        c.drawRect(0f, 2150f, W.toFloat(), H.toFloat(), p)
        return bmp
    }

    /** Winter: moon or sun, snowy mountains and a row of pines. Snowflakes fall on top. */
    fun snow(dark: Boolean): Bitmap {
        val (bmp, c) = base(dark, "#07121F", "#1B3F63", "#EAF4FF", "#B4D3F0")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 880f, 200f, 260f, if (dark) 0xCFE6FF else 0xFFF3C4, 120)
        p.color = if (dark) Color.parseColor("#F4F8FF") else Color.parseColor("#FFF6D6")
        c.drawCircle(880f, 200f, 78f, p)
        // Mountains.
        fun mountain(col: String, pts: List<Pair<Float, Float>>) {
            val path = Path().apply { moveTo(0f, H.toFloat()); for ((x, y) in pts) lineTo(x, y); lineTo(W.toFloat(), H.toFloat()); close() }
            p.color = Color.parseColor(col); c.drawPath(path, p)
        }
        mountain(if (dark) "#2B5B88" else "#C9DDF2", listOf(0f to 1250f, 230f to 1000f, 430f to 1220f, 650f to 930f, 900f to 1210f, 1080f to 1080f))
        mountain(if (dark) "#1E4468" else "#AFC9E4", listOf(0f to 1450f, 300f to 1190f, 560f to 1440f, 830f to 1130f, 1080f to 1400f))
        // Snow field.
        p.color = if (dark) Color.parseColor("#D8E6F5") else Color.parseColor("#FFFFFF")
        c.drawRoundRect(RectF(-80f, 1700f, W + 80f, H + 80f), 400f, 400f, p)
        // Pines.
        val rnd = Random(11)
        for (i in 0 until 9) {
            val x = 40f + i * 125f + rnd.nextInt(30); val base = 1760f + rnd.nextInt(180); val h = 300f + rnd.nextInt(220)
            p.color = Color.parseColor(if (dark) "#0B2438" else "#2F6B58")
            for (t in 0 until 3) {
                val top = base - h + t * h * 0.28f; val half = 60f + t * 28f
                c.drawPath(Path().apply { moveTo(x, top); lineTo(x - half, top + h * 0.42f); lineTo(x + half, top + h * 0.42f); close() }, p)
            }
            p.color = Color.parseColor("#5A3B2A"); c.drawRect(x - 9f, base, x + 9f, base + 38f, p)
        }
        return bmp
    }

    /** Galaxy: nebula clouds, a ringed planet and a field of stars. Comets and twinkling are animated. */
    fun space(dark: Boolean): Bitmap {
        val (bmp, c) = base(dark, "#05050F", "#1A1038", "#EDEBFF", "#B8B0F5")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 300f, 700f, 620f, if (dark) 0x6A3CFF else 0xB9A8FF, if (dark) 120 else 110)
        glow(c, 840f, 1300f, 560f, if (dark) 0xFF3CA8 else 0xFFB8DC, if (dark) 95 else 100)
        glow(c, 420f, 1900f, 520f, if (dark) 0x2AA8FF else 0xA8DCFF, if (dark) 80 else 90)
        val rnd = Random(3)
        p.color = if (dark) Color.WHITE else Color.parseColor("#8C84D9")
        for (i in 0 until 160) { p.alpha = 60 + rnd.nextInt(180); c.drawCircle(rnd.nextInt(W).toFloat(), rnd.nextInt(H).toFloat(), 1f + rnd.nextFloat() * 2.2f, p) }
        // Planet with a ring.
        p.alpha = 255
        c.save(); c.translate(0f, -140f); c.rotate(-18f, 760f, 1010f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 18f; p.color = Color.argb(if (dark) 150 else 120, 255, 210, 160)
        c.drawOval(RectF(560f, 940f, 960f, 1080f), p)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(660f, 910f, 860f, 1110f, Color.parseColor(if (dark) "#FFB27A" else "#FFCFA8"), Color.parseColor(if (dark) "#B04A8A" else "#D98AB0"), Shader.TileMode.CLAMP)
        c.drawCircle(760f, 1010f, 110f, p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 18f; p.color = Color.argb(if (dark) 170 else 140, 255, 220, 175)
        c.drawArc(RectF(560f, 940f, 960f, 1080f), 0f, 180f, false, p)
        c.restore()
        return bmp
    }
}
