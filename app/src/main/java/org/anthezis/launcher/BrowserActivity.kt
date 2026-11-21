package org.anthezis.launcher

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder

/**
 * AOS Browser: a small browser in the AOS style, laid out like AOS Tube (top pill, address bar, content, bottom bar).
 * Shields block known ad and tracker hosts, plain http pages are upgraded to https and third-party cookies are off.
 * Tabs, private tabs, bookmarks and history are stored on this phone. It is a WebView underneath, so it renders like Chrome.
 */
class BrowserActivity : Activity() {
    private class Tab(val web: WebView, var title: String = "New tab", var url: String = "", val priv: Boolean = false)

    private lateinit var p: Palette
    private val prefs by lazy { getSharedPreferences("aos_browser", MODE_PRIVATE) }
    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private lateinit var holder: FrameLayout
    private lateinit var address: EditText
    private lateinit var progress: View
    private lateinit var shieldText: TextView
    private lateinit var sheet: FrameLayout
    private lateinit var sheetBody: LinearLayout
    private lateinit var sheetTitle: TextView
    private var blocked = 0
    private var shields = true
    private var desktop = false

    private val trackers = listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "connect.facebook.net", "ads-twitter.com",
        "scorecardresearch.com", "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com",
        "moatads.com", "hotjar.com", "quantserve.com", "pubmatic.com", "rubiconproject.com", "openx.net", "adsrvr.org",
        "mc.yandex.ru", "an.yandex.ru", "yandexadexchange.net", "top-fwz1.mail.ru", "ads.yahoo.com", "bat.bing.com", "clarity.ms",
        "adform.net", "casalemedia.com", "smartadserver.com", "teads.tv", "mathtag.com", "bidswitch.net", "everesttech.net",
        "krxd.net", "demdex.net", "2mdn.net", "adsafeprotected.com", "serving-sys.com", "popads.net", "propellerads.com")

    private fun isTracker(host: String?) = host != null && trackers.any { host == it || host.endsWith(".$it") }

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        shields = prefs.getBoolean("shields", true)

        address = EditText(this).apply {
            hint = "Search or type a web address"; setHintTextColor(p.sub); setTextColor(p.text); typeface = appFont(400)
            background = shape(p.surface, 22, p.stroke); isSingleLine = true; textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10)); imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { go(text.toString()); true } else false }
            setOnFocusChangeListener { _, f -> if (f) post { selectAll() } }
        }
        shieldText = text("0", 11f, p.onAccent, true).apply { gravity = Gravity.CENTER }
        val shield = FrameLayout(this).apply {
            background = circle(p.accent)
            addView(ImageView(this@BrowserActivity).apply { setImageResource(R.drawable.ic_lock); setColorFilter(p.onAccent); setPadding(dp(9), dp(7), dp(9), dp(15)) })
            addView(shieldText, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(4) })
            setOnClickListener { toggleShields() }
        }
        val reload = ImageView(this).apply {
            setImageResource(R.drawable.ic_history); setColorFilter(p.text); setPadding(dp(10), dp(10), dp(10), dp(10)); background = circle(p.surfaceHigh)
            setOnClickListener { web()?.reload() }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(shield, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) })
            addView(address, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(reload, LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        progress = View(this).apply { setBackgroundColor(p.accent); visibility = View.GONE }
        holder = FrameLayout(this).apply { background = shape(p.surface, 18); clipToOutline = true }

        sheetTitle = text("", 20f, p.text, true)
        sheetBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet = FrameLayout(this).apply {
            visibility = View.GONE; setBackgroundColor(p.surface); isClickable = true
            addView(LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(LinearLayout(this@BrowserActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(sheetTitle, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ImageView(this@BrowserActivity).apply {
                        setImageResource(R.drawable.ic_close); setColorFilter(p.text); setPadding(dp(8), dp(8), dp(8), dp(8)); background = circle(p.surfaceHigh)
                        setOnClickListener { sheet.visibility = View.GONE }
                    }, LinearLayout.LayoutParams(dp(36), dp(36)))
                })
                addView(ScrollView(this@BrowserActivity).apply { isVerticalScrollBarEnabled = false; addView(sheetBody) },
                    LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
            })
        }

        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)); background = glassShape(p, 32, true) }
        fun navItem(name: String, icon: Int, onClick: () -> Unit) = nav.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(2), dp(7), dp(2), dp(6))
            addView(ImageView(this@BrowserActivity).apply { setImageResource(icon); setColorFilter(p.sub) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text(name, 10f, p.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
            setOnClickListener { onClick() }; pressFx(p, round = false)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        navItem("Back", R.drawable.ic_chevron_left) { goBack() }
        navItem("Home", R.drawable.ic_home) { showStart() }
        navItem("Tabs", R.drawable.ic_folder) { showTabs() }
        navItem("VPN", R.drawable.ic_lock) { vpnDialog() }
        navItem("Page", R.drawable.ic_more) { showMenu() }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.wallTop, p.wallBottom))
            setPadding(dp(12), dp(52), dp(12), dp(8))
            addView(bar)
            addView(progress, LinearLayout.LayoutParams(0, dp(3)).apply { topMargin = dp(6) })
            addView(holder, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
            addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        setContentView(FrameLayout(this).apply { addView(column); addView(sheet, FrameLayout.LayoutParams(-1, -1)) })
        addAosTopBar(p)

        val target = intent?.data?.toString()
        newTab(target, false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.toString()?.let { newTab(it, false) }
    }

    // ---- tabs ----

    private fun web(): WebView? = tabs.getOrNull(cur)?.web

    private fun newTab(url: String?, priv: Boolean) {
        val wv = WebView(this)
        val tab = Tab(wv, priv = priv)
        wv.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true; loadWithOverviewMode = true; useWideViewPort = true
            if (priv) { cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE; saveFormData = false }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.scheme == "aos") {
                    // Start page actions: choose a search engine, open the news sources picker.
                    when (u.host) {
                        "engine" -> { getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putString("engine", u.lastPathSegment).apply(); showStart() }
                        "sources" -> newsSourcesDialog(p) { showStart() }
                        "vpn" -> vpnDialog()
                    }
                    return true
                }
                if (req.isForMainFrame && u.scheme == "http" && u.host != "localhost" && u.host?.startsWith("192.168.") != true) {
                    view.loadUrl(u.buildUpon().scheme("https").build().toString()); return true
                }
                if (u.scheme != "http" && u.scheme != "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
                }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (shields && isTracker(req.url.host)) {
                    runOnUiThread { blocked++; shieldText.text = blocked.toString() }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.url = url
                if (tabs.getOrNull(cur) === tab && !url.startsWith("https://aos.start")) address.setText(url.removePrefix("https://").removeSuffix("/"))
            }
            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                if (!tab.priv && !url.startsWith("https://aos.start") && url != "about:blank") remember("history", url, tab.title)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tabs.getOrNull(cur) !== tab) return
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                progress.layoutParams = (progress.layoutParams as LinearLayout.LayoutParams).apply { width = (holder.width * newProgress / 100) }
            }
            override fun onReceivedTitle(view: WebView, title: String) { tab.title = title }
        }
        wv.setDownloadListener { dl, ua, disp, mime, _ ->
            try {
                val r = DownloadManager.Request(Uri.parse(dl)).apply {
                    setMimeType(mime); addRequestHeader("User-Agent", ua)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(dl, disp, mime))
                }
                getSystemService(DownloadManager::class.java).enqueue(r)
                android.widget.Toast.makeText(this, "Downloading…", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
        tabs += tab
        select(tabs.lastIndex)
        if (url.isNullOrBlank()) showStart() else go(url)
    }

    private fun select(i: Int) {
        cur = i.coerceIn(0, tabs.lastIndex)
        holder.removeAllViews()
        val t = tabs[cur]
        holder.addView(t.web, FrameLayout.LayoutParams(-1, -1))
        address.setText(if (t.url.isBlank() || t.url.startsWith("https://aos.start")) "" else t.url.removePrefix("https://").removeSuffix("/"))
        sheet.visibility = View.GONE
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        t.web.destroy()
        if (tabs.isEmpty()) { newTab(null, false) } else select(if (cur >= tabs.size) tabs.lastIndex else cur.coerceAtMost(tabs.lastIndex))
    }

    private fun goBack() {
        val w = web() ?: return
        if (sheet.visibility == View.VISIBLE) { sheet.visibility = View.GONE; return }
        if (w.canGoBack()) w.goBack() else if (tabs.size > 1) closeTab(cur) else showStart()
    }

    // ---- navigation ----

    private fun go(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> {
                // Same engine as the home search tabs (Google by default).
                val name = getSharedPreferences("aos_setup", MODE_PRIVATE).getString("engine", "Google")
                val base = mapOf("Google" to "https://www.google.com/search?q=", "DuckDuckGo" to "https://duckduckgo.com/?q=",
                    "Brave" to "https://search.brave.com/search?q=", "Bing" to "https://www.bing.com/search?q=",
                    "YouTube" to "https://www.youtube.com/results?search_query=", "Wikipedia" to "https://en.wikipedia.org/w/index.php?search=")[name]
                (base ?: "https://www.google.com/search?q=") + URLEncoder.encode(s, "UTF-8")
            }
        }
        web()?.loadUrl(url)
        address.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private class Engine(val name: String, val action: String, val param: String)
    private val engines = listOf(
        Engine("Google", "https://www.google.com/search", "q"), Engine("DuckDuckGo", "https://duckduckgo.com/", "q"),
        Engine("Brave", "https://search.brave.com/search", "q"), Engine("Bing", "https://www.bing.com/search", "q"),
        Engine("YouTube", "https://www.youtube.com/results", "search_query"), Engine("Wikipedia", "https://en.wikipedia.org/w/index.php", "search"))

    private fun engine(): Engine {
        val name = getSharedPreferences("aos_setup", MODE_PRIVATE).getString("engine", "Google")
        return engines.firstOrNull { it.name == name } ?: engines[0]
    }

    /**
     * The start page of a tab, like a browser's new-tab page: search with search-engine tabs (Google by default), your news
     * recommendations with a Sources link, and your bookmarks. Links of the form aos://... are handled by the browser itself.
     */
    // ---- VPN button: a proxy for the browser tabs (servers are yours: country name + host:port) ----

    private fun proxyServers(): List<Pair<String, String>> =
        prefs.getString("proxy_servers", "").orEmpty().lines().filter { it.contains("|") }.map { it.substringBefore("|").trim() to it.substringAfter("|").trim() }

    private fun saveProxyServers(list: List<Pair<String, String>>) = prefs.edit().putString("proxy_servers", list.joinToString("\n") { "${it.first}|${it.second}" }).apply()

    private fun proxyActive() = prefs.getInt("proxy_active", -1)

    /** Applies the chosen server to every browser tab (or removes the proxy when it is off). */
    private fun applyProxy() {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) return
        val server = proxyServers().getOrNull(proxyActive())
        val ctl = androidx.webkit.ProxyController.getInstance()
        if (server == null) ctl.clearProxyOverride({ it.run() }) { }
        else ctl.setProxyOverride(androidx.webkit.ProxyConfig.Builder().addProxyRule(server.second).build(), { it.run() }) { }
    }

    private fun vpnDialog() {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) {
            android.widget.Toast.makeText(this, "This phone's WebView cannot use a proxy: update Android System WebView", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val servers = proxyServers()
        val actions = ArrayList<DialogAction>()
        actions += DialogAction("Off", if (proxyActive() < 0) 1 else 0) { prefs.edit().putInt("proxy_active", -1).apply(); applyProxy(); showStart() }
        servers.forEachIndexed { i, (country, addr) ->
            actions += DialogAction("$country  ·  $addr", if (i == proxyActive()) 1 else 0) {
                prefs.edit().putInt("proxy_active", i).apply(); applyProxy(); showStart()
                android.widget.Toast.makeText(this, "VPN on: $country", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        actions += DialogAction("Add a server…") { addProxyDialog() }
        if (servers.isNotEmpty()) actions += DialogAction("Remove a server…", 2) { removeProxyDialog() }
        actions += DialogAction("Close")
        aosDialog(p, "VPN", "Sends this browser's traffic through your own proxy server in the country you pick. Only the browser tabs, not the whole phone.", null, *actions.toTypedArray())
    }

    private fun addProxyDialog() {
        val country = android.widget.EditText(this).apply { hint = "Country (e.g. Germany)"; setSingleLine() }
        val addr = android.widget.EditText(this).apply { hint = "host:port  (http://, https:// or socks5:// allowed)"; setSingleLine() }
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(country); addView(addr) }
        android.app.AlertDialog.Builder(this).setTitle("Add a server").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val c = country.text.toString().trim().replace("|", " "); val a = addr.text.toString().trim()
                if (c.isNotEmpty() && a.isNotEmpty()) { saveProxyServers(proxyServers() + (c to a)); vpnDialog() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun removeProxyDialog() {
        val servers = proxyServers()
        android.app.AlertDialog.Builder(this).setTitle("Remove which server?")
            .setItems(servers.map { "${it.first}  ·  ${it.second}" }.toTypedArray()) { _, i ->
                val left = servers.filterIndexed { k, _ -> k != i }
                val active = proxyActive()
                prefs.edit().putInt("proxy_active", if (active == i) -1 else if (active > i) active - 1 else active).apply()
                saveProxyServers(left); applyProxy(); showStart()
            }.show()
    }
    private var proxyApplied = false

    private fun showStart(articles: List<News.Article>? = null) {
        if (!proxyApplied) { proxyApplied = true; applyProxy() }   // the chosen VPN server is restored when the browser opens
        val t = tabs.getOrNull(cur) ?: return
        t.url = ""; t.title = "New tab"; address.setText("")
        val bg = String.format("#%06X", p.bg and 0xFFFFFF); val fg = String.format("#%06X", p.text and 0xFFFFFF)
        val sub = String.format("#%06X", p.sub and 0xFFFFFF); val acc = String.format("#%06X", p.accent and 0xFFFFFF)
        val card = String.format("#%06X", p.surfaceHigh and 0xFFFFFF); val onAcc = String.format("#%06X", p.onAccent and 0xFFFFFF)
        fun esc(s: String) = android.text.Html.escapeHtml(s)
        val e = engine()
        val vpnNow = proxyServers().getOrNull(proxyActive())
        val vpnChip = """<a class="c${if (vpnNow != null) " on" else ""}" href="aos://vpn">🛡 VPN: ${if (vpnNow != null) esc(vpnNow.first) else "Off"}</a>"""
        val chips = engines.joinToString("") { """<a class="c${if (it === e) " on" else ""}" href="aos://engine/${it.name}">${it.name}</a>""" }
        val marks = bookmarks().take(8).joinToString("") { (u, n) ->
            val label = n.ifBlank { u.removePrefix("https://").substringBefore('/') }.take(14)
            """<a class="t" href="${esc(u)}"><span>${esc(label.take(1).uppercase())}</span>${esc(label)}</a>"""
        }
        val news = when {
            articles == null -> """<p class="m">Loading…</p>"""
            articles.isEmpty() -> """<p class="m">No news yet. Tap Sources to pick some.</p>"""
            else -> articles.take(14).joinToString("") {
                """<a class="n" href="${esc(it.link)}"><small>${esc(it.source)}${News.ago(it.time).let { a -> if (a.isEmpty()) "" else " · $a" }}</small>${esc(it.title)}</a>"""
            }
        }
        val html = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
            body{margin:0;background:$bg;color:$fg;font-family:sans-serif;padding:0 16px 40px}
            h1{margin:44px 0 4px;font-size:28px;text-align:center}.s{color:$sub;margin:0 0 18px;font-size:13px;text-align:center}
            input{width:100%;box-sizing:border-box;padding:14px 18px;border-radius:26px;border:0;background:$card;color:$fg;font-size:16px}
            .cs{display:flex;overflow-x:auto;margin:12px -16px 0;padding:0 16px}
            .c{flex:none;margin-right:8px;padding:8px 14px;border-radius:18px;background:$card;color:$fg;text-decoration:none;font-size:13px;font-weight:600}
            .c.on{background:$acc;color:$onAcc}
            .h{display:flex;justify-content:space-between;align-items:center;margin:26px 4px 8px}.h b{font-size:18px}
            .h a{color:$onAcc;background:$acc;text-decoration:none;font-size:12px;font-weight:700;padding:6px 12px;border-radius:14px}
            .n{display:block;margin-bottom:8px;padding:12px 14px;border-radius:18px;background:$card;color:$fg;text-decoration:none;font-size:15px;font-weight:600;line-height:1.3}
            .n small{display:block;color:$acc;font-size:11px;font-weight:700;margin-bottom:4px}.m{color:$sub;font-size:13px;margin:6px 4px}
            .g{display:flex;flex-wrap:wrap;justify-content:center;margin:22px 0 0}
            .t{width:76px;margin:10px;color:$fg;text-decoration:none;font-size:12px;text-align:center}
            .t span{display:block;margin:0 auto 6px;width:52px;height:52px;line-height:52px;border-radius:26px;background:$acc;color:#fff;font-size:22px;font-weight:700}
            </style></head><body><h1>AOS Browser</h1><p class="s">Private by default. Ads and trackers are blocked.</p>
            <form action="${e.action}" method="get"><input name="${e.param}" placeholder="Search ${e.name}" autocomplete="off"></form>
            <div class="cs">$chips</div>
            <div class="cs">$vpnChip</div>
            <div class="h"><b>For you</b><a href="aos://sources">Sources</a></div>$news
            <div class="g">$marks</div></body></html>"""
        t.web.loadDataWithBaseURL("https://aos.start/", html, "text/html", "utf-8", null)
        if (articles == null) News.load(this) { list -> runOnUiThread { if (tabs.getOrNull(cur) === t && t.url.isBlank()) showStart(list) } }
    }

    // ---- storage ----

    private fun list(key: String): List<Pair<String, String>> {
        val a = JSONArray(prefs.getString(key, "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("u") to o.optString("t") } }
    }

    private fun save(key: String, items: List<Pair<String, String>>) {
        val a = JSONArray(); items.forEach { a.put(JSONObject().put("u", it.first).put("t", it.second)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun remember(key: String, url: String, title: String) {
        save(key, (listOf(url to title) + list(key).filter { it.first != url }).take(if (key == "history") 200 else 100))
    }

    private fun bookmarks() = list("bookmarks")

    // ---- sheets and menu ----

    private fun openSheet(title: String, rows: List<Pair<String, String>>, onPick: (String) -> Unit, onLong: ((String) -> Unit)? = null) {
        sheetTitle.text = title
        sheetBody.removeAllViews()
        if (rows.isEmpty()) sheetBody.addView(text("Nothing here yet.", 14f, p.sub).apply { setPadding(dp(4), dp(12), 0, 0) })
        for ((u, t) in rows) sheetBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(p.surfaceHigh, 16); setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(text(t.ifBlank { u }, 15f, p.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(text(u.removePrefix("https://"), 12f, p.sub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            setOnClickListener { sheet.visibility = View.GONE; onPick(u) }
            if (onLong != null) setOnLongClickListener { onLong(u); true }
            pressFx(p, round = false)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        sheet.visibility = View.VISIBLE
    }

    private fun showTabs() {
        sheetTitle.text = "Tabs (${tabs.size})"
        sheetBody.removeAllViews()
        tabs.forEachIndexed { i, t ->
            sheetBody.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = shape(if (i == cur) p.accent else p.surfaceHigh, 16); setPadding(dp(14), dp(10), dp(8), dp(10))
                val c = if (i == cur) p.onAccent else p.text
                addView(LinearLayout(this@BrowserActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text((if (t.priv) "● Private  " else "") + t.title, 15f, c, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(t.url.removePrefix("https://").ifBlank { "Start page" }, 12f, if (i == cur) p.onAccent else p.sub).apply { maxLines = 1 })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(this@BrowserActivity).apply {
                    setImageResource(R.drawable.ic_close); setColorFilter(c); setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { closeTab(i); showTabs() }
                }, LinearLayout.LayoutParams(dp(36), dp(36)))
                setOnClickListener { select(i) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        sheetBody.addView(text("+  New tab", 15f, p.onAccent, true).apply {
            gravity = Gravity.CENTER; background = shape(p.accent, 24); setOnClickListener { newTab(null, false) }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        sheet.visibility = View.VISIBLE
    }

    private fun showMenu() {
        val w = web()
        val url = tabs.getOrNull(cur)?.url.orEmpty()
        val marked = bookmarks().any { it.first == url }
        aosDialog(p, "Menu", "$blocked ads and trackers blocked this session", null,
            DialogAction("Forward") { w?.let { if (it.canGoForward()) it.goForward() } },
            DialogAction("New tab", 1) { newTab(null, false) },
            DialogAction("New private tab") { newTab(null, true) },
            DialogAction(if (marked) "Remove bookmark" else "Bookmark this page") {
                if (url.isNotBlank()) save("bookmarks", if (marked) bookmarks().filter { it.first != url } else listOf(url to (tabs[cur].title)) + bookmarks())
            },
            DialogAction("Bookmarks") { openSheet("Bookmarks", bookmarks(), { go(it) }) { u -> save("bookmarks", bookmarks().filter { it.first != u }); openSheet("Bookmarks", bookmarks(), { go(it) }) } },
            DialogAction("History") { openSheet("History", list("history"), { go(it) }) },
            DialogAction(if (desktop) "Mobile site" else "Desktop site") {
                desktop = !desktop
                w?.settings?.userAgentString = if (desktop) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36" else null
                w?.reload()
            },
            DialogAction(if (shields) "Shields: on" else "Shields: off") { toggleShields() },
            DialogAction("Clear browsing data", 2) {
                save("history", emptyList()); CookieManager.getInstance().removeAllCookies(null); w?.clearCache(true)
                android.widget.Toast.makeText(this, "Cleared", android.widget.Toast.LENGTH_SHORT).show()
            })
    }

    private fun toggleShields() {
        shields = !shields
        prefs.edit().putBoolean("shields", shields).apply()
        shieldText.text = if (shields) blocked.toString() else "off"
        android.widget.Toast.makeText(this, if (shields) "Shields on" else "Shields off", android.widget.Toast.LENGTH_SHORT).show()
        web()?.reload()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet.visibility == View.VISIBLE) sheet.visibility = View.GONE
        else if (web()?.canGoBack() == true) web()?.goBack()
        else if (tabs.size > 1) closeTab(cur)
        else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }
}
