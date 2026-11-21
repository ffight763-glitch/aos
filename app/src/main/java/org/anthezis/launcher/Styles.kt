package org.anthezis.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * AOS styles: a wallpaper plus the accent colour that goes with it. Blue and Aurora ship as images; the others are
 * painted in code (two soft glows over a vertical gradient), so a new style costs a few colours, not a new asset.
 */
object AosStyles {
    class Style(
        val id: String, val name: String,
        val accentLight: String, val accentDark: String,
        val dayTop: String = "", val dayBottom: String = "", val dayGlow: String = "",
        val nightTop: String = "", val nightBottom: String = "", val nightGlow: String = "",
    )

    const val DEFAULT = "ocean"

    val all = listOf(
        Style("ocean", "Ocean", "#1A8FD0", "#4FC3F0", "#D8F6FF", "#A9E6FF", "#6FC8F0", "#041C33", "#02111F", "#146A94"),
        Style("forestowl", "Forest Owl", "#2E9E6B", "#58D6A0"),
        Style("aurora", "Aurora", "#6D4AE0", "#8B7CF6"),
        Style("blue", "Blue", "#2563EB", "#3B7BF5"),
        Style("ember", "Ember", "#E0592A", "#FF8A5C", "#FFE9DD", "#FFD1BA", "#FFB08A", "#2A1410", "#1A0C0A", "#8A3B1F"),
        Style("forest", "Forest", "#1E9E6A", "#3DDC97", "#E3F6EC", "#C5EBD6", "#8FDDB5", "#0C1F18", "#07120E", "#1B7A55"),
        Style("rose", "Rose", "#D6336C", "#FF6FA0", "#FDE8F0", "#F9CADD", "#F59BBA", "#26101A", "#150910", "#8A2B52"),
        Style("graphite", "Graphite", "#4B5563", "#9CA3AF", "#EEF0F2", "#D5D9DD", "#B8BEC6", "#121417", "#0B0D0F", "#3A4048"),
        // Exclusive flat themes: one solid colour (a deep shade at night, a soft tint by day), no gradient.
        Style("flat_red", "Flat Red", "#E53935", "#FF5C58", "#FF8A80", "#FF8A80", "#FF8A80", "#4A0F0D", "#4A0F0D", "#4A0F0D"),
        Style("flat_orange", "Flat Orange", "#FB8C00", "#FFA733", "#FFCC80", "#FFCC80", "#FFCC80", "#4A2A00", "#4A2A00", "#4A2A00"),
        Style("flat_yellow", "Flat Yellow", "#E0B000", "#FFD21F", "#FFF176", "#FFF176", "#FFF176", "#3F3400", "#3F3400", "#3F3400"),
        Style("flat_green", "Flat Green", "#2E9E48", "#4FD66E", "#A5E6AE", "#A5E6AE", "#A5E6AE", "#0E3A1A", "#0E3A1A", "#0E3A1A"),
        Style("flat_blue", "Flat Blue", "#1E6FE0", "#4D93FF", "#90BFFF", "#90BFFF", "#90BFFF", "#0B2A5C", "#0B2A5C", "#0B2A5C"),
        Style("flat_pink", "Flat Pink", "#E0306E", "#FF5C94", "#FFA6C4", "#FFA6C4", "#FFA6C4", "#4A0F27", "#4A0F27", "#4A0F27"),
        Style("flat_black", "Flat Black", "#6B7280", "#9CA3AF", "#D9DCE0", "#D9DCE0", "#D9DCE0", "#000000", "#000000", "#000000"),        Style("twilight", "Twilight", "#8A4FD8", "#B58CFF", "#E6D8FF", "#FFD9F0", "#E0B0FF", "#120A2E", "#6A3A8A", "#4A2C72"),
        Style("forest_peaks", "Forest Peaks", "#2E9E5B", "#4FD68A", "#DDF5D0", "#F5FFE8", "#BFFFD0", "#04120C", "#1B5A3A", "#1F6B46"),
        Style("bokeh_mint", "Mint Bokeh", "#1FB58C", "#3DF0B8", "#E3FFF3", "#A8F0D4", "#3DF0B8", "#02201A", "#0B5A4A", "#2CC9A0"),
        Style("bokeh_rose", "Rose Bokeh", "#E0406A", "#FF5C84", "#FFE6EC", "#FFB8C8", "#FF5C84", "#2A0814", "#7A1A3C", "#E0406A"),
        Style("alps", "Alps", "#4B7BD8", "#7FA6FF", "#CFE6FF", "#F3FAFF", "#BFD4FF", "#0A1230", "#3A4A8A", "#2B3A72"),
        Style("volcano", "Volcano", "#E0592A", "#FF8A5C", "#FFD9B8", "#FF9A5C", "#FF8A3D", "#220808", "#7A2410", "#4A1810"),
        Style("glacier", "Glacier", "#1AA6C8", "#4FD8F5", "#E3FBFF", "#B5EEF8", "#9AF0FF", "#04141F", "#14506A", "#1E6A86"),
        Style("bokeh_candy", "Candy Bokeh", "#E0409F", "#FF70C8", "#FFE3F3", "#FFB8DE", "#FF5CC8", "#2A0B3A", "#7A1F62", "#B06CFF"),
        Style("bokeh_gold", "Gold Bokeh", "#D9A100", "#FFC53D", "#FFF5D6", "#FFD97A", "#FFC53D", "#120C02", "#4A3205", "#FF9F1C"),
        Style("bokeh_ice", "Ice Bokeh", "#1A8FD0", "#4FD8FF", "#E6FAFF", "#9EE0F5", "#4FD8FF", "#031A2A", "#0A4F72", "#3C9BFF"),
        Style("storm", "Storm", "#5A7AA8", "#8FB0E0", "#9AA6B8", "#5B6678", "#6B7688", "#05070D", "#1B2434", "#2A3650"),
        Style("fog", "Fog", "#5E8A8A", "#8FC4C4", "#E9EEEE", "#C3CFCF", "#B2C4C4", "#0C1214", "#2A3A3A", "#2E4242"),
        Style("dawn", "Dawn", "#E0688A", "#FF9AB5", "#BFE3FF", "#FFD3B0", "#FFE9A8", "#1B1440", "#E0728A", "#FFA56B"),
        Style("x_blocks", "Blocks", "#4CA832", "#7FDB5A", "#8FD0FF", "#CDEBFF", "#5DBB3D", "#0B1330", "#1F3566", "#2F7A34"),
        Style("sakura", "Sakura", "#E0457A", "#FF7AA8", "#FFF0F5", "#FFD3E1", "#FFB7C9", "#1A0F2E", "#2A1240", "#7A2C6B"),
        Style("desert", "Desert", "#D9822B", "#FFA84D", "#FFF1C9", "#FAD08F", "#F6B26B", "#0C0A24", "#2A1038", "#6A2E4F"),
        Style("jungle", "Jungle", "#2E9E5B", "#4FD68A", "#E6F9D0", "#9FDDA0", "#2E8B57", "#04140C", "#082A1B", "#0F4A32"),
        Style("circuit", "Circuit", "#0FA7B8", "#38F2FF", "#EAF8F8", "#CDEEF0", "#B9E4E8", "#02121A", "#032430", "#04303C"),
        Style("inferno", "Inferno", "#E8431C", "#FF7A3D", "#FFEBD6", "#FFD1A8", "#FF9A6B", "#2A0606", "#1A0303", "#8A2410"),
        Style("hornet", "Black & Yellow", "#E0A800", "#FFC400", "#FFF7CC", "#FFE680", "#FFD83D", "#0B0B0B", "#050505", "#6B5200"),
        Style("royal", "Royal Blue", "#1E4FD8", "#4D7BFF", "#E3EDFF", "#C5D8FF", "#8FB2FF", "#04103A", "#020924", "#173E94"),
        Style("synth", "Synthwave", "#E0298F", "#FF4FB0", "#FFE9D6", "#FFC9C9", "#E3B5FF", "#1A0840", "#12002E", "#8A2C8F"),
        Style("matrix", "Matrix", "#12A24A", "#2BFF7A", "#EAF8EC", "#CDEBD0", "#A8D8AE", "#02130A", "#000A04", "#0E6A32"),
        Style("meadow", "Meadow", "#3FA83C", "#8FE06B", "#E6F7CF", "#CFEFA8", "#9FD97A", "#0B1B3A", "#071327", "#2E7A55"),
        Style("neon", "Neon City", "#D63AF0", "#FF6AF5", "#F6E6FF", "#E1C8FF", "#C8A6FF", "#12082B", "#0A0620", "#7A2C9E"),
        Style("snow", "Winter", "#3B82C4", "#7CC0F2", "#EAF4FF", "#CFE5FA", "#A9CCEE", "#0B1A2C", "#07121F", "#2C5F8A"),
        Style("space", "Galaxy", "#6D5BFF", "#9A8CFF", "#EDEBFF", "#D3CEFF", "#B0A8F5", "#0B0A1F", "#05050F", "#41358F"),
        Style("sunrise", "Sunrise", "#D6336C", "#FF6FA0", "#FFE9E0", "#FFD3B8", "#F7A0B8", "#1B0F2E", "#150B2B", "#7A2B6B"),
        Style("lagoon", "Lagoon", "#1D7FD1", "#4FB4E8", "#E6F5FF", "#C9ECFF", "#8FD0F2", "#0A1E33", "#06182B", "#1B5E8C"),
    )

