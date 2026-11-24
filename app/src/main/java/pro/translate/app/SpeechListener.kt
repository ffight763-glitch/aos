package pro.translate.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Patient live speech-to-text. The system recogniser stops after every short pause, so this keeps
 * restarting it and glues the pieces together; the phrase is finished only when nobody has spoken
 * for [waitMs] (or [finishNow] is called). Words come in [onPartial] as they are said.
 *
 * [boost] (Android 13+): we record the mic ourselves with gain control and an extra software gain,
 * and hand that audio to the recogniser, so quiet speech is heard too. If the recogniser can't take
 * our audio, it falls back to its own mic automatically.
 */
class SpeechListener(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var recOnDevice = false
    var active = false
        private set

    /** Silence that ends the phrase. */
    var waitMs = 2500L
    /** Amplify quiet speech (Android 13+). */
    var boost = true
    /** Give up when nothing at all is said for this long. */
    var idleTimeoutMs = 20_000L

    // session state
    private var lang = "en"
    private var heard = ""          // finished pieces
    private var current = ""        // partial of the running piece
    private var lastVoice = 0L
    private var started = 0L
    private var onDevice = false
    private var onDeviceTried = false
    private var boostOn = false
    private var cb: Callbacks? = null

    private class Callbacks(
        val onReady: () -> Unit, val onLevel: (Float) -> Unit, val onPartial: (String) -> Unit,
        val onFinal: (String) -> Unit, val onError: (String) -> Unit,
    )

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx) ||
        (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))

    private val text get() = (heard + " " + current).trim()

    fun start(
        lang: String,
        onReady: () -> Unit,
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        this.lang = lang
        cb = Callbacks(onReady, onLevel, onPartial, onFinal, onError)
        heard = ""; current = ""; firstReady = true
        started = SystemClock.elapsedRealtime(); lastVoice = 0L
        onDevice = !SpeechRecognizer.isRecognitionAvailable(ctx)
        onDeviceTried = false
        boostOn = boost && Build.VERSION.SDK_INT >= 33
        active = true
        round()
        main.post(watchdog)
    }

    /** Ends now: whatever was heard is the phrase. */
    fun finishNow() {
        if (!active) return
        val t = text
        val c = cb
        stop()
        if (t.isNotBlank()) c?.onFinal?.invoke(t) else c?.onError?.invoke("Ничего не расслышал")
    }

    /** Forget what was heard so far and keep listening. */
    fun erase() {
        if (!active) return
        heard = ""; current = ""; lastVoice = 0L; started = SystemClock.elapsedRealtime()
        cb?.onPartial?.invoke("")
        restartRound()
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        stopAudio()
        rec?.cancel(); rec?.destroy(); rec = null
        cb = null
    }

    // Finishes the phrase after a real pause; gives up if nothing is said at all.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (text.isNotBlank() && lastVoice > 0 && now - lastVoice >= waitMs) { finishNow(); return }
            if (boostOn && !roundGotWords && loudFrames >= 50) { // ~2.5 s of voice and not a single word
                boostOn = false; rec?.destroy(); rec = null; restartRound(100)
            }
            if (text.isBlank() && now - started >= idleTimeoutMs) {
                val c = cb; stop(); c?.onError?.invoke("Ничего не расслышал"); return
            }
            main.postDelayed(this, 150)
        }
    }

    private fun heardSomething() { lastVoice = SystemClock.elapsedRealtime(); roundGotWords = true }

    // boost safety: if our mic hears a voice but the recogniser gives no words, it ignores our audio
    @Volatile private var loudFrames = 0
    private var roundGotWords = false

    private fun recognizer(): SpeechRecognizer {
        rec?.let { if (recOnDevice == onDevice) return it; it.destroy() }
        val r = if (onDevice && Build.VERSION.SDK_INT >= 33) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        recOnDevice = onDevice
        r.setRecognitionListener(listener)
        rec = r
        return r
    }

    private var firstReady = true

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { if (firstReady) { firstReady = false; cb?.onReady?.invoke() } }
        override fun onRmsChanged(v: Float) { if (!boostOn) cb?.onLevel?.invoke(((v + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onPartialResults(b: Bundle?) {
            val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (p.isNotBlank() && p != current) { current = p; heardSomething(); cb?.onPartial?.invoke(text) }
        }
        override fun onResults(b: Bundle?) {
            if (!active) return
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: current
            if (t.isNotBlank()) { heard = (heard + " " + t).trim(); heardSomething() }
            current = ""
            cb?.onPartial?.invoke(text)
            restartRound() // keep listening: the phrase ends only after a real pause
        }
        override fun onError(e: Int) {
            if (!active) return
            if (current.isNotBlank()) { heard = (heard + " " + current).trim(); current = "" }
            when (e) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartRound()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { rec?.destroy(); rec = null; restartRound(300) }
                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_AUDIO, 11 /* ERROR_SERVER_DISCONNECTED */ -> {
                    if (boostOn) { boostOn = false; rec?.destroy(); rec = null; restartRound(150) } // can't take our audio
                    else fail("Ошибка микрофона ($e)")
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, 12, 13 -> {
                    if (!onDevice && !onDeviceTried && Build.VERSION.SDK_INT >= 33 &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                        onDeviceTried = true; onDevice = true; restartRound(150) // no internet: on the device
                    } else fail(if (e == 12 || e == 13) "Этот язык недоступен для голоса" else "Нет интернета для распознавания речи")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Нет доступа к микрофону")
                else -> restartRound(300)
            }
        }
        override fun onBeginningOfSpeech() { heardSomething() }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    private fun fail(msg: String) {
        val t = text; val c = cb
        stop()
        if (t.isNotBlank()) c?.onFinal?.invoke(t) else c?.onError?.invoke(msg)
    }

    private fun restartRound(delay: Long = 0) {
        if (!active) return
        stopAudio()
        rec?.cancel()
        main.postDelayed({ if (active) round() }, delay)
    }

    private fun round() {
        if (!active) return
        loudFrames = 0; roundGotWords = false
        val tag = speechTag(lang)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, waitMs + 1500)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, waitMs + 1000)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10_000L)
        if (boostOn && Build.VERSION.SDK_INT >= 33) {
            val pfd = startAudio()
            if (pfd != null) {
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            } else boostOn = false
        }
        try { recognizer().startListening(i) } catch (e: Exception) { fail("Ошибка распознавания: ${e.message}") }
    }

    // ---- boosted microphone ----
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null
    private var readSide: ParcelFileDescriptor? = null

    @SuppressLint("MissingPermission")
    private fun startAudio(): ParcelFileDescriptor? = try {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5 * 2))
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); null } else {
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(ar.audioSessionId)?.enabled = true }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(ar.audioSessionId)?.enabled = true }
            val (r, w) = ParcelFileDescriptor.createPipe()
            readSide = r
            audioRunning = true
            ar.startRecording()
            audioThread = thread(name = "tp-mic") {
                val buf = ShortArray(RATE / 20) // 50 ms
                val bytes = ByteArray(buf.size * 2)
                var gain = 3f
                FileOutputStream(w.fileDescriptor).use { out ->
                    try {
                        while (audioRunning) {
                            val n = ar.read(buf, 0, buf.size)
                            if (n <= 0) continue
                            var peak = 0; var sum = 0.0
                            for (k in 0 until n) { val a = abs(buf[k].toInt()); if (a > peak) peak = a; sum += a.toDouble() * a }
                            // adaptive gain: lift quiet speech up to x6, never clip loud speech
                            val want = if (peak > 0) (22000f / peak).coerceIn(1f, 6f) else 6f
                            gain += (want - gain) * if (want < gain) 0.5f else 0.05f
                            for (k in 0 until n) {
                                val v = (buf[k] * gain).toInt().coerceIn(-32767, 32767)
                                bytes[2 * k] = (v and 0xff).toByte(); bytes[2 * k + 1] = (v shr 8 and 0xff).toByte()
                            }
                            out.write(bytes, 0, n * 2)
                            val rms = sqrt(sum / n) * gain
                            val lvl = if (rms > 1) ((20 * log10(rms / 32767.0) + 50) / 40).toFloat().coerceIn(0f, 1f) else 0f
                            if (lvl > 0.45f) loudFrames++
                            main.post { cb?.onLevel?.invoke(lvl) }
                        }
                    } catch (_: Exception) { // recogniser closed its end
                    } finally {
                        runCatching { ar.stop() }; ar.release(); runCatching { w.close() }
                    }
                }
            }
            r
        }
    } catch (_: Exception) { null }

    private fun stopAudio() {
        audioRunning = false
        audioThread = null
        readSide?.let { runCatching { it.close() } }; readSide = null
    }

    companion object {
        private const val RATE = 16000

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
