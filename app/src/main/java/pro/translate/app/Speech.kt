package pro.translate.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text-to-speech on the system engine; [speak] can report when the phrase has been said. */
class Speaker(context: Context) {
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, () -> Unit>()
    private var n = 0
    private var lastLocale: Locale? = null
    var rate = 1.1f
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { st ->
            ready = st == TextToSpeech.SUCCESS
            if (ready) tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done(id)
                @Deprecated("old API") override fun onError(id: String?) = done(id)
                override fun onError(id: String?, code: Int) = done(id)
                override fun onStop(id: String?, interrupted: Boolean) = done(id)
            })
        }
    }

    private fun done(id: String?) {
        val cb = synchronized(pending) { pending.remove(id) } ?: return
        main.post(cb)
    }

    /** Returns false if it can't speak (then [onDone] is called right away). */
    fun speak(text: String, locale: Locale, onDone: (() -> Unit)? = null): Boolean {
        if (!ready || text.isBlank()) { onDone?.let { main.post(it) }; return false }
        if (locale != lastLocale) {
            val r = tts.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                onDone?.let { main.post(it) }; return false
            }
            lastLocale = locale
        }
        tts.setSpeechRate(rate)
        val id = "tp${n++}"
        if (onDone != null) synchronized(pending) { pending[id] = onDone }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        return true
    }

    val speaking: Boolean get() = ready && tts.isSpeaking

    /** Stops speech; pending callbacks are dropped (nobody waits for them). */
    fun stop() { synchronized(pending) { pending.clear() }; if (ready) tts.stop() }
    fun shutdown() = tts.shutdown()
}