    /** Picker groups: 0 = Themes V1 (the first set, kept as it was), 1 = Themes V2 (the new animated set), 2 = Exclusive flat colours. */
    private val v2 = setOf("neon", "snow", "space", "synth", "ocean", "matrix", "meadow", "inferno", "hornet", "royal")
    private val v3 = setOf("sakura", "desert", "jungle", "circuit")
    private val v4 = setOf("alps", "volcano", "glacier")
    private val v5 = setOf("bokeh_candy", "bokeh_gold", "bokeh_ice")
    private val v6 = setOf("storm", "fog", "dawn")
    private val v7 = setOf("twilight", "forest_peaks", "bokeh_mint", "bokeh_rose")
    /** Popular themes (the Top tab) and the newest ones (they get a NEW badge). */
    val top = listOf("ocean", "forestowl", "aurora", "neon", "synth", "sakura", "space", "inferno", "meadow", "x_blocks")
    val newest = setOf("alps", "volcano", "glacier", "bokeh_candy", "bokeh_gold", "bokeh_ice", "storm", "fog", "dawn", "x_blocks", "twilight", "forest_peaks", "bokeh_mint", "bokeh_rose")
    fun groupIndex(id: String) = if (isExclusive(id)) 7 else if (id in v7) 6 else if (id in v6) 5 else if (id in v5) 4 else if (id in v4) 3 else if (id in v3) 2 else if (id in v2) 1 else 0
    fun groupName(i: Int) = listOf("Themes V1", "Themes V2", "Themes V3", "Themes V4", "Themes V5", "Themes V6", "Themes V7", "Exclusive")[i]

