package pro.translate.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val engine = TranslatorEngine()
    val history = HistoryStore(app)
    val speaker = Speaker(app)
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ---- translate screen ----
    var from by mutableStateOf(prefs.getString("from", AUTO)!!)
        private set
    var to by mutableStateOf(prefs.getString("to", TranslateLanguage.RUSSIAN)!!)
        private set
    var detected by mutableStateOf<String?>(null)
        private set
    var input by mutableStateOf("")
        private set
    var output by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    fun setFromLang(code: String) { from = code; prefs.edit().putString("from", code).apply(); retranslate(0) }
    fun setToLang(code: String) { to = code; prefs.edit().putString("to", code).apply(); retranslate(0) }

    fun swap() {
        val src = if (from == AUTO) detected ?: return else from
        val newInput = output
        from = to; to = src
        prefs.edit().putString("from", from).putString("to", to).apply()
        input = newInput
        retranslate(0)
    }

    fun updateInput(text: String) { input = text; retranslate(600) }

    fun clear() { job?.cancel(); input = ""; output = ""; detected = null; status = null; busy = false }

    fun load(e: Entry) {
        from = e.from; to = e.to; input = e.input; output = e.output; detected = null; status = null
        prefs.edit().putString("from", from).putString("to", to).apply()
    }

    private fun retranslate(debounce: Long) {
        job?.cancel()
        val text = input
        if (text.isBlank()) { output = ""; detected = null; busy = false; status = null; return }
        job = viewModelScope.launch {
            delay(debounce)
            busy = true
            try {
                val src = if (from == AUTO) {
                    engine.detect(text).also { detected = it } ?: run {
                        status = "Не удалось определить язык — выберите его вручную"
                        return@launch
                    }
                } else from
                val need = listOf(src, to).filterNot { engine.isDownloaded(it) }
                status = if (need.isNotEmpty())
                    "Скачиваю модель: ${need.joinToString { Languages.displayName(it) }} (один раз, ~30 МБ)…"
                else null
                val res = engine.translate(text, src, to)
                output = res
                status = null
                delay(1500)
                history.add(src, to, text, res)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Ошибка: ${e.message ?: e.javaClass.simpleName}. Для первой загрузки модели нужен интернет."
            } finally {
                busy = false
            }
        }
    }

    // ---- camera screen ----
    var photo by mutableStateOf<Bitmap?>(null)
        private set
    var photoText by mutableStateOf("")
        private set
    var photoTranslation by mutableStateOf("")
        private set
    var photoBusy by mutableStateOf(false)
        private set
    var photoStatus by mutableStateOf<String?>(null)
        private set

    fun recognize(bitmap: Bitmap) {
        photo = bitmap; photoText = ""; photoTranslation = ""; photoStatus = "Распознаю текст…"
        viewModelScope.launch {
            photoBusy = true
            try {
                val text = ocr.process(InputImage.fromBitmap(bitmap, 0)).await()
                    .textBlocks.joinToString("\n\n") { it.text }
                photoText = text
                if (text.isBlank()) { photoStatus = "Текст не найден"; return@launch }
                val src = engine.detect(text) ?: TranslateLanguage.ENGLISH
                if (!engine.isDownloaded(src) || !engine.isDownloaded(to)) photoStatus = "Скачиваю модель…"
                else photoStatus = "Перевожу…"
                photoTranslation = engine.translate(text, src, to)
                photoStatus = "${Languages.displayName(src)} → ${Languages.displayName(to)}"
                history.add(src, to, text, photoTranslation)
            } catch (e: Exception) {
                photoStatus = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                photoBusy = false
            }
        }
    }

    fun sendPhotoToTranslator() { from = AUTO; updateInput(photoText) }

    // ---- models screen ----
    var downloaded by mutableStateOf<Set<String>>(emptySet())
        private set
    var downloading by mutableStateOf<Set<String>>(emptySet())
        private set

    fun refreshModels() = viewModelScope.launch { downloaded = engine.downloaded() }

    fun downloadModel(code: String) = viewModelScope.launch {
        downloading = downloading + code
        try { engine.download(code) } catch (_: Exception) { }
        downloading = downloading - code
        downloaded = engine.downloaded()
    }

    fun deleteModel(code: String) = viewModelScope.launch {
        runCatching { engine.delete(code) }
        downloaded = engine.downloaded()
    }

    override fun onCleared() {
        engine.close(); speaker.shutdown(); ocr.close()
    }
}
