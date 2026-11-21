package org.anthezis.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * AOS Tube: a small YouTube client in the AOS style. It uses the official YouTube Data API v3 with the user's own API key
 * and YouTube's embedded player, so the service's terms (ads included) stay intact. Bottom navigation: Home (trending),
 * Channels, Subscriptions, Settings and Profile. Subscriptions and the profile live on this phone: the API key cannot
 * read a Google account, so channels are followed locally.
 */
class TubeActivity : Activity() {
    private class Video(val id: String, val title: String, val channel: String, val channelId: String, val thumb: String, val published: String = "")

    private lateinit var p: Palette
    private val prefs by lazy { getSharedPreferences("aos_tube", MODE_PRIVATE) }
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var searchBox: EditText
    private lateinit var playerBox: FrameLayout
    private lateinit var web: WebView
    private lateinit var nav: LinearLayout
    private lateinit var searchBar: View
    private var tab = 0
    /** Home feeds, the pills under the search box: Recommended, History, Favorites. */
    private var feed = 0
    private val feeds = listOf("Recommended", "History", "Favorites")
    private lateinit var chips: LinearLayout
    private lateinit var chipRow: View
    private val tabs = listOf("Home" to R.drawable.ic_home, "Channels" to R.drawable.ic_play, "Subscriptions" to R.drawable.ic_star,
        "Settings" to R.drawable.ic_tools, "Profile" to R.drawable.ic_person)

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        searchBox = EditText(this).apply {
            hint = "Search YouTube"; setHintTextColor(p.sub); setTextColor(p.text); typeface = appFont(400)
            background = shape(p.surface, 22, p.stroke); isSingleLine = true
            setPadding(dp(18), dp(10), dp(18), dp(10)); imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { search(text.toString()); true } else false }
        }
        val searchBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_search); setColorFilter(p.onAccent); setPadding(dp(11), dp(11), dp(11), dp(11))
            background = circle(p.accent); setOnClickListener { search(searchBox.text.toString()) }
        }
        searchBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(searchBox, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(searchBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chipRow = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(chips) }
        status = text("", 14f, p.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(32), 0, 0) }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(status) }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = android.webkit.WebChromeClient()
            addJavascriptInterface(PlayerBridge(), "AOS")
            setBackgroundColor(0xFF000000.toInt())
        }
        playerBox = FrameLayout(this).apply {
            visibility = View.GONE; setBackgroundColor(0xFF000000.toInt())
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(this@TubeActivity).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(0xFFFFFFFF.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8))
                background = circle(0x66000000); setOnClickListener { stopPlayer() }
            }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
        }
        nav = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)); background = glassShape(p, 32, true) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.wallTop, p.wallBottom))
            setPadding(dp(16), dp(48), dp(16), dp(56))
            addView(text("AOS Tube", 24f, p.text, true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
            addView(searchBar)
            addView(chipRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(playerBox, LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels - dp(32)) * 9 / 16).apply { topMargin = dp(12) })
            addView(ScrollView(this@TubeActivity).apply { isVerticalScrollBarEnabled = false; addView(results) },
                LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
            addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        })
        addAosTopBar(p)
        intent?.getStringExtra("play")?.let { id -> web.post { play(Video(id, id, "", "", "")) } }
        openTab(0)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("play")?.let { play(Video(it, it, "", "", "")) }
    }

    // ---- API ----

    /** The key typed in Settings wins; otherwise the default baked in at build time (empty in public builds). */
    private fun apiKey(): String = prefs.getString("key", "").orEmpty().ifBlank { BuildConfig.YOUTUBE_KEY }
    private fun region() = prefs.getString("region", "UA").orEmpty()

    private fun api(path: String): JSONObject {
        val sep = if (path.contains("?")) "&" else "?"
        val conn = URL("https://www.googleapis.com/youtube/v3/$path${sep}key=" + URLEncoder.encode(apiKey(), "UTF-8")).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 10000
        val body = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        val json = JSONObject(body)
        if (json.has("error")) throw IllegalStateException(json.getJSONObject("error").optString("message", "API error"))
        return json
    }

    private fun clean(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun parse(items: JSONArray, kind: String): List<Video> = (0 until items.length()).mapNotNull { i ->
        val it = items.getJSONObject(i)
        val sn = it.getJSONObject("snippet")
        val id = when (kind) {
            "search" -> it.getJSONObject("id").optString("videoId")
            "playlist" -> sn.getJSONObject("resourceId").optString("videoId")
            else -> it.optString("id")
        }
        if (id.isBlank()) return@mapNotNull null
        val th = sn.optJSONObject("thumbnails")
        Video(id, clean(sn.getString("title")), sn.optString("channelTitle"), sn.optString("channelId"),
            th?.optJSONObject("medium")?.optString("url") ?: th?.optJSONObject("default")?.optString("url").orEmpty(), sn.optString("publishedAt"))
    }

    /** Runs [work] off the main thread and hands the videos to the list, or shows the error. */
    private fun load(label: String, work: () -> List<Video>) {
        if (apiKey().isBlank()) { status.text = "Add your YouTube Data API key in Settings."; results.removeAllViews(); results.addView(status); return }
        status.text = label
        results.removeAllViews(); results.addView(status)
        Thread {
            try { val v = work(); runOnUiThread { show(v) } }
            catch (e: Exception) { runOnUiThread { status.text = "Failed: ${e.message}"; results.removeAllViews(); results.addView(status) } }
        }.start()
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        tab = 0; paintNav(); feed = -1; paintChips()
        load("Searching…") { parse(api("search?part=snippet&type=video&videoEmbeddable=true&maxResults=20&q=" + URLEncoder.encode(query, "UTF-8")).getJSONArray("items"), "search") }
    }

    private fun trending() = load("Loading…") { parse(api("videos?part=snippet&chart=mostPopular&maxResults=20&regionCode=${region()}").getJSONArray("items"), "video") }

    private fun uploads(channelId: String, n: Int) =
        parse(api("playlistItems?part=snippet&maxResults=$n&playlistId=UU${channelId.drop(2)}").getJSONArray("items"), "playlist")

    // ---- followed channels, history (stored on this phone) ----

    private fun channels(): List<Pair<String, String>> {
        val a = JSONArray(prefs.getString("channels", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("title") } }
    }

    private fun saveChannels(list: List<Pair<String, String>>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("id", it.first).put("title", it.second)) }
        prefs.edit().putString("channels", a.toString()).apply()
    }

    private fun history(): List<Pair<String, String>> {
        val a = JSONArray(prefs.getString("history", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("title") } }
    }

    private fun remember(v: Video) {
        if (v.title == v.id) return
        store("history", listOf(v) + saved("history").filter { it.id != v.id }, 60)
    }

    /** Saved videos ("history", "favorites"); older history entries only have an id and a title. */
    private fun saved(key: String): List<Video> {
        val a = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val id = o.getString("id")
            Video(id, o.optString("title", id), o.optString("channel"), o.optString("channelId"),
                o.optString("thumb").ifBlank { "https://i.ytimg.com/vi/$id/mqdefault.jpg" })
        }
    }

    private fun store(key: String, list: List<Video>, max: Int) {
        val a = JSONArray()
        list.take(max).forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel).put("channelId", it.channelId).put("thumb", it.thumb)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun isFavorite(v: Video) = saved("favorites").any { it.id == v.id }

    private fun toggleFavorite(v: Video) {
        val list = saved("favorites")
        store("favorites", if (list.any { it.id == v.id }) list.filter { it.id != v.id } else listOf(v) + list, 200)
        android.widget.Toast.makeText(this, if (isFavorite(v)) "Added to Favorites" else "Removed from Favorites", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- Home feeds (pills) ----

    private fun paintChips() {
        chips.removeAllViews()
        feeds.forEachIndexed { i, name ->
            val on = i == feed
            chips.addView(text(name, 14f, if (on) p.onAccent else p.text, on).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8))
                background = if (on) shape(p.accent, 18) else glassShape(p, 18)
                setOnClickListener { openFeed(i) }
                pressFx(p, round = false)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    private fun openFeed(i: Int) {
        feed = i; paintChips()
        when (i) {
            0 -> recommended()
            1 -> saved("history").let { if (it.isEmpty()) empty("No history yet. Videos you watch appear here.") else show(it) }
            else -> saved("favorites").let { if (it.isEmpty()) empty("No favorites yet. Long-press a video to add it.") else show(it) }
        }
    }

    /** Drops videos whose owner forbids playing them outside YouTube (they would only show "Video unavailable"). */
    private fun embeddable(list: List<Video>): List<Video> {
        if (list.isEmpty()) return list
        val ok = HashSet<String>()
        for (chunk in list.chunked(50)) runCatching {
            val items = api("videos?part=status&id=" + chunk.joinToString(",") { it.id }).getJSONArray("items")
            for (i in 0 until items.length()) items.getJSONObject(i).let { if (it.optJSONObject("status")?.optBoolean("embeddable", true) != false) ok += it.getString("id") }
        }.onFailure { return list }
        return list.filter { it.id in ok }
    }

    /** The player reports an error (embedding forbidden, removed...): open the video in the YouTube app instead. */
    inner class PlayerBridge {
        @android.webkit.JavascriptInterface fun failed(id: String) = runOnUiThread {
            stopPlayer()
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id"))) } catch (_: Exception) {}
        }
    }

    private fun empty(msg: String) { status.text = msg; results.removeAllViews(); results.addView(status) }

    /** New videos from the channels you follow and the ones you watched, without what you have seen already. */
    private fun recommended() = load("Picking videos for you…") {
        val seen = saved("history").map { it.id }.toSet()
        val from = (channels().map { it.first } + saved("history").map { it.channelId } + saved("favorites").map { it.channelId })
            .filter { it.isNotBlank() }.distinct().take(8)
        val picked = from.flatMap { runCatching { uploads(it, 6) }.getOrDefault(emptyList()) }.filter { it.id !in seen }.shuffled().take(30)
        embeddable(picked).ifEmpty { parse(api("videos?part=snippet&chart=mostPopular&maxResults=20&regionCode=${region()}").getJSONArray("items"), "video") }
    }

    // ---- tabs ----

    private fun paintNav() {
        nav.removeAllViews()
        tabs.forEachIndexed { i, (name, icon) ->
            val on = i == tab
            val c = if (on) p.onAccent else p.sub
            nav.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(2), dp(7), dp(2), dp(6))
                background = if (on) shape(p.accent, 24) else null
                addView(ImageView(this@TubeActivity).apply { setImageResource(icon); setColorFilter(c) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(if (name == "Subscriptions") "Subs" else name, 10f, c, on).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) },
                    LinearLayout.LayoutParams(-2, -2))
                setOnClickListener { openTab(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
    }

    private fun openTab(i: Int) {
        tab = i; paintNav()
        searchBar.visibility = if (i == 0) View.VISIBLE else View.GONE
        chipRow.visibility = searchBar.visibility
        when (i) {
            0 -> openFeed(feed.coerceAtLeast(0))
            1 -> showChannels()
            2 -> load("Loading your channels…") {
                channels().take(10).flatMap { runCatching { uploads(it.first, 6) }.getOrDefault(emptyList()) }.sortedByDescending { it.published }.take(30)
            }
            3 -> showSettings()
            else -> showProfile()
        }
    }

    private fun section(title: String, sub: String = "") {
        results.addView(text(title, 20f, p.text, true).apply { setPadding(dp(4), dp(6), 0, dp(2)) })
        if (sub.isNotBlank()) results.addView(text(sub, 13f, p.sub).apply { setPadding(dp(4), 0, 0, dp(10)) })
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = text(label, 15f, if (primary) p.onAccent else p.text, true).apply {
        gravity = Gravity.CENTER; background = shape(if (primary) p.accent else p.surfaceHigh, 24); setOnClickListener { onClick() }; pressFx(p, round = false)
    }

    private fun showChannels() {
        results.removeAllViews()
        section("Channels", "Channels you follow. Long-press a video anywhere to follow its channel.")
        val list = channels()
        if (list.isEmpty()) results.addView(text("No channels yet.", 14f, p.sub).apply { setPadding(dp(4), dp(8), 0, 0) })
        for ((id, title) in list) results.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 18); setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text(title.take(1).uppercase(), 16f, p.onAccent, true).apply { gravity = Gravity.CENTER; background = circle(p.accent) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(text(title, 16f, p.text, true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            setOnClickListener { tab = 0; paintNav(); searchBar.visibility = View.VISIBLE; load("Loading $title…") { uploads(id, 20) } }
            setOnLongClickListener { unfollow(id, title); true }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    /** Long-press on a video: add it to Favorites (or take it out) or follow its channel. */
    private fun videoMenu(v: Video) {
        val fav = isFavorite(v)
        aosDialog(p, v.title, v.channel, null,
            DialogAction(if (fav) "Remove from Favorites" else "Add to Favorites", if (fav) 2 else 1) {
                toggleFavorite(v); if (tab == 0 && feed == 2) openFeed(2)
            },
            DialogAction("Channel…") { follow(v) },
            DialogAction("Cancel"))
    }

    private fun follow(v: Video) {
        if (v.channelId.isBlank()) return
        val followed = channels().any { it.first == v.channelId }
        aosDialog(p, v.channel, if (followed) "You follow this channel." else "Follow it to see its new videos in Subscriptions.", null,
            DialogAction(if (followed) "Unfollow" else "Follow", if (followed) 2 else 1) {
                saveChannels(if (followed) channels().filter { it.first != v.channelId } else channels() + (v.channelId to v.channel))
            }, DialogAction("Cancel"))
    }

    private fun unfollow(id: String, title: String) = aosDialog(p, title, "Stop following this channel?", null,
        DialogAction("Unfollow", 2) { saveChannels(channels().filter { it.first != id }); showChannels() }, DialogAction("Cancel"))

    private fun showSettings() {
        results.removeAllViews()
        section("Settings", "Everything is stored on this phone.")
        val gap = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) }
        results.addView(button("YouTube API key: " + if (apiKey().isBlank()) "not set" else "set", apiKey().isBlank()) { askKey() }, gap)
        results.addView(button("Region: ${region()}") {
            val all = listOf("UA", "US", "PL", "DE", "GB", "FR", "TR", "KZ")
            prefs.edit().putString("region", all[(all.indexOf(region()) + 1) % all.size]).apply(); showSettings()
        }, gap)
        results.addView(button("Clear watch history") { prefs.edit().remove("history").apply(); showSettings() }, gap)
    }

    // ---- Google account (Authorization API; the app is matched by package name and signing key in Google Cloud) ----

    private val youtubeScope = "https://www.googleapis.com/auth/youtube.readonly"

    private fun signIn() {
        val req = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(youtubeScope))).build()
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener { res ->
                val pi = res.pendingIntent
                if (res.hasResolution() && pi != null) startIntentSenderForResult(pi.intentSender, 9, null, 0, 0, 0)
                else res.accessToken?.let { useToken(it) }
            }
            .addOnFailureListener { android.widget.Toast.makeText(this, "Google sign-in failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 9 || resultCode != RESULT_OK) return
        try {
            com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data).accessToken?.let { useToken(it) }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Google sign-in failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun authed(path: String, token: String): JSONObject {
        val conn = URL("https://www.googleapis.com/youtube/v3/$path").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 10000; conn.readTimeout = 10000
        val body = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        val json = JSONObject(body)
        if (json.has("error")) throw IllegalStateException(json.getJSONObject("error").optString("message", "API error"))
        return json
    }

    /** Reads the account's channel (name, picture) and its subscriptions, and follows those channels on this phone. */
    private fun useToken(token: String) {
        Thread {
            try {
                val me = authed("channels?part=snippet&mine=true", token).optJSONArray("items")?.optJSONObject(0)?.optJSONObject("snippet")
                val subs = JSONArray()
                var page: String? = null
                do {
                    val j = authed("subscriptions?part=snippet&mine=true&maxResults=50" + (page?.let { "&pageToken=$it" } ?: ""), token)
                    val items = j.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) subs.put(items.getJSONObject(i).getJSONObject("snippet"))
                    page = j.optString("nextPageToken").ifBlank { null }
                } while (page != null && subs.length() < 200)
                val merged = channels().toMutableList()
                for (i in 0 until subs.length()) {
                    val sn = subs.getJSONObject(i)
                    val id = sn.getJSONObject("resourceId").getString("channelId")
                    if (merged.none { it.first == id }) merged += id to sn.getString("title")
                }
                saveChannels(merged)
                prefs.edit().putString("google_name", me?.optString("title").orEmpty().ifBlank { "Google account" })
                    .putString("google_avatar", me?.optJSONObject("thumbnails")?.optJSONObject("default")?.optString("url").orEmpty()).apply()
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Signed in, ${subs.length()} subscriptions imported", android.widget.Toast.LENGTH_LONG).show()
                    if (tab == 4) showProfile()
                }
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this, "Could not read your account: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showProfile() {
        results.removeAllViews()
        val google = prefs.getString("google_name", "").orEmpty()
        val name = google.ifBlank { prefs.getString("name", "").orEmpty().ifBlank { Profiles.userName(this) } }
        if (google.isNotBlank()) {
            val avatar = ImageView(this).apply { background = circle(p.surfaceHigh); clipToOutline = true; scaleType = ImageView.ScaleType.CENTER_CROP }
            prefs.getString("google_avatar", "").orEmpty().takeIf { it.isNotBlank() }?.let { loadThumb(it, avatar) }
            results.addView(avatar, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        }
        section(name, (if (google.isNotBlank()) "Signed in with Google  ·  " else "") + "${history().size} watched  ·  ${channels().size} channels followed")
        val rowGap = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) }
        if (google.isBlank()) results.addView(button("Sign in with Google", true) { signIn() }, rowGap)
        else {
            results.addView(button("Refresh subscriptions", true) { signIn() }, rowGap)
            results.addView(button("Sign out") { prefs.edit().remove("google_name").remove("google_avatar").apply(); showProfile() }, rowGap)
        }
        results.addView(button("Change name") {
            val input = EditText(this).apply { setText(name); isSingleLine = true }
            AlertDialog.Builder(this).setTitle("Your name").setView(input)
                .setPositiveButton("Save") { _, _ -> prefs.edit().putString("name", input.text.toString().trim()).apply(); showProfile() }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(12) })
        results.addView(text("Recently watched", 15f, p.sub, true).apply { setPadding(dp(4), 0, 0, dp(6)) })
        if (history().isEmpty()) results.addView(text("Nothing yet.", 14f, p.sub).apply { setPadding(dp(4), 0, 0, 0) })
        for ((id, title) in history().take(12)) results.addView(text(title, 14f, p.text).apply {
            maxLines = 2; background = shape(p.surfaceHigh, 14); setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { play(Video(id, title, "", "", "")) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun askKey() {
        val input = EditText(this).apply { hint = "YouTube Data API v3 key"; setText(apiKey()); isSingleLine = true }
        AlertDialog.Builder(this).setTitle("API key").setView(input)
            .setPositiveButton("Save") { _, _ -> prefs.edit().putString("key", input.text.toString().trim()).apply(); if (tab == 3) showSettings() else openTab(tab) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- list and player ----

    private fun show(videos: List<Video>) {
        results.removeAllViews()
        if (videos.isEmpty()) { status.text = "Nothing here yet"; results.addView(status); return }
        for (v in videos) {
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(p.surfaceHigh, 12) }
            loadThumb(v.thumb, thumb)
            results.addView(LinearLayout(this).apply {
                background = glassShape(p, 18); setPadding(dp(8), dp(8), dp(12), dp(8))
                gravity = Gravity.CENTER_VERTICAL
                addView(thumb, LinearLayout.LayoutParams(dp(128), dp(72)))
                addView(LinearLayout(this@TubeActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                    addView(text(v.title, 14f, p.text, true).apply { maxLines = 2 })
                    addView(text(v.channel, 12f, p.sub).apply { maxLines = 1; setPadding(0, dp(4), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { play(v) }
                setOnLongClickListener { videoMenu(v); true }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    /** Shared loader: pooled threads, timeouts, scaled-down pictures, a bounded cache (see [NetImages]). */
    private fun loadThumb(url: String, into: ImageView) = NetImages.load(url, into, resources.displayMetrics.widthPixels / 2)

    private fun play(v: Video) {
        remember(v)
        playerBox.visibility = View.VISIBLE
        // YouTube rejects embeds that do not identify the embedding app (errors 152/153). The player is an iframe on a page
        // whose base URL is this app's own origin, so the request carries a proper Referer/origin.
        val origin = "https://$packageName"
        val html = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <style>html,body{margin:0;height:100%;background:#000}iframe,#p{border:0;width:100%;height:100%;display:block}</style></head><body>
            <div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            // Start playing at once instead of showing the cover with a play button. If the WebView blocks autoplay with
            // sound, start muted and turn the sound back on as soon as the video runs.
            function onYouTubeIframeAPIReady() {
              new YT.Player('p', { videoId: '${v.id}', width: '100%', height: '100%',
                playerVars: { autoplay: 1, playsinline: 1, rel: 0, origin: '$origin', widget_referrer: '$origin' },
                events: {
                  onError: function (e) { if (e.data == 101 || e.data == 150 || e.data == 152 || e.data == 153 || e.data == 100) AOS.failed('${v.id}'); },
                  onReady: function (e) {
                    e.target.playVideo();
                    setTimeout(function () {
                      if (e.target.getPlayerState() !== 1) { e.target.mute(); e.target.playVideo(); setTimeout(function () { e.target.unMute(); }, 900); }
                    }, 1200);
                  }
                } });
            }
            </script></body></html>"""
        web.loadDataWithBaseURL(origin, html, "text/html", "utf-8", null)
    }

    private fun stopPlayer() {
        web.loadUrl("about:blank")
        playerBox.visibility = View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playerBox.visibility == View.VISIBLE) stopPlayer() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
