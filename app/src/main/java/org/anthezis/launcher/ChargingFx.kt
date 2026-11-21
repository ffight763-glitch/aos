package org.anthezis.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/** Translucent theme-coloured liquid on Home. The overlay never consumes touches. */
class ChargingFx(private val act: Activity, private val p: Palette) {
    private val d = act.resources.displayMetrics.density
    private var level = 0f
    private var phase = 0f
    private var fillFraction = 0.5f
    private var fillAnim: ValueAnimator? = null
    private var targetFill = 0.5f
    private var editLevel = 0f
    private var state: Int? = null
    private var animator: ValueAnimator? = null
    private var revealAnim: ValueAnimator? = null
    private var editAnim: ValueAnimator? = null

    private val fx = object : View(act) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val wave = Path()
        private val crest = Path()
        private val editPath = Path()
        private val rect = RectF()
        private val radii = FloatArray(8)
        private var waterShader: Shader? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (h <= 0) return
            // Fade down into the wallpaper, retaining readable icons in either theme.
            waterShader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(p.accent, p.accent and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            if (level > 0.001f && width > 0 && height > 0) {
                val tau = (2 * PI).toFloat()
                // Fill upwards to the battery level, with a gentle swell during the reveal.
                val surge = sin(level * PI).toFloat() * 0.03f
                val depth = height * (fillFraction + surge) * level
                val amplitude = (8 * d + height * surge * 0.12f) * level
                for (layer in 0..1) {
                    val base = height - depth + if (layer == 0) -7 * d * level else 4 * d * level
                    val offset = phase * tau * (if (layer == 0) 1f else -1f) + layer * 1.9f
                    wave.rewind()
                    crest.rewind()
                    val steps = 64
                    for (i in 0..steps) {
                        val x = width * i / steps.toFloat()
                        val angle = x / width * tau
                        val y = base + sin(angle * 1.2f + offset) * amplitude +
                            sin(angle * 2.3f - offset) * amplitude * 0.28f
                        if (i == 0) { wave.moveTo(x, y); crest.moveTo(x, y) }
                        else { wave.lineTo(x, y); crest.lineTo(x, y) }
                    }
                    wave.lineTo(width.toFloat(), height.toFloat())
                    wave.lineTo(0f, height.toFloat())
                    wave.close()
                    fill.shader = waterShader
                    fill.alpha = ((if (layer == 0) 30 else 46) * level).toInt()
                    c.drawPath(wave, fill)
                    line.color = if (layer == 0) p.accent else p.text
                    line.strokeWidth = (if (layer == 0) 1f else 0.7f) * d
                    line.alpha = ((if (layer == 0) 72 else 42) * level).toInt()
                    c.drawPath(crest, line)
                }
            }
            // Home editing still has its own outline, independent of charging.
            if (editLevel > 0.01f) {
                val inset = 3 * d
                rect.set(inset, inset, width - inset, height - inset)
                val corners = intArrayOf(android.view.RoundedCorner.POSITION_TOP_LEFT,
                    android.view.RoundedCorner.POSITION_TOP_RIGHT,
                    android.view.RoundedCorner.POSITION_BOTTOM_RIGHT,
                    android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
                for (i in corners.indices) {
                    val radius = ((rootWindowInsets?.getRoundedCorner(corners[i])?.radius?.toFloat()
                        ?: (44 * d)) - inset).coerceAtLeast(4 * d)
                    radii[i * 2] = radius; radii[i * 2 + 1] = radius
                }
                editPath.rewind()
                editPath.addRoundRect(rect, radii, Path.Direction.CW)
                line.color = p.accent
                for (i in 0..2) {
                    line.strokeWidth = (when (i) { 0 -> 12f; 1 -> 7f; else -> 3f }) * d
                    line.alpha = (255 * editLevel * when (i) { 0 -> 0.10f; 1 -> 0.18f; else -> 1f }).toInt()
                    c.drawPath(editPath, line)
                }
            }
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    private val badge = TextView(act).apply {
        text = "Full"; textSize = 14f; setTextColor(p.onAccent); typeface = appFont(700)
        setPadding((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
        background = act.shape(p.accent, 16); alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val view = FrameLayout(act).apply {
        addView(fx, FrameLayout.LayoutParams(-1, -1))
        addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (140 * d).toInt()
        })
    }

    fun setEditing(on: Boolean) {
        editAnim?.cancel()
        editAnim = ValueAnimator.ofFloat(editLevel, if (on) 0.9f else 0f).apply {
            duration = 200; interpolator = Motion.easeOut
            addUpdateListener { editLevel = it.animatedValue as Float; fx.invalidate() }
            start()
        }
    }

    /** Battery broadcasts may arrive repeatedly; they must not restart the wave. */
    fun apply(charging: Boolean, full: Boolean, batteryPercent: Int) {
        // Leave room for the crest at 100% and keep very low levels visible.
        val fraction = (batteryPercent.coerceIn(0, 100) / 100f).coerceIn(0.02f, 0.98f)
        if (state == null) {
            fillAnim?.cancel()
            fillFraction = fraction
            targetFill = fraction
        } else if (fraction != targetFill) {
            targetFill = fraction
            fillAnim?.cancel()
            fillAnim = ValueAnimator.ofFloat(fillFraction, fraction).apply {
                duration = 650; interpolator = Motion.easeOut
                addUpdateListener { fillFraction = it.animatedValue as Float; fx.invalidate() }
                start()
            }
        }
        val next = when { !charging -> 0; full -> 2; else -> 1 }
        if (state == next) return
        state = next
        animator?.cancel()
        animator = null
        revealAnim?.cancel()
        revealAnim = ValueAnimator.ofFloat(level, if (charging) 1f else 0f).apply {
            duration = if (charging) 2200 else 500
            interpolator = Motion.easeOut
            addUpdateListener { level = it.animatedValue as Float; fx.invalidate() }
            start()
        }
        badge.animate().alpha(if (next == 2) 1f else 0f).setDuration(Motion.NORMAL).start()
        if (next == 1) {
            val startPhase = phase
            animator = ValueAnimator.ofFloat(startPhase, startPhase + 1f).apply {
                duration = 4800; interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { phase = (it.animatedValue as Float) % 1f; fx.invalidate() }
                start()
            }
        }
    }

    /** Release frame callbacks off-screen; the sticky battery broadcast restores the state. */
    fun stop() {
        animator?.cancel(); animator = null
        revealAnim?.cancel(); revealAnim = null
        editAnim?.cancel(); editAnim = null
        fillAnim?.cancel(); fillAnim = null
        badge.animate().cancel()
        level = 0f
        fx.invalidate()
        state = null
    }
}
