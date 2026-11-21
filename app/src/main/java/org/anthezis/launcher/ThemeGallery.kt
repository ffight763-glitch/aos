package org.anthezis.launcher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Small, shared previews: one renderer instead of a full-sized bitmap/thread for every theme. */
internal class ThemePreview(context: Context, private val style: AosStyles.Style, dark: Boolean) : View(context) {
    private var night = dark
    private var bitmap: Bitmap? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    @Volatile private var attached = false
    private var generation = 0

    fun showNight(value: Boolean) { if (night != value) { night = value; bitmap = null; load(); invalidate() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; load() }
    override fun onDetachedFromWindow() { attached = false; generation++; super.onDetachedFromWindow() }
    private fun load() {
        val token = ++generation
        val isNight = night
        val key = style.id + if (isNight) "/night" else "/day"
        cache.get(key)?.let { bitmap = it; invalidate(); return }
        renderer.execute {
            if (!attached) return@execute
            val small = cache.get(key) ?: runCatching {
                AosStyles.bitmap(style, isNight)?.let { full ->
                    Bitmap.createScaledBitmap(full, 144, 320, true).also { if (it !== full) full.recycle() }
                }
            }.getOrNull()?.also { cache.put(key, it) }
            post { if (attached && token == generation) { bitmap = small; invalidate() } }
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        rect.set(0f, 0f, w, h)
        val accent = Color.parseColor(if (night) style.accentDark else style.accentLight)
        ink.color = accent; c.drawRect(rect, ink)
        bitmap?.let { b ->
            val scale = maxOf(w / b.width, h / b.height)
            val bw = b.width * scale; val bh = b.height * scale
            rect.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
            c.drawBitmap(b, null, rect, ink)
        }
        ink.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x33000000, 0, 0xD9000000.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, ink); ink.shader = null
        ink.color = Color.WHITE; ink.textAlign = Paint.Align.CENTER; ink.typeface = appFont(600)
        ink.textSize = w * 0.19f; c.drawText("09:41", w / 2, h * 0.25f, ink)
        ink.textSize = w * 0.055f; ink.alpha = 190; c.drawText("AOS · ${if (night) "Night" else "Day"}", w / 2, h * 0.30f, ink)
        ink.alpha = 255
        ink.color = 0x40FFFFFF; rect.set(w * 0.14f, h * 0.69f, w * 0.86f, h * 0.77f)
        c.drawRoundRect(rect, w * 0.04f, w * 0.04f, ink)
        ink.color = Color.WHITE
        for (i in 0..4) c.drawCircle(w * (0.23f + i * 0.135f), h * 0.73f, w * 0.028f, ink)
    }

    companion object {
        private val cache = LruCache<String, Bitmap>(32)
        private val renderer = Executors.newSingleThreadExecutor { task -> Thread(task, "AOS-theme-previews").apply { isDaemon = true } }
    }
}

/** Theme cards and an explicit day/night preview before applying a theme. */
internal class ThemeGallery(private val act: Activity, private val p: Palette, private val featuredCount: Int = 8) {
    private val prefs = act.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)
    private fun dp(v: Int) = act.dp(v)
    private fun text(label: String, sp: Float, color: Int = p.text, bold: Boolean = false) = TextView(act).apply {
        text = label; textSize = sp; setTextColor(color); typeface = appFont(if (bold) 700 else 400)
    }
    private fun favourites() = prefs.getStringSet("fav_themes", emptySet()).orEmpty()
    private fun toggleFavourite(id: String) {
        val ids = favourites().toMutableSet()
        if (!ids.add(id)) ids.remove(id)
        prefs.edit().putStringSet("fav_themes", ids).apply()
    }

