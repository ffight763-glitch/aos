package com.dozy.pocketai.web

import com.dozy.pocketai.data.WebSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

data class SearchHit(val title: String, val url: String, val snippet: String)

data class WebContext(val text: String, val sources: List<WebSource>)

/**
 * Gives local models internet access: searches the web (DuckDuckGo, Wikipedia as
 * fallback), pulls the readable text of the top pages and packs it into a compact
 * context block the model answers from.
 */
object WebSearch {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true }

    /** query → (time, hits); repeated questions are answered without a network round-trip */
    private val cache = object : LinkedHashMap<String, Pair<Long, List<SearchHit>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<SearchHit>>>) = size > 40
    }
    private const val CACHE_MS = 10 * 60 * 1000L

    /** Engines to ask, set from the settings. */
    @Volatile var engines: Set<String> = setOf("google", "bing", "duckduckgo", "brave")
    /** Drop Russian sites and search engines from the results. */
    @Volatile var blockRu: Boolean = true

    private val ruHosts = listOf(
        "yandex.", "ya.ru", "mail.ru", "vk.com", "vk.ru", "ok.ru", "dzen.ru", "rambler.ru", "rutube.ru",
        "sber", "habr.com", "pikabu.ru", "rbc.ru", "ria.ru", "tass.", "lenta.ru", "gazeta.ru", "kp.ru",
    )

    private fun isRu(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (host.endsWith(".ru") || host.endsWith(".su") || host.endsWith(".xn--p1ai") || host.endsWith(".рф")) return true
        return ruHosts.any { host.contains(it) }
    }

    /** Asks all engines in parallel and merges their answers rank by rank, without duplicates. */
    suspend fun search(query: String, limit: Int = 5): List<SearchHit> = withContext(Dispatchers.IO) {
        val key = query.trim().lowercase() + "#" + limit + "#" + engines.sorted() + blockRu
        synchronized(cache) { cache[key] }?.let { (t, hits) -> if (System.currentTimeMillis() - t < CACHE_MS) return@withContext hits }
        val want = limit + 4
        val lists = coroutineScope {
            listOfNotNull(
                if ("google" in engines) async { runCatching { google(query, want) }.getOrDefault(emptyList()) } else null,
                if ("bing" in engines) async { runCatching { bing(query, want) }.getOrDefault(emptyList()) } else null,
                if ("duckduckgo" in engines) async { runCatching { duckDuckGo(query, want) }.getOrDefault(emptyList()) } else null,
                if ("brave" in engines) async { runCatching { brave(query, want) }.getOrDefault(emptyList()) } else null,
            ).awaitAll()
        }
        val merged = LinkedHashMap<String, SearchHit>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (rank in 0 until longest) for (l in lists) {
            val h = l.getOrNull(rank) ?: continue
            if (blockRu && isRu(h.url)) continue
            val norm = h.url.substringBefore('#').removeSuffix("/").replace("://www.", "://").lowercase()
            val prev = merged[norm]
            if (prev == null) merged[norm] = h
            else if (prev.snippet.length < h.snippet.length) merged[norm] = prev.copy(snippet = h.snippet)
        }
        var hits = merged.values.take(limit)
        if (hits.isEmpty()) hits = runCatching { wikipedia(query, limit) }.getOrDefault(emptyList()).filterNot { blockRu && isRu(it.url) }
        if (hits.isNotEmpty()) synchronized(cache) { cache[key] = System.currentTimeMillis() to hits }
        hits
    }

    private fun google(query: String, limit: Int): List<SearchHit> {
        val doc = Jsoup.connect("https://www.google.com/search")
            .userAgent(UA)
            .data("q", query).data("num", "${limit + 2}").data("hl", "en").data("gbv", "1").data("safe", "off")
            // skip the EU consent page
            .cookie("CONSENT", "YES+cb").cookie("SOCS", "CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
            .timeout(7_000)
            .get()
        return doc.select("a:has(h3)").asSequence().mapNotNull { a ->
            var href = a.attr("href")
            if (href.startsWith("/url?")) href = Regex("[?&]q=([^&]+)").find(href)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: return@mapNotNull null
            if (!href.startsWith("http") || "google." in href.substringAfter("://").substringBefore('/')) return@mapNotNull null
            val title = a.selectFirst("h3")?.text().orEmpty()
            // the snippet sits in the result block around the link
            val block = generateSequence(a.parent()) { it.parent() }.take(5).firstOrNull { it.text().length > title.length + 40 }
            val snippet = block?.text()?.replace(title, "")?.trim()?.take(400).orEmpty()
            SearchHit(title, href, snippet)
        }.distinctBy { it.url }.take(limit).toList()
    }

    private fun bing(query: String, limit: Int): List<SearchHit> {
        val doc = Jsoup.connect("https://www.bing.com/search")
            .userAgent(UA).data("q", query).data("count", "${limit + 2}").data("setlang", "en")
            .cookie("SRCHHPGUSR", "ADLT=OFF").timeout(7_000).get()
        return doc.select("li.b_algo").asSequence().mapNotNull { li ->
            val a = li.selectFirst("h2 a") ?: return@mapNotNull null
            val url = cleanBingUrl(a.attr("href"))
            if (!url.startsWith("http")) return@mapNotNull null
            val snippet = (li.selectFirst(".b_caption p") ?: li.selectFirst(".b_lineclamp2, .b_lineclamp3, .b_lineclamp4, p"))?.text().orEmpty()
            SearchHit(a.text(), url, snippet)
        }.take(limit).toList()
    }

    /** Bing wraps links as bing.com/ck/a?…&u=a1<base64url>. */
    private fun cleanBingUrl(href: String): String {
        if (!href.contains("bing.com/ck/")) return href
        val u = Regex("[?&]u=a1([^&]+)").find(href)?.groupValues?.get(1) ?: return href
        return runCatching {
            String(android.util.Base64.decode(u, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
        }.getOrDefault(href)
    }

    private fun brave(query: String, limit: Int): List<SearchHit> {
        val doc = Jsoup.connect("https://search.brave.com/search")
            .userAgent(UA).data("q", query).data("source", "web").timeout(7_000).get()
        return doc.select("div.snippet[data-type=web], div[data-type=web]").asSequence().mapNotNull { d ->
            val a = d.selectFirst("a[href^=http]") ?: return@mapNotNull null
            val url = a.attr("href")
            if ("brave.com" in url) return@mapNotNull null
            val title = (d.selectFirst(".title, .snippet-title, .heading") ?: a).text()
            val snippet = d.selectFirst(".snippet-description, .snippet-content, .description, .content")?.text().orEmpty()
            SearchHit(title, url, snippet)
        }.distinctBy { it.url }.take(limit).toList()
    }

    /** ⚡ fast mode: search snippets only, no page downloads. */
    suspend fun quickContext(query: String, results: Int = 6): WebContext {
        val hits = search(query, results)
        if (hits.isEmpty()) return WebContext("", emptyList())
        val text = hits.mapIndexed { i, h -> "[${i + 1}] ${h.title} — ${h.url}\n${h.snippet}" }.joinToString("\n\n")
        return WebContext(text, hits.map { WebSource(it.title, it.url) })
    }

    private fun duckDuckGo(query: String, limit: Int): List<SearchHit> {
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/")
            .userAgent(UA)
            .data("q", query)
            .data("kl", "wt-wt")
            .timeout(7_000)
            .post()
        return doc.select("div.result").asSequence()
            .filterNot { it.hasClass("result--ad") }
            .mapNotNull { r ->
                val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
                val url = cleanDdgUrl(a.attr("href"))
                if (!url.startsWith("http")) return@mapNotNull null
                SearchHit(a.text(), url, r.selectFirst(".result__snippet")?.text().orEmpty())
            }
            .distinctBy { it.url }
            .take(limit)
            .toList()
    }

    private fun cleanDdgUrl(href: String): String {
        val h = if (href.startsWith("//")) "https:$href" else href
        val uddg = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1) ?: return h
        return URLDecoder.decode(uddg, "UTF-8")
    }

    private fun wikipedia(query: String, limit: Int): List<SearchHit> {
        val lang = if (query.any { it in 'а'..'я' || it in 'А'..'Я' }) "ru" else "en"
        val q = URLEncoder.encode(query, "UTF-8")
        val body = Jsoup.connect(
            "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=$limit&srsearch=$q",
        ).ignoreContentType(true).userAgent("PocketAI/1.0").timeout(10_000).execute().body()
        val items = json.parseToJsonElement(body).jsonObject["query"]!!.jsonObject["search"]!!.jsonArray
        return items.map {
            val o = it.jsonObject
            val title = o["title"]!!.jsonPrimitive.content
            SearchHit(
                title,
                "https://$lang.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                Jsoup.parse(o["snippet"]?.jsonPrimitive?.content.orEmpty()).text(),
            )
        }
    }

    /** Readable main text of a page, trimmed to [maxChars]. */
    suspend fun fetchText(url: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            val doc = Jsoup.connect(url).userAgent(UA).timeout(6_000).maxBodySize(2_000_000)
                .followRedirects(true).get()
            doc.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg").remove()
            val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
            val paragraphs = root.select("p, li, h1, h2, h3, td")
                .map { it.text().trim() }
                .filter { it.length > 40 }
                .distinct()
            val text = (if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n") else root.text())
                .replace(Regex("\\s{3,}"), "  ")
            if (text.length > maxChars) text.take(maxChars).substringBeforeLast(' ') + "…" else text
        }.getOrDefault("")
    }

    /** Search + read the top pages, return the block that is injected into the prompt. */
    suspend fun buildContext(query: String, pages: Int, charsPerPage: Int): WebContext = coroutineScope {
        val hits = search(query, limit = pages.coerceAtLeast(1) + 2)
        if (hits.isEmpty()) return@coroutineScope WebContext("", emptyList())
        val top = hits.take(pages)
        val bodies = top.map { h -> async { fetchText(h.url, charsPerPage) } }.awaitAll()
        val sb = StringBuilder()
        top.forEachIndexed { i, h ->
            sb.append("[${i + 1}] ").append(h.title).append(" — ").append(h.url).append('\n')
            val body = bodies[i].ifBlank { h.snippet }
            sb.append(body).append("\n\n")
        }
        WebContext(sb.toString().trim(), top.map { WebSource(it.title, it.url) })
    }
}
