package org.anthezis.launcher

import android.app.Activity
import android.content.Context
import android.content.pm.ResolveInfo
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
 * AOS recents screen. A normal app cannot read the system task list or task thumbnails, so this lists the apps
 * opened through AOS Home. In the firmware the same layouts belong in the launcher's RecentsView (Launcher3 Quickstep),
 * fed by the real task list.
 *
 * Layouts (pref "recents_layout"): "vertical" = two cards per row, scrolling down (Xiaomi style, the default),
 * "horizontal" = one row of large cards, "list" = compact rows.
 */
class RecentsScreen(
    private val act: Activity,
    private val p: Palette,
    private val apps: List<ResolveInfo>,
    private val launch: (ResolveInfo) -> Unit,
    private val onVisibility: (Boolean) -> Unit,
) {
    private val pm = act.packageManager
    private val prefs = act.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)
    private val dm = act.resources.displayMetrics
    private fun dp(v: Int) = act.dp(v)

    private val tabs = LinearLayout(act).apply { gravity = Gravity.CENTER }
    private val content = FrameLayout(act)

    val view = FrameLayout(act).apply {
        visibility = View.GONE
        setBackgroundColor(p.glassScreen)
        isClickable = true
        setOnClickListener { hide() }
    }
    val isOpen get() = view.visibility == View.VISIBLE

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    init {
        view.addView(tabs, FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(48) })
        view.addView(content, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(104); bottomMargin = dp(100) })
        // Clear all, bottom centre, like Xiaomi's recents.
        view.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(p.text); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = act.circle(p.glassStrong, p.stroke, 1); elevation = dp(4).toFloat()
            contentDescription = "Clear all"
            setOnClickListener { Recents.clear(act); render() }
        }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
    }

    private fun layoutName() = prefs.getString("recents_layout", "vertical").orEmpty()

    private fun items(): List<ResolveInfo> =
        Recents.get(act).mapNotNull { pkg -> apps.firstOrNull { it.activityInfo.packageName == pkg } }

    private fun render() {
        val current = layoutName()
        tabs.removeAllViews()
        for ((id, label) in listOf("vertical" to "Grid", "horizontal" to "Row", "list" to "List")) {
            val on = id == current
            tabs.addView(text(label, 13f, if (on) p.onAccent else p.text, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0)
                background = act.shape(if (on) p.accent else p.glass, 20, if (on) null else p.stroke)
                setOnClickListener { prefs.edit().putString("recents_layout", id).apply(); render() }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        content.removeAllViews()
        val list = items()
        if (list.isEmpty()) {
            content.addView(text("No recent apps", 16f, p.sub).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1))
            return
        }
        content.addView(when (current) {
            "horizontal" -> rowLayout(list)
            "list" -> listLayout(list)
            else -> gridLayout(list)
        })
    }

    /** One card: app icon and name on top, the app's icon large in the middle, tap opens, the cross removes it. */
    private fun card(ri: ResolveInfo, w: Int, h: Int): View = FrameLayout(act).apply {
        background = act.glassShape(p, 26, true)
        val pkg = ri.activityInfo.packageName
        addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(6), 0)
            addView(ImageView(act).apply { setImageDrawable(AosIcons.themed(act, ri.loadIcon(pm), p, 24, ri.activityInfo.packageName)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text(ri.loadLabel(pm).toString(), 13f, p.text, true).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(p.sub); setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { Recents.remove(act, pkg); render() }
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        addView(ImageView(act).apply { setImageDrawable(AosIcons.themed(act, ri.loadIcon(pm), p, 80, ri.activityInfo.packageName)) },
            FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
        layoutParams = ViewGroupParams.fixed(w, h)
        setOnClickListener { hide(); launch(ri) }
    }

    /** Layout helper: margin-aware fixed size params. */
    private object ViewGroupParams {
        fun fixed(w: Int, h: Int) = android.view.ViewGroup.LayoutParams(w, h)
    }

    /** Two cards per row, scrolling down. */
    private fun gridLayout(list: List<ResolveInfo>): View {
        val gap = dp(12)
        val cardW = (dm.widthPixels - dp(32) - gap) / 2
        val cardH = (cardW * 1.55f).toInt()
        val grid = GridLayout(act).apply { columnCount = 2 }
        for (ri in list) grid.addView(card(ri, cardW, cardH), GridLayout.LayoutParams().apply {
            width = cardW; height = cardH; setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
        })
        return ScrollView(act).apply {
            isVerticalScrollBarEnabled = false; setPadding(dp(10), 0, dp(10), 0); clipToPadding = false
            addView(grid)
        }
    }

    /** One row of large cards, swiping sideways. */
    private fun rowLayout(list: List<ResolveInfo>): View {
        val cardW = (dm.widthPixels * 0.66f).toInt()
        val cardH = (dm.heightPixels - dp(104) - dp(100) - dp(48)).coerceAtLeast(dp(260))
        val row = LinearLayout(act).apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        for (ri in list) row.addView(card(ri, cardW, cardH), LinearLayout.LayoutParams(cardW, cardH).apply { marginEnd = dp(14) })
        return HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    /** Compact rows, one app per line. */
    private fun listLayout(list: List<ResolveInfo>): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        for (ri in list) col.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(8), 0)
            background = act.glassShape(p, 22, true)
            addView(ImageView(act).apply { setImageDrawable(AosIcons.themed(act, ri.loadIcon(pm), p, 44, ri.activityInfo.packageName)) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(text(ri.loadLabel(pm).toString(), 15f, p.text, true).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(p.sub); setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { Recents.remove(act, ri.activityInfo.packageName); render() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            setOnClickListener { hide(); launch(ri) }
        }, LinearLayout.LayoutParams(-1, dp(68)).apply { bottomMargin = dp(8) })
        return ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    /** [instant]: no fade-in (when it replaces the stock Recents, any animation only shows the switch). */
    fun show(instant: Boolean = false) {
        render()
        view.animate().cancel()
        if (instant) { view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f; view.visibility = View.VISIBLE }
        else {
            view.alpha = 0f; view.scaleX = 0.96f; view.scaleY = 0.96f; view.visibility = View.VISIBLE
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        onVisibility(true)
    }

    fun hide() {
        if (!isOpen) return
        onVisibility(false)
        view.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(130).withEndAction { view.visibility = View.GONE }.start()
    }
}
