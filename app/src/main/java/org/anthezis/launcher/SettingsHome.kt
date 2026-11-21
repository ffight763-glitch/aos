package org.anthezis.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * AOS Settings > Home: the update banner, then one-row sliders of banners: the A-Apps, recommended and premium themes,
 * and the plans (Basic = the user's plan now, Plus monthly, Ultra with everything). Purchases are not wired yet.
 */
class SettingsHome(private val act: Activity, private val p: Palette) {
    private fun dp(v: Int) = act.dp(v)
    private val prefs get() = act.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)
    private val cardW get() = (act.resources.displayMetrics.widthPixels * 0.6f).toInt()

    private fun text(t: String, sp: Float, c: Int, w: Int = 400) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(w)
    }

    private fun title(t: String, hint: String) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(22), 0, dp(8))
        addView(text(t, 19f, p.text, 700)); addView(text(hint, 12f, p.sub))
    }

    /** A row of banners that scrolls sideways, one card at a time. */
    private fun slider(cards: List<View>, h: Int) = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
        addView(LinearLayout(act).apply {
            cards.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(cardW, h).apply { if (i > 0) marginStart = dp(10) }) }
        })
    }

    private fun gradient(a: Int, b: Int, r: Int = 24) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = dp(r).toFloat() }
    private fun col(hex: String, fallback: Int) = runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

    private fun banner(bg: android.graphics.drawable.Drawable, badge: String?, head: String, sub: String, button: String, fg: Int, onTap: () -> Unit) =
        LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = bg; setPadding(dp(14), dp(12), dp(14), dp(12))
            if (badge != null) addView(text(badge, 10f, fg, 700).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2)); background = act.shape((fg and 0xFFFFFF) or 0x33000000, 9)
            }, LinearLayout.LayoutParams(-2, -2))
            addView(text(head, 16f, fg, 700), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(text(sub, 12f, fg).apply { alpha = 0.85f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(text(button, 13f, fg, 700).apply {
                gravity = Gravity.CENTER; background = act.shape((fg and 0xFFFFFF) or 0x2E000000, 15)
            }, LinearLayout.LayoutParams(-1, dp(32)))
            setOnClickListener { onTap() }; pressFx(p, round = false)
        }

    // ---- A-Apps ----
    private class App(val name: String, val sub: String, val icon: Int, val open: () -> Intent?)
    private fun own(cls: Class<*>) = { Intent(act, cls) }
    private fun pkg(vararg names: String) = { names.firstNotNullOfOrNull { act.packageManager.getLaunchIntentForPackage(it) } }
    private val apps = listOf(
        App("Phone", "Calls and contacts", R.drawable.ic_phone, own(PhoneActivity::class.java)),
        App("Messages", "SMS in the AOS style", R.drawable.ic_chat, own(MessagesActivity::class.java)),
        App("Agram", "AOS Telegram", R.drawable.ic_chat, pkg("org.anthezis.atg.beta", "org.anthezis.atg")),
        App("Camera", "Photo and video", R.drawable.ic_camera, own(CameraActivity::class.java)),
        App("Browser", "With VPN button", R.drawable.ic_language, own(BrowserActivity::class.java)),
        App("Gallery", "Your photos", R.drawable.ic_camera, own(GalleryActivity::class.java)),
        App("Files", "AOS file manager", R.drawable.ic_folder, own(FilesActivity::class.java)),
        App("Translate", "Works offline", R.drawable.ic_translate, own(TranslatorActivity::class.java)),
        App("AOS Tube", "Videos", R.drawable.ic_tube, own(TubeActivity::class.java)),
    )

    /** One app as a story circle: accent ring, the glyph inside, the name under it. */
    private fun appStory(a: App): View {
        val intent = a.open()
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(android.widget.FrameLayout(act).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
                    setStroke(dp(2), if (intent != null) p.accent else p.sub)
                }
                addView(ImageView(act).apply {
                    setImageResource(a.icon); setColorFilter(p.onAccent); setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (intent != null) p.accent else p.surfaceHigh) }
                }, android.widget.FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(66), dp(66)))
            addView(text(a.name, 12f, if (intent != null) p.text else p.sub, 500).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(72), -2).apply { topMargin = dp(6) })
            setOnClickListener {
                if (intent == null) Toast.makeText(act, "${a.name} is not installed", Toast.LENGTH_SHORT).show()
                else try { act.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            pressFx(p)
        }
    }

    private fun stories() = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        addView(LinearLayout(act).apply {
            apps.forEachIndexed { i, a -> addView(appStory(a), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) }) }
        })
    }

    // ---- themes ----
    private val recommended = listOf("glacier", "aurora", "sakura", "neon", "forest_peaks", "synth", "dawn", "space")

    private fun applyTheme(id: String) {
        if (!AosPlan.check(act, AosPlan.themeNeeds(id))) return
        prefs.edit().putString("wallpaper", id).putBoolean("aos_dirty", true).apply()
        Toast.makeText(act, "${AosStyles.find(id).name} is on: go to the home screen", Toast.LENGTH_SHORT).show()
        act.recreate()
    }

    private fun themeCard(id: String, premium: Boolean): View {
        val s = AosStyles.find(id)
        val a = col(s.nightGlow, col(s.accentDark, p.accent)); val b = col(s.nightBottom, Color.BLACK)
        val current = prefs.getString("wallpaper", AosStyles.DEFAULT) == id
        val fx = Fx.family(id).replaceFirstChar { it.uppercase() }
        return banner(gradient(a, b), if (premium) "PREMIUM" else if (current) "ON NOW" else "RECOMMENDED", s.name,
            "Own wallpaper, accent and the $fx effects set", if (current) "Selected" else if (premium) "Try it" else "Apply", Color.WHITE) {
            if (!current) applyTheme(id)
        }
    }

    // ---- plans ----
    private class Plan(val id: String, val name: String, val price: String, val perks: String, val a: String, val b: String)
    private val plans = listOf(
        Plan("basic", "Basic", "Free", "Launcher, A-Apps, keyboard, free themes, basic effects", "#3A4048", "#1E2227"),
        Plan("plus", "Plus", "€2.99 / month", "Premium themes, glass, blur, parallax, depth, springs, Stack & Cube, own effects per theme", "#1A8FD0", "#6D4AE0"),
        Plan("ultra", "Ultra", "€6.99 / month", "Everything: Liquid Dock, Pinwheel, Dynamic Color and all of Plus", "#E0298F", "#D9A100"),
    )

    private fun planCard(pl: Plan): View {
        val mine = prefs.getString("aos_plan", "basic") == pl.id
        return banner(gradient(Color.parseColor(pl.a), Color.parseColor(pl.b)), if (mine) "YOUR PLAN" else pl.price, pl.name, pl.perks,
            if (mine) "Current plan" else if (AosPlan.ids.indexOf(pl.id) < AosPlan.level(act)) "Switch to ${pl.name}" else "Get ${pl.name} free", Color.WHITE) {
            // No payment yet: the plan switches at once.
            if (!mine) {
                AosPlan.set(act, AosPlan.ids.indexOf(pl.id))
                Toast.makeText(act, "AOS ${pl.name} is on (free while payments are not ready)", Toast.LENGTH_SHORT).show()
                act.recreate()
            }
        }
    }

    /** Account card: avatar, name, badges (plan, version, theme) and the launcher update button, all in one plate. */
    private fun accountCard(): View {
        val name = prefs.getString("google_name", "").orEmpty()
        val avatarUrl = prefs.getString("google_avatar", "").orEmpty()
        val fg = p.onAccent
        val planLevel = AosPlan.level(act)
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val deep = Color.rgb(Color.red(p.accent) * 3 / 5, Color.green(p.accent) * 3 / 5, Color.blue(p.accent) * 3 / 5)
            background = gradient(p.accent, deep, 26); setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                // Avatar: the Google picture when signed in (AOS Tube), otherwise the first letter on a soft circle.
                addView(android.widget.FrameLayout(act).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((fg and 0xFFFFFF) or 0x33000000) }
                    addView(text((name.firstOrNull() ?: 'A').uppercase(), 24f, fg, 700).apply { gravity = Gravity.CENTER },
                        android.widget.FrameLayout.LayoutParams(-1, -1))
                    if (avatarUrl.isNotBlank()) addView(ImageView(act).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
                        NetImages.load(avatarUrl, this, dp(56))
                    }, android.widget.FrameLayout.LayoutParams(-1, -1))
                }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                    addView(text(name.ifBlank { "AOS user" }, 19f, fg, 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(if (name.isBlank()) "Sign in with Google in AOS Tube" else "Google account", 12f, fg).apply { alpha = 0.8f })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            // Badges: plan, launcher version, current theme.
            addView(LinearLayout(act).apply {
                fun badge(t: String, strong: Boolean) = addView(text(t, 11f, if (strong) p.accent else fg, 700).apply {
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = act.shape(if (strong) fg else (fg and 0xFFFFFF) or 0x33000000, 11)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                badge(AosPlan.names[planLevel].uppercase(), planLevel > AosPlan.BASIC)
                badge("v" + Updater.currentVersion(act).second, false)
                badge(AosStyles.find(prefs.getString("wallpaper", AosStyles.DEFAULT)).name, false)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(text("Update launcher", 15f, p.accent, 700).apply {
                gravity = Gravity.CENTER; background = act.shape(fg, 20)
                setOnClickListener { Updater.check(act, true) }; pressFx(p, round = false)
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })
        }
    }

    fun view(): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(accountCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(title("A-Apps", "The apps of the AOS series"))
        addView(stories())
        addView(title("Themes", "Wallpapers, matching colours and motion"))
        addView(ThemeGallery(act, p, featuredCount = 4).view())
        addView(title("Subscriptions", "Your plan: " + (plans.firstOrNull { it.id == prefs.getString("aos_plan", "basic") }?.name ?: "Basic")))
        addView(slider(plans.map { planCard(it) }, dp(140)))
    }
}
