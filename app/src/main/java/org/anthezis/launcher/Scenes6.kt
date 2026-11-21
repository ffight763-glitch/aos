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

/**
 * Themes V4 (mountains: Alps, Volcano, Glacier), V5 (bokeh: Candy, Gold, Ice), V6 (weather: Storm, Fog, Dawn) and the
 * exclusive Blocks theme (a blocky pixel landscape drawn from plain squares, in the spirit of voxel games).
 * All 1080x2400, night and day.
 */
internal object Scenes6 {
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

    // ---- V4: mountains ----
    private fun mountains(dark: Boolean, skyN: Pair<String, String>, skyD: Pair<String, String>, ridgesN: List<String>, ridgesD: List<String>, sun: Int, seed: Int): Bitmap {
        val (bmp, c) = if (dark) canvasWith(skyN.first, skyN.second) else canvasWith(skyD.first, skyD.second)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 880f, 200f, 380f, sun, if (dark) 90 else 140)
        p.color = if (dark) Color.parseColor("#F4F6FF") else Color.parseColor("#FFF2C2"); c.drawCircle(880f, 200f, 70f, p)
        val rnd = Random(seed)
        val cols = if (dark) ridgesN else ridgesD
        for ((i, col) in cols.withIndex()) {
            val base = 1250f + i * 260f
            val path = Path().apply {
                moveTo(0f, H.toFloat()); lineTo(0f, base)
                var x = 0f; var up = true
                while (x < W) { val step = 90f + rnd.nextInt(130); x += step; lineTo(x.coerceAtMost(W.toFloat()), base - (if (up) 160f + rnd.nextInt(260) - i * 50f else rnd.nextInt(60).toFloat())); up = !up }
                lineTo(W.toFloat(), H.toFloat()); close()
            }
            p.color = Color.parseColor(col); c.drawPath(path, p)
        }
        return bmp
    }

    fun alps(dark: Boolean) = mountains(dark, "#0A1230" to "#3A4A8A", "#CFE6FF" to "#F3FAFF", listOf("#2B3A72", "#1D2A58", "#121C3E"), listOf("#9DB8E0", "#6F8FC4", "#4A6AA6"), 0xBFD4FF, 3)
    fun volcano(dark: Boolean) = mountains(dark, "#220808" to "#7A2410", "#FFD9B8" to "#FF9A5C", listOf("#4A1810", "#34100B", "#1F0806"), listOf("#C4552C", "#9A3D1C", "#6E2A12"), 0xFF8A3D, 8)
    fun glacier(dark: Boolean) = mountains(dark, "#04141F" to "#14506A", "#E3FBFF" to "#B5EEF8", listOf("#1E6A86", "#124D66", "#0A3347"), listOf("#8FD3E6", "#5FB6D0", "#3C94B4"), 0x9AF0FF, 14)

    fun twilight(dark: Boolean) = mountains(dark, "#120A2E" to "#6A3A8A", "#E6D8FF" to "#FFD9F0", listOf("#4A2C72", "#321E54", "#1F1238"), listOf("#B59AE0", "#8F72C4", "#6A52A0"), 0xE0B0FF, 21)
    fun forestPeaks(dark: Boolean) = mountains(dark, "#04120C" to "#1B5A3A", "#DDF5D0" to "#F5FFE8", listOf("#1F6B46", "#14513A", "#0B3526"), listOf("#8FD0A0", "#5FB07A", "#3C8F5C"), 0xBFFFD0, 31)
    fun bokehMint(dark: Boolean) = bokeh(dark, "#02201A" to "#0B5A4A", "#E3FFF3" to "#A8F0D4", listOf(0x3DF0B8, 0xA8FFE0, 0x2CC9A0, 0xFFFFFF), 41)
    fun bokehRose(dark: Boolean) = bokeh(dark, "#2A0814" to "#7A1A3C", "#FFE6EC" to "#FFB8C8", listOf(0xFF5C84, 0xFFA8BC, 0xE0406A, 0xFFD0DC), 42)

    // ---- V5: bokeh ----
    private fun bokeh(dark: Boolean, n: Pair<String, String>, d: Pair<String, String>, tones: List<Int>, seed: Int): Bitmap {
        val (bmp, c) = if (dark) canvasWith(n.first, n.second) else canvasWith(d.first, d.second)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(seed)
        for (i in 0 until 38) {
            val rgb = tones[rnd.nextInt(tones.size)]
            val r = 50f + rnd.nextInt(150)
            val x = rnd.nextInt(W).toFloat(); val y = rnd.nextInt(H).toFloat()
            p.shader = RadialGradient(x, y, r, Color.argb(if (dark) 90 else 110, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Color.argb(8, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
        }
        return bmp
    }

    fun bokehCandy(dark: Boolean) = bokeh(dark, "#2A0B3A" to "#7A1F62", "#FFE3F3" to "#FFB8DE", listOf(0xFF5CC8, 0xFFA8E0, 0xB06CFF, 0xFF8AB0), 5)
    fun bokehGold(dark: Boolean) = bokeh(dark, "#120C02" to "#4A3205", "#FFF5D6" to "#FFD97A", listOf(0xFFC53D, 0xFFE08A, 0xFF9F1C, 0xFFF1B8), 6)
    fun bokehIce(dark: Boolean) = bokeh(dark, "#031A2A" to "#0A4F72", "#E6FAFF" to "#9EE0F5", listOf(0x4FD8FF, 0xA8EEFF, 0x3C9BFF, 0xFFFFFF), 7)

    // ---- V6: weather ----
    fun storm(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#05070D", "#1B2434") else canvasWith("#9AA6B8", "#5B6678")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(2)
        for (i in 0 until 18) {
            val x = rnd.nextInt(W).toFloat(); val y = 120f + rnd.nextInt(700); val r = 160f + rnd.nextInt(220)
            p.color = Color.argb(if (dark) 80 else 110, if (dark) 40 else 70, if (dark) 50 else 80, if (dark) 70 else 100); c.drawOval(RectF(x - r, y - r * 0.5f, x + r, y + r * 0.5f), p)
        }
        p.color = Color.parseColor(if (dark) "#0A0F17" else "#3C4658"); c.drawRect(0f, 2180f, W.toFloat(), H.toFloat(), p)
        return bmp
    }

    fun fog(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#0C1214", "#2A3A3A") else canvasWith("#E9EEEE", "#C3CFCF")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(4)
        for (layer in 0 until 4) {
            p.color = Color.parseColor(if (dark) listOf("#2E4242", "#223232", "#182626", "#0F1A1A")[layer] else listOf("#B2C4C4", "#9AB0B0", "#7F9898", "#667F7F")[layer])
            val base = 1350f + layer * 280f
            c.drawPath(Path().apply {
                moveTo(0f, H.toFloat()); lineTo(0f, base)
                var x = 0f; while (x < W) { x += 40f; lineTo(x, base - rnd.nextInt(180) - layer * 20f) }
                lineTo(W.toFloat(), H.toFloat()); close()
            }, p)
        }
        return bmp
    }

    fun dawn(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#1B1440", "#E0728A") else canvasWith("#BFE3FF", "#FFD3B0")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        glow(c, 540f, 1700f, 700f, if (dark) 0xFFA56B else 0xFFE9A8, 140)
        p.color = if (dark) Color.parseColor("#FFD2A0") else Color.parseColor("#FFEFB0"); c.drawCircle(540f, 1700f, 120f, p)
        p.color = Color.parseColor(if (dark) "#2A1A3C" else "#8BA6C8"); c.drawRect(0f, 1760f, W.toFloat(), H.toFloat(), p)
        p.color = Color.parseColor(if (dark) "#1B1030" else "#6F8CB0"); c.drawRect(0f, 1980f, W.toFloat(), H.toFloat(), p)
        return bmp
    }

    // ---- Exclusive: Blocks ----
    /** A landscape made of square blocks (grass, dirt, stone, trees, clouds, a square sun). Original pixel art. */
    fun blocks(dark: Boolean): Bitmap {
        val (bmp, c) = if (dark) canvasWith("#0B1330", "#1F3566") else canvasWith("#8FD0FF", "#CDEBFF")
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.isAntiAlias = false
        val s = 60f   // one block
        val rnd = Random(10)
        // Sun or moon: a big square.
        p.color = if (dark) Color.parseColor("#E8EEFF") else Color.parseColor("#FFE04A"); c.drawRect(780f, 300f, 780f + s * 2, 300f + s * 2, p)
        // Stars at night.
        if (dark) { p.color = Color.WHITE; for (i in 0 until 60) { p.alpha = 90 + rnd.nextInt(160); val sx = rnd.nextInt(36) * 30f; val sy = rnd.nextInt(30) * 30f; c.drawRect(sx, sy, sx + 12f, sy + 12f, p) }; p.alpha = 255 }
        // Terrain: a column height per block, grass on top, dirt, then stone.
        val cols = (W / s).toInt() + 1
        var h = 1500f
        for (i in 0 until cols) {
            h += (rnd.nextInt(3) - 1) * s
            h = h.coerceIn(1380f, 1740f)
            val x = i * s
            p.color = Color.parseColor(if (dark) "#2F7A34" else "#5DBB3D"); c.drawRect(x, h, x + s, h + s, p)
            p.color = Color.parseColor(if (dark) "#4A3320" else "#8B5A2B"); c.drawRect(x, h + s, x + s, h + s * 4, p)
            p.color = Color.parseColor(if (dark) "#3A3D45" else "#8A8F99"); c.drawRect(x, h + s * 4, x + s, H.toFloat(), p)
            // Block edges.
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.argb(60, 0, 0, 0)
            var yy = h; while (yy < H) { c.drawRect(x, yy, x + s, yy + s, p); yy += s }
            p.style = Paint.Style.FILL
            // A tree here and there.
            if (rnd.nextInt(9) == 0 && i in 1 until cols - 1) {
                p.color = Color.parseColor(if (dark) "#3B2A18" else "#6B4423"); c.drawRect(x + s * 0.3f, h - s * 3, x + s * 0.7f, h, p)
                p.color = Color.parseColor(if (dark) "#1E5A26" else "#2E8B2E"); c.drawRect(x - s, h - s * 5, x + s * 2, h - s * 3, p); c.drawRect(x, h - s * 6, x + s, h - s * 5, p)
            }
        }
        return bmp
    }
}
