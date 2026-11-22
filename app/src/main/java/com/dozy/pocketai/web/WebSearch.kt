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

    suspend fun search(query: String, limit: Int = 5): List<SearchHit> = withContext(Dispatchers.IO) {
        val key = query.trim().lowercase() + "#" + limit
        synchronized(cache) { cache[key] }?.let { (t, hits) -> if (System.currentTimeMillis() - t < CACHE_MS) return@withContext hits }
        val ddg = runCatching { duckDuckGo(query, limit) }.getOrDefault(emptyList())
        val hits = ddg.ifEmpty { runCatching { wikipedia(query, limit) }.getOrDefault(emptyList()) }
        if (hits.isNotEmpty()) synchronized(cache) { cache[key] = System.currentTimeMillis() to hits }
        hits
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
