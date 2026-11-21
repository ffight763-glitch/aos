package org.anthezis.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

/** Automatic app categories: Android's own category when the app declares one, otherwise by package name. */
object AppCategories {
    val order = listOf("Communication", "Internet", "Media", "Tools", "System", "Games", "Social", "Maps", "News", "Other")

    fun of(ri: ResolveInfo): String {
        val pkg = ri.activityInfo.packageName.lowercase()
        if (ri.activityInfo.name.endsWith("TubeActivity")) return "Media"
        if (Build.VERSION.SDK_INT >= 26) {
            when (ri.activityInfo.applicationInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> return "Games"
                ApplicationInfo.CATEGORY_SOCIAL -> return "Social"
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return "Media"
                ApplicationInfo.CATEGORY_MAPS -> return "Maps"
                ApplicationInfo.CATEGORY_NEWS -> return "News"
            }
        }
        fun has(vararg k: String) = k.any { pkg.contains(it) }
        return when {
            has("dialer", "contacts", "messag", "sms", "telecom") -> "Communication"
            has("chrome", "browser", "firefox") -> "Internet"
            has("camera", "photo", "gallery", "music", "video", "player", "youtube") -> "Media"
            has("clock", "documentsui", "files", "calculator", "calendar", "weather") -> "Tools"
            has("settings", "stk", "setupwizard") -> "System"
            else -> "Other"
        }
    }
}

/** App groups (folders), stored as "name<TAB>pkg,pkg" lines. */
object Groups {
    private fun prefs(c: Context) = c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)

    fun all(c: Context): LinkedHashMap<String, MutableList<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (line in prefs(c).getString("groups", "").orEmpty().lines().filter { it.isNotBlank() }) {
            val parts = line.split("\t")
            map[parts[0]] = parts.getOrNull(1).orEmpty().split(",").filter { it.isNotBlank() }.toMutableList()
        }
        return map
    }

    private fun save(c: Context, m: Map<String, List<String>>) =
        prefs(c).edit().putString("groups", m.entries.joinToString("\n") { it.key + "\t" + it.value.joinToString(",") }).apply()

    fun put(c: Context, name: String, packages: List<String>) {
        val clean = name.trim().replace("\t", " ").replace("\n", " ")
        if (clean.isEmpty()) return
        val groups = all(c)
        groups[clean] = packages.distinct().toMutableList()
        save(c, groups)
    }

    fun delete(c: Context, name: String) {
        val groups = all(c)
        groups.remove(name)
        save(c, groups)
    }

    fun add(c: Context, name: String, pkg: String) {
        val clean = name.trim().replace("\t", " ").replace("\n", " ")
        if (clean.isEmpty()) return
        val m = all(c)
        val list = m.getOrPut(clean) { mutableListOf() }
        if (pkg !in list) list.add(pkg)
        save(c, m)
    }

    fun remove(c: Context, name: String, pkg: String) {
        val m = all(c)
        m[name]?.remove(pkg)
        if (m[name].isNullOrEmpty()) m.remove(name)
        save(c, m)
    }
}

/** Apps launched through AOS Home, newest first. */
object Recents {
    private fun prefs(c: Context) = c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)
    fun get(c: Context): List<String> = prefs(c).getString("recent", "").orEmpty().split(",").filter { it.isNotBlank() }
    fun push(c: Context, pkg: String) =
        prefs(c).edit().putString("recent", (listOf(pkg) + get(c).filter { it != pkg }).take(12).joinToString(",")).apply()
    fun clear(c: Context) = prefs(c).edit().remove("recent").apply()
    fun remove(c: Context, pkg: String) =
        prefs(c).edit().putString("recent", get(c).filter { it != pkg }.joinToString(",")).apply()
}

/**
 * App drawer: toolbar (title, options, close), search, A-Z index, pill navigation (All, Recent, Groups, categories)
 * and horizontally swipeable pages with vertically scrolling app grids, like the home screen pager.
 */