    fun view(): View {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        // One level below the section title ("Themes", 19 sp): smaller, same left edge (4 dp) and room above and below.
        root.addView(text("Your theme, your atmosphere", 15f, bold = true).apply { setPadding(dp(4), dp(10), 0, 0) })
        root.addView(text("Preview day and night, then choose the look you like.", 12f, p.sub).apply {
            setPadding(dp(4), dp(4), 0, dp(14))
        })
        val tabs = LinearLayout(act)
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var filter = "Featured"
        fun render() {
            tabs.removeAllViews(); grid.removeAllViews()
            for (name in listOf("Featured", "Nature", "Neon", "Soft", "Minimal", "Favourites", "All")) {
                tabs.addView(text(name, 12f, if (filter == name) p.onAccent else p.text, true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
                    background = act.shape(if (filter == name) p.accent else p.surfaceHigh, 16)
                    setOnClickListener { filter = name; render() }
                }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
            }
            val selected = AosStyles.selectedId(act)
            val styles = when (filter) {
                "Featured" -> (listOf(selected).filter { it != "custom" } + AosStyles.top).distinct().take(featuredCount).map { AosStyles.find(it) }
                "Nature" -> AosStyles.all.filter { Fx.family(it.id) in setOf("nature", "calm") || it.id in setOf("ocean", "blue", "sunrise", "lagoon") }
                "Neon" -> AosStyles.all.filter { Fx.family(it.id) in setOf("cyber", "energy") }
                "Soft" -> AosStyles.all.filter { Fx.family(it.id) == "dreamy" }
                "Minimal" -> AosStyles.all.filter { it.id.startsWith("flat_") || it.id == "graphite" }
                "Favourites" -> AosStyles.all.filter { it.id in favourites() }
                else -> AosStyles.all
            }
            if (styles.isEmpty()) grid.addView(text("Tap ☆ on a theme to save it here.", 13f, p.sub).apply { setPadding(0, dp(18), 0, dp(18)) })
            for (pair in styles.chunked(2)) {
                val row = LinearLayout(act)
                pair.forEachIndexed { index, style ->
                    row.addView(card(style) { render() }, LinearLayout.LayoutParams(0, dp(230), 1f).apply {
                        if (index > 0) marginStart = dp(10)
                    })
                }
                if (pair.size == 1) row.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
        }
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        root.addView(grid)
        render()
        return root
    }

    private fun card(style: AosStyles.Style, refresh: () -> Unit): View = FrameLayout(act).apply {
        val selected = AosStyles.selectedId(act) == style.id
        background = act.shape(p.surfaceHigh, 22); clipToOutline = true
        addView(ThemePreview(act, style, p.dark), FrameLayout.LayoutParams(-1, -1))
        addView(text(if (selected) "✓ Selected" else Fx.family(style.id).replaceFirstChar { it.uppercase() }, 10f, Color.WHITE, true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4)); background = act.shape(if (selected) p.accent else 0x66000000, 10)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(10); marginStart = dp(10); marginEnd = dp(40) })
        addView(text(if (style.id in favourites()) "★" else "☆", 24f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; contentDescription = "Favourite ${style.name}"
            setOnClickListener { toggleFavourite(style.id); refresh() }
        }, FrameLayout.LayoutParams(dp(42), dp(44), Gravity.TOP or Gravity.END))
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(12))
            addView(text(style.name, 16f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(text(if (AosPlan.themeNeeds(style.id) > AosPlan.BASIC) "Day & night · Plus" else "Day & night · Preview", 10f, 0xCCFFFFFF.toInt()))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        foreground = act.shape(Color.TRANSPARENT, 22, if (selected) p.accent else p.stroke, if (selected) 2 else 1)
        setOnClickListener { preview(style) }
    }

    private fun preview(style: AosStyles.Style) {
        val dialog = Dialog(act)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        var night = p.dark
        val preview = ThemePreview(act, style, night).apply { background = act.shape(p.surfaceHigh, 22); clipToOutline = true }
        val choices = LinearLayout(act)
        fun paintChoices() {
            choices.removeAllViews()
            for ((value, name) in listOf(false to "Day preview", true to "Night preview")) {
                choices.addView(text(name, 13f, if (night == value) p.onAccent else p.text, true).apply {
                    gravity = Gravity.CENTER; background = act.shape(if (night == value) p.accent else p.surfaceHigh, 16)
                    setOnClickListener { night = value; preview.showNight(value); paintChoices() }
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
        }
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(16)); background = act.shape(p.surface, 28, p.stroke)
            addView(LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(style.name, 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("×", 28f).apply { gravity = Gravity.CENTER; contentDescription = "Close preview"; setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(dp(40), dp(40)))
            })
            addView(preview, LinearLayout.LayoutParams(-1, (act.resources.displayMetrics.heightPixels * 0.38f).toInt()).apply { topMargin = dp(12) })
            addView(choices, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(text("Wallpaper, matching accent and ${Fx.family(style.id)} effects.", 12f, p.sub).apply { setPadding(0, dp(12), 0, dp(12)) })
            addView(text(if (AosStyles.selectedId(act) == style.id) "Keep this theme" else "Apply theme", 15f, p.onAccent, true).apply {
                gravity = Gravity.CENTER; background = act.shape(p.accent, 20)
                setOnClickListener {
                    if (AosPlan.check(act, AosPlan.themeNeeds(style.id))) {
                        prefs.edit().putString("wallpaper", style.id).remove("wallpaper_applied").putBoolean("aos_dirty", true).apply()
                        dialog.dismiss(); act.recreate()
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        paintChoices()
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.92f).toInt(), -2)
    }
}
