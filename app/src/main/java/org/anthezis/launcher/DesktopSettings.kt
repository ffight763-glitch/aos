package org.anthezis.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AOS Settings > Desktop: the whole desktop manager in one window. Accordion sections (pages, apps, widgets, wallpaper,
 * recents, folders) show their controls right inside; every change is saved and the Home rebuilds when it comes back.
 */
class DesktopSettings(private val act: Activity, private val p: Palette) {
    private val prefs = act.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)
    private fun dp(v: Int) = act.dp(v)
    private fun dirty() = prefs.edit().putBoolean("aos_dirty", true).apply()

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    private val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    fun view(): View { paint(); return root }

    /** Which accordion sections are open; survives a repaint. */
    private val open = mutableSetOf("Pages")

    private fun paint() {
        root.removeAllViews()
        root.addView(button("Edit on the desktop", primary = true) {
            act.startActivity(Intent(act, HomeActivity::class.java).putExtra("aos_edit", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        section("Pages", R.drawable.ic_start, prefs.getInt("pages", 3).let { if (it == 1) "1 desktop" else "$it desktops" }) { pages(it) }
        section("Apps", R.drawable.ic_folder, "Grid ${prefs.getInt("grid_cols", 4)} × ${prefs.getInt("grid_rows", 2)}") { apps(it) }
        section("Dock & panels", R.drawable.ic_home, "Dock: " + (dockStyles.firstOrNull { it.first == prefs.getString("dock_style", "glass") }?.second ?: "Glass")) { dock(it) }
        section("Widgets", R.drawable.ic_tools, "Put widgets on desktop 2") { widgets(it) }
        section("Wallpaper & themes", R.drawable.ic_style, AosStyles.find(AosStyles.selectedId(act)).name) { wallpaper(it) }
        section("Clock style", R.drawable.ic_style,
            ClockStyles.options.first { it.first == ClockStyles.selected(act) }.second) { clockStyles(it) }
        section("Recents", R.drawable.ic_split, recentsNames.firstOrNull { it.first == prefs.getString("recents_layout", "vertical") }?.second ?: "") { recents(it) }
        section("Folders & layout", R.drawable.ic_lock, if (prefs.getBoolean("layout_locked", false)) "Layout locked" else "Layout unlocked") { folders(it) }
    }

    /** One accordion card: the header row folds the content in or out. */
    private fun section(title: String, iconRes: Int, hint: String, fill: (LinearLayout) -> Unit) {
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(12)) }
        val isOpen = title in open
        if (isOpen) fill(body) else body.visibility = View.GONE
        val chevron = ImageView(act).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(p.sub); rotation = if (isOpen) 90f else 0f }
        val head = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0)
            addView(ImageView(act).apply { setImageResource(iconRes); setColorFilter(p.accent) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(text(title, 16f, p.text, true))
                addView(text(hint, 12f, p.sub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)))
            setOnClickListener {
                val show = body.visibility != View.VISIBLE
                if (show) { open += title; if (body.childCount == 0) fill(body); body.alpha = 0f; body.visibility = View.VISIBLE; body.animate().alpha(1f).setDuration(Motion.NORMAL).start() }
                else { open -= title; body.visibility = View.GONE }
                chevron.animate().rotation(if (show) 90f else 0f).setDuration(Motion.FAST).start()
            }
        }
        root.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = act.glassShape(p, 24)
            addView(head, LinearLayout.LayoutParams(-1, dp(66))); addView(body)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    // ---- building blocks ----
    private fun label(t: String) = text(t, 13f, p.sub).apply { setPadding(0, dp(10), 0, dp(6)) }

    private fun clockStyles(b: LinearLayout) {
        b.addView(label("The same clock style on the main desktop and lock screen"))
        b.addView(pills(ClockStyles.options, ClockStyles.selected(act)) { id ->
            prefs.edit().putString("clock_style", id).putBoolean("aos_dirty", true).apply()
        })
    }

    private fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = text(t, 15f, if (primary) p.onAccent else p.text, true).apply {
        gravity = Gravity.CENTER; background = if (primary) act.shape(p.accent, 20) else act.shape(p.surfaceHigh, 18)
        setOnClickListener { onClick() }; pressFx(p, round = false)
    }

    private fun switchRow(t: String, key: String, def: Boolean = true, onSet: ((Boolean) -> Unit)? = null) = LinearLayout(act).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(t, 15f, p.text), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = android.widget.Switch(act).apply {
            isChecked = prefs.getBoolean(key, def)
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf((p.accent and 0xFFFFFF) or 0x66000000, (p.sub and 0xFFFFFF) or 0x44000000))
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).putBoolean("aos_dirty", true).apply(); onSet?.invoke(on) }
        }
        addView(sw); setOnClickListener { sw.toggle() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(50))
    }

    /** A row of choice pills; the chosen one is filled with the accent. */
    private fun pills(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): View {
        val row = LinearLayout(act)
        fun draw(cur: String) {
            row.removeAllViews()
            for ((id, name) in options) {
                val on = id == cur
                row.addView(text(name, 14f, if (on) p.onAccent else p.text, on).apply {
                    gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
                    background = if (on) act.shape(p.accent, 18) else act.shape(p.surfaceHigh, 18)
                    setOnClickListener { onPick(id); draw(id) }
                }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
            }
        }
        draw(current)
        return HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    // ---- sections ----
    private fun pages(b: LinearLayout) {
        val n = prefs.getInt("pages", 3).coerceIn(1, 5)
        b.addView(label("Desktops"))
        b.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("−") { if (n > 1) { prefs.edit().putInt("pages", n - 1).apply(); dirty(); repaint("Pages") } }, LinearLayout.LayoutParams(dp(48), dp(48)))
            for (i in 1..n) addView(text("$i", 15f, p.text, true).apply { gravity = Gravity.CENTER; background = act.shape(p.surfaceHigh, 12, p.stroke) },
                LinearLayout.LayoutParams(dp(36), dp(56)).apply { marginStart = dp(8) })
            addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button("+") { if (n < 5) { prefs.edit().putInt("pages", n + 1).apply(); dirty(); repaint("Pages") } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        b.addView(switchRow("Feed page", "feed"))
        b.addView(switchRow("Folders page", "folders_page"))
        b.addView(switchRow("Page dots", "show_page_dots", false))
        b.addView(switchRow("Desktop indicator", "show_desktop_dots", false))
    }

    private fun apps(b: LinearLayout) {
        b.addView(switchRow("App stack on the first page", "stack"))
        b.addView(label("Columns"))
        b.addView(pills(listOf("3" to "3", "4" to "4", "5" to "5"), "${prefs.getInt("grid_cols", 4)}") { prefs.edit().putInt("grid_cols", it.toInt()).apply(); dirty() })
        b.addView(label("Rows"))
        b.addView(pills((2..5).map { "$it" to "$it" }, "${prefs.getInt("grid_rows", 2)}") { prefs.edit().putInt("grid_rows", it.toInt()).apply(); dirty() })
        b.addView(label("Icon size"))
        b.addView(pills(listOf("0" to "90%", "1" to "100%", "2" to "110%"), "${prefs.getInt("icon_scale", 1)}") { prefs.edit().putInt("icon_scale", it.toInt()).apply(); dirty() })
        b.addView(label("Home style"))
        b.addView(pills(listOf("android" to "ADroid", "tiles" to "Windows Phone tiles"), prefs.getString("home_style", "android") ?: "android") {
            prefs.edit().putString("home_style", it).apply(); dirty()
        })
        b.addView(button("Choose home apps") {
            act.startActivity(Intent(act, HomeActivity::class.java).putExtra("aos_choose_apps", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
    }

    private val dockStyles = listOf("glass" to "Glass", "solid" to "Solid", "clear" to "Clear", "compact" to "Compact", "semicircle" to "Semicircle")

    /** Variations of the dock, its buttons, the status plate above it and the cards behind the clock and the app stack. */
    private fun dock(b: LinearLayout) {
        fun pick(key: String, def: String, options: List<Pair<String, String>>) =
            pills(options, prefs.getString(key, def) ?: def) { prefs.edit().putString(key, it).apply(); dirty() }
        b.addView(label("Dock style"))
        b.addView(pick("dock_style", "glass", dockStyles))
        b.addView(label("Dock buttons"))
        b.addView(pick("dock_buttons", "circle", listOf("circle" to "Circles", "square" to "Squares", "none" to "No plate")))
        b.addView(label("Top bar (time, network, battery) — with the Semicircle dock it sits inside the arc, under the apps"))
        b.addView(pick("status_plate", "full", listOf("full" to "Full", "mini" to "Mini", "hidden" to "Off")))
        b.addView(label("Desktop panels (clock and apps)"))
        b.addView(pick("desk_panels", "none", listOf("none" to "No background", "glass" to "Glass", "solid" to "Solid")))
    }

    private val baseWidgets = listOf("weather" to "Weather", "battery" to "Battery", "calendar" to "Calendar", "notes" to "Notes", "clock" to "Clock")

    private fun widgets(b: LinearLayout) {
        b.addView(label("Tap a widget to put it on desktop 2. Resize and move it there in edit mode."))
        for ((id, name) in baseWidgets) b.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(name, 15f, p.text), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("+ Add", 14f, p.accent, true).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8)); background = act.shape(p.surfaceHigh, 16)
                setOnClickListener { android.widget.Toast.makeText(act, if (addWidget(id)) "$name added to desktop 2" else "Desktop 2 is full", android.widget.Toast.LENGTH_SHORT).show() }
            })
        }, LinearLayout.LayoutParams(-1, dp(52)))
        b.addView(button("Clear all widgets") {
            val e = prefs.edit(); for (pg in 2..5) { e.putString("wlay_$pg", ""); for (s in 0 until 6) e.remove("wslot_${pg}_$s") }
            e.putBoolean("aos_dirty", true).apply()
            android.widget.Toast.makeText(act, "All widgets removed", android.widget.Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
    }

    /** Same layout format and grid (4 × 6, a new widget is 2 × 2) as the Home's desktop 2. */
    private fun addWidget(type: String): Boolean {
        data class P(val c: Int, val r: Int, val w: Int, val h: Int)
        val raw = prefs.getString("wlay_2", null)
        val items = mutableListOf<String>()
        if (raw == null) for (i in 0 until 6) prefs.getString("wslot_2_$i", null)?.let { items += "$it,${(i % 2) * 2},${(i / 2) * 2},2,2" }
        else items += raw.split(";").filter { it.isNotBlank() }
        val placed = items.mapNotNull { s -> s.split(",").takeIf { it.size >= 5 }?.let { q -> P(q[1].toInt(), q[2].toInt(), q[3].toInt(), q[4].toInt()) } }
        fun fits(c: Int, r: Int) = c + 2 <= 4 && r + 2 <= 6 && placed.none { c < it.c + it.w && it.c < c + 2 && r < it.r + it.h && it.r < r + 2 }
        for (r in 0..4) for (c in 0..2) if (fits(c, r)) {
            items += "$type,$c,$r,2,2"
            prefs.edit().putString("wlay_2", items.joinToString(";")).putInt("pages", maxOf(prefs.getInt("pages", 3), 2)).putBoolean("aos_dirty", true).apply()
            return true
        }
        return false
    }

    private fun wallpaper(b: LinearLayout) {
        b.addView(ThemeGallery(act, p).view())
        b.addView(label("Light or dark"))
        b.addView(pills(listOf("0" to "Auto", "1" to "Light", "2" to "Dark"), "${prefs.getInt("theme", 0)}") {
            prefs.edit().putInt("theme", it.toInt()).putBoolean("aos_dirty", true).apply(); reopen()
        })
        b.addView(switchRow("Animated theme", "anim_theme"))
        b.addView(button("Own picture from the gallery") {
            prefs.edit().putString("wallpaper", "custom").putBoolean("aos_dirty", true).apply()
            try { act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) } catch (_: Exception) {}
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
    }

    private val recentsNames = listOf("vertical" to "Grid", "horizontal" to "Row", "list" to "List")

    private fun recents(b: LinearLayout) {
        b.addView(label("Layout of the recent apps screen (swipe up from the bottom edge and hold)"))
        b.addView(pills(recentsNames, prefs.getString("recents_layout", "vertical") ?: "vertical") { prefs.edit().putString("recents_layout", it).apply() })
    }

    private fun folders(b: LinearLayout) {
        b.addView(switchRow("Layout lock", "layout_locked", false))
        b.addView(button("Unlock all folders") {
            prefs.edit().remove("locked_folders").putBoolean("aos_dirty", true).apply()
            android.widget.Toast.makeText(act, "All folders are unlocked", android.widget.Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
    }

    private fun repaint(keepOpen: String) { open += keepOpen; paint() }

    /** Rebuild the settings in the new theme's colours and come back to this tab. */
    private fun reopen() { act.intent.putExtra("section", "Desktop"); act.recreate() }
}
