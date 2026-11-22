package com.dozy.pocketai.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/** A downloadable GGUF from HuggingFace. */
data class CatalogModel(
    val id: String,
    val title: String,
    val sizeLabel: String,
    val note: String,
    val url: String,
) {
    val fileName get() = url.substringAfterLast('/').substringBefore('?')
}

object Catalog {
    private const val HF = "https://huggingface.co"
    val models = listOf(
        CatalogModel(
            "qwen25-05b", "Qwen2.5 0.5B Instruct", "≈ 0.4 ГБ",
            "Самая быстрая. Для слабых телефонов и простых вопросов.",
            "$HF/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        ),
        CatalogModel(
            "qwen25-15b", "Qwen2.5 1.5B Instruct", "≈ 1.1 ГБ",
            "Лучший баланс скорость/качество, хорошо знает русский. Рекомендуется.",
            "$HF/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        ),
        CatalogModel(
            "qwen25-15b-abl", "Qwen2.5 1.5B без цензуры", "≈ 0.9 ГБ",
            "Abliterated-версия: без встроенных отказов модели. Только для себя.",
            "$HF/Triangle104/Qwen2.5-1.5B-Instruct-abliterated-Q4_K_S-GGUF/resolve/main/qwen2.5-1.5b-instruct-abliterated-q4_k_s.gguf",
        ),
        CatalogModel(
            "qwen25-coder-15b", "Qwen2.5 Coder 1.5B", "≈ 1.1 ГБ",
            "Для режима codProd: пишет и чинит код (Kotlin, Python, JS, C++…).",
            "$HF/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        ),
        CatalogModel(
            "llama32-1b", "Llama 3.2 1B Instruct", "≈ 0.8 ГБ",
            "Модель Meta, быстрая, лучше всего на английском.",
            "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        ),
        CatalogModel(
            "gemma2-2b", "Gemma 2 2B it", "≈ 1.7 ГБ",
            "Модель Google, умнее, но медленнее. Нужно 4+ ГБ ОЗУ.",
            "$HF/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
        ),
        CatalogModel(
            "qwen25-3b", "Qwen2.5 3B Instruct", "≈ 2.1 ГБ",
            "Самая умная из списка. Медленная, нужно 6+ ГБ ОЗУ.",
            "$HF/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
        ),
    )
}

data class LocalModel(val file: File) {
    val name get() = file.nameWithoutExtension
    val sizeMb get() = file.length() / (1024 * 1024)
}

data class DownloadState(val fileName: String, val progress: Float, val bytes: Long, val total: Long, val bytesPerSec: Long = 0)

class ModelRepository(private val context: Context, private val scope: CoroutineScope) {
    val dir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }
    private val prefs = context.getSharedPreferences("downloads2", Context.MODE_PRIVATE)
    private val downloader = FastDownloader(scope)

    private val _local = MutableStateFlow<List<LocalModel>>(emptyList())
    val local: StateFlow<List<LocalModel>> = _local.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    init {
        refresh()
        // resume downloads interrupted by a restart
        prefs.all.forEach { (name, url) -> if (url is String) download(url, name) }
    }

    fun clearError() { _errors.value = null }

    fun refresh() {
        _local.value = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { LocalModel(it) }
            ?: emptyList()
    }

    fun download(url: String, fileNameHint: String? = null) {
        val name = (fileNameHint ?: url.substringAfterLast('/').substringBefore('?'))
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        val dest = File(dir, name)
        if (downloader.isRunning(dest)) return
        prefs.edit().putString(name, url).apply()
        _downloads.update { it + (name to (it[name] ?: DownloadState(name, 0f, 0, 0))) }
        DownloadService.start(context)
        downloader.start(
            url, dest,
            onProgress = { pr ->
                _downloads.update {
                    it + (name to DownloadState(name, if (pr.total > 0) pr.done.toFloat() / pr.total else 0f, pr.done, pr.total, pr.bytesPerSec))
                }
            },
            onDone = { r ->
                prefs.edit().remove(name).apply()
                _downloads.update { it - name }
                r.onFailure { e -> _errors.value = "Загрузка $name не удалась: ${e.message}" }
                refresh()
            },
        )
    }

    fun cancel(fileName: String) {
        downloader.cancel(File(dir, fileName))
        prefs.edit().remove(fileName).apply()
        _downloads.update { it - fileName }
    }

    fun delete(model: LocalModel) {
        model.file.delete()
        refresh()
    }

    /** Copies a .gguf picked with the system file picker into the models folder. */
    suspend fun import(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            var name = "imported.gguf"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            if (!name.endsWith(".gguf", true)) error("Нужен файл модели .gguf")
            val out = File(dir, name)
            val tmp = File(dir, "$name.part")
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
            if (!tmp.renameTo(out)) error("Не удалось сохранить файл")
            refresh()
            out
        }
    }
}
