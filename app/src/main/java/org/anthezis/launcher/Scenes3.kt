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
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Third set of painted AOS scenes (1080x2400): Synthwave, Ocean, Matrix and Meadow. Night and day versions of each. */
internal object Scenes3 {
    private const val W = 1080
    private const val H = 2400

    private fun canvasWith(top: String, mid: String, bottom: String): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(Color.parseColor(top), Color.parseColor(mid), Color.parseColor(bottom)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        return bmp to c
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, rgb: Int, a: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(x, y, r, Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Color.argb(0, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
    }

    /** Synthwave: a striped retro sun over dark mountains and a perspective grid (the grid scrolls when animated). */
    fun synthwave(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#12002E", "#5A1A8A", "#FF4F8B") else canvasWith("#FFE0B8", "#FF9FB0", "#C48BFF")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 540f, 1180f, 560f, if (dark) 0xFF4F8B else 0xFFD08A, 120)
        // The sun, cut by horizontal slits toward the bottom.
        p.shader = LinearGradient(0f, 880f, 0f, 1480f, Color.parseColor("#FFE066"), Color.parseColor("#FF3D81"), Shader.TileMode.CLAMP)
        c.drawCircle(540f, 1180f, 300f, p); p.shader = null
        p.color = Color.parseColor(if (dark) "#5A1A8A" else "#FF9FB0")
        for (i in 0 until 7) { val y = 1230f + i * 36f; c.drawRect(230f, y, 850f, y + 6f + i * 2.4f, p) }
        // Mountains.
        p.color = Color.parseColor(if (dark) "#1A0540" else "#8A5BC0")
        c.drawPath(Path().apply { moveTo(0f, 1500f); lineTo(160f, 1330f); lineTo(300f, 1450f); lineTo(430f, 1300f); lineTo(560f, 1500f); lineTo(0f, 1500f); close() }, p)
        c.drawPath(Path().apply { moveTo(520f, 1500f); lineTo(700f, 1320f); lineTo(860f, 1440f); lineTo(1000f, 1340f); lineTo(1080f, 1500f); close() }, p)
        // Floor with a perspective grid.
        p.color = Color.parseColor(if (dark) "#10002A" else "#6E3FA8")
        c.drawRect(0f, 1500f, W.toFloat(), H.toFloat(), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.parseColor(if (dark) "#FF4FD8" else "#FFE7FA")
        for (i in -9..9) c.drawLine(540f + i * 40f, 1500f, 540f + i * 190f, H.toFloat(), p)
        return bmp
    }

    /** Ocean: light rays from the surface, deep blue water and a sandy floor with weeds. Bubbles rise when animated. */
    fun ocean(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#0A5C8A", "#06304F", "#02111F") else canvasWith("#C6F3FF", "#4FB6E8", "#1E6FB5")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 5) {
            val x = 100f + i * 220f
            p.shader = LinearGradient(x, 0f, x + 120f, 1700f, Color.argb(if (dark) 70 else 110, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawPath(Path().apply { moveTo(x, 0f); lineTo(x + 130f, 0f); lineTo(x + 330f, 1700f); lineTo(x + 40f, 1700f); close() }, p)
        }
        p.shader = null
        // Sand and rocks.
        p.color = Color.parseColor(if (dark) "#2A4A55" else "#EBD9A8")
        c.drawRoundRect(RectF(-100f, 2120f, W + 100f, H + 200f), 420f, 420f, p)
        p.color = Color.parseColor(if (dark) "#173642" else "#BFA878")
        c.drawOval(RectF(120f, 2160f, 420f, 2330f), p); c.drawOval(RectF(700f, 2200f, 1000f, 2380f), p)
        // Weeds.
        val rnd = Random(5)
        p.style = Paint.Style.STROKE; p.strokeWidth = 14f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 14) {
            val x = 40f + i * 76f; val h = 160f + rnd.nextInt(220)
            p.color = Color.parseColor(if (dark) "#1E7F6A" else "#2FAE7A")
            c.drawPath(Path().apply { moveTo(x, 2200f); quadTo(x - 30f + rnd.nextInt(60), 2200f - h / 2, x + rnd.nextInt(50) - 25f, 2200f - h) }, p)
        }
        return bmp
    }

    /** Matrix: a dark green field with faint columns; the falling code is animated. Day: a pale green screen. */
    fun matrix(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#000A04", "#01240F", "#003B14") else canvasWith("#F2FBF3", "#CDEBD0", "#9ED4A4")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(9)
        p.color = if (dark) Color.argb(40, 40, 255, 120) else Color.argb(50, 20, 120, 60)
        for (i in 0 until 30) for (j in 0 until 40) if (rnd.nextInt(3) == 0) c.drawRect(10f + i * 36f, 40f + j * 58f, 10f + i * 36f + 14f, 40f + j * 58f + 22f, p)
        glow(c, 540f, 1200f, 700f, if (dark) 0x14FF78 else 0xFFFFFF, if (dark) 40 else 90)
        return bmp
    }

    /** Meadow: rolling hills with flowers under a sun or moon. Fireflies (night) or drifting pollen (day) are animated. */
    fun meadow(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#0B1B3A", "#173A5A", "#1E5A4A") else canvasWith("#BFE8FF", "#E7F7C9", "#9FD97A")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 840f, 200f, 300f, if (dark) 0xDDEBFF else 0xFFF2B0, 130)
        p.color = if (dark) Color.parseColor("#F1F6FF") else Color.parseColor("#FFE98A")
        c.drawCircle(840f, 200f, 84f, p)
        fun hill(col: String, y: Float, amp: Float, ph: Float) {
            val path = Path().apply {
                moveTo(0f, H.toFloat()); lineTo(0f, y)
                var x = 0f
                while (x <= W) { lineTo(x, y + sin(x / W * 2 * PI + ph).toFloat() * amp); x += 12f }
                lineTo(W.toFloat(), H.toFloat()); close()
            }
            p.color = Color.parseColor(col); c.drawPath(path, p)
        }
        hill(if (dark) "#1F5A55" else "#B8E68F", 1500f, 90f, 0.6f)
        hill(if (dark) "#17493F" else "#8FD36B", 1760f, 110f, 2.2f)
        hill(if (dark) "#0F3A2F" else "#63BC4D", 2030f, 100f, 4.0f)
        // Flowers.
        val rnd = Random(21)
        val cols = listOf("#FF7AA8", "#FFD84D", "#FFFFFF", "#B58CFF")
        for (i in 0 until 60) {
            p.color = Color.parseColor(cols[rnd.nextInt(cols.size)])
            p.alpha = if (dark) 180 else 255
            c.drawCircle(rnd.nextInt(W).toFloat(), 1850f + rnd.nextInt(520), 6f + rnd.nextInt(5), p)
        }
        return bmp
    }
}
