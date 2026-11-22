package pro.translate.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Text-to-speech wrapper on the system TTS engine. */
class Speaker(context: Context) {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { ready = it == TextToSpeech.SUCCESS }

    /** Returns false if the language isn't available in the TTS engine. */
    fun speak(text: String, locale: Locale): Boolean {
        if (!ready || text.isBlank()) return false
        val r = tts.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tp")
        return true
    }

    fun stop() { if (ready) tts.stop() }
    fun shutdown() = tts.shutdown()
}
