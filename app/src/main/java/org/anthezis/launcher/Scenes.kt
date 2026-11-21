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

/** Painted wallpaper scenes for the AOS styles (night and day), drawn in code like Forest Owl and Blue. */
internal object Scenes {
    private const val W = 1080
    private const val H = 2400

    private class Ctx(val dark: Boolean) {
        val bmp: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun col(night: String, day: String) = Color.parseColor(if (dark) night else day)
        fun sky(nightTop: String, nightBottom: String, dayTop: String, dayBottom: String) {
            paint.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), col(nightTop, dayTop), col(nightBottom, dayBottom), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint); paint.shader = null
        }
        fun glow(x: Float, y: Float, r: Float, rgb: Int, alpha: Int) {
            paint.shader = RadialGradient(x, y, r, Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb)),
                Color.argb(0, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint); paint.shader = null
        }
        fun disc(x: Float, y: Float, r: Float, color: Int) { paint.color = color; c.drawCircle(x, y, r, paint) }
        fun stars(seed: Long, n: Int, maxY: Float) {
            if (!dark) return
            val rnd = java.util.Random(seed)
            paint.color = Color.argb(210, 255, 255, 255)
            repeat(n) { c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * maxY, 1.5f + rnd.nextFloat() * 2.2f, paint) }
        }
        /** A rolling hill band: base line with a sine wave on top, filled down to the bottom edge. */
        fun hills(base: Float, amp: Float, waves: Float, phase: Float, color: Int) {
            val path = Path().apply {
                moveTo(0f, H.toFloat()); lineTo(0f, base)
                var x = 0f
                while (x <= W) { lineTo(x, base - amp * sin(x / W * 2 * PI.toFloat() * waves + phase)); x += 16f }
                lineTo(W.toFloat(), H.toFloat()); close()
            }
            paint.color = color; c.drawPath(path, paint)
        }
        fun pine(x: Float, base: Float, height: Float, width: Float, color: Int) {
            paint.color = color
            for (i in 0 until 4) {
                val top = base - height + i * height * 0.2f
                val bottom = base - height * (0.62f - i * 0.18f).coerceAtLeast(0.05f) + height * 0.25f
                val half = width * (0.38f + i * 0.2f) / 2f
                c.drawPath(Path().apply { moveTo(x, top); lineTo(x - half, bottom); lineTo(x + half, bottom); close() }, paint)
            }
            c.drawRect(x - width * 0.03f, base - height * 0.1f, x + width * 0.03f, base + 30f, paint)
        }
        fun mist(fromY: Float) {
            paint.shader = LinearGradient(0f, fromY, 0f, H.toFloat(), Color.argb(0, 255, 255, 255), Color.argb(if (dark) 36 else 80, 235, 245, 255), Shader.TileMode.CLAMP)
            c.drawRect(0f, fromY, W.toFloat(), H.toFloat(), paint); paint.shader = null
        }
    }

    /** Aurora: violet and teal ribbons of light over snowy mountains and a pine shore. */
    fun aurora(dark: Boolean): Bitmap {
        val s = Ctx(dark)
        s.sky("#070618", "#112A4E", "#EAE5FF", "#BBD8FF")
        s.stars(11, 70, H * 0.45f)
        // Ribbons.
        val ribbons = listOf(Triple(0.22f, "#35E0B5", 0.9f), Triple(0.30f, "#8B7CF6", 1.3f), Triple(0.38f, "#4DB5F2", 0.7f))
        for ((k, r) in ribbons.withIndex()) {
            val (y0, hex, waves) = r
            val base = H * y0; val top = Color.parseColor(hex)
            val path = Path().apply {
                moveTo(0f, base)
                var x = 0f
                while (x <= W) { lineTo(x, base + 90 * sin(x / W * 2 * PI.toFloat() * waves + k)); x += 12f }
                var x2 = W.toFloat()
                while (x2 >= 0) { lineTo(x2, base + 460 + 90 * sin(x2 / W * 2 * PI.toFloat() * waves + k + 1.2f)); x2 -= 12f }
                close()
            }
            s.paint.shader = LinearGradient(0f, base, 0f, base + 460, Color.argb(if (dark) 150 else 110, Color.red(top), Color.green(top), Color.blue(top)),
                Color.argb(0, Color.red(top), Color.green(top), Color.blue(top)), Shader.TileMode.CLAMP)
            s.c.drawPath(path, s.paint); s.paint.shader = null
        }
        s.hills(H * 0.70f, 150f, 1.3f, 0.4f, s.col("#0A1B3C", "#C7D7F2"))
        s.hills(H * 0.76f, 110f, 1.8f, 2.0f, s.col("#07142E", "#A9BFE6"))
        for ((x, hgt) in listOf(60f to 520f, 300f to 420f, 560f to 560f, 840f to 460f, 1040f to 520f)) s.pine(x, H * 0.90f, hgt, 280f, s.col("#030A1B", "#6F8AC0"))
        s.mist(H * 0.82f)
        return s.bmp
    }

    /** Ember: a huge low sun over layered desert dunes with a lone cactus. */
    fun ember(dark: Boolean): Bitmap {
        val s = Ctx(dark)
        s.sky("#1A0A14", "#7A2A1A", "#FFE6D2", "#FFB98A")
        s.stars(23, 40, H * 0.3f)
        val sx = W * 0.74f; val sy = H * 0.365f   // in the gap between the clock and the icons
        s.glow(sx, sy, 420f, 0xFF8A4A, if (dark) 170 else 140)
        s.disc(sx, sy, 112f, s.col("#FFB37A", "#FFF3E0"))
        s.hills(H * 0.62f, 90f, 1.2f, 0.3f, s.col("#5A1F18", "#F0A878"))
        s.hills(H * 0.70f, 130f, 1.0f, 2.2f, s.col("#3D1512", "#E58F63"))
        s.hills(H * 0.80f, 150f, 0.8f, 4.0f, s.col("#240C0C", "#C96F4C"))
        // Cactus.
        val cx = W * 0.25f; val cb = H * 0.79f
        s.paint.color = s.col("#120606", "#6E3A2A")
        s.c.drawRoundRect(cx - 24f, cb - 330f, cx + 24f, cb, 24f, 24f, s.paint)
        s.c.drawRoundRect(cx - 120f, cb - 240f, cx - 76f, cb - 130f, 22f, 22f, s.paint); s.c.drawRect(cx - 120f, cb - 150f, cx - 24f, cb - 118f, s.paint)
        s.c.drawRoundRect(cx + 76f, cb - 280f, cx + 120f, cb - 170f, 22f, 22f, s.paint); s.c.drawRect(cx + 24f, cb - 190f, cx + 120f, cb - 158f, s.paint)
        s.mist(H * 0.86f)
        return s.bmp
    }

    /** Forest: soft green hills in the morning haze with a small cabin whose window glows. */
    fun forest(dark: Boolean): Bitmap {
        val s = Ctx(dark)
        s.sky("#06161A", "#16423A", "#E4F6EE", "#A9DCC4")
        s.stars(31, 50, H * 0.4f)
        s.glow(W * 0.74f, H * 0.16f, 400f, 0xF3F5D0, if (dark) 110 else 150)
        s.disc(W * 0.74f, H * 0.08f, 140f, s.col("#F2F4CF", "#FFFFFF"))
        s.hills(H * 0.58f, 130f, 1.1f, 0.5f, s.col("#0F3A33", "#B9E3CD"))
        s.hills(H * 0.66f, 110f, 1.6f, 2.4f, s.col("#0B2F2A", "#8FD0B0"))
        for ((x, hgt) in listOf(120f to 300f, 260f to 380f, 900f to 340f, 1020f to 280f)) s.pine(x, H * 0.70f, hgt, 190f, s.col("#07241F", "#5FA888"))
        s.hills(H * 0.76f, 90f, 1.0f, 1.0f, s.col("#082520", "#6DBB98"))
        // Cabin on the near hill.
        val bx = W * 0.56f; val by = H * 0.745f
        s.paint.color = s.col("#2A1B14", "#7A5238"); s.c.drawRect(bx - 130f, by - 150f, bx + 130f, by + 20f, s.paint)
        s.paint.color = s.col("#140D0A", "#4D3322"); s.c.drawPath(Path().apply { moveTo(bx - 160f, by - 148f); lineTo(bx, by - 270f); lineTo(bx + 160f, by - 148f); close() }, s.paint)
        s.c.drawRect(bx + 70f, by - 300f, bx + 110f, by - 190f, s.paint)
        if (dark) s.glow(bx - 30f, by - 70f, 120f, 0xFFD36B, 160)
        s.paint.color = s.col("#FFD36B", "#F6D58A"); s.c.drawRect(bx - 62f, by - 100f, bx + 2f, by - 36f, s.paint)
        s.mist(H * 0.80f)
        return s.bmp
    }

    /** Rose: a blossoming branch under a pale moon, petals drifting down. */
    fun rose(dark: Boolean): Bitmap {
        val s = Ctx(dark)
        s.sky("#1E0A1C", "#4A1A3A", "#FFEAF2", "#F8C3DA")
        s.stars(41, 50, H * 0.35f)
        s.glow(W * 0.28f, H * 0.2f, 460f, 0xFFD6E8, if (dark) 120 else 150)
        s.disc(W * 0.22f, H * 0.075f, 130f, s.col("#FFE9F3", "#FFFFFF"))
        // Branch from the upper right.
        s.paint.style = Paint.Style.STROKE; s.paint.strokeCap = Paint.Cap.ROUND; s.paint.color = s.col("#1A0A14", "#6B4A57")
        s.paint.strokeWidth = 30f; s.c.drawPath(Path().apply { moveTo(W + 40f, H * 0.05f); quadTo(W * 0.7f, H * 0.2f, W * 0.45f, H * 0.34f) }, s.paint)
        s.paint.strokeWidth = 18f; s.c.drawPath(Path().apply { moveTo(W * 0.78f, H * 0.14f); quadTo(W * 0.62f, H * 0.1f, W * 0.5f, H * 0.14f) }, s.paint)
        s.paint.strokeWidth = 14f; s.c.drawPath(Path().apply { moveTo(W * 0.64f, H * 0.22f); quadTo(W * 0.74f, H * 0.3f, W * 0.7f, H * 0.38f) }, s.paint)
        s.paint.style = Paint.Style.FILL
        val rnd = java.util.Random(7)
        val petal = intArrayOf(s.col("#FF8FBF", "#FF9CC7"), s.col("#FFB8D6", "#FFC2DC"), s.col("#E85C9A", "#F27BAE"))
        val spots = listOf(0.48f to 0.33f, 0.56f to 0.28f, 0.63f to 0.21f, 0.71f to 0.16f, 0.8f to 0.12f, 0.52f to 0.14f, 0.6f to 0.11f, 0.7f to 0.37f, 0.66f to 0.31f, 0.9f to 0.08f)
        for ((px, py) in spots) repeat(5) {
            s.paint.color = petal[rnd.nextInt(3)]
            s.c.drawCircle(W * px + rnd.nextFloat() * 90f - 45f, H * py + rnd.nextFloat() * 90f - 45f, 26f + rnd.nextFloat() * 22f, s.paint)
        }
        // Falling petals.
        repeat(26) {
            val x = rnd.nextFloat() * W; val y = H * (0.36f + rnd.nextFloat() * 0.5f)
            s.paint.color = petal[rnd.nextInt(3)]; s.c.drawOval(android.graphics.RectF(x, y, x + 26f, y + 14f), s.paint)
        }
        s.hills(H * 0.86f, 60f, 1.2f, 0.7f, s.col("#2A0F25", "#F0A6C6"))
        s.mist(H * 0.84f)
        return s.bmp
    }

    /** Graphite: a quiet city skyline at dusk with lit windows. */
    fun graphite(dark: Boolean): Bitmap {
        val s = Ctx(dark)
        s.sky("#0B0D10", "#2B3037", "#F1F3F6", "#C4CAD2")
        s.stars(53, 45, H * 0.35f)
        s.glow(W * 0.7f, H * 0.17f, 420f, 0xE6ECF5, if (dark) 90 else 140)
        s.disc(W * 0.7f, H * 0.08f, 140f, s.col("#E9EDF4", "#FFFFFF"))
        val rnd = java.util.Random(5)
        fun layer(baseY: Float, minH: Float, maxH: Float, color: Int, lit: Boolean) {
            var x = -20f
            while (x < W) {
                val w = 90f + rnd.nextFloat() * 130f; val h = minH + rnd.nextFloat() * (maxH - minH)
                s.paint.color = color; s.c.drawRect(x, baseY - h, x + w, H.toFloat(), s.paint)
                if (rnd.nextFloat() < 0.25f) s.c.drawRect(x + w / 2 - 3f, baseY - h - 70f, x + w / 2 + 3f, baseY - h, s.paint)   // antenna
                if (lit) {
                    var wy = baseY - h + 28f
                    while (wy < baseY - 30f) {
                        var wx = x + 14f
                        while (wx < x + w - 24f) {
                            if (rnd.nextFloat() < (if (dark) 0.35f else 0.12f)) { s.paint.color = s.col("#F6D58A", "#FFFFFF"); s.c.drawRect(wx, wy, wx + 12f, wy + 18f, s.paint) }
                            wx += 30f
                        }
                        wy += 44f
                    }
                }
                x += w + 6f
            }
        }
        layer(H * 0.72f, 220f, 560f, s.col("#171B21", "#B7BFCA"), false)
        layer(H * 0.80f, 180f, 480f, s.col("#0E1115", "#8F99A8"), true)
        s.mist(H * 0.86f)
        return s.bmp
    }
}
