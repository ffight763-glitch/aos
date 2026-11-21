package org.anthezis.launcher

import android.app.Activity
import android.os.BatteryManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * The AOS top bar for every AOS app: the stock status bar is hidden (swipe from the top edge still shows it briefly) and a
 * transparent glass pill with the time, date and battery floats over the content, the same as on the home screen.
 * Call it right after setContentView.
 */
fun Activity.addAosTopBar(p: Palette) {
    // Every AOS app paints the chosen theme itself, so the phone's stock wallpaper never shows through.
    AosStyles.windowBackground(this, p.dark)?.let { window.setBackgroundDrawable(it) }
    // AOS Settings > Panels > "AOS top bar in apps": off = the phone's own status bar stays, no AOS pill.
    if (!getSharedPreferences("aos_setup", android.content.Context.MODE_PRIVATE).getBoolean("app_topbar", true)) return
    window.insetsController?.apply {
        hide(WindowInsets.Type.statusBars())
        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    val content = findViewById<ViewGroup>(android.R.id.content) as FrameLayout
    fun label(t: String, sp: Float, c: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }
    val pct = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val pill = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(6), dp(14), dp(6))
        // A plate in the app's background colour: content scrolling under the bar no longer runs into the clock.
        background = shape((p.bg and 0xFFFFFF) or 0xF0000000.toInt(), 18)
        addView(TextClock(this@addAosTopBar).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 14f; setTextColor(p.text); typeface = appFont(700)
        })
        addView(label(android.text.format.DateFormat.format("EEE, d MMM", java.util.Date()).toString(), 12f, p.sub, false).apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@addAosTopBar).apply { setImageResource(R.drawable.ic_battery); setColorFilter(p.text) }, LinearLayout.LayoutParams(dp(16), dp(16)))
        addView(label("$pct%", 12f, p.text, true).apply { setPadding(dp(4), 0, 0, 0) })
    }
    content.addView(pill, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(8), dp(12), 0) })
}
