package org.anthezis.launcher

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import kotlin.math.cos
import kotlin.math.sin

/**
 * Semicircle dock: a glass half-disc standing on the bottom edge, its buttons spread along the arc from left to right.
 * The children are the dock's own button cells; this only places them.
 */
class SemiDock(ctx: Context, p: Palette, private val cellPx: Int) : ViewGroup(ctx) {
    /** Optional view in the hollow of the arc, centred under the apps (the top bar with the Semicircle dock). */
    private var inner: android.view.View? = null

    fun setInner(v: android.view.View) { inner?.let { removeView(it) }; inner = v; addView(v) }

    init {
        clipChildren = false
        background = GradientDrawable().apply {
            setColor(p.glass)
            setStroke(maxOf(1, ctx.dp(1)), p.stroke)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = w / 2
        // A half-disc: the top corners round off with the full height, the bottom stays flat.
        (background as? GradientDrawable)?.cornerRadii = floatArrayOf(h.toFloat(), h.toFloat(), h.toFloat(), h.toFloat(), 0f, 0f, 0f, 0f)
        val spec = MeasureSpec.makeMeasureSpec(cellPx, MeasureSpec.EXACTLY)
        // The inner view may be as wide as the gap between the lowest icons on both sides.
        val innerMax = (w - cellPx * 3.2f).toInt().coerceAtLeast(cellPx)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === inner) c.measure(MeasureSpec.makeMeasureSpec(innerMax, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(context.dp(34), MeasureSpec.EXACTLY))
            else c.measure(spec, spec)
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        inner?.let { v ->
            val bottom = h - context.dp(10)
            val left = (w - v.measuredWidth) / 2
            v.layout(left, bottom - v.measuredHeight, left + v.measuredWidth, bottom)
        }
        val cells = (0 until childCount).map { getChildAt(it) }.filter { it !== inner }
        val n = cells.size
        if (n == 0) return
        val cx = w / 2f; val cy = h.toFloat()
        // The arc of the icon centres: inside the rim, from 165° on the left to 15° on the right.
        val radius = h - cellPx * 0.75f
        val from = Math.toRadians(165.0); val to = Math.toRadians(15.0)
        for (i in 0 until n) {
            val a = if (n == 1) Math.PI / 2 else from + (to - from) * i / (n - 1)
            val x = cx + radius * cos(a).toFloat(); val y = cy - radius * sin(a).toFloat()
            val half = cellPx / 2
            cells[i].layout((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
        }
    }

    /** Takes the button cells out of [bar] (spacers are dropped) and places them on the arc. */
    fun adopt(bar: ViewGroup) {
        val cells = (0 until bar.childCount).map { bar.getChildAt(it) }.filter { it is ViewGroup && it.childCount > 0 }
        bar.removeAllViews()
        for (c in cells) addView(c)
    }
}
