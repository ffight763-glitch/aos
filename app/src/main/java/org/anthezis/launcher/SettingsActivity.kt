package org.anthezis.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * AOS Settings: header with title, category pills, a centred "recommended for developers" block with quick-access
 * cards, and expandable groups whose sub-items open the matching system screen. Plain views, no lists with heavy
 * adapters, so it opens instantly. The same layout is meant to move into the AOSSettings firmware package.
 */
class SettingsActivity : Activity() {
    private class Sub(val label: String, val action: () -> Unit)
    private class Group(val title: String, val icon: Int, val category: String, val subs: List<Sub>)

    private lateinit var p: Palette
    private lateinit var content: LinearLayout
    private lateinit var pills: LinearLayout
    // Only the desktop section is left: the system shortcuts already live in the launcher itself.
    private var category = "desktop"
    private val categories = listOf("desktop" to "Desktop")

    private fun prefs() = getSharedPreferences("aos_setup", Context.MODE_PRIVATE)

    private fun open(action: String) {
        try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
    }

    private fun gesturesOn() = prefs().getBoolean("gestures", true)

    private fun toggleGestures() {
        prefs().edit().putBoolean("gestures", !gesturesOn()).apply()
        SplitService.instance?.refreshGestures()
        rebuild()
    }

    private fun cycleTheme() {
        prefs().edit().putInt("theme", (prefs().getInt("theme", 0) + 1) % 3).apply()
        recreate()
    }

    private fun dirty() = prefs().edit().putBoolean("aos_dirty", true).apply()   // the Home rebuilds itself when it comes back

    private fun toggle(key: String, def: Boolean = true) {
        prefs().edit().putBoolean(key, !prefs().getBoolean(key, def)).putBoolean("aos_dirty", true).apply(); recreate()
    }

    private fun cycle(key: String, size: Int) {
        prefs().edit().putInt(key, (prefs().getInt(key, 0) + 1) % size).putBoolean("aos_dirty", true).apply(); recreate()
    }

    private fun onOff(key: String, def: Boolean = true) = if (prefs().getBoolean(key, def)) "on" else "off"

