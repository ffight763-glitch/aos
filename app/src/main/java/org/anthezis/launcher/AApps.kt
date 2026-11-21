package org.anthezis.launcher

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AOS Settings > A-Apps: the A-series apps in one place, the ones built into the launcher (Phone, Messages, Camera...) and
 * the separate ones (Agram). A tile opens the app; an app that is not on the phone says so instead.
 */
class AApps(private val act: Activity, private val p: Palette) {
    private class App(val name: String, val sub: String, val icon: Int, val open: () -> Intent?)

    private fun own(cls: Class<*>) = { Intent(act, cls) }

    /** A separate app by package; Agram ships as a debug (".beta") or a release build. */
    private fun pkg(vararg names: String) = { names.firstNotNullOfOrNull { act.packageManager.getLaunchIntentForPackage(it) } }

    private val apps = listOf(
        App("Phone", "Calls and contacts", R.drawable.ic_phone, own(PhoneActivity::class.java)),
        App("Messages", "SMS", R.drawable.ic_chat, own(MessagesActivity::class.java)),
        App("Agram", "AOS Telegram", R.drawable.ic_chat, pkg("org.anthezis.atg.beta", "org.anthezis.atg")),
        App("Camera", "Photo and video", R.drawable.ic_camera, own(CameraActivity::class.java)),
        App("Browser", "AOS Browser", R.drawable.ic_language, own(BrowserActivity::class.java)),
        App("Gallery", "Photos", R.drawable.ic_camera, own(GalleryActivity::class.java)),
        App("Files", "AOS Files", R.drawable.ic_folder, own(FilesActivity::class.java)),
        App("Translate", "AOS Translate", R.drawable.ic_language, own(TranslatorActivity::class.java)),
        App("AOS Tube", "Videos", R.drawable.ic_tube, own(TubeActivity::class.java)),
    )

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400); gravity = Gravity.CENTER; maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    fun view(): View {
        val cols = 3
        val w = (act.resources.displayMetrics.widthPixels - act.dp(32) - act.dp(8) * (cols - 1)) / cols
        val grid = GridLayout(act).apply { columnCount = cols }
        apps.forEachIndexed { i, a ->
            val intent = a.open()
            grid.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                background = act.glassShape(p, 22); setPadding(act.dp(8), act.dp(14), act.dp(8), act.dp(12))
                // The A tile: the app's glyph on an accent square, like the dock icons of the launcher.
                addView(ImageView(act).apply {
                    setImageResource(a.icon); setColorFilter(p.onAccent); setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12))
                    background = act.shape(if (intent != null) p.accent else p.surfaceHigh, 16)
                }, LinearLayout.LayoutParams(act.dp(54), act.dp(54)))
                addView(text(a.name, 14f, p.text, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(8) })
                addView(text(if (intent != null) a.sub else "Not installed", 11f, p.sub), LinearLayout.LayoutParams(-1, -2))
                setOnClickListener {
                    if (intent == null) android.widget.Toast.makeText(act, "${a.name} is not installed", android.widget.Toast.LENGTH_SHORT).show()
                    else try { act.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
                pressFx(p, round = false)
            }, GridLayout.LayoutParams().apply {
                width = w; height = -2
                setMargins(if (i % cols == 0) 0 else act.dp(8), act.dp(8), 0, 0)
            })
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("A-Apps", 20f, p.text, true).apply { gravity = Gravity.START; setPadding(act.dp(4), act.dp(14), 0, act.dp(2)) })
            addView(text("The apps of the AOS series", 13f, p.sub).apply { gravity = Gravity.START; setPadding(act.dp(4), 0, 0, act.dp(4)) })
            addView(grid)
        }
    }
}
