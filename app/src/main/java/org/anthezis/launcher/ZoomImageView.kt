package org.anthezis.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs

/**
 * Photo viewer surface, like the big Android galleries: pinch to zoom (up to 6x), double tap to zoom in / back,
 * drag a zoomed photo around (it never leaves the edges). At normal size a sideways fling goes to the next / previous
 * photo and a downward fling closes the viewer.
 */
@SuppressLint("AppCompatCustomView", "ClickableViewAccessibility")
class ZoomImageView(ctx: Context) : ImageView(ctx) {
    var onSwipe: ((Int) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private val m = Matrix()
    private val rect = RectF()
    private var cur = 1f
    private var anim: ValueAnimator? = null
    val zoomed get() = cur > 1.02f

    init { scaleType = ScaleType.MATRIX }

    override fun setImageDrawable(drawable: Drawable?) { super.setImageDrawable(drawable); fit() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    /** Back to the whole photo, centred. */
    fun fit() {
        anim?.cancel()
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        val s = minOf(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
        m.setScale(s, s)
        m.postTranslate((width - d.intrinsicWidth * s) / 2f, (height - d.intrinsicHeight * s) / 2f)
        cur = 1f
        imageMatrix = m
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val next = (cur * f).coerceIn(1f, 6f)
        val k = next / cur
        m.postScale(k, k, fx, fy); cur = next
        clamp(); imageMatrix = m
    }

    /** Keeps the photo on screen: centred while smaller than the view, edges never pulled inside it. */
    private fun clamp() {
        val d = drawable ?: return
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()); m.mapRect(rect)
        fun shift(lo: Float, hi: Float, size: Float) = when {
            hi - lo <= size -> (size - (hi - lo)) / 2f - lo
            lo > 0 -> -lo
            hi < size -> size - hi
            else -> 0f
        }
        m.postTranslate(shift(rect.left, rect.right, width.toFloat()), shift(rect.top, rect.bottom, height.toFloat()))
    }

    /** Smooth zoom to [target] around a point (double tap). */
    private fun animateTo(target: Float, fx: Float, fy: Float) {
        anim?.cancel()
        if (target <= 1f) {
            val start = cur
            anim = ValueAnimator.ofFloat(start, 1f).apply {
                duration = 220
                addUpdateListener { zoomBy(it.animatedValue as Float / cur, fx, fy); if (it.animatedFraction >= 1f) fit() }
                start()
            }
            return
        }
        anim = ValueAnimator.ofFloat(cur, target).apply {
            duration = 220
            addUpdateListener { zoomBy(it.animatedValue as Float / cur, fx, fy) }
            start()
        }
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor, d.focusX, d.focusY); return true }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { animateTo(if (zoomed) 1f else 2.5f, e.x, e.y); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed) return false
            m.postTranslate(-dx, -dy); clamp(); imageMatrix = m; return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoomed || e1 == null || scaler.isInProgress) return false
            val dx = e2.x - e1.x; val dy = e2.y - e1.y
            val dp = resources.displayMetrics.density
            if (abs(dy) > abs(dx) * 1.5f && dy > 120 * dp) { onDismiss?.invoke(); return true }
            if (abs(dx) > 80 * dp && abs(dx) > abs(dy)) { onSwipe?.invoke(if (dx < 0) 1 else -1); return true }
            return false
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        parent?.requestDisallowInterceptTouchEvent(zoomed || e.pointerCount > 1)
        return true
    }
}
