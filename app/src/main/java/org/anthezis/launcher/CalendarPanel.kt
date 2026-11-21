package org.anthezis.launcher

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/**
 * Calendar panel, opened by the time and date of the top bar: the same frosted card and the same rise from above the dock
 * as the Control Center. A month with ‹ › to change it, today in the accent, a tap on "Today" comes back to this month.
 */
class CalendarPanel(private val act: Activity, private val p: Palette, private val bottomGap: () -> Int) {
    private fun dp(v: Int) = act.dp(v)
    private val cal: Calendar = Calendar.getInstance()

    private fun text(t: String, sp: Float, c: Int, w: Int = 400) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(w); gravity = Gravity.CENTER
    }

    private val card = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; background = act.frostShape(p, 30); setPadding(dp(16), dp(18), dp(16), dp(18)); isClickable = true
    }

    val view: FrameLayout = FrameLayout(act).apply {
        visibility = View.GONE
        // Transparent layer: a tap outside the card closes it (no dimming, like the Control Center).
        addView(View(act).apply { setOnClickListener { close() } }, FrameLayout.LayoutParams(-1, -1))
        addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    val isOpen get() = view.visibility == View.VISIBLE

    private fun paint() {
        card.removeAllViews()
        val now = Calendar.getInstance()
        val thisMonth = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) && cal.get(Calendar.MONTH) == now.get(Calendar.MONTH)
        // Header: big day and weekday of today, then the month with its arrows.
        card.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(now.get(Calendar.DAY_OF_MONTH).toString(), 40f, p.accent, 700))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                addView(text(android.text.format.DateFormat.format("EEEE", now).toString().replaceFirstChar { it.uppercase() }, 17f, p.text, 700).apply { gravity = Gravity.START })
                addView(text(android.text.format.DateFormat.format("d MMMM yyyy", now).toString(), 13f, p.sub).apply { gravity = Gravity.START })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (!thisMonth) addView(text("Today", 13f, p.onAccent, 700).apply {
                background = act.shape(p.accent, 16); setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener { cal.timeInMillis = System.currentTimeMillis(); paint() }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        fun nav(t: String, d: Int) = text(t, 22f, p.text, 700).apply {
            background = act.circle(p.glassCircle); setOnClickListener { cal.add(Calendar.MONTH, d); paint() }
        }
        card.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(nav("‹", -1), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(text(android.text.format.DateFormat.format("LLLL yyyy", cal.time).toString().replaceFirstChar { it.uppercase() }, 17f, p.text, 700),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(nav("›", 1), LinearLayout.LayoutParams(dp(40), dp(40)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val cellW = (act.resources.displayMetrics.widthPixels - dp(24) - dp(32)) / 7
        val grid = GridLayout(act).apply { columnCount = 7 }
        for ((i, wd) in listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su").withIndex())
            grid.addView(text(wd, 12f, if (i >= 5) p.accent else p.sub, 700), GridLayout.LayoutParams().apply { width = cellW; height = dp(28) })
        val first = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        for (k in 0 until offset) grid.addView(View(act), GridLayout.LayoutParams().apply { width = cellW; height = dp(44) })
        for (d in 1..cal.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            val today = thisMonth && d == now.get(Calendar.DAY_OF_MONTH)
            val wd = (offset + d - 1) % 7
            grid.addView(FrameLayout(act).apply {
                addView(text("$d", 15f, if (today) p.onAccent else if (wd >= 5) p.accent else p.text, if (today) 700 else 500).apply {
                    if (today) background = act.circle(p.accent)
                }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
            }, GridLayout.LayoutParams().apply { width = cellW; height = dp(44) })
        }
        card.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    fun show() {
        cal.timeInMillis = System.currentTimeMillis()
        paint()
        (card.layoutParams as FrameLayout.LayoutParams).apply { setMargins(dp(12), 0, dp(12), bottomGap()) }.also { card.layoutParams = it }
        view.visibility = View.VISIBLE
        card.alpha = 0f; card.translationY = dp(36).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(170).start()
    }

    fun close() {
        if (!isOpen) return
        card.animate().alpha(0f).translationY(dp(36).toFloat()).setDuration(130).withEndAction { view.visibility = View.GONE }.start()
    }
}
