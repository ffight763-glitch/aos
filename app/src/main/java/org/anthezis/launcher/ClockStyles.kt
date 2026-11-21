package org.anthezis.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.TextClock
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/** One clock choice shared by Home and the lock screen. Split preserves the original AOS design. */
internal object ClockStyles {
    val options = listOf("split" to "AOS original", "digital" to "Digital", "classic" to "Classic round", "minimal" to "Minimal round")
    fun selected(context: Context): String = context.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)
        .getString("clock_style", "split").takeIf { id -> options.any { it.first == id } } ?: "split"

    fun face(context: Context, palette: Palette): View? = when (val style = selected(context)) {
        "split" -> null
        "digital" -> TextClock(context).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            typeface = appFont(700); setTextColor(palette.accent); gravity = Gravity.CENTER
            includeFontPadding = false; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(20, 96, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        else -> RoundClock(context, palette, style == "classic")
    }
}

private class RoundClock(context: Context, private val p: Palette, private val classic: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val now = Calendar.getInstance()
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            if (isShown) postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(ticker); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(visible: Boolean) {
        super.onVisibilityAggregated(visible)
        removeCallbacks(ticker)
        if (visible) post(ticker)
    }

    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f; val cy = height / 2f; val radius = size * 0.46f
        paint.style = Paint.Style.FILL; paint.color = p.glass; c.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE; paint.color = p.accent; paint.strokeWidth = size * 0.012f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius); c.drawOval(bounds, paint)
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 60) {
            if (!classic && i % 5 != 0) continue
            val a = Math.toRadians((i * 6 - 90).toDouble())
            val outer = radius * 0.90f
            val inner = radius * if (i % 5 == 0) 0.80f else 0.86f
            paint.color = if (i % 5 == 0) p.text else p.sub
            paint.strokeWidth = size * if (i % 5 == 0) 0.012f else 0.005f
            c.drawLine(cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner,
                cx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer, paint)
        }
        if (classic) {
            paint.style = Paint.Style.FILL; paint.color = p.text; paint.textSize = size * 0.095f
            paint.textAlign = Paint.Align.CENTER; paint.typeface = appFont(600)
            for (number in listOf(12, 3, 6, 9)) {
                val a = Math.toRadians((number * 30 - 90).toDouble())
                c.drawText(number.toString(), cx + cos(a).toFloat() * radius * 0.65f,
                    cy + sin(a).toFloat() * radius * 0.65f - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        now.timeInMillis = System.currentTimeMillis()
        val seconds = now.get(Calendar.SECOND)
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = now.get(Calendar.HOUR) + minutes / 60f
        fun hand(degrees: Float, length: Float, thickness: Float, color: Int) {
            val a = Math.toRadians((degrees - 90).toDouble())
            paint.style = Paint.Style.STROKE; paint.color = color; paint.strokeWidth = size * thickness
            c.drawLine(cx, cy, cx + cos(a).toFloat() * radius * length, cy + sin(a).toFloat() * radius * length, paint)
        }
        hand(hours * 30f, 0.47f, 0.035f, p.text)
        hand(minutes * 6f, 0.69f, 0.023f, p.text)
        hand(seconds * 6f, 0.74f, 0.010f, p.accent)
        paint.style = Paint.Style.FILL; paint.color = p.accent; c.drawCircle(cx, cy, size * 0.025f, paint)
        contentDescription = DateFormat.format("h:mm:ss a", now).toString()
    }
}
