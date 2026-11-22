package pro.translate.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class Entry(
    val id: Long,
    val from: String,
    val to: String,
    val input: String,
    val output: String,
    val time: Long,
    val fav: Boolean = false,
)

class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    val items = mutableStateListOf<Entry>()

    init { load() }

    private fun load() {
        val arr = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items += Entry(
                o.getLong("id"), o.getString("from"), o.getString("to"),
                o.getString("in"), o.getString("out"), o.getLong("t"), o.optBoolean("fav")
            )
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("from", it.from).put("to", it.to)
                .put("in", it.input).put("out", it.output).put("t", it.time).put("fav", it.fav))
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    /** Adds a translation; replaces the newest entry when the user just kept typing it. */
    fun add(from: String, to: String, input: String, output: String) {
        val inp = input.trim()
        if (inp.isEmpty() || output.isBlank()) return
        val now = System.currentTimeMillis()
        val last = items.firstOrNull()
        if (last != null && !last.fav && now - last.time < 90_000 && last.to == to &&
            (inp.startsWith(last.input) || last.input.startsWith(inp))
        ) {
            items[0] = last.copy(from = from, input = inp, output = output, time = now)
        } else {
            items.removeAll { it.input == inp && it.to == to && !it.fav }
            items.add(0, Entry(now, from, to, inp, output, now))
        }
        // keep favourites forever, at most 300 other entries
        var others = 0
        items.removeAll { !it.fav && ++others > 300 }
        save()
    }

    fun toggleFav(id: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) { items[i] = items[i].copy(fav = !items[i].fav); save() }
    }

    fun favOf(input: String, to: String): Entry? =
        items.firstOrNull { it.input == input.trim() && it.to == to }

    fun remove(id: Long) { items.removeAll { it.id == id }; save() }

    fun clearHistory() { items.removeAll { !it.fav }; save() }
}
