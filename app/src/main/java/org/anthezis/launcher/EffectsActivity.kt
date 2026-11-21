package org.anthezis.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Settings > Effects: a live preview phone at the top, then the three groups (theme, wallpaper, desktop) with a switch
 * per effect, the page transition, the speed and one switch for everything. Tapping an effect plays it in the preview
 * with the same code the launcher uses.
 */
class EffectsPanel(private val act: Activity) : android.content.ContextWrapper(act) {
    private lateinit var p: Palette
    private lateinit var preview: Preview

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    /** The effects page: preview, then the cards. Shown in the Effects tab of AOS Settings and on its own screen. */
    fun view(): View {
        Fx.refresh(this)
        p = Palette(this)
        preview = Preview()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        column.addView(preview.view, LinearLayout.LayoutParams(-1, dp(300)).apply { topMargin = dp(12) })
        column.addView(text("Tap an effect to see it here", 12f, p.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }, LinearLayout.LayoutParams(-1, -2))

        // Effects of the current theme: the theme's own preset, kept per theme, with a way back to it.
        val styleName = AosStyles.find(Fx.styleOf(this)).name
        val blocks = linkedMapOf<String, View>()
        blocks["Preset"] = card("Theme: $styleName", listOf(
            switchRow("Own effects for each theme" + if (AosPlan.has(this, AosPlan.PER_THEME)) "" else "  ·  Plus",
                "Now: ${Fx.preset(this).name} set for $styleName. Your changes stay with this theme",
                Fx.perTheme(this)) { if (AosPlan.check(this, AosPlan.PER_THEME)) Fx.setPerTheme(this, it); rebuild() },
            text("Reset to the $styleName set", 14f, p.accent, true).apply {
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { Fx.resetTheme(this@EffectsPanel); rebuild() }
            },
        ))
        // Master switch and speed.
        blocks["General"] = card("General", listOf(
            switchRow("Effects", "Turn every effect on or off at once", Fx.masterOn(this)) { Fx.setMaster(this, it) },
            speedRow(),
        ))
        blocks["Theme"] = card("Theme effects", Fx.theme.map { effectRow(it) })
        blocks["Wallpaper"] = card("Wallpaper effects", Fx.wallpaper.map { effectRow(it) })
        blocks["Desktop"] = card("Desktop effects", listOf(pageModeRow()) + Fx.desktop.map { effectRow(it) })
        // Inner tabs (same look as the tabs of the settings): one block at a time instead of one long list.
        val tabRow = LinearLayout(this)
        val shown = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun showTab(name: String) {
            innerTab = name
            tabRow.removeAllViews(); shown.removeAllViews()
            for (n in blocks.keys) tabRow.addView(text(n, 13f, if (n == name) p.onAccent else p.text, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
                background = if (n == name) shape(p.accent, 17) else glassShape(p, 17, true)
                setOnClickListener { showTab(n) }
            }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
            shown.addView(blocks.getValue(name))
        }
        // Tabs on top, the phone preview under them, the chosen block under that.
        column.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) },
            0, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        column.addView(shown, LinearLayout.LayoutParams(-1, -2))
        showTab(innerTab.takeIf { it in blocks } ?: "Preset")
        column.addView(text("Heavy effects (blur, parallax, depth, liquid dock) pause in battery saver. The launcher asks for the screen's fastest refresh rate (up to 120 Hz) only while it is in front.",
            12f, p.sub).apply { setPadding(dp(6), dp(14), dp(6), 0) })

