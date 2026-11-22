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

    // ---- dialogue ----
    data class DMsg(val id: Long, val mine: Boolean, val original: String, val translated: String?, val failed: Boolean = false)

    private fun defaultMe(): String {
        val sys = java.util.Locale.getDefault().language
        return if (Languages.all.any { it.code == sys }) sys else TranslateLanguage.RUSSIAN
    }
    var dMe by mutableStateOf(prefs.getString("dme", null) ?: defaultMe())
        private set
    var dThem by mutableStateOf(prefs.getString("dthem", null) ?: if (defaultMe() == "en") "de" else "en")
        private set
    /** Manual layout override: null = follow the phone's rotation (portrait = top/bottom, landscape = left/right). */
    var dManual by mutableStateOf<String?>(prefs.getString("dmanual", null))
        private set
    /** "Turn the other person's half to face them", kept separately for each layout. */
    var dFlipV by mutableStateOf(prefs.getBoolean("dflipv", true))
        private set
    var dFlipH by mutableStateOf(prefs.getBoolean("dfliph", false))
        private set
    var dAutoSpeak by mutableStateOf(prefs.getBoolean("dspeak", true))
        private set
    val dMsgs = androidx.compose.runtime.mutableStateListOf<DMsg>()

    fun setDLang(mine: Boolean, code: String) {
        if (mine) dMe = code else dThem = code
        prefs.edit().putString("dme", dMe).putString("dthem", dThem).apply()
    }
    fun swapDLangs() { val a = dMe; dMe = dThem; dThem = a; prefs.edit().putString("dme", dMe).putString("dthem", dThem).apply() }
    /** Button: switch top/bottom <-> left/right by hand, from whatever is shown now. */
    fun toggleSplit(current: String) {
        dManual = if (current == "vertical") "horizontal" else "vertical"
        prefs.edit().putString("dmanual", dManual).apply()
    }
    /** The phone was turned: go back to following the rotation. */
    fun resetSplitToAuto() { if (dManual != null) { dManual = null; prefs.edit().remove("dmanual").apply() } }
    fun toggleFlip(vertical: Boolean) {
        if (vertical) dFlipV = !dFlipV else dFlipH = !dFlipH
        prefs.edit().putBoolean("dflipv", dFlipV).putBoolean("dfliph", dFlipH).apply()
    }
    fun toggleAutoSpeak() { dAutoSpeak = !dAutoSpeak; prefs.edit().putBoolean("dspeak", dAutoSpeak).apply() }
    fun clearDialogue() = dMsgs.clear()

    fun dSend(mine: Boolean, text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val id = System.nanoTime()
        val src = if (mine) dMe else dThem
        val dst = if (mine) dThem else dMe
        dMsgs += DMsg(id, mine, t, null)
        viewModelScope.launch {
            val res = try { engine.translate(t, src, dst) } catch (e: Exception) { null }
            val i = dMsgs.indexOfFirst { it.id == id }
            if (i >= 0) dMsgs[i] = dMsgs[i].copy(translated = res, failed = res == null)
            if (res != null) {
                history.add(src, dst, t, res)
                if (dAutoSpeak) speaker.speak(res, Languages.locale(dst))
            }
        }
    }

    fun dRetry(m: DMsg) { dMsgs.removeAll { it.id == m.id }; dSend(m.mine, m.original) }

    override fun onCleared() {
        engine.close(); speaker.shutdown(); ocr.close()
    }
}
