package org.anthezis.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AOS power menu through the assistant slot. The phone does not give the power button to accessibility services, but it does
 * give a long press to the default assistant. AOS Home registers as that assistant, and when it is started this way it
 * just opens [PowerMenuActivity] (the AOS power menu) instead of any search or voice screen.
 */
class AosAssistService : VoiceInteractionService()

class AosAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AosAssistSession(this)
}

class AosAssistSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            startAssistantActivity(Intent(context, PowerMenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: Exception) { }
        hide()
    }
}

/** The AOS power menu: a dimmed screen with a glass card, shown over everything (also the lock screen). */
class PowerMenuActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val p = Palette(this)
        fun button(label: String, glyph: String, color: Int, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(this@PowerMenuActivity).apply {
                text = glyph; textSize = 24f; gravity = Gravity.CENTER; setTextColor(p.onAccent); background = circle(color)
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(TextView(this@PowerMenuActivity).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(p.text); typeface = appFont(600)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            setOnClickListener { finish(); onClick() }
        }
        val svc = SplitService.instance
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(p.surface, 30, p.stroke); isClickable = true
            setPadding(dp(18), dp(18), dp(18), dp(16))
            addView(TextView(this@PowerMenuActivity).apply {
                text = "Power"; textSize = 18f; typeface = appFont(700); setTextColor(p.text)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            addView(LinearLayout(this@PowerMenuActivity).apply {
                val gap = dp(10)
                addView(button("Lock", "🔒", p.accent) { svc?.lockScreen() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("Screenshot", "▣", p.accent) { svc?.screenshot() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
                addView(button("Settings", "⚙", p.accent) {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
                addView(button("Power off", "⏻", 0xFFE5484D.toInt()) { svc?.powerDialog() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
            })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { finish() }
            addView(card, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.9f).toInt(), -2, Gravity.CENTER))
        })
    }
}
