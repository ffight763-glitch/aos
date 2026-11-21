package org.anthezis.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/*
 * Windows Phone style ("tiles" home style) screens. Used only in that style; the Android style keeps its own design.
 */

private val DIM = Color.argb(150, 255, 255, 255)

internal fun Activity.metroText(t: CharSequence, sp: Float, c: Int = Color.WHITE, w: Int = 400) = TextView(this).apply {
    text = t; textSize = sp; setTextColor(c); typeface = appFont(w); includeFontPadding = false
}

/** Round outlined button with a caption under it, like the app bar of Windows Phone. */
internal fun Activity.metroRoundButton(icon: Int, caption: String, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
    addView(ImageView(this@metroRoundButton).apply {
        setImageResource(icon); setColorFilter(Color.WHITE); setPadding(dp(9), dp(9), dp(9), dp(9))
        background = circle(Color.TRANSPARENT, Color.WHITE, 2)
    }, LinearLayout.LayoutParams(dp(40), dp(40)))
    if (caption.isNotEmpty()) addView(metroText(caption, 10f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
    setPadding(dp(14), 0, dp(14), 0)
    setOnClickListener { onClick() }
}

/** The app list right of Start: letter squares in the accent, square icon tiles, a search button on top. */
internal class MetroApps(
    private val act: Activity, apps: List<ResolveInfo>, private val accent: Int,
    private val glyph: (ResolveInfo, Int) -> View,
    private val onLaunch: (ResolveInfo) -> Unit, private val onPin: (ResolveInfo) -> Unit,
) {
    private fun dp(v: Int) = act.dp(v)
    private val named = apps.map { it to it.loadLabel(act.packageManager).toString() }.sortedBy { it.second.lowercase() }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(44), dp(12), dp(140)) }
    private val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }
    private val headers = HashMap<String, View>()
    private val jump = FrameLayout(act).apply {
        visibility = View.GONE; setBackgroundColor(Color.argb(238, 0, 0, 0)); isClickable = true
        setOnClickListener { visibility = View.GONE }
    }
    private val search: EditText = EditText(act).apply {
        hint = "search"; setTextColor(Color.WHITE); setHintTextColor(DIM); textSize = 18f; isSingleLine = true
        background = act.shape(Color.WHITE, 0); setTextColor(Color.BLACK); setHintTextColor(Color.GRAY)
        setPadding(dp(10), 0, dp(10), 0); visibility = View.GONE
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { fill(s?.toString().orEmpty()) }
        })
    }
    val view: FrameLayout = FrameLayout(act).apply {
        setBackgroundColor(Color.BLACK)
        addView(scroll, FrameLayout.LayoutParams(-1, -1))
        addView(search, FrameLayout.LayoutParams(-1, dp(44), Gravity.TOP).apply { setMargins(dp(14), dp(40), dp(14), 0) })
        addView(jump, FrameLayout.LayoutParams(-1, -1))
    }

    init { fill("") }

    fun openSearch() {
        search.visibility = View.VISIBLE; list.setPadding(dp(14), dp(96), dp(12), dp(140)); search.requestFocus()
        act.getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
    }

    fun reset() {
        if (search.visibility == View.VISIBLE) {
            act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
            search.setText(""); search.visibility = View.GONE; list.setPadding(dp(14), dp(44), dp(12), dp(140))
        }
        jump.visibility = View.GONE
        scroll.scrollTo(0, 0)
    }

    /** Back on this page: first closes the search or the letter grid. */
    fun back(): Boolean {
        if (jump.visibility == View.VISIBLE) { jump.visibility = View.GONE; return true }
        if (search.visibility == View.VISIBLE) { reset(); return true }
        return false
    }

    private fun letter(n: String) = n.firstOrNull()?.lowercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"

    private fun fill(q: String) {
        list.removeAllViews(); headers.clear()
        if (q.isBlank()) list.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_metro_search); setColorFilter(Color.WHITE); setPadding(dp(9), dp(9), dp(9), dp(9))
            background = act.circle(Color.TRANSPARENT, Color.WHITE, 2); setOnClickListener { openSearch() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(8) })
        var last = ""
        for ((ri, name) in named) {
            if (q.isNotBlank() && !name.contains(q, true)) continue
            val l = letter(name)
            if (q.isBlank() && l != last) {
                last = l
                val h = act.metroText(l, 24f, accent, 300).apply {
                    gravity = Gravity.BOTTOM or Gravity.START; setPadding(dp(6), 0, 0, dp(4))
                    background = act.shape(Color.TRANSPARENT, 0, accent, 2)
                    setOnClickListener { showJump() }
                }
                headers[l] = h
                list.addView(h, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(10); bottomMargin = dp(6) })
            }
            list.addView(LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5))
                addView(FrameLayout(act).apply {
                    setBackgroundColor(accent); addView(glyph(ri, dp(28)))
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(act.metroText(name, 19f, Color.WHITE, 300).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
                setOnClickListener { onLaunch(ri) }
                setOnLongClickListener { v ->
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    android.widget.PopupMenu(act, v).apply {
                        menu.add(0, 1, 0, "pin to start"); menu.add(0, 2, 1, "app info"); menu.add(0, 3, 2, "uninstall")
                        setOnMenuItemClickListener {
                            val pkg = ri.activityInfo.packageName
                            when (it.itemId) {
                                1 -> onPin(ri)
                                2 -> act.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")))
                                3 -> act.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")))
                            }
                            true
                        }
                    }.show(); true
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (list.childCount == 0) list.addView(act.metroText("no results", 18f, DIM, 300))
    }

    /** Letter grid: a tap on a letter square jumps to that letter, like on the phone. */
    private fun showJump() {
        jump.removeAllViews()
        val gap = dp(6)
        val w = (act.resources.displayMetrics.widthPixels - dp(28) - 3 * gap) / 4
        val grid = GridLayout(act).apply { columnCount = 4 }
        for ((i, l) in ("#abcdefghijklmnopqrstuvwxyz".map { it.toString() }).withIndex()) {
            val has = l in headers
            grid.addView(act.metroText(l, 30f, if (has) Color.WHITE else Color.argb(90, 255, 255, 255), 300).apply {
                gravity = Gravity.BOTTOM or Gravity.START; setPadding(dp(8), 0, 0, dp(6))
                setBackgroundColor(if (has) accent else Color.rgb(34, 34, 34))
                if (has) setOnClickListener { jump.visibility = View.GONE; headers[l]?.let { h -> scroll.post { scroll.scrollTo(0, h.top - dp(8)) } } }
            }, GridLayout.LayoutParams().apply { width = w; height = w; setMargins(0, 0, if (i % 4 == 3) 0 else gap, gap) })
        }
        jump.addView(ScrollView(act).apply { addView(grid) }, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(48) })
        jump.alpha = 0f; jump.visibility = View.VISIBLE; jump.animate().alpha(1f).setDuration(150).start()
    }
}

/** Calendar like on Windows Phone: "this week / next week" with a two-column week, or a month grid; an app bar at the bottom. */
internal class MetroCalendar(private val act: Activity, private val accent: Int, private val weather: () -> String?) {
    private fun dp(v: Int) = act.dp(v)
    private var monthMode = false
    private var index = 0
    private val dlg = android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar)
    private val topLabel = act.metroText("", 12f, Color.WHITE, 700)
    private val pivot = LinearLayout(act)
    private val body = FrameLayout(act)

    fun show() {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(0, dp(34), 0, 0)
            addView(topLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
            addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(pivot) },
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(2), 0, dp(10)) })
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(act).apply {
                gravity = Gravity.CENTER; setBackgroundColor(Color.rgb(31, 31, 31)); setPadding(0, dp(10), 0, dp(10))
                addView(act.metroRoundButton(R.drawable.ic_metro_today, "today") { index = 0; paint() })
                addView(act.metroRoundButton(R.drawable.ic_metro_add, "new") {
                    try { act.startActivity(Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)) } catch (_: Exception) {}
                })
                addView(act.metroRoundButton(R.drawable.ic_metro_month, if (monthMode) "week" else "month") { monthMode = !monthMode; index = 0; show2() })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        // Sideways swipe on the days moves to the next / previous week or month.
        var x0 = 0f
        body.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x0 = e.x
                MotionEvent.ACTION_UP -> { val dx = e.x - x0; if (Math.abs(dx) > dp(60)) { index = (index + if (dx < 0) 1 else -1).coerceIn(0, 11); paint() } }
            }
            true
        }
        dlg.setContentView(root)
        dlg.window?.setLayout(-1, -1)
        paint()
        dlg.show()
    }

    private fun show2() { dlg.dismiss(); MetroCalendar(act, accent, weather).also { it.monthMode = monthMode }.show() }

    private fun paint() {
        val now = Calendar.getInstance()
        pivot.removeAllViews()
        val labels = if (monthMode) (0..11).map { i -> Calendar.getInstance().apply { add(Calendar.MONTH, i) }.let { android.text.format.DateFormat.format("MMMM", it).toString().lowercase() } }
            else (0..11).map { i -> when (i) { 0 -> "this week"; 1 -> "next week"; else -> android.text.format.DateFormat.format("d MMM", weekStart(i)).toString().lowercase() } }
        for ((i, l) in labels.withIndex()) if (i >= index) pivot.addView(act.metroText(l, 40f, if (i == index) Color.WHITE else Color.argb(90, 255, 255, 255), 300).apply {
            setPadding(0, 0, dp(16), 0); setOnClickListener { index = i; paint() }
        })
        val shown = if (monthMode) Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, index) } else weekStart(index)
        topLabel.text = android.text.format.DateFormat.format("MMMM yyyy", shown).toString().uppercase()
        body.removeAllViews()
        body.addView(if (monthMode) month(shown, now) else week(shown, now), FrameLayout.LayoutParams(-1, -1))
    }

    private fun weekStart(offset: Int) = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (after(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, -7)
        add(Calendar.DAY_OF_MONTH, 7 * offset)
    }

    private fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    private val line = Color.rgb(70, 70, 70)
    private fun cellBg() = act.shape(Color.BLACK, 0, line, 1)

    /** Seven days in two columns plus a small month in the eighth cell. */
    private fun week(start: Calendar, now: Calendar): View {
        val grid = GridLayout(act).apply { columnCount = 2; rowCount = 4 }
        val w = act.resources.displayMetrics.widthPixels / 2
        val h = ((act.resources.displayMetrics.heightPixels - dp(260)) / 4).coerceAtLeast(dp(90))
        val d = start.clone() as Calendar
        for (i in 0 until 7) {
            val today = same(d, now)
            grid.addView(FrameLayout(act).apply {
                background = cellBg()
                addView(act.metroText(android.text.format.DateFormat.format("EEE d", d).toString().uppercase(), 12f, if (today) accent else Color.WHITE, 700),
                    FrameLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), dp(8), 0, 0) })
                if (today) {
                    addView(View(act).apply { setBackgroundColor(accent) }, FrameLayout.LayoutParams(dp(4), dp(18), Gravity.START or Gravity.CENTER_VERTICAL))
                    addView(act.metroText(weather()?.substringBefore("  ") ?: "", 13f, Color.WHITE), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(8), 0) })
                    addView(act.metroText("today", 12f, accent), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(12) })
                }
            }, GridLayout.LayoutParams().apply { width = w; height = h })
            d.add(Calendar.DAY_OF_MONTH, 1)
        }
        grid.addView(FrameLayout(act).apply {
            background = cellBg()
            addView(mini(start, now), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }, GridLayout.LayoutParams().apply { width = w; height = h })
        return ScrollView(act).apply { addView(grid) }
    }

    private fun mini(ref: Calendar, now: Calendar): View {
        val first = (ref.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val g = GridLayout(act).apply { columnCount = 7 }
        g.addView(act.metroText(android.text.format.DateFormat.format("MMMM yyyy", first), 9f, Color.WHITE), GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 7)))
        val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        repeat(lead) { g.addView(View(act), GridLayout.LayoutParams().apply { width = dp(18); height = dp(13) }) }
        val c = first.clone() as Calendar
        for (n in 1..first.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            c.set(Calendar.DAY_OF_MONTH, n)
            val t = same(c, now)
            g.addView(act.metroText("$n", 8f, Color.WHITE).apply { gravity = Gravity.CENTER; if (t) setBackgroundColor(accent) },
                GridLayout.LayoutParams().apply { width = dp(18); height = dp(13) })
        }
        return g
    }

    /** Month grid with thin lines; today gets an accent corner. */
    private fun month(first: Calendar, now: Calendar): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val w = act.resources.displayMetrics.widthPixels / 7
        col.addView(LinearLayout(act).apply {
            for (n in listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) addView(act.metroText(n, 11f, DIM).apply { setPadding(dp(4), 0, 0, dp(4)) }, LinearLayout.LayoutParams(w, -2))
        })
        val c = first.clone() as Calendar
        c.add(Calendar.DAY_OF_MONTH, -((first.get(Calendar.DAY_OF_WEEK) + 5) % 7))
        val h = ((act.resources.displayMetrics.heightPixels - dp(300)) / 6).coerceAtLeast(dp(44))
        for (r in 0 until 6) col.addView(LinearLayout(act).apply {
            for (k in 0 until 7) {
                val inMonth = c.get(Calendar.MONTH) == first.get(Calendar.MONTH)
                val t = same(c, now)
                addView(FrameLayout(act).apply {
                    background = cellBg()
                    addView(act.metroText("${c.get(Calendar.DAY_OF_MONTH)}", 13f, if (t) accent else if (inMonth) Color.WHITE else Color.argb(90, 255, 255, 255)),
                        FrameLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), dp(3), 0, 0) })
                    if (t) addView(View(act).apply { setBackgroundColor(accent) }, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
                }, LinearLayout.LayoutParams(w, h))
                c.add(Calendar.DAY_OF_MONTH, 1)
            }
        })
        return col
    }
}