    private var cachedKey = ""
    private var cachedBg: Bitmap? = null

    private val lumCache = HashMap<String, Float>()

    /** Average brightness (0..1) of the DAY scene of a style, from a grid of sample points; flat and unknown scenes count as light. */
    @Synchronized
    fun dayLuminance(s: Style): Float = lumCache.getOrPut(s.id) {
        val b = sceneBitmap(s, false) ?: return@getOrPut 1f
        var sum = 0.0; var n = 0
        for (yy in 0 until 16) for (xx in 0 until 8) {
            val c = b.getPixel((xx * 2 + 1) * b.width / 16, (yy * 2 + 1) * b.height / 32)
            sum += (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0; n++
        }
        (sum / n).toFloat()
    }

    /** The scene of a style, kept for the last request: the Home, the apps and the picker ask for it again and again. */
    @Synchronized
    fun sceneBitmap(s: Style, dark: Boolean): Bitmap? {
        val key = s.id + "/" + dark
        if (key == cachedKey && cachedBg != null) return cachedBg
        val b = bitmap(s, dark)
        if (b != null) { cachedKey = key; cachedBg = b }
        return b
    }

    /** The scene of the chosen style as a window background (the last one is kept, so opening an app stays quick). */
    fun windowBackground(ctx: android.content.Context, dark: Boolean): android.graphics.drawable.Drawable? {
        val id = selectedId(ctx)
        if (id == "custom") return null
        val key = "$id/$dark"
        val bmp = try { sceneBitmap(find(id), dark) } catch (_: Throwable) { null }
        return bmp?.let { android.graphics.drawable.BitmapDrawable(ctx.resources, it) }
    }

    /** Exclusive themes: the flat one-colour ones. */    fun isExclusive(id: String) = id.startsWith("flat_") || id.startsWith("x_")

    fun find(id: String?): Style = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }

