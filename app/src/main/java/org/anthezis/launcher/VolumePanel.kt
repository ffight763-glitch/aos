package org.anthezis.launcher

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * AOS volume panel: a glass card above the bottom navigation with three sound modes (Sound, Vibrate, Silent) and a wide
 * volume slider. It appears when a volume key is pressed, can be touched, and hides itself after a couple of seconds.
 * Turn it on or off in the Manager (Style).
 */
class VolumePanel(private val svc: AccessibilityService) {
    private val p = Palette(svc)
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val audio = svc.getSystemService(AudioManager::class.java)
    private val handler = android.os.Handler(svc.mainLooper)
    private val d = svc.resources.displayMetrics.density
    private var shown = false
    private var level = 0f
    private var stream = AudioManager.STREAM_MUSIC

    private val hide = Runnable {
        if (shown) { try { wm.removeView(root) } catch (_: Exception) {}; shown = false }
    }

    private fun keepOpen() { handler.removeCallbacks(hide); handler.postDelayed(hide, 2600) }

    /** Wide slider: the accent fill grows from the left; dragging it sets the volume of the current stream. */
    private val slider = object : View(svc) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = p.glassCircle; c.drawRoundRect(rect, r, r, paint)
            c.save(); c.clipRect(0f, 0f, width * level, height.toFloat())
            paint.color = p.accent; c.drawRoundRect(rect, r, r, paint)
            c.restore()
            paint.color = if (level > 0.12f) p.onAccent else p.text
            paint.textSize = 14 * d; paint.typeface = appFont(700); paint.textAlign = Paint.Align.LEFT
            c.drawText("${(level * 100).toInt()}%", 16 * d, height / 2f + 5 * d, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    level = (e.x / width).coerceIn(0f, 1f)
                    val max = audio.getStreamMaxVolume(stream)
                    audio.setStreamVolume(stream, Math.round(level * max).coerceIn(0, max), 0)
                    invalidate(); keepOpen()
                }
            }
            return true
        }
    }

    private val modeViews = mutableListOf<TextView>()

    private fun paintModes() {
        val cur = when (audio.ringerMode) { AudioManager.RINGER_MODE_NORMAL -> 0; AudioManager.RINGER_MODE_VIBRATE -> 1; else -> 2 }
        modeViews.forEachIndexed { i, t ->
            t.setTextColor(if (i == cur) p.onAccent else p.text)
            t.background = if (i == cur) svc.shape(p.accent, 18) else svc.shape(p.glassCircle, 18)
        }
    }

    private fun setMode(i: Int) {
        try {
            audio.ringerMode = when (i) { 0 -> AudioManager.RINGER_MODE_NORMAL; 1 -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_SILENT }
        } catch (_: SecurityException) {
            Toast.makeText(svc, "Allow Do Not Disturb access to use Silent", Toast.LENGTH_SHORT).show()
        }
        paintModes(); keepOpen()
    }

    private val root = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = svc.glassShape(p, 28, true)
        setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
        // Sound modes as tabs.
        addView(LinearLayout(svc).apply {
            for ((i, name) in listOf("Sound", "Vibrate", "Silent").withIndex()) {
                val t = TextView(svc).apply {
                    text = name; textSize = 14f; typeface = appFont(600); gravity = Gravity.CENTER
                    setOnClickListener { setMode(i) }
                }
                modeViews += t
                addView(t, LinearLayout.LayoutParams(0, (38 * d).toInt(), 1f).apply { if (i > 0) marginStart = (6 * d).toInt() })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })
        addView(slider, LinearLayout.LayoutParams(-1, (44 * d).toInt()))
        elevation = 12 * d
    }

    fun show(value: Float, stream: Int) {
        level = value.coerceIn(0f, 1f); this.stream = stream
        slider.invalidate(); paintModes()
        if (!shown) {
            val w = (svc.resources.displayMetrics.widthPixels * 0.92f).toInt()
            val lp = WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
                .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (112 * d).toInt() }   // above the dock / navigation bar
            try { wm.addView(root, lp); shown = true } catch (_: Exception) {}
            root.translationY = 40 * d; root.alpha = 0f
            root.animate().translationY(0f).alpha(1f).setDuration(160).start()
        }
        keepOpen()
    }
}
