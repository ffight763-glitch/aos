package org.anthezis.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** News sources picker shared by the home Feed and the browser start page: tick sources, add your own RSS link, long-press your own link to remove it. */
fun Activity.newsSourcesDialog(p: Palette, onDone: () -> Unit) {
    val dlg = Dialog(this)
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
    fun label(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400); gravity = Gravity.CENTER
    }
    val chosen = News.selected(this).toMutableSet()
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun fill() {
        rows.removeAllViews()
        for (s in News.all(this)) rows.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val on = s.url in chosen
            background = shape(if (on) p.accent else p.surfaceHigh, 18); setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(LinearLayout(this@newsSourcesDialog).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(s.name, 15f, if (on) p.onAccent else p.text, true).apply { gravity = Gravity.START })
                addView(label(s.url.removePrefix("https://").substringBefore('/'), 11f, if (on) p.onAccent else p.sub).apply { gravity = Gravity.START })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(if (on) "✓" else "", 18f, p.onAccent, true))
            setOnClickListener { if (on) chosen -= s.url else chosen += s.url; fill() }
            if (News.custom(this@newsSourcesDialog).any { it.url == s.url }) setOnLongClickListener {
                News.removeCustom(this@newsSourcesDialog, s.url); chosen -= s.url; fill(); true
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }
    fill()
    val add = label("+  Add RSS link", 15f, p.text, true).apply {
        background = shape(p.surfaceHigh, 24); setOnClickListener {
            val input = EditText(this@newsSourcesDialog).apply { hint = "https://site.com/feed"; isSingleLine = true }
            android.app.AlertDialog.Builder(this@newsSourcesDialog).setTitle("RSS or Atom link").setView(input)
                .setPositiveButton("Add") { _, _ ->
                    if (input.text.isNotBlank()) { News.addCustom(this@newsSourcesDialog, input.text.toString()); chosen.clear(); chosen += News.selected(this@newsSourcesDialog); fill() }
                }.setNegativeButton("Cancel", null).show()
        }
    }
    val done = label("Done", 15f, p.onAccent, true).apply {
        background = shape(p.accent, 24); setOnClickListener { News.setSelected(this@newsSourcesDialog, chosen); dlg.dismiss(); onDone() }
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = glassShape(p, 28, true); setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(label("News sources", 20f, p.text, true).apply { gravity = Gravity.START })
        addView(label("Tap to choose. Long-press your own link to remove it.", 12f, p.sub).apply { gravity = Gravity.START; setPadding(0, dp(2), 0, dp(10)) })
        addView(ScrollView(this@newsSourcesDialog).apply { isVerticalScrollBarEnabled = false; addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(add, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        addView(done, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
    }
    dlg.setContentView(FrameLayout(this).apply {
        setPadding(dp(12), dp(40), dp(12), dp(40))
        addView(panel, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.78f).toInt()))
    })
    dlg.window?.apply { setBackgroundDrawable(ColorDrawable(0)); setLayout(-1, -2); setDimAmount(0.5f) }
    dlg.show()
}
