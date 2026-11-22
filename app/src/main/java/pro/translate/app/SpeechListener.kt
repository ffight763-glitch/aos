package pro.translate.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Live speech-to-text without the system dialog: partial words come in [onPartial],
 * the final phrase in [onFinal]. Uses the normal (online, better) recogniser and
 * falls back to the on-device one (Android 13+) when there is no network.
 */
class SpeechListener(private val ctx: Context) {
    private var rec: SpeechRecognizer? = null
    private var onDeviceTried = false
    var active = false
        private set

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx) ||
        (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))

    fun start(
        lang: String,
        onReady: () -> Unit,
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        onDeviceTried = false
        begin(lang, onDevice = !SpeechRecognizer.isRecognitionAvailable(ctx), onReady, onLevel, onPartial, onFinal, onError)
    }

    private fun begin(
        lang: String, onDevice: Boolean,
        onReady: () -> Unit, onLevel: (Float) -> Unit, onPartial: (String) -> Unit,
        onFinal: (String) -> Unit, onError: (String) -> Unit,
    ) {
        val r = if (onDevice && Build.VERSION.SDK_INT >= 33) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r; active = true
        var lastPartial = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = onReady()
            override fun onRmsChanged(v: Float) = onLevel(((v + 2f) / 12f).coerceIn(0f, 1f))
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { lastPartial = it; onPartial(it) }
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() } ?: lastPartial
                finish()
                if (t.isNotBlank()) onFinal(t) else onError("Ничего не расслышал")
            }
            override fun onError(e: Int) {
                if (!active) return
                val net = e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    e == 13 /* ERROR_LANGUAGE_UNAVAILABLE */ || e == 12 /* ERROR_LANGUAGE_NOT_SUPPORTED */
                if (net && !onDevice && !onDeviceTried && Build.VERSION.SDK_INT >= 33 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
                ) { // no internet: retry on the device
                    onDeviceTried = true; finish()
                    begin(lang, true, onReady, onLevel, onPartial, onFinal, onError); return
                }
                finish()
                if (lastPartial.isNotBlank()) { onFinal(lastPartial); return }
                onError(when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ничего не расслышал"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет интернета для распознавания речи"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, попробуйте ещё раз"
                    12, 13 -> "Этот язык недоступен для голоса"
                    else -> "Ошибка распознавания ($e)"
                })
            }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val tag = speechTag(lang)
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L))
    }

    /** Ends listening now; whatever was heard comes as a final result. */
    fun finishNow() { rec?.stopListening() }

    private fun finish() { active = false; rec?.destroy(); rec = null }

    fun stop() { active = false; rec?.cancel(); rec?.destroy(); rec = null }

    companion object {
        private val regions = mapOf("ru" to "RU", "uk" to "UA", "en" to "US", "de" to "DE", "pl" to "PL", "fr" to "FR",
            "es" to "ES", "it" to "IT", "pt" to "BR", "tr" to "TR", "zh" to "CN", "ja" to "JP", "ko" to "KR", "ar" to "SA",
            "hi" to "IN", "be" to "BY", "cs" to "CZ", "nl" to "NL", "ro" to "RO", "he" to "IL", "el" to "GR", "sv" to "SE",
            "hu" to "HU", "bg" to "BG", "ka" to "GE", "lt" to "LT", "lv" to "LV", "et" to "EE", "fi" to "FI", "da" to "DK",
            "no" to "NO", "sk" to "SK", "hr" to "HR", "vi" to "VN", "th" to "TH", "id" to "ID", "fa" to "IR")

        fun speechTag(lang: String): String = when (lang) {
            "he" -> "he-IL"
            "no" -> "nb-NO"
            else -> regions[lang]?.let { "$lang-$it" } ?: lang
        }
    }
}
