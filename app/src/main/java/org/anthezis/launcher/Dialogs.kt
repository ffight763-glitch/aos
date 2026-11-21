package org.anthezis.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** iOS-style "slide to ..." control: drag the knob to the end to confirm; letting go early springs it back. */
class SlideToConfirm(ctx: android.content.Context, private val p: Palette, private val label: String, private val onDone: () -> Unit,
                     icon: Int = R.drawable.ic_power) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = ctx.getDrawable(icon)!!.mutate()
    private var pos = 0f      // 0..1
    private var dragging = false
    private var completed = false
    private var resetAnim: android.animation.ValueAnimator? = null
    private var grabOffset = 0f
    private val knob get() = height.toFloat() - 8 * d
    init { contentDescription = label }

    fun reset() {
        resetAnim?.cancel(); resetAnim = null
        dragging = false; completed = false; pos = 0f; invalidate()
    }

    private fun springBack() {
        resetAnim?.cancel()
        resetAnim = android.animation.ValueAnimator.ofFloat(pos, 0f).apply {
            duration = 180
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = p.surfaceHigh; c.drawRoundRect(rect, height / 2f, height / 2f, paint)
        paint.color = p.sub; paint.textSize = 14 * d; paint.textAlign = Paint.Align.CENTER; paint.typeface = appFont(600)
        paint.alpha = ((1 - pos) * 255).toInt()
        c.drawText(label, width / 2f + knob / 2, height / 2f + 5 * d, paint); paint.alpha = 255
        val x = 4 * d + pos * (width - knob - 8 * d)
        paint.color = p.accent; c.drawCircle(x + knob / 2, height / 2f, knob / 2, paint)
        val g = (22 * d).toInt()
        glyph.setTint(p.onAccent); glyph.setBounds((x + knob / 2 - g / 2).toInt(), (height / 2f - g / 2).toInt(), (x + knob / 2 + g / 2).toInt(), (height / 2f + g / 2).toInt()); glyph.draw(c)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (completed || width <= knob + 8 * d) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetAnim?.cancel()
                val x = 4 * d + pos * (width - knob - 8 * d)
                dragging = e.x >= x - 8 * d && e.x <= x + knob + 8 * d
                grabOffset = e.x - x
                if (dragging) parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) { pos = ((e.x - grabOffset - 4 * d) / (width - knob - 8 * d)).coerceIn(0f, 1f); invalidate() }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                if (pos > 0.88f) { completed = true; pos = 1f; invalidate(); onDone() }
                else springBack()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; springBack() }
        }
        return true
    }

    override fun onDetachedFromWindow() { resetAnim?.cancel(); super.onDetachedFromWindow() }
}

/** One button of an AOS dialog. [kind]: 0 normal, 1 primary (accent), 2 danger (red). */
class DialogAction(val label: String, val kind: Int = 0, val onClick: () -> Unit = {})

/**
 * AOS dialog: a glass panel with an optional app icon, a title, a message and full-width pill buttons, instead of the
 * grey system AlertDialog. Every button closes the dialog and then runs its action.
 */
fun Activity.aosDialog(p: Palette, title: String, message: String, icon: Drawable? = null, vararg actions: DialogAction): Dialog {
    val dlg = Dialog(this)
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
    fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400); gravity = Gravity.CENTER
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        background = glassShape(p, 30, true); setPadding(dp(22), dp(22), dp(22), dp(18))
        if (icon != null) addView(ImageView(this@aosDialog).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(12) })
        addView(text(title, 19f, p.text, true))
        if (message.isNotBlank()) addView(text(message, 14f, p.sub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        for (a in actions) {
            val danger = 0xFFE5484D.toInt()
            addView(text(a.label, 15f, when (a.kind) { 1 -> p.onAccent; 2 -> danger; else -> p.text }, true).apply {
                background = shape(if (a.kind == 1) p.accent else p.surfaceHigh, 24)
                setOnClickListener { dlg.dismiss(); a.onClick() }; pressFx(p, round = false)
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        }
    }
    dlg.setContentView(FrameLayout(this).apply {
        setPadding(dp(28), 0, dp(28), 0)
        addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    })
    dlg.window?.apply {
        setBackgroundDrawable(ColorDrawable(0)); setLayout(-1, -2); setDimAmount(0.5f)
    }
    panel.alpha = 0f; panel.scaleX = 0.94f; panel.scaleY = 0.94f
    panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.easeOut).start()
    dlg.show()
    return dlg
}
