package org.anthezis.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AOS power menu: shown when the power button is held, instead of the stock one. A dimmed screen with a glass card of
 * four round buttons (Lock, Screenshot, Settings, Power off). Power off hands over to the system's own confirmation,
 * because only the system may switch the phone off.
 */
class PowerMenu(private val svc: SplitService) {
    private val p = Palette(svc)
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val d = svc.resources.displayMetrics.density
    private var root: View? = null

    val isShown get() = root != null

    fun hide() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
    }

    private fun button(label: String, glyph: String, color: Int, onClick: () -> Unit): View = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(TextView(svc).apply {
            text = glyph; textSize = 24f; gravity = Gravity.CENTER; setTextColor(p.onAccent)
            background = svc.circle(color)
        }, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()))
        addView(TextView(svc).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(p.text); typeface = appFont(600)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * d).toInt() })
        setOnClickListener { hide(); onClick() }
    }

    fun show() {
        if (root != null) return
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = svc.shape(p.surface, 30, p.stroke)
            setPadding((18 * d).toInt(), (18 * d).toInt(), (18 * d).toInt(), (16 * d).toInt())
            isClickable = true
            addView(TextView(svc).apply {
                text = "Power"; textSize = 18f; typeface = appFont(700); setTextColor(p.text); gravity = Gravity.START
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (14 * d).toInt() })
            addView(LinearLayout(svc).apply {
                val gap = (10 * d).toInt()
                addView(button("Lock", "🔒", p.accent) { svc.lockScreen() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("Screenshot", "▣", p.accent) { svc.screenshot() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
                addView(button("Settings", "⚙", p.accent) {
                    svc.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
                addView(button("Power off", "⏻", 0xFFE5484D.toInt()) { svc.powerDialog() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
            })
        }
        val scrim = FrameLayout(svc).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { hide() }
            addView(card, FrameLayout.LayoutParams((svc.resources.displayMetrics.widthPixels * 0.9f).toInt(), -2, Gravity.CENTER))
            alpha = 0f
        }
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(scrim, lp); root = scrim; scrim.animate().alpha(1f).setDuration(140).start() } catch (_: Exception) {}
    }
}
