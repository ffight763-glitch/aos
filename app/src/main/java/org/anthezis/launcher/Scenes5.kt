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

/** Themes V3 (1080x2400, night and day): Sakura, Desert, Jungle and Circuit. */
internal object Scenes5 {
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

    /** Sakura: a blossoming branch over a pink sky and a full moon or sun. Petals fall when animated. */
    fun sakura(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#1A0F2E", "#5A2358") else canvasWith("#FFF0F5", "#FFB7C9")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 870f, 200f, 300f, if (dark) 0xFFE0F0 else 0xFFFFFF, 130)
        p.color = if (dark) Color.parseColor("#FFF5FA") else Color.parseColor("#FFFFFF")
        c.drawCircle(870f, 200f, 105f, p)
        // The branch.
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.color = Color.parseColor(if (dark) "#2A1220" else "#6B4A44")
        p.strokeWidth = 34f; c.drawPath(Path().apply { moveTo(1100f, 180f); quadTo(760f, 320f, 520f, 300f); quadTo(360f, 290f, 260f, 380f) }, p)
        p.strokeWidth = 20f; c.drawPath(Path().apply { moveTo(760f, 310f); quadTo(700f, 500f, 560f, 560f) }, p)
        p.strokeWidth = 16f; c.drawPath(Path().apply { moveTo(620f, 300f); quadTo(600f, 180f, 470f, 140f) }, p)
        p.style = Paint.Style.FILL
        // Blossoms along the branch.
        val rnd = Random(4)
        val tones = if (dark) listOf("#FF9CC8", "#FFC2DE", "#E57AB0") else listOf("#FF8FB8", "#FFC2D8", "#FFFFFF")
        for (i in 0 until 130) {
            val t = rnd.nextFloat()
            val bx = 1080f - t * 880f + rnd.nextInt(120) - 60f
            val by = 190f + t * 190f + rnd.nextInt(200) - 80f
            p.color = Color.parseColor(tones[rnd.nextInt(tones.size)]); p.alpha = 190 + rnd.nextInt(60)
            c.drawCircle(bx, by, 14f + rnd.nextInt(18), p)
        }
        // Ground of soft hills.
        p.alpha = 255
        for ((i, col) in listOf(if (dark) "#3A1A44" else "#F7A9C2", if (dark) "#2A1033" else "#EE8DB0").withIndex()) {
            p.color = Color.parseColor(col)
            c.drawPath(Path().apply {
                moveTo(0f, H.toFloat()); lineTo(0f, 1900f + i * 220f)
                var x = 0f; while (x <= W) { lineTo(x, 1900f + i * 220f + sin(x / W * 3 * PI + i).toFloat() * 80f); x += 12f }
                lineTo(W.toFloat(), H.toFloat()); close()
            }, p)
        }
        return bmp
    }

    /** Desert: a huge sun or moon, layered dunes and cactus silhouettes. Sand streaks blow across when animated. */
    fun desert(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#0C0A24", "#6A2E4F") else canvasWith("#FFF1C9", "#F6B26B")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 800f, 875f, 360f, if (dark) 0xFFB27A else 0xFFE59A, 130)   // sun in the gap between the clock and the icons
        p.color = if (dark) Color.parseColor("#FFE3C2") else Color.parseColor("#FFC24D")
        c.drawCircle(800f, 875f, 112f, p)
        val cols = if (dark) listOf("#5A2447", "#3C1836", "#220C25") else listOf("#F0A25A", "#D9823F", "#B5602B")
        for ((i, col) in cols.withIndex()) {
            val base = 1450f + i * 260f
            p.color = Color.parseColor(col)
            c.drawPath(Path().apply {
                moveTo(0f, H.toFloat()); lineTo(0f, base)
                var x = 0f; while (x <= W) { lineTo(x, base + sin(x / W * 2.4 * PI + i * 1.7).toFloat() * (110f - i * 14f)); x += 12f }
                lineTo(W.toFloat(), H.toFloat()); close()
            }, p)
        }
        // Cacti.
        p.color = Color.parseColor(if (dark) "#12081B" else "#5C3A1E"); p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        for ((cx, cy, h) in listOf(Triple(180f, 2100f, 260f), Triple(860f, 2180f, 200f))) {
            p.strokeWidth = 26f; c.drawLine(cx, cy, cx, cy - h, p)
            p.strokeWidth = 18f
            c.drawPath(Path().apply { moveTo(cx, cy - h * 0.5f); lineTo(cx - 52f, cy - h * 0.5f); lineTo(cx - 52f, cy - h * 0.78f) }, p)
            c.drawPath(Path().apply { moveTo(cx, cy - h * 0.38f); lineTo(cx + 50f, cy - h * 0.38f); lineTo(cx + 50f, cy - h * 0.64f) }, p)
        }
        p.style = Paint.Style.FILL
        return bmp
    }

    /** Jungle: big layered leaves on both sides over a deep green gradient. Rain and fireflies are animated. */
    fun jungle(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#04140C", "#0F4A32") else canvasWith("#E6F9D0", "#2E8B57")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 540f, 700f, 640f, if (dark) 0x3DDC97 else 0xFFFFFF, if (dark) 60 else 130)
        val rnd = Random(12)
        val greens = if (dark) listOf("#0B3B28", "#0F5A3A", "#146B46", "#0A2E20") else listOf("#2E9E5B", "#43B26C", "#1F7F49", "#5CC77F")
        for (side in 0..1) for (i in 0 until 9) {
            val y = 500f + i * 190f + rnd.nextInt(60)
            val len = 380f + rnd.nextInt(260)
            val ang = (if (side == 0) 12f else 168f) + rnd.nextInt(40) - 20f
            p.color = Color.parseColor(greens[rnd.nextInt(greens.size)]); p.alpha = 235
            c.save(); c.translate(if (side == 0) -40f else W + 40f, y); c.rotate(ang)
            c.drawOval(RectF(0f, -46f, len, 46f), p)
            c.restore()
        }
        p.alpha = 255
        p.color = Color.parseColor(if (dark) "#062016" else "#1F6B3E")
        c.drawRect(0f, 2250f, W.toFloat(), H.toFloat(), p)
        return bmp
    }

    /** Circuit: dark teal board with right-angle traces and nodes; light pulses run along the traces when animated. */
    fun circuit(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#02121A", "#04303C") else canvasWith("#EAF8F8", "#B9E4E8")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(2)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
        p.color = if (dark) Color.argb(120, 40, 220, 230) else Color.argb(130, 20, 130, 150)
        for (i in 0 until 16) {
            val y = 200f + i * 150f
            c.drawLine(0f, y, 1080f, y, p.apply { alpha = 60 })
            // A trace that steps down and continues.
            val x1 = 80f + rnd.nextInt(500); val x2 = x1 + 160f + rnd.nextInt(300)
            p.alpha = 150
            c.drawPath(Path().apply { moveTo(x1, y); lineTo(x2, y); lineTo(x2 + 60f, y + 60f); lineTo(1080f, y + 60f) }, p)
        }
        p.style = Paint.Style.FILL
        for (i in 0 until 40) { p.color = if (dark) Color.argb(200, 80, 240, 255) else Color.argb(200, 10, 120, 140); c.drawCircle(rnd.nextInt(W).toFloat(), 200f + rnd.nextInt(16) * 150f, 9f, p) }
        glow(c, 540f, 1300f, 700f, if (dark) 0x16E0F0 else 0xFFFFFF, if (dark) 50 else 110)
        return bmp
    }
}
