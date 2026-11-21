package org.anthezis.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Dynamic island around the front camera hole: a black pill that grows out of the cutout with a short message
 * ("Charging 55%", "Full") and shrinks back. Only the pill's size animates (a tiny view), so it stays cheap.
 * Never takes touches.
 */
class DynamicIsland(private val act: Activity, p: Palette) {
    private val d = act.resources.displayMetrics.density

    private val label = TextView(act).apply {
        textSize = 13f; setTextColor(Color.WHITE); typeface = appFont(600); gravity = Gravity.CENTER
        maxLines = 1; alpha = 0f
    }

    private val pill = FrameLayout(act).apply {
        background = act.shape(Color.BLACK, 40)
        addView(label, FrameLayout.LayoutParams(-1, -1))
        visibility = View.GONE
    }

    val view = FrameLayout(act).apply {
        isClickable = false
        addView(pill, FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    private var anim: ValueAnimator? = null
    private val hide = Runnable { collapse() }

    /** Cutout size/top, with a top-centre fallback for screens that report none. */
    private fun cutout(): Triple<Int, Int, Int> {
        val r = view.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull { it.top <= 2 }
        return if (r != null) Triple(r.width(), r.height(), r.top) else Triple((16 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
    }

    private fun resize(w: Int, h: Int, top: Int, ms: Long, end: (() -> Unit)? = null) {
        val lp = pill.layoutParams as FrameLayout.LayoutParams
        val w0 = lp.width; val h0 = lp.height
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = Motion.easeOut
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.width = (w0 + (w - w0) * t).toInt(); lp.height = (h0 + (h - h0) * t).toInt(); lp.topMargin = top
                pill.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { end?.invoke() }
            })
            start()
        }
    }

    /** Grows out of the camera hole with [text] for [holdMs], then shrinks back and disappears. */
    fun show(text: String, holdMs: Long = 2200) {
        val (cw, ch, top) = cutout()
        val padTop = maxOf(0, top - (3 * d).toInt())
        view.removeCallbacks(hide)
        label.text = text
        if (pill.visibility != View.VISIBLE) {
            val lp = pill.layoutParams as FrameLayout.LayoutParams
            lp.width = cw + (6 * d).toInt(); lp.height = ch + (6 * d).toInt(); lp.topMargin = padTop
            pill.layoutParams = lp; pill.visibility = View.VISIBLE
        }
        resize((190 * d).toInt(), maxOf(ch + (14 * d).toInt(), (36 * d).toInt()), padTop, 220)
        label.animate().alpha(1f).setStartDelay(90).setDuration(Motion.FAST).start()
        view.postDelayed(hide, holdMs)
    }

    private fun collapse() {
        val (cw, ch, top) = cutout()
        label.animate().setStartDelay(0).alpha(0f).setDuration(80).start()
        resize(cw + (6 * d).toInt(), ch + (6 * d).toInt(), maxOf(0, top - (3 * d).toInt()), 180) { pill.visibility = View.GONE }
    }

    fun stop() { view.removeCallbacks(hide); anim?.cancel(); pill.visibility = View.GONE }
}
