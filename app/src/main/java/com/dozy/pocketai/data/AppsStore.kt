package com.dozy.pocketai.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class BuiltApp(val id: String, val title: String, val file: File) {
    val updated get() = file.lastModified()
}

/** Apps made in Builder mode: one self-contained HTML file each. */
class AppsStore(context: Context) {
    private val dir = File(context.filesDir, "apps").apply { mkdirs() }
    private val _apps = MutableStateFlow<List<BuiltApp>>(emptyList())
    val apps: StateFlow<List<BuiltApp>> = _apps.asStateFlow()

    init { refresh() }

    private fun refresh() {
        _apps.value = dir.listFiles { f -> f.extension == "html" }
            ?.map { f -> BuiltApp(f.nameWithoutExtension, File(dir, f.nameWithoutExtension + ".title").takeIf { it.exists() }?.readText() ?: "Приложение", f) }
            ?.sortedByDescending { it.updated }
            ?: emptyList()
    }

    /** Saves (or overwrites, for the same chat) an app and returns it. */
    fun save(id: String, title: String, html: String): BuiltApp {
        File(dir, "$id.html").writeText(html)
        File(dir, "$id.title").writeText(title.take(60))
        refresh()
        return _apps.value.first { it.id == id }
    }

    fun delete(app: BuiltApp) {
        app.file.delete()
        File(dir, app.id + ".title").delete()
        refresh()
    }

    companion object {
        /** Pulls a runnable HTML document out of a model answer. */
        fun extractHtml(text: String): String? {
            val fence = Regex("```(?:html|htm)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE).findAll(text)
                .map { it.groupValues[1].trim() }
                .filter { it.contains("<html", true) || it.contains("<!doctype", true) || it.contains("<body", true) || it.contains("<div", true) }
                .maxByOrNull { it.length }
            if (fence != null) return fence
            val start = text.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 } ?: text.indexOf("<html", ignoreCase = true)
            if (start < 0) return null
            val end = text.lastIndexOf("</html>", ignoreCase = true)
            return if (end > start) text.substring(start, end + 7) else text.substring(start)
        }
    }
}