class AppDrawer(
    private val act: Activity,
    private val p: Palette,
    private val apps: List<ResolveInfo>,
    private val onClose: () -> Unit,
    private val launch: (ResolveInfo) -> Unit,
    private val onAddHome: (ResolveInfo) -> Unit = {},
    /** Long-press on an app: the home screen takes over the drag (drop on the desktop = add it there). */
    private val onPickUp: (ResolveInfo, View) -> Unit = { _, _ -> },
) {
    /** The long-press menu, for a press that was released without dragging. */
    fun showMenu(ri: ResolveInfo) = showAppMenu(ri, null) {}

    private val pm = act.packageManager
    private val screenW = act.resources.displayMetrics.widthPixels
    private val rulerW get() = 0   // the A-Z ruler is gone: pages must be exactly as wide as the pager or they drift to the right
    /** Width of the app area: screen minus side margins and the right ruler. */
    private val contentW get() = screenW - dp(24) - rulerW
    private var query = ""
    private var reversed = false
    private var selected = 0
    private var pageNames = listOf<String>()
    private val scrollers = mutableListOf<ScrollView>()

    /** True when the current page's list is scrolled to the top, so a swipe down should close the drawer instead of scrolling. */
    fun atTop(): Boolean = (scrollers.getOrNull(selected)?.scrollY ?: 0) <= 0
    private val headerMaps = mutableListOf<Map<String, View>>()
    private var rulerLetters = listOf<String>()

    private lateinit var pager: Pager
    private lateinit var rail: LinearLayout
    private lateinit var railScroll: HorizontalScrollView
    private lateinit var searchBox: EditText
    private lateinit var ruler: LinearLayout
    private lateinit var title: TextView

    private fun dp(v: Int) = act.dp(v)
    private fun name(ri: ResolveInfo) = ri.loadLabel(pm).toString()

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    private fun iconButton(res: Int, onClick: (View) -> Unit) = ImageView(act).apply {
        setImageResource(res); setColorFilter(p.text); setPadding(dp(10), dp(10), dp(10), dp(10))
        background = act.circle(p.surfaceHigh); setOnClickListener { onClick(it) }
    }

    private fun railIcon(page: String) = when (page) {
        "All" -> R.drawable.ic_start
        "Recent" -> R.drawable.ic_history
        "Groups" -> R.drawable.ic_folder
        "Communication" -> R.drawable.ic_chat
        "Internet" -> R.drawable.ic_language
        "Media" -> R.drawable.ic_play
        "Tools" -> R.drawable.ic_tools
        "System" -> R.drawable.ic_info
        "Social" -> R.drawable.ic_person
        "Maps" -> R.drawable.ic_pin
        "News" -> R.drawable.ic_legal
        else -> R.drawable.ic_star
    }

    private val column: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(44), dp(12), dp(12))
    }

    val view: FrameLayout = FrameLayout(act).apply {
        setBackgroundColor(p.glassScreen)
        addView(column, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * Stops the background processes of every launcher app and empties this app's cache. Android only lets a
     * normal app kill background processes; wiping other apps' caches needs a system permission, which the AOS
     * firmware build will grant to the launcher.
     */
    private fun boost() {
        val am = act.getSystemService(android.app.ActivityManager::class.java)
        var stopped = 0
        for (ri in apps) {
            val pkg = ri.activityInfo.packageName
            if (pkg == act.packageName) continue
            am.killBackgroundProcesses(pkg)
            stopped++
        }
        act.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        android.widget.Toast.makeText(act, "Background stopped for $stopped apps", android.widget.Toast.LENGTH_SHORT).show()
    }

    init {
        title = text("Apps", 24f, p.text, true)
        val toolbar = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            // View mode: tiles (grid) or a list of cards; the icon shows what a tap switches to.
            viewButton = iconButton(if (listMode()) R.drawable.ic_start else R.drawable.ic_list) { toggleView() }
            addView(viewButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            addView(iconButton(R.drawable.ic_search) { toggleSearch() }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            addView(iconButton(R.drawable.ic_more) { showOptions(it) }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            addView(iconButton(R.drawable.ic_close) { onClose() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val search = EditText(act).apply {
            hint = "Search apps"; setHintTextColor(p.sub); setTextColor(p.text); typeface = appFont(400)
            background = act.shape(p.surface, 22); isSingleLine = true
            setPadding(dp(18), dp(10), dp(18), dp(10))
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString(); selected = 0; refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        searchBox = search
        // Rounded header with its own background.
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = act.glassShape(p, 28)
            setPadding(dp(18), dp(12), dp(12), dp(14))
            addView(toolbar, LinearLayout.LayoutParams(-1, dp(44)))
            addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        // Floating category bar at the bottom: horizontal, glass, scrolls sideways when there are many categories.
        rail = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        railScroll = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(rail) }
        val railBar = FrameLayout(act).apply { addView(railScroll) }
        ruler = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setOnTouchListener { v, ev ->
                if (rulerLetters.isNotEmpty()) {
                    val i = (ev.y / v.height * rulerLetters.size).toInt().coerceIn(0, rulerLetters.lastIndex)
                    jump(rulerLetters[i], smooth = false)
                }
                true
            }
        }
        pager = Pager(act) { page -> select(page) }.apply { isFillViewport = true }

        val body = LinearLayout(act).apply {
            addView(pager, LinearLayout.LayoutParams(0, -1, 1f))
            // The A-Z ruler on the right was removed; the list is searched with the search button instead.
        }
        column.addView(header, LinearLayout.LayoutParams(-1, -2))
        // Slider tab bar right under the header: pill tabs (icon + title) that scroll sideways.
        column.addView(railBar, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })

        view.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_boost); setColorFilter(p.onAccent)
            background = act.circle(p.accent); setPadding(dp(16), dp(16), dp(16), dp(16)); elevation = dp(6).toFloat()
            contentDescription = "Close background apps"
            setOnClickListener { boost() }
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(24), dp(40)) })
        refresh()
    }

    private fun toggleSearch() {
        val imm = act.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (searchBox.visibility == View.VISIBLE) {
            searchBox.setText("")
            imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
            searchBox.animate().alpha(0f).setDuration(120).withEndAction { searchBox.visibility = View.GONE }.start()
        } else {
            searchBox.alpha = 0f; searchBox.visibility = View.VISIBLE
            searchBox.animate().alpha(1f).setDuration(150).start()
            searchBox.requestFocus(); imm.showSoftInput(searchBox, 0)
        }
    }

    private fun showOptions(anchor: View) {
        PopupMenu(act, anchor).apply {
            menu.add(if (reversed) "Sort A → Z" else "Sort Z → A")
            menu.add("Clear recents")
            setOnMenuItemClickListener {
                if (it.order == 0) reversed = !reversed else Recents.clear(act)
                refresh(); true
            }
        }.show()
    }

    private fun filtered(): List<ResolveInfo> {
        val sorted = apps.sortedBy { name(it).lowercase() }.let { if (reversed) it.reversed() else it }
        return if (query.isBlank()) sorted else sorted.filter { name(it).contains(query, true) }
    }

    private fun letterOf(ri: ResolveInfo): String {
        val c = name(ri).trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c.isLetter()) c.toString() else "#"
    }

    private fun refresh() {
        val list = filtered()
        val byCategory = list.groupBy { AppCategories.of(it) }
        val categories = AppCategories.order.filter { byCategory.containsKey(it) }
        // All first, then the categories; the user's folders (groups) and the recent apps close the bar.
        pageNames = listOf("All") + categories
        selected = selected.coerceIn(0, pageNames.lastIndex)

        scrollers.clear(); headerMaps.clear()
        val strip = LinearLayout(act)
        val recentPkgs = Recents.get(act)
        pageNames.forEach { page ->
            val pageView = when (page) {
                "All" -> appsPage(list, true)
                "Recent" -> appsPage(recentPkgs.mapNotNull { pkg -> list.firstOrNull { it.activityInfo.packageName == pkg } }, false,
                    "No recent apps yet")
                "Groups" -> groupsPage()
                else -> appsPage(byCategory[page].orEmpty(), true)
            }
            strip.addView(pageView, LinearLayout.LayoutParams(contentW, -1))
        }
        pager.removeAllViews()
        pager.addView(strip, FrameLayout.LayoutParams(-2, -1))
        pager.post { pager.scrollTo(selected * pager.width, 0) }
        buildRuler(list)
        buildRail()
    }

    private fun buildRuler(list: List<ResolveInfo>) {
        ruler.removeAllViews()
        rulerLetters = list.map { letterOf(it) }.distinct().sorted()
        for (l in rulerLetters) {
            ruler.addView(text(l, 11f, p.accent, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun buildRail() {
        rail.removeAllViews()
        pageNames.forEachIndexed { i, n ->
            // A pill tab: icon on the left, title on the right; the selected one is filled with the accent.
            rail.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(act).apply { setImageResource(railIcon(n)) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(text(when (n) { "Communication" -> "Comms"; "Groups" -> "Folders"; else -> n }, 13f, p.sub, true).apply {
                    maxLines = 1; gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
                contentDescription = n
                setOnClickListener { goTo(i) }
                setPadding(dp(14), dp(8), dp(16), dp(8))
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        styleRail()
    }

    private fun styleRail() {
        for (i in 0 until rail.childCount) {
            val on = i == selected
            (rail.getChildAt(i) as LinearLayout).apply {
                val c = if (on) p.onAccent else p.sub
                (getChildAt(0) as ImageView).setColorFilter(c)
                (getChildAt(1) as TextView).setTextColor(c)
                background = if (on) act.shape(p.accent, 19) else act.shape(p.surfaceHigh, 19)
            }
        }
        rail.getChildAt(selected)?.let { v -> railScroll.post { railScroll.smoothScrollTo(maxOf(0, v.left - dp(80)), 0) } }
        title.text = if (pageNames.getOrNull(selected).let { it == null || it == "All" }) "Apps" else pageNames[selected]
    }

    // While a tab tap is moving the pager, the pages it passes on the way must not re-highlight tabs or move the tab bar.
    private var navTarget = -1

    private fun select(page: Int) {
        if (navTarget >= 0) { if (page != navTarget) return; navTarget = -1 }
        if (page == selected || page !in 0 until pageNames.size) return
        selected = page
        styleRail()
    }

    private fun goTo(i: Int) {
        if (i == selected) return
        selected = i; navTarget = i; styleRail()
        // A far tab jumps straight there, a neighbour slides.
        if (Math.abs(i - ((pager.scrollX + pager.width / 2) / pager.width.coerceAtLeast(1))) > 1) pager.scrollTo(i * pager.width, 0)
        else pager.smoothScrollTo(i * pager.width, 0)
        pager.postDelayed({ navTarget = -1 }, 450)
    }

    private fun jump(letter: String, smooth: Boolean = true) {
        val idx = if (headerMaps.getOrNull(selected)?.containsKey(letter) == true) selected
        else headerMaps.indexOfFirst { it.containsKey(letter) }
        if (idx < 0) return
        if (idx != selected) goTo(idx)
        val target = headerMaps[idx][letter] ?: return
        val sv = scrollers[idx]
        if (smooth) sv.post { sv.smoothScrollTo(0, target.top) } else sv.scrollTo(0, target.top)
    }

    private fun appsPage(list: List<ResolveInfo>, headers: Boolean, empty: String = "No apps found"): View {
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(120)) }
        val map = LinkedHashMap<String, View>()
        if (list.isEmpty()) {
            content.addView(text(empty, 14f, p.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0) })
        } else if (headers) {
            for ((letter, group) in list.groupBy { letterOf(it) }.toSortedMap(if (reversed) compareByDescending { it } else compareBy { it })) {
                val h = text(letter, 13f, p.accent, true).apply { setPadding(dp(6), dp(12), 0, dp(6)) }
                map[letter] = h
                content.addView(h)
                content.addView(grid(group))
            }
        } else content.addView(grid(list))
        val sv = ScrollView(act).apply {
            isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            addView(content)
        }
        scrollers.add(sv); headerMaps.add(map)
        return sv
    }

    private lateinit var viewButton: ImageView
    private fun listMode() = act.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getInt("drawer_view", 0) == 1

    private fun toggleView() {
        val next = if (listMode()) 0 else 1
        act.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).edit().putInt("drawer_view", next).apply()
        viewButton.setImageResource(if (next == 1) R.drawable.ic_start else R.drawable.ic_list)
        refresh()
    }

    /**
     * One list card with swipe actions, like the Redmi app list: drag the card to the left to reveal Info, Favourite (adds it
     * to the home screen) and Remove (uninstall). Tap puts the card back.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun swipeRow(ri: ResolveInfo, after: () -> Unit): View {
        val pkg = ri.activityInfo.packageName
        val btn = dp(46); val gap = dp(8)
        val actionsW = btn * 3 + gap * 4
        fun round(label: String, color: Int, onClick: () -> Unit) = text(label, 18f, p.onAccent, true).apply {
            gravity = Gravity.CENTER; background = act.circle(color); setOnClickListener { onClick() }
        }
        val actions = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(gap, 0, gap, 0)
            addView(round("i", p.accent) { act.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))) }, LinearLayout.LayoutParams(btn, btn).apply { marginStart = gap })
            addView(round("★", 0xFFE0A800.toInt()) { onAddHome(ri) }, LinearLayout.LayoutParams(btn, btn).apply { marginStart = gap })
            addView(round("✕", 0xFFE5484D.toInt()) {
                val system = (ri.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (system) android.widget.Toast.makeText(act, "System app: it cannot be removed here", android.widget.Toast.LENGTH_SHORT).show()
                else act.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")))
            }, LinearLayout.LayoutParams(btn, btn).apply { marginStart = gap })
        }
        val card = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL; background = act.shape(p.surface, 18, p.stroke); setPadding(dp(12), dp(8), dp(14), dp(8))
            addView(ImageView(act).apply { setImageDrawable(AosIcons.themed(act, ri.loadIcon(pm), p, 44, ri.activityInfo.packageName)) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(name(ri), 15f, p.text, true).apply { maxLines = 1 })
                addView(text(AppCategories.of(ri), 12f, p.sub).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            pressFx(p, round = false)
        }
        card.setOnClickListener { if (card.translationX < -dp(4)) card.animate().translationX(0f).setDuration(140).start() else { after(); launch(ri) } }
        card.setOnLongClickListener { if (card.translationX < -dp(4)) false else { onPickUp(ri, card); true } }
        var downX = 0f; var downY = 0f; var startTx = 0f; var dragging = false
        val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startTx = v.translationX; dragging = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true; v.cancelLongPress(); v.isPressed = false; v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) v.translationX = (startTx + dx).coerceIn(-actionsW.toFloat(), 0f)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (dragging) {
                    v.animate().translationX(if (v.translationX < -actionsW / 2f) -actionsW.toFloat() else 0f).setDuration(160).start()
                    dragging = false
                    return@setOnTouchListener true
                }
            }
            dragging
        }
        return FrameLayout(act).apply {
            addView(actions, FrameLayout.LayoutParams(-1, -1))
            addView(card, FrameLayout.LayoutParams(-1, -1))
        }
    }
    /** Tiles: three apps per row. List: one card per app with its icon, name and category. Both scroll vertically. */
    private fun grid(list: List<ResolveInfo>, group: String? = null, after: () -> Unit = {}): View {
        if (listMode()) return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            for (ri in list) addView(swipeRow(ri, after), LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(6) })
        }
        val cellW = (contentW - dp(8)) / 3
        return GridLayout(act).apply {
            columnCount = 3
            for (ri in list) addView(cell(ri, group, after), GridLayout.LayoutParams().apply { width = cellW; height = dp(104) })
        }
    }

    private fun cell(ri: ResolveInfo, group: String?, after: () -> Unit) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        // 3D look: the icon stands on a little round pedestal (an accent glow with a soft contact shadow under it).
        addView(FrameLayout(act).apply {
            clipChildren = false; clipToPadding = false
            addView(object : View(act) {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: android.graphics.Canvas) {
                    val w = width.toFloat(); val h = height.toFloat()
                    // Contact shadow.
                    paint.shader = android.graphics.RadialGradient(w / 2, h / 2, w / 2, 0x66000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP)
                    c.drawOval(android.graphics.RectF(0f, 0f, w, h), paint)
                    // Glowing rim of the pedestal in the theme accent.
                    paint.shader = null; paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1.6f * act.resources.displayMetrics.density
                    paint.color = (p.accent and 0xFFFFFF) or 0x99000000.toInt()
                    c.drawOval(android.graphics.RectF(w * 0.16f, h * 0.22f, w * 0.84f, h * 0.78f), paint)
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.color = (p.accent and 0xFFFFFF) or 0x2E000000
                    c.drawOval(android.graphics.RectF(w * 0.16f, h * 0.22f, w * 0.84f, h * 0.78f), paint)
                }
            }, FrameLayout.LayoutParams(dp(60), dp(16), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            addView(ImageView(act).apply {
                setImageDrawable(AosIcons.themed(act, ri.loadIcon(pm), p, 52, ri.activityInfo.packageName))
                elevation = dp(6).toFloat()
            }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
        }, LinearLayout.LayoutParams(dp(64), dp(72)))
        addView(text(name(ri), 11f, p.text).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        setOnClickListener { after(); launch(ri) }
        setOnLongClickListener { onPickUp(ri, this); true }
        pressFx(p)
    }

    private fun groupsPage(): View {
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(120)) }
        val groups = Groups.all(act)
        if (groups.isEmpty()) {
            content.addView(text("No groups yet.\nLong-press an app and choose “Add to group”.", 14f, p.sub)
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0) })
        } else {
            val g = GridLayout(act).apply { columnCount = 3 }
            val tileW = (contentW - dp(8)) / 3
            for ((gname, pkgs) in groups) {
                val members = pkgs.mapNotNull { pkg -> apps.firstOrNull { it.activityInfo.packageName == pkg } }
                g.addView(folderTile(gname, members), GridLayout.LayoutParams().apply { width = tileW; height = dp(120) })
            }
            content.addView(g)
        }
        val sv = ScrollView(act).apply {
            isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false
            addView(content)
        }
        scrollers.add(sv); headerMaps.add(emptyMap())
        return sv
    }

    private fun folderTile(gname: String, members: List<ResolveInfo>) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val preview = GridLayout(act).apply {
            columnCount = 2; background = act.glassShape(p, 20); setPadding(dp(8), dp(8), dp(8), dp(8))
            for (ri in members.take(4)) addView(ImageView(act).apply {
                setImageDrawable(AosIcons.themed(act, ri.loadIcon(pm), p, 28, ri.activityInfo.packageName))
            }, GridLayout.LayoutParams().apply { width = dp(28); height = dp(28); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        addView(preview, LinearLayout.LayoutParams(dp(76), dp(76)))
        addView(text(gname, 12f, p.text).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        setOnClickListener { openGroup(gname) }
    }

    private fun openGroup(gname: String) {
        lateinit var dialog: AlertDialog
        val members = Groups.all(act)[gname].orEmpty().mapNotNull { pkg -> apps.firstOrNull { it.activityInfo.packageName == pkg } }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = act.shape(p.surface, 28, p.stroke)
            setPadding(dp(12), dp(18), dp(12), dp(14))
            addView(text(gname, 18f, p.text, true).apply { setPadding(dp(8), 0, 0, dp(8)) })
            addView(grid(members, gname) { dialog.dismiss() })
        }
        dialog = AlertDialog.Builder(act).setView(box).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showAppMenu(ri: ResolveInfo, group: String?, after: () -> Unit) {
        val pkg = ri.activityInfo.packageName
        // The AOS dialog replaces the grey system list: the first choice puts the app on the home screen.
        val actions = listOfNotNull(
            DialogAction("Add to home screen", 1) { onAddHome(ri) },
            DialogAction("Add to folder") { pickGroup(pkg) },
            DialogAction("App info") { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) },
            group?.let { g -> DialogAction("Remove from “$g”", 2) { Groups.remove(act, g, pkg); after(); refresh() } },
            DialogAction("Cancel"))
        act.aosDialog(p, name(ri), "", AosIcons.themed(act, ri.loadIcon(pm), p, 56, ri.activityInfo.packageName), *actions.toTypedArray())
    }

    private fun pickGroup(pkg: String) {
        val names = Groups.all(act).keys.toList()
        val options = (names + "New group…").toTypedArray()
        AlertDialog.Builder(act).setTitle("Add to group").setItems(options) { _, which ->
            if (which < names.size) { Groups.add(act, names[which], pkg); refresh() }
            else {
                val input = EditText(act).apply { hint = "Group name"; isSingleLine = true }
                AlertDialog.Builder(act).setTitle("New group").setView(input)
                    .setPositiveButton("Create") { _, _ -> Groups.add(act, input.text.toString(), pkg); refresh() }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }
}
