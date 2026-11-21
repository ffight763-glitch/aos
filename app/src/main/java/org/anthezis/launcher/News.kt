package org.anthezis.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** News for the Feed page: RSS and Atom feeds picked by the user, merged and sorted by time. Everything stays on this phone. */
object News {
    class Source(val name: String, val url: String)
    class Article(val title: String, val link: String, val source: String, val time: Long, val image: String)

    val defaults = listOf(
        Source("Українська правда", "https://www.pravda.com.ua/rss/view_news/"),
        Source("Суспільне", "https://suspilne.media/rss/all.rss"),
        Source("Укрінформ", "https://www.ukrinform.ua/rss/block-lastnews"),
        Source("Європейська правда", "https://www.eurointegration.com.ua/rss/"),
        Source("BBC Ukrainian", "https://www.bbc.com/ukrainian/index.xml"),
        Source("BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
        Source("DW", "https://rss.dw.com/rdf/rss-en-all"),
        Source("The Verge", "https://www.theverge.com/rss/index.xml"),
        Source("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
        Source("TechCrunch", "https://techcrunch.com/feed/"),
        Source("Hacker News", "https://news.ycombinator.com/rss"),
        Source("NASA", "https://www.nasa.gov/rss/dyn/breaking_news.rss"))

    private fun prefs(c: Context) = c.getSharedPreferences("aos_news", Context.MODE_PRIVATE)

    fun custom(c: Context): List<Source> =
        prefs(c).getString("custom", "").orEmpty().split("\n").filter { it.contains("|") }.map { it.substringBefore("|") to it.substringAfter("|") }.map { Source(it.first, it.second) }

    fun all(c: Context) = defaults + custom(c)

    fun selected(c: Context): Set<String> =
        prefs(c).getStringSet("on", null) ?: setOf(defaults[0].url, defaults[1].url, defaults[5].url)

    fun setSelected(c: Context, urls: Set<String>) = prefs(c).edit().putStringSet("on", urls).putLong("fetched", 0).apply()

    fun addCustom(c: Context, url: String) {
        val clean = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val name = try { URL(clean).host.removePrefix("www.") } catch (_: Exception) { clean }
        val lines = prefs(c).getString("custom", "").orEmpty().split("\n").filter { it.isNotBlank() } + "$name|$clean"
        prefs(c).edit().putString("custom", lines.distinct().joinToString("\n")).apply()
        setSelected(c, selected(c) + clean)
    }

    fun removeCustom(c: Context, url: String) {
        val lines = prefs(c).getString("custom", "").orEmpty().split("\n").filter { it.isNotBlank() && !it.endsWith("|$url") }
        prefs(c).edit().putString("custom", lines.joinToString("\n")).apply()
        setSelected(c, selected(c) - url)
    }

    /** Articles from the cache when it is fresh (15 min), otherwise downloaded on a background thread. [done] runs off the main thread. */
    fun load(c: Context, force: Boolean = false, done: (List<Article>) -> Unit) {
        val app = c.applicationContext
        val p = prefs(app)
        if (!force && System.currentTimeMillis() - p.getLong("fetched", 0) < 15 * 60_000L) {
            val cached = fromJson(p.getString("cache", "[]").orEmpty())
            if (cached.isNotEmpty()) { done(cached); return }
        }
        Thread {
            val sources = all(app).filter { it.url in selected(app) }
            val out = mutableListOf<Article>()
            for (s in sources) try { out += parse(s) } catch (_: Exception) { }
            val sorted = out.sortedByDescending { it.time }.take(60)
            if (sorted.isNotEmpty()) p.edit().putString("cache", toJson(sorted)).putLong("fetched", System.currentTimeMillis()).apply()
            done(if (sorted.isEmpty()) fromJson(p.getString("cache", "[]").orEmpty()) else sorted)
        }.start()
    }

    private val formats = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    private fun time(s: String): Long {
        for (f in formats) try { return SimpleDateFormat(f, Locale.US).parse(s.trim())!!.time } catch (_: Exception) { }
        return 0L
    }

    private fun parse(src: Source): List<Article> {
        val conn = URL(src.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 10000; conn.setRequestProperty("User-Agent", "Mozilla/5.0 AOS")
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(conn.inputStream, null)
        val out = mutableListOf<Article>()
        var inItem = false
        var title = ""; var link = ""; var date = ""; var image = ""
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = parser.name
                if (n == "item" || n == "entry") { inItem = true; title = ""; link = ""; date = ""; image = "" }
                else if (inItem) when (n) {
                    "title" -> title = parser.nextText().trim()
                    "link" -> { val href = parser.getAttributeValue(null, "href"); link = href ?: parser.nextText().trim() }
                    "pubDate", "published", "updated", "date" -> if (date.isEmpty()) date = parser.nextText()
                    "thumbnail", "content", "enclosure" -> {
                        val u = parser.getAttributeValue(null, "url")
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val medium = parser.getAttributeValue(null, "medium").orEmpty()
                        if (image.isEmpty() && u != null && (n == "thumbnail" || type.startsWith("image") || medium == "image")) image = u
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && (parser.name == "item" || parser.name == "entry")) {
                inItem = false
                if (title.isNotEmpty() && link.isNotEmpty()) out += Article(title, link, src.name, time(date), image)
            }
            ev = parser.next()
        }
        return out.take(25)
    }

    private fun toJson(list: List<Article>) = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("t", it.title).put("l", it.link).put("s", it.source).put("d", it.time).put("i", it.image)) }
    }.toString()

    private fun fromJson(s: String): List<Article> = try {
        val a = JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Article(o.getString("t"), o.getString("l"), o.getString("s"), o.getLong("d"), o.optString("i")) } }
    } catch (_: Exception) { emptyList() }

    fun ago(ms: Long): String {
        if (ms <= 0) return ""
        val m = (System.currentTimeMillis() - ms) / 60_000
        return when { m < 1 -> "now"; m < 60 -> "$m min"; m < 1440 -> "${m / 60} h"; else -> "${m / 1440} d" }
    }
}
