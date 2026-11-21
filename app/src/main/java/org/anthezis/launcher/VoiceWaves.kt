package org.anthezis.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Live voice waves (the same layered look as the charging effect): translucent waves in the theme's accent rise from the
 * bottom while the user speaks. [level] takes the recogniser's loudness (RMS in dB, about -2..10) and the waves follow it
 * smoothly; [show] / [hide] fade them in and out.
 */
class VoiceWaves(ctx: Context, private val p: Palette) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var target = 0.15f
    private var amp = 0.15f
    private var phase = 0f
    private var running = false

    init { alpha = 0f; visibility = INVISIBLE }

    fun level(rmsDb: Float) { target = ((rmsDb + 2f) / 12f).coerceIn(0.12f, 1f) }

    fun show() {
        if (running) return
        running = true; visibility = VISIBLE
        animate().alpha(1f).setDuration(250).start()
        postInvalidateOnAnimation()
    }

    fun hide() {
        if (!running) return
        running = false; target = 0.12f
        animate().alpha(0f).setDuration(350).withEndAction { if (!running) visibility = INVISIBLE }.start()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        amp += (target - amp) * 0.18f          // follow the voice smoothly
        target += (0.15f - target) * 0.04f     // and settle back while it is quiet
        phase += 0.06f
        val colors = listOf(p.accent, lift(p.accent, 0.35f), p.accent, lift(p.accent, 0.6f))
        for (i in 0 until 4) {
            val base = h * (0.55f + i * 0.11f) - h * 0.35f * amp * (1f - i * 0.18f)
            val height = h * 0.12f * (0.4f + amp) * (1f - i * 0.15f)
            path.reset(); path.moveTo(0f, h); path.lineTo(0f, base)
            var x = 0f
            while (x <= w) {
                val t = x / w * 2 * PI
                val y = base + (sin(t * (1.2 + i * 0.35) + phase * (1f + i * 0.4f) + i) * height +
                    sin(t * 3.1 - phase * 1.3 + i * 2) * height * 0.35).toFloat()
                path.lineTo(x, y); x += 10f
            }
            path.lineTo(w, h); path.close()
            paint.color = colors[i]; paint.alpha = 70 + i * 30
            c.drawPath(path, paint)
        }
        if (running || alpha > 0f) postInvalidateOnAnimation()
    }
}