        Fx.preferHighRefresh(act)
        preview.view.post { preview.play("pages") }
        root = column
        return column
    }

    private var root: LinearLayout? = null
    private var innerTab = "Preset"   // kept across rebuilds, so a switch does not throw you back to the first tab

    /** Builds the page again in place (switches show the values of the new mode / the reset preset). */
    private fun rebuild() {
        val old = root ?: return
        val parent = old.parent as? android.view.ViewGroup ?: return
        val i = parent.indexOfChild(old)
        val lp = old.layoutParams
        parent.removeViewAt(i)
        parent.addView(view(), i, lp)
    }

    private fun card(title: String, rows: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = glassShape(p, 24); setPadding(dp(16), dp(14), dp(16), dp(8))
        addView(text(title.uppercase(), 12f, p.accent, true).apply { letterSpacing = 0.08f; setPadding(0, 0, 0, dp(6)) })
        for (r in rows) addView(r, LinearLayout.LayoutParams(-1, -2))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun switchRow(title: String, hint: String, on: Boolean, demo: (() -> Unit)? = null, set: (Boolean) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
        addView(LinearLayout(this@EffectsPanel).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, p.text, true))
            addView(text(hint, 12f, p.sub))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Switch(this@EffectsPanel).apply {
            isChecked = on
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf((p.accent and 0xFFFFFF) or 0x66000000, (p.sub and 0xFFFFFF) or 0x44000000))
            setOnCheckedChangeListener { _, c -> set(c); demo?.invoke() }
        })
        if (demo != null) setOnClickListener { demo() }
    }

    private fun effectRow(e: Fx.Effect): View {
        val need = AosPlan.effectNeeds(e.id)
        if (AosPlan.has(this, need)) return switchRow(e.title, e.hint, Fx.stored(this, e.id), { preview.play(e.id) }) { Fx.set(this, e.id, it) }
        // Locked: shown off with the plan it comes with; the preview still plays it.
        return switchRow("${e.title}  ·  ${AosPlan.names[need]}", e.hint, false, { preview.play(e.id) }) { on -> if (on) { AosPlan.check(this, need); rebuild() } }
    }

    private fun speedRow() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
        val value = text("", 13f, p.sub)
        fun show(s: Float) { value.text = "Speed  ${"%.2f".format(s)}x" }
        show(Fx.speed)
        addView(LinearLayout(this@EffectsPanel).apply {
            addView(text("Animation speed", 15f, p.text, true), LinearLayout.LayoutParams(0, -2, 1f)); addView(value)
        })
        addView(SeekBar(this@EffectsPanel).apply {
            max = 150; progress = ((Fx.speed - 0.5f) * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(p.accent); thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) { show(0.5f + v / 100f) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { Fx.setSpeed(this@EffectsPanel, 0.5f + sb.progress / 100f); preview.play("pages") }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun pageModeRow() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
        addView(text("Page transition", 15f, p.text, true))
        val pills = LinearLayout(this@EffectsPanel)
        fun paint() {
            pills.removeAllViews()
            val cur = Fx.pageMode(this@EffectsPanel)
            for ((id, name) in Fx.pageModes) {
                val on = id == cur
                val lock = AosPlan.pageNeeds(id).takeIf { !AosPlan.has(this@EffectsPanel, it) }?.let { " · " + AosPlan.names[it] } ?: ""
                pills.addView(text(name + lock, 13f, if (on) p.onAccent else if (lock.isEmpty()) p.text else p.sub, on).apply {
                    gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
                    background = if (on) shape(p.accent, 16) else shape(p.surfaceHigh, 16)
                    setOnClickListener {
                        if (AosPlan.check(this@EffectsPanel, AosPlan.pageNeeds(id))) { Fx.setPageMode(this@EffectsPanel, id); paint() }
                        preview.play("pages")
                    }
                }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
            }
        }
        paint()
        addView(android.widget.HorizontalScrollView(this@EffectsPanel).apply { isHorizontalScrollBarEnabled = false; addView(pills) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    /** A small phone that runs the effects with the launcher's own code: wallpaper layer, three pages, a dock. */
    private inner class Preview {
        private val w = dp(160); private val h = dp(290)
        private val wallBmp = gradient(p.accent, p.bg)
        private val wall = WallpaperFxDrawable(wallBmp)
        private val screen = FrameLayout(this@EffectsPanel).apply {
            background = wall; clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(22).toFloat()) }
            }
        }
        private val scene = View(this@EffectsPanel).apply {
            // A few "scene" bubbles in front of the wallpaper, for the depth effect.
            background = object : android.graphics.drawable.Drawable() {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
                override fun draw(c: Canvas) { for (i in 0 until 7) c.drawCircle(bounds.width() * ((i * 37) % 100) / 100f, bounds.height() * ((i * 53) % 100) / 100f, dp(4 + i % 3 * 3).toFloat(), paint) }
                override fun setAlpha(a: Int) {}; override fun setColorFilter(f: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
        }
        private val pages = List(3) { i -> page(i) }
        private val strip = FrameLayout(this@EffectsPanel)
        private val dockRow = LinearLayout(this@EffectsPanel).apply { gravity = Gravity.CENTER; clipChildren = false }
        private val dock = FrameLayout(this@EffectsPanel).apply { background = glassShape(p, 16); clipChildren = false; addView(dockRow, FrameLayout.LayoutParams(-1, -1)) }
        private val panel = View(this@EffectsPanel).apply { background = glassShape(p, 18, true); alpha = 0f }
        val view = FrameLayout(this@EffectsPanel).apply {
            clipChildren = false
            addView(screen, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        }
        private var running: ValueAnimator? = null

        init {
            screen.addView(scene, FrameLayout.LayoutParams(-1, -1))
            for (pg in pages) strip.addView(pg, FrameLayout.LayoutParams(w, h - dp(56)))
            screen.addView(strip, FrameLayout.LayoutParams(-1, -1))
            repeat(4) { dockRow.addView(tile(dp(22)).apply { isClickable = true }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
            screen.addView(dock, FrameLayout.LayoutParams(-1, dp(36), Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
            screen.addView(panel, FrameLayout.LayoutParams(-1, dp(120), Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
            layoutPages(0f)
        }

        private fun gradient(a: Int, b: Int): Bitmap = Bitmap.createBitmap(240, 420, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 240f, 420f, a, b, Shader.TileMode.CLAMP) })
        }

        private fun tile(size: Int) = ImageView(this@EffectsPanel).apply {
            setImageDrawable(android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(p.accent); cornerRadius = size * 0.3f })))
        }

        private fun page(i: Int) = android.widget.GridLayout(this@EffectsPanel).apply {
            columnCount = 3; setPadding(dp(12), dp(40), dp(12), 0)
            repeat(9) { addView(tile(dp(30)).apply { alpha = 0.6f + 0.13f * i }, android.widget.GridLayout.LayoutParams().apply { width = dp(30); height = dp(30); setMargins(dp(7), dp(7), dp(7), dp(7)) }) }
        }

        /** [pos] in pages (0 .. 2): places the pages with the chosen transition and drifts the wallpaper. */
        private fun layoutPages(pos: Float) {
            val mode = Fx.pageMode(this@EffectsPanel)
            pages.forEachIndexed { i, pg ->
                val t = (i - pos).coerceIn(-1f, 1f)
                Fx.pageTransform(pg, t, mode)
                pg.translationX += t * w   // the strip itself does not scroll: each page sits at its own offset
                if (Math.abs(i - pos) >= 1f) pg.visibility = View.INVISIBLE
            }
            if (Fx.on(this@EffectsPanel, "parallax")) wall.offset = pos / 2f
            if (Fx.on(this@EffectsPanel, "depth")) scene.translationX = (1f - pos) * dp(14)
        }

        private fun anim(ms: Long, vararg values: Float, interp: android.animation.TimeInterpolator = Motion.easeOut, f: (Float) -> Unit) {
            running?.cancel()
            running = ValueAnimator.ofFloat(*values).apply {
                duration = Fx.dur(ms); interpolator = interp
                addUpdateListener { f(it.animatedValue as Float) }
                start()
            }
        }

        private fun reset() {
            running?.cancel()
            wall.zoom = 1f; wall.dim = 0f; wall.blur = 0f; strip.setRenderEffect(null); panel.alpha = 0f; panel.translationY = 0f
            scene.translationX = 0f; wall.parallax = Fx.on(this@EffectsPanel, "parallax")
            wall.tiltEnabled = Fx.on(this@EffectsPanel, "tilt"); wall.setTilt(0f, 0f)
            scene.translationY = 0f
            for (v in pages[0].let { (0 until (it as android.view.ViewGroup).childCount).map { i -> it.getChildAt(i) } }) { v.alpha = 0.6f; v.scaleX = 1f; v.scaleY = 1f }
            Fx.magnify(dockRow, null)
            layoutPages(0f)
        }

        fun play(id: String) {
            reset()
            val icons = (0 until pages[0].childCount).map { pages[0].getChildAt(it) as ImageView }
            when (id) {
                "tilt" -> anim(2400, 0f, 1f, 0f, -1f, 0f) { f ->
                    wall.tiltEnabled = Fx.on(this@EffectsPanel, "tilt")
                    if (wall.tiltEnabled) { wall.setTilt(f, -f); scene.translationX = -f * dp(8); scene.translationY = f * dp(10) }
                }
                "pages", "elastic", "parallax", "depth" -> anim(2200, 0f, 2f, 0f, interp = android.view.animation.AccelerateDecelerateInterpolator()) { pos ->
                    val over = if (id == "elastic" && Fx.on(this@EffectsPanel, "elastic")) 0.06f else 0f
                    layoutPages(pos); if (over > 0 && (pos > 1.9f)) strip.scaleX = 1f + over * (pos - 1.9f) * 10 else strip.scaleX = 1f
                }
                "zoom_fade", "glass", "wp_blur" -> {
                    val zoom = id != "wp_blur" && Fx.on(this@EffectsPanel, "zoom_fade")
                    val blur = Fx.on(this@EffectsPanel, "wp_blur") || id == "wp_blur"
                    val glass = id == "glass" && Fx.on(this@EffectsPanel, "glass")
                    anim(1600, 0f, 1f, 1f, 0f) { f ->
                        if (zoom) { wall.zoom = 1f + 0.08f * f; wall.dim = 0.5f * f }
                        if (blur && Fx.on(this@EffectsPanel, "wp_blur")) wall.blur = f
                        panel.alpha = f; panel.translationY = (1f - f) * dp(30)
                        strip.setRenderEffect(if (glass && f > 0.05f) android.graphics.RenderEffect.createBlurEffect(10f * f, 10f * f, Shader.TileMode.CLAMP) else null)
                    }
                }
                "wp_fade" -> wall.swap(gradient(p.bg, p.accent), Fx.on(this@EffectsPanel, "wp_fade")).also {
                    view.postDelayed({ wall.swap(wallBmp, Fx.on(this@EffectsPanel, "wp_fade")) }, Fx.dur(1200))
                }
                "theme_fade" -> {
                    val snap = Bitmap.createBitmap(screen.width.coerceAtLeast(1), screen.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { screen.draw(Canvas(it)) }
                    val cover = ImageView(this@EffectsPanel).apply { setImageBitmap(snap) }
                    screen.addView(cover, FrameLayout.LayoutParams(-1, -1))
                    wall.swap(gradient(if (p.uiDark) 0xFFE8EEF2.toInt() else 0xFF101418.toInt(), p.accent), false)
                    if (Fx.on(this@EffectsPanel, "theme_fade")) cover.animate().alpha(0f).setDuration(Fx.dur(420)).withEndAction { screen.removeView(cover) }.start()
                    else screen.removeView(cover)
                    view.postDelayed({ wall.swap(wallBmp, false) }, Fx.dur(1600))
                }
                "dynamic_color" -> {
                    val c = if (Fx.on(this@EffectsPanel, "dynamic_color")) getColor(android.R.color.system_accent1_400) else p.accent
                    for (iv in icons) ((iv.drawable as android.graphics.drawable.LayerDrawable).getDrawable(0) as GradientDrawable).setColor(c)
                    icons.forEach { it.invalidate() }
                }
                "liquid_open" -> {
                    val icon = icons[4]
                    if (Fx.on(this@EffectsPanel, "liquid_open")) {
                        icon.animate().scaleX(1.25f).scaleY(0.82f).setDuration(Fx.dur(90)).withEndAction {
                            icon.animate().scaleX(6f).scaleY(10f).alpha(1f).setDuration(Fx.dur(420)).setInterpolator(Fx.spring)
                                .withEndAction { icon.animate().scaleX(1f).scaleY(1f).alpha(0.6f).setDuration(Fx.dur(300)).start() }.start()
                        }.start()
                    } else icon.animate().scaleX(6f).scaleY(10f).setDuration(Fx.dur(250)).withEndAction { icon.scaleX = 1f; icon.scaleY = 1f }.start()
                }
                "morph", "icon_morph" -> {
                    val on = Fx.on(this@EffectsPanel, id)
                    for ((i, iv) in icons.withIndex()) iv.postDelayed({
                        iv.animate().scaleX(0.9f).scaleY(0.9f).setDuration(Motion.FAST).start()
                        if (on) Fx.morphIcon(iv, true)
                        iv.postDelayed({ iv.animate().scaleX(1f).scaleY(1f).setDuration(Fx.dur(380)).setInterpolator(Fx.settle(this@EffectsPanel)).start(); if (on) Fx.morphIcon(iv, false) }, Fx.dur(420))
                    }, Fx.dur(i * 60L))
                }
                "spring" -> for ((i, iv) in icons.withIndex()) iv.postDelayed({
                    iv.scaleX = 0.6f; iv.scaleY = 0.6f
                    iv.animate().scaleX(1f).scaleY(1f).setDuration(Fx.dur(520)).setInterpolator(Fx.settle(this@EffectsPanel)).start()
                }, Fx.dur(i * 40L))
                "stagger" -> if (Fx.on(this@EffectsPanel, "stagger")) Fx.stagger(icons)
                    else icons.forEach { it.alpha = 0f; it.animate().alpha(0.6f).setDuration(Motion.NORMAL).start() }
                "liquid_dock" -> if (Fx.on(this@EffectsPanel, "liquid_dock")) {
                    val loc = IntArray(2)
                    anim(1600, 0f, 1f, 0f) { f -> dockRow.getLocationOnScreen(loc); Fx.magnify(dockRow, loc[0] + dockRow.width * f) }
                    running?.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) = Fx.magnify(dockRow, null)
                    })
                }
            }
        }
    }

}

/** Effects on their own screen (the same page as the Effects tab of AOS Settings). */
class EffectsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Palette(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(48), dp(16), dp(28))
            addView(LinearLayout(this@EffectsActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@EffectsActivity).apply {
                    setImageResource(R.drawable.ic_chevron_left); setColorFilter(p.text); setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = circle(p.glassCircle); setOnClickListener { finish() }; pressFx(p)
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(this@EffectsActivity).apply { text = "Effects"; textSize = 26f; setTextColor(p.text); typeface = appFont(700); setPadding(dp(14), 0, 0, 0) })
            }, LinearLayout.LayoutParams(-1, dp(44)))
            addView(EffectsPanel(this@EffectsActivity).view(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(p.glassScreen)
            addView(ScrollView(this@EffectsActivity).apply { isVerticalScrollBarEnabled = false; addView(column) })
        })
    }
}