    /** Move the previous default to Ocean once; later explicit theme choices stay intact. */
    fun selectedId(ctx: android.content.Context): String {
        val prefs = ctx.getSharedPreferences("aos_setup", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("ocean_default_v1", false)) {
            val previous = prefs.getString("wallpaper", null)
            val edit = prefs.edit().putBoolean("ocean_default_v1", true)
            if (previous == null || previous == "forestowl") edit.putString("wallpaper", DEFAULT)
            edit.apply()
        }
        return prefs.getString("wallpaper", DEFAULT) ?: DEFAULT
    }

    /** AOS Blue, painted like Forest Owl: moon, layered blue mountains, a calm sea and a lighthouse whose lamp glows warm at night. */
    private fun blueLighthouse(dark: Boolean): Bitmap {
        val w = 1080; val h = 2400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun col(night: String, day: String) = Color.parseColor(if (dark) night else day)

        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), col("#050F2B", "#DCEBFF"), col("#10386C", "#8DBAF0"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // Stars at night.
        if (dark) {
            paint.color = Color.argb(200, 255, 255, 255)
            for ((x, y, r) in listOf(Triple(120f, 280f, 3f), Triple(300f, 160f, 2f), Triple(520f, 380f, 3f), Triple(760f, 120f, 2f), Triple(940f, 420f, 3f),
                Triple(200f, 560f, 2f), Triple(640f, 600f, 2f), Triple(880f, 760f, 3f), Triple(60f, 800f, 2f), Triple(420f, 760f, 2f))) c.drawCircle(x, y, r, paint)
        }
        // Moon (or pale sun) with a soft glow.
        val mx = w * 0.72f; val my = h * 0.08f   // above the clock and date of the home widget
        paint.shader = RadialGradient(mx, my, 420f, Color.argb(if (dark) 120 else 150, 235, 245, 255), Color.argb(0, 235, 245, 255), Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, 420f, paint)
        paint.shader = null
        paint.color = col("#F1F6FF", "#FFFFFF"); c.drawCircle(mx, my, 150f, paint)

        // Mountain ridges, far to near.
        fun ridge(base: Float, peaks: List<Pair<Float, Float>>, color: Int) {
            val path = android.graphics.Path().apply {
                moveTo(0f, h.toFloat()); lineTo(0f, base)
                for ((x, up) in peaks) lineTo(x, base - up)
                lineTo(w.toFloat(), base); lineTo(w.toFloat(), h.toFloat()); close()
            }
            paint.color = color; c.drawPath(path, paint)
        }
        ridge(h * 0.66f, listOf(120f to 330f, 330f to 190f, 560f to 420f, 800f to 230f, 1000f to 360f), col("#0B2A58", "#B5D0F2"))
        ridge(h * 0.68f, listOf(60f to 160f, 260f to 300f, 480f to 170f, 700f to 330f, 930f to 200f), col("#09224A", "#93B8E8"))

        // Sea.
        val seaTop = h * 0.68f
        paint.shader = LinearGradient(0f, seaTop, 0f, h.toFloat(), col("#0A2B5C", "#7FAEE6"), col("#041532", "#4F86CC"), Shader.TileMode.CLAMP)
        c.drawRect(0f, seaTop, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = col("#1B4C8C", "#B5D3F5"); paint.alpha = 140
        for (i in 0 until 14) { val y = seaTop + 40f + i * 82f; c.drawRoundRect(60f + (i * 97) % 380, y, 380f + (i * 131) % 560, y + 6f, 3f, 3f, paint) }
        paint.alpha = 255

        // Lighthouse on a rock.
        val bx = w * 0.40f; val by = h * 0.74f
        paint.color = col("#071A3A", "#4A6F9E")
        c.drawPath(android.graphics.Path().apply {
            moveTo(bx - 260f, by + 90f); lineTo(bx - 150f, by - 30f); lineTo(bx - 40f, by - 70f); lineTo(bx + 90f, by - 40f); lineTo(bx + 230f, by + 90f); close()
        }, paint)
        val towerTop = by - 640f
        paint.color = col("#D7E3F6", "#FFFFFF")
        c.drawPath(android.graphics.Path().apply {
            moveTo(bx - 82f, by - 60f); lineTo(bx - 48f, towerTop); lineTo(bx + 48f, towerTop); lineTo(bx + 82f, by - 60f); close()
        }, paint)
        paint.color = col("#B63A44", "#D9505A")                          // two red stripes
        for (k in listOf(0.18f, 0.52f)) {
            val y1 = by - 60f - (by - 60f - towerTop) * k; val y2 = y1 - 110f
            val hw1 = 82f - 34f * ((by - 60f - y1) / (by - 60f - towerTop)); val hw2 = 82f - 34f * ((by - 60f - y2) / (by - 60f - towerTop))
            c.drawPath(android.graphics.Path().apply { moveTo(bx - hw1, y1); lineTo(bx - hw2, y2); lineTo(bx + hw2, y2); lineTo(bx + hw1, y1); close() }, paint)
        }
        paint.color = col("#0B1E43", "#38557F"); c.drawRect(bx - 74f, towerTop - 14f, bx + 74f, towerTop + 6f, paint)    // gallery
        // Lamp room with a warm glow and beam.
        val lampY = towerTop - 78f
        if (dark) {
            paint.shader = LinearGradient(bx, lampY, bx + 700f, lampY, Color.argb(120, 255, 214, 120), Color.argb(0, 255, 214, 120), Shader.TileMode.CLAMP)
            c.drawPath(android.graphics.Path().apply { moveTo(bx, lampY - 14f); lineTo(bx + 760f, lampY - 150f); lineTo(bx + 760f, lampY + 120f); lineTo(bx, lampY + 14f); close() }, paint)
            paint.shader = RadialGradient(bx, lampY, 190f, Color.argb(190, 255, 214, 120), Color.argb(0, 255, 214, 120), Shader.TileMode.CLAMP)
            c.drawCircle(bx, lampY, 190f, paint); paint.shader = null
        }
        paint.color = col("#FFD66B", "#F4C24A"); c.drawRect(bx - 38f, lampY - 44f, bx + 38f, lampY + 44f, paint)
        paint.color = col("#0B1E43", "#38557F")
        c.drawPath(android.graphics.Path().apply { moveTo(bx - 56f, lampY - 44f); lineTo(bx, lampY - 112f); lineTo(bx + 56f, lampY - 44f); close() }, paint)

        // Foreground waves.
        paint.color = col("#061C42", "#5E92D6")
        c.drawPath(android.graphics.Path().apply {
            moveTo(0f, h * 0.90f)
            var x = 0f
            while (x < w) { quadTo(x + 90f, h * 0.90f - 40f, x + 180f, h * 0.90f); x += 180f }
            lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
        }, paint)
        paint.shader = LinearGradient(0f, h * 0.86f, 0f, h.toFloat(), Color.argb(0, 220, 235, 255), Color.argb(if (dark) 40 else 90, 220, 235, 255), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.86f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    /** Forest Owl: moon (or pale sun), layered pines, and an owl on a branch. Night is deep green, day is misty green. */
    private fun forestOwl(dark: Boolean): Bitmap {
        val w = 1080; val h = 2400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun col(night: String, day: String) = Color.parseColor(if (dark) night else day)

        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), col("#071A16", "#D4EBDD"), col("#14382C", "#9BCDB2"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Moon with a soft glow.
        val mx = w * 0.72f; val my = h * 0.08f   // above the clock and date of the home widget
        paint.shader = RadialGradient(mx, my, 420f, Color.argb(if (dark) 110 else 150, 255, 250, 210), Color.argb(0, 255, 250, 210), Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, 420f, paint)
        paint.shader = null
        paint.color = col("#F4F0CC", "#FFFFFF")
        c.drawCircle(mx, my, 150f, paint)

        // Pines: a few stacked triangles per tree, three depth layers from pale and far to dark and near.
        fun pine(x: Float, base: Float, height: Float, width: Float, color: Int) {
            paint.color = color
            for (i in 0 until 4) {
                val top = base - height + i * height * 0.2f
                val bottom = base - height * (0.62f - i * 0.18f).coerceAtLeast(0.05f) + height * 0.25f
                val half = width * (0.38f + i * 0.2f) / 2f
                val path = android.graphics.Path().apply { moveTo(x, top); lineTo(x - half, bottom); lineTo(x + half, bottom); close() }
                c.drawPath(path, paint)
            }
            c.drawRect(x - width * 0.03f, base - height * 0.1f, x + width * 0.03f, base + 40f, paint)
        }
        val far = col("#0E2D24", "#B2D8C3"); val mid = col("#0A241C", "#86BFA2"); val near = col("#05140F", "#5E9C80")
        for ((x, hgt) in listOf(40f to 520f, 240f to 640f, 470f to 560f, 700f to 680f, 930f to 600f, 1080f to 520f)) pine(x, h * 0.74f, hgt, 300f, far)
        for ((x, hgt) in listOf(-20f to 700f, 330f to 760f, 620f to 700f, 900f to 820f)) pine(x, h * 0.80f, hgt, 360f, mid)

        // Owl on a branch.
        val by = h * 0.58f
        paint.color = col("#1D130C", "#4D3A29")
        c.drawRoundRect(-40f, by, w * 0.82f, by + 34f, 17f, 17f, paint)
        val ox = w * 0.38f
        val body = col("#2D2218", "#6A503A"); val belly = col("#5A4632", "#B79A78")
        paint.color = body
        c.drawOval(ox - 135f, by - 330f, ox + 135f, by + 6f, paint)           // body
        paint.color = belly
        c.drawOval(ox - 85f, by - 270f, ox + 85f, by - 10f, paint)            // chest
        paint.color = body
        c.drawCircle(ox, by - 360f, 112f, paint)                              // head
        c.drawPath(android.graphics.Path().apply { moveTo(ox - 100f, by - 430f); lineTo(ox - 60f, by - 500f); lineTo(ox - 25f, by - 445f); close() }, paint)
        c.drawPath(android.graphics.Path().apply { moveTo(ox + 100f, by - 430f); lineTo(ox + 60f, by - 500f); lineTo(ox + 25f, by - 445f); close() }, paint)
        paint.color = belly
        c.drawCircle(ox - 44f, by - 362f, 56f, paint); c.drawCircle(ox + 44f, by - 362f, 56f, paint) // face discs
        val eye = col("#FFC53D", "#F2A81D")
        for (dx in listOf(-44f, 44f)) {
            paint.shader = RadialGradient(ox + dx, by - 362f, 70f, Color.argb(if (dark) 130 else 60, 255, 197, 61), Color.argb(0, 255, 197, 61), Shader.TileMode.CLAMP)
            c.drawCircle(ox + dx, by - 362f, 70f, paint)
            paint.shader = null
            paint.color = eye; c.drawCircle(ox + dx, by - 362f, 33f, paint)
            paint.color = Color.parseColor("#0A0705"); c.drawCircle(ox + dx, by - 362f, 15f, paint)
        }
        paint.color = col("#D98A2B", "#C9761A")
        c.drawPath(android.graphics.Path().apply { moveTo(ox - 14f, by - 340f); lineTo(ox + 14f, by - 340f); lineTo(ox, by - 306f); close() }, paint)
        paint.color = col("#1D130C", "#4D3A29")                               // feet
        c.drawRoundRect(ox - 70f, by - 14f, ox - 30f, by + 14f, 10f, 10f, paint); c.drawRoundRect(ox + 30f, by - 14f, ox + 70f, by + 14f, 10f, 10f, paint)

        for ((x, hgt) in listOf(-60f to 900f, 300f to 760f, 760f to 940f, 1060f to 820f)) pine(x, h * 0.93f, hgt, 420f, near)
        // Ground mist.
        paint.shader = LinearGradient(0f, h * 0.84f, 0f, h.toFloat(), Color.argb(0, 255, 255, 255), Color.argb(if (dark) 40 else 90, 230, 255, 240), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.84f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    /** Shared finishing pass keeps previews, Home and the lock screen visually consistent. */
    fun bitmap(s: Style, dark: Boolean): Bitmap? {
        val bmp = rawBitmap(s, dark) ?: return null
        if (s.id.startsWith("flat_") || s.id == "graphite") return bmp
        val c = Canvas(bmp)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val accent = Color.parseColor(if (dark) s.accentDark else s.accentLight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(w * 0.78f, h * 0.35f, w * 0.95f,
            intArrayOf((accent and 0xFFFFFF) or ((if (dark) 20 else 12) shl 24), accent and 0xFFFFFF),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.argb(if (dark) 24 else 8, 0, 0, 0), 0,
                Color.argb(if (dark) 55 else 24, 0, 0, 0)), floatArrayOf(0f, 0.43f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        return bmp
    }

    private fun rawBitmap(s: Style, dark: Boolean): Bitmap? {
        if (s.id.startsWith("flat_")) return Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.parseColor(if (dark) s.nightTop else s.dayTop)) }
        if (s.id == "forestowl") return forestOwl(dark)
        if (s.id == "blue") return blueLighthouse(dark)
        // Every other style is a painted scene too (Forest Owl is the model for all of them).
        when (s.id) {
            "aurora" -> return Scenes.aurora(dark)
            "ember" -> return Scenes.ember(dark)
            "forest" -> return Scenes.forest(dark)
            "rose" -> return Scenes.rose(dark)
            "graphite" -> return Scenes.graphite(dark)
            "twilight" -> return Scenes6.twilight(dark)
            "forest_peaks" -> return Scenes6.forestPeaks(dark)
            "bokeh_mint" -> return Scenes6.bokehMint(dark)
            "bokeh_rose" -> return Scenes6.bokehRose(dark)
            "alps" -> return Scenes6.alps(dark)
            "volcano" -> return Scenes6.volcano(dark)
            "glacier" -> return Scenes6.glacier(dark)
            "bokeh_candy" -> return Scenes6.bokehCandy(dark)
            "bokeh_gold" -> return Scenes6.bokehGold(dark)
            "bokeh_ice" -> return Scenes6.bokehIce(dark)
            "storm" -> return Scenes6.storm(dark)
            "fog" -> return Scenes6.fog(dark)
            "dawn" -> return Scenes6.dawn(dark)
            "x_blocks" -> return Scenes6.blocks(dark)
            "sakura" -> return Scenes5.sakura(dark)
            "desert" -> return Scenes5.desert(dark)
            "jungle" -> return Scenes5.jungle(dark)
            "circuit" -> return Scenes5.circuit(dark)
            "inferno" -> return Scenes4.inferno(dark)
            "hornet" -> return Scenes4.hornet(dark)
            "royal" -> return Scenes4.royal(dark)
            "synth" -> return Scenes3.synthwave(dark)
            "ocean" -> return Scenes3.ocean(dark)
            "matrix" -> return Scenes3.matrix(dark)
            "meadow" -> return Scenes3.meadow(dark)
            "neon" -> return Scenes2.neon(dark)
            "snow" -> return Scenes2.snow(dark)
            "space" -> return Scenes2.space(dark)
            "sunrise" -> return Waves.sunrise(dark)
            "lagoon" -> return Waves.lagoon(dark)
        }
        if (s.dayTop.isEmpty()) return null
        val w = 1080; val h = 2400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val top = Color.parseColor(if (dark) s.nightTop else s.dayTop)
        val bottom = Color.parseColor(if (dark) s.nightBottom else s.dayBottom)
        val glow = Color.parseColor(if (dark) s.nightGlow else s.dayGlow)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        fun blob(x: Float, y: Float, r: Float, alpha: Int) {
            val edge = Color.argb(0, Color.red(glow), Color.green(glow), Color.blue(glow))
            val centre = Color.argb(alpha, Color.red(glow), Color.green(glow), Color.blue(glow))
            paint.shader = RadialGradient(x, y, r, centre, edge, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint)
        }
        blob(w * 0.2f, h * 0.22f, w * 0.9f, 200)
        blob(w * 0.8f, h * 0.78f, w * 0.85f, 170)
        return bmp
    }
}
