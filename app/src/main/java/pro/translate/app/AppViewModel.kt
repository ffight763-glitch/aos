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

    private fun retranslate(
        debounce: Long,
        srcOverride: String? = null,
        save: Boolean = true,
        onDone: ((String?) -> Unit)? = null,
    ) {
        job?.cancel()
        val text = input
        if (text.isBlank()) { output = ""; detected = null; busy = false; status = null; onDone?.invoke(null); return }
        val target = to
        job = viewModelScope.launch {
            // instant answer from the cache (a voice phrase usually was already translated as a partial)
            val src0 = srcOverride ?: from.takeIf { it != AUTO }
            if (src0 != null) engine.cached(text, src0, target)?.let { hit ->
                if (from == AUTO) detected = src0
                output = hit; status = null
                onDone?.invoke(hit)
                if (save) { if (onDone == null) delay(1500); history.add(src0, target, text, hit) }
                return@launch
            }
            delay(debounce)
            busy = true
            var res: String? = null
            try {
                val src = srcOverride?.also { if (from == AUTO) detected = it } ?: if (from == AUTO) {
                    engine.detect(text).also { detected = it } ?: run {
                        status = "Не удалось определить язык — выберите его вручную"
                        return@launch
                    }
                } else from
                val need = listOf(src, target).filterNot { engine.isDownloaded(it) }
                status = if (need.isNotEmpty())
                    "Скачиваю модель: ${need.joinToString { Languages.displayName(it) }} (один раз, ~30 МБ)…"
                else null
                res = engine.translate(text, src, target)
                output = res
                status = null
                busy = false
                onDone?.invoke(res)
                if (save) { if (onDone == null) delay(1500); history.add(src, target, text, res) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Ошибка: ${e.message ?: e.javaClass.simpleName}. Для первой загрузки модели нужен интернет."
                onDone?.invoke(null)
            } finally {
                busy = false
            }
        }
    }

    // ---- voice settings ----
    /** Pause (ms) after which a spoken phrase counts as finished. */
    var vWait by mutableStateOf(prefs.getLong("vwait", 2500L))
        private set
    fun setWait(ms: Long) { vWait = ms; prefs.edit().putLong("vwait", ms).apply() }
    /** Amplify quiet speech. */
    var vBoost by mutableStateOf(prefs.getBoolean("vboost", true))
        private set
    fun toggleBoost() { vBoost = !vBoost; prefs.edit().putBoolean("vboost", vBoost).apply() }
    fun configure(l: SpeechListener) { l.waitMs = vWait; l.boost = vBoost }

    // ---- live voice on the translate screen ----
    /** Keep listening phrase after phrase (like an interpreter) until the mic is tapped again. */
    var vContinuous by mutableStateOf(prefs.getBoolean("vcont", true))
        private set
    fun toggleContinuous() { vContinuous = !vContinuous; prefs.edit().putBoolean("vcont", vContinuous).apply() }

    /** Language the user speaks into the mic. */
    fun voiceLang(): String = if (from != AUTO) from else detected ?: run {
        val sys = java.util.Locale.getDefault().language
        if (Languages.all.any { it.code == sys } && sys != to) sys else if (to == "en") "ru" else "en"
    }

    /** Words as they are being said: translated on the fly, nothing saved or spoken yet. */
    fun voicePartial(text: String, lang: String) { input = text; retranslate(80, lang, save = false) }

    /** The phrase is complete: translate (usually already in the cache) and speak it at once. */
    fun voiceFinal(text: String, lang: String, after: () -> Unit) {
        input = text
        retranslate(0, lang, save = true) { res ->
            if (res.isNullOrBlank()) after() else speaker.speak(res, Languages.locale(to), after)
        }
    }

    /** Preloads the current pair so the first phrase isn't slow. */
    fun warmUp(a: String, b: String) = viewModelScope.launch { engine.warmUp(a, b) }

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
    /** Dialogue turns with the phone even when the system rotation lock is on. */
    var dRotate by mutableStateOf(prefs.getBoolean("drotate", true))
        private set
    fun toggleRotate() { dRotate = !dRotate; prefs.edit().putBoolean("drotate", dRotate).apply() }
    fun toggleAutoSpeak() { dAutoSpeak = !dAutoSpeak; prefs.edit().putBoolean("dspeak", dAutoSpeak).apply() }
    fun clearDialogue() = dMsgs.clear()

    /** Auto turns: after a phrase is spoken, the mic opens for the other person. */
    var dAutoTurn by mutableStateOf(prefs.getBoolean("dturn", false))
        private set
    fun toggleAutoTurn() { dAutoTurn = !dAutoTurn; prefs.edit().putBoolean("dturn", dAutoTurn).apply() }

    /** Live phrase being said right now and its on-the-fly translation (shown on the other half). */
    var dLiveMine by mutableStateOf<Boolean?>(null)
        private set
    var dLiveText by mutableStateOf("")
        private set
    var dLiveTr by mutableStateOf("")
        private set
    private var liveJob: Job? = null

    fun dPartial(mine: Boolean, text: String) {
        dLiveMine = mine; dLiveText = text
        val src = if (mine) dMe else dThem
        val dst = if (mine) dThem else dMe
        engine.cached(text, src, dst)?.let { dLiveTr = it; return }
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            delay(60)
            runCatching { engine.translate(text, src, dst) }.getOrNull()?.let { if (dLiveText == text) dLiveTr = it }
        }
    }

    fun dClearLive() { liveJob?.cancel(); dLiveMine = null; dLiveText = ""; dLiveTr = "" }

    /** Adds a phrase, translates it and speaks it; [after] runs once it has been said (or failed). */
    fun dSend(mine: Boolean, text: String, after: (() -> Unit)? = null) {
        val t = text.trim(); if (t.isEmpty()) { after?.invoke(); return }
        dClearLive()
        val id = System.nanoTime()
        val src = if (mine) dMe else dThem
        val dst = if (mine) dThem else dMe
        val hit = engine.cached(t, src, dst)
        dMsgs += DMsg(id, mine, t, hit)
        if (hit != null) {
            if (dAutoSpeak) speaker.speak(hit, Languages.locale(dst), after) else after?.invoke()
            history.add(src, dst, t, hit)
            return
        }
        viewModelScope.launch {
            val res = try { engine.translate(t, src, dst) } catch (e: Exception) { null }
            val i = dMsgs.indexOfFirst { it.id == id }
            if (i >= 0) dMsgs[i] = dMsgs[i].copy(translated = res, failed = res == null)
            if (res != null) {
                history.add(src, dst, t, res)
                if (dAutoSpeak) speaker.speak(res, Languages.locale(dst), after) else after?.invoke()
            } else after?.invoke()
        }
    }

    fun dDeleteLast() { if (dMsgs.isNotEmpty()) dMsgs.removeAt(dMsgs.lastIndex) }
    fun dDelete(id: Long) { dMsgs.removeAll { it.id == id } }

    fun dRetry(m: DMsg) { dMsgs.removeAll { it.id == m.id }; dSend(m.mine, m.original) }

    override fun onCleared() {
        engine.close(); speaker.shutdown(); ocr.close()
    }
}