    private fun toHome(extra: String) = startActivity(Intent(this, HomeActivity::class.java).putExtra(extra, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    private val themeNames = listOf("Auto", "Light", "Dark")

    /** One setting: a switch (stored under [key]) or a row that does something. */
    private class Item(val title: String, val key: String? = null, val def: Boolean = true, val onSet: ((Boolean) -> Unit)? = null, val action: (() -> Unit)? = null)
    private class Section(val title: String, val icon: Int, val items: List<Item>)

    private fun choice(names: List<String>, key: String, def: Int = 0) = names[prefs().getInt(key, def).coerceIn(0, names.lastIndex)]

    private val sections get() = listOf(
        Section("Desktop", R.drawable.ic_start, listOf(
            Item("Edit the desktop: pages, widgets, folders", action = { toHome("aos_edit") }),
            Item("Widgets, recents, updates (manager panel)", action = { toHome("aos_manager") }),
            Item("Wallpaper and themes", action = { toHome("aos_pick_wallpaper") }),
            Item("Unlock all folders", action = { prefs().edit().remove("locked_folders").putBoolean("aos_dirty", true).apply(); android.widget.Toast.makeText(this, "All folders are unlocked", android.widget.Toast.LENGTH_SHORT).show() }),
            Item("Layout lock", "layout_locked", def = false))),
        Section("Pages", R.drawable.ic_folder, listOf(
            Item("Feed page", "feed"),
            Item("Folders page", "folders_page"),
            Item("Page dots", "show_page_dots", def = false),
            Item("Desktop indicator", "show_desktop_dots", def = false))),
        // One place to turn the moving parts off (smoother on slow phones, easier on the battery).
        Section("Effects & animations", R.drawable.ic_style, listOf(
            Item("Animated theme (moving scenes: stars, fish, petals…)", "anim_theme"),
            Item("Effects (glass, parallax, motion, springs…)", "fx_master", onSet = { Fx.refresh(this) }),
            Item("Tune each effect…", action = { startActivity(android.content.Intent(this, EffectsActivity::class.java)) }))),
        Section("Icons", R.drawable.ic_style, listOf(
            Item("Icon shape: ${choice(listOf("Squircle", "Circle", "Rounded", "Square", "Teardrop"), "icon_shape")}", action = { cycle("icon_shape", 5) }),
            Item("Icon animation: ${choice(listOf("Off", "Float", "Pulse", "Hop"), "icon_anim")}", action = { cycle("icon_anim", 4) }),
            Item("Icon size: ${choice(listOf("90%", "100%", "110%"), "icon_scale", 1)}", action = { prefs().edit().putInt("icon_scale", (prefs().getInt("icon_scale", 1) + 1) % 3).putBoolean("aos_dirty", true).apply(); recreate() }))),
        Section("Panels", R.drawable.ic_tools, listOf(
            Item("AOS top bar in apps", "app_topbar"),
            Item("Volume panel", "volume_panel"),
            Item("Power menu", "power_menu"),
            Item("Charging effect", "charging_fx"),
            Item("Gestures", "gestures", onSet = { SplitService.instance?.refreshGestures() }),
            Item("AOS navigation buttons", "aos_navbar"),
            Item("Navigation: icons instead of dots", "navbar_icons", def = false))),
        Section("Lock screen", R.drawable.ic_lock, listOf(
            Item("AOS lock screen", "lock_screen"))),
        Section("Keyboard", R.drawable.ic_language, listOf(
            Item("Turn on AOS Keyboard", action = {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
            }),
            Item("Choose keyboard", action = {
                getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker()
            }),
            Item("Opposite tone (dark keys in light mode)", "kb_invert", def = false),
            Item("Suggestions and autocorrect (T9)", "kb_autocorrect"),
            Item("Round keys", "kb_round"),
            Item("Vibrate on keys", "kb_vibe"))),
    )

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res); setColorFilter(tint); layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pills = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_left); setColorFilter(p.text); setPadding(dp(9), dp(9), dp(9), dp(9))
            background = circle(p.glassCircle); contentDescription = "Back"
            setOnClickListener { finish() }; pressFx(p)
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(text("Settings", 26f, p.text, true).apply { setPadding(dp(14), 0, 0, 0) })
        }
        val pillScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(pills)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(48), dp(16), dp(28))
            addView(header, LinearLayout.LayoutParams(-1, dp(44)))
            addView(pillScroll, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })
            addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(p.glassScreen)
            addView(ScrollView(this@SettingsActivity).apply { isVerticalScrollBarEnabled = false; addView(column) })
        })
        addAosTopBar(p)
        tab = intent?.getStringExtra("section")?.let { if (it == "Main" || it == "A-Apps") "Home" else it } ?: tab
        rebuild()
    }

    /** Open tab: "Main" (update banner), or one section; Effects opens its own screen. */
    private var tab = "Home"

    private fun rebuild() {
        // Tabs under the header: Main, Effects, then one per section.
        pills.visibility = View.VISIBLE
        pills.removeAllViews()
        for (name in listOf("Home", "Effects") + sections.map { it.title }) {
            val on = name == tab
            pills.addView(text(name, 14f, if (on) p.onAccent else p.text, on).apply {
                gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0)
                background = if (on) shape(p.accent, 20) else glassShape(p, 20)
                setOnClickListener {
                    tab = name; rebuild()
                }
                pressFx(p, round = false)
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
        }
        content.removeAllViews()
        val sec = sections.firstOrNull { it.title == tab }
        // Desktop is the whole desktop manager in one window: accordion sections with their controls inside.
        if (tab == "Desktop") content.addView(DesktopSettings(this, p).view(), LinearLayout.LayoutParams(-1, -2))
        else if (tab == "Effects") content.addView(EffectsPanel(this).view(), LinearLayout.LayoutParams(-1, -2))
        else if (sec != null) content.addView(sectionCard(sec), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        // Home: update banner, then sliders of A-Apps, themes and plans.
        else content.addView(SettingsHome(this, p).view(), LinearLayout.LayoutParams(-1, -2))
    }

    /** First card: the launcher's version and a big "Check for updates" button. */
    private fun updateBanner() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = shape(p.accent, 26); setPadding(dp(18), dp(18), dp(18), dp(16))
        addView(LinearLayout(this@SettingsActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_boost, p.onAccent, 28))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(text("AOS Launcher", 19f, p.onAccent, true))
                addView(text("Version ${Updater.currentVersion(this@SettingsActivity).second}", 13f, p.onAccent).apply { alpha = 0.8f })
            })
        })
        addView(text("Check for updates", 15f, p.accent, true).apply {
            gravity = Gravity.CENTER; background = shape(p.onAccent, 20)
            setOnClickListener { Updater.check(this@SettingsActivity, true) }; pressFx(p, round = false)
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(14) })
    }

    private fun entryCard(iconRes: Int, title: String, hint: String, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 24); setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(icon(iconRes, p.accent, 26))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
            addView(text(title, 16f, p.text, true)); addView(text(hint, 12f, p.sub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(R.drawable.ic_chevron_right, p.sub, 18))
        setOnClickListener { onClick() }; pressFx(p, round = false)
    }

    /** A section tile: its title, then a switch per function (or a row that opens it). */
    private fun sectionCard(sec: Section) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = glassShape(p, 24); setPadding(dp(16), dp(14), dp(16), dp(6))
        addView(LinearLayout(this@SettingsActivity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(4))
            addView(icon(sec.icon, p.accent, 20))
            addView(text(sec.title.uppercase(), 12f, p.accent, true).apply { letterSpacing = 0.08f; setPadding(dp(10), 0, 0, 0) })
        })
        for (it in sec.items) addView(itemRow(it), LinearLayout.LayoutParams(-1, dp(50)))
    }

    private fun itemRow(item: Item): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(item.title, 15f, p.text), LinearLayout.LayoutParams(0, -2, 1f))
        val key = item.key
        if (key != null) {
            val sw = android.widget.Switch(this@SettingsActivity).apply {
                isChecked = prefs().getBoolean(key, item.def)
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.sub))
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf((p.accent and 0xFFFFFF) or 0x66000000, (p.sub and 0xFFFFFF) or 0x44000000))
                setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean(key, on).putBoolean("aos_dirty", true).apply(); item.onSet?.invoke(on) }
            }
            addView(sw)
            setOnClickListener { sw.toggle() }
        } else {
            addView(icon(R.drawable.ic_chevron_right, p.sub, 16))
            setOnClickListener { item.action?.invoke() }
        }
    }
}
