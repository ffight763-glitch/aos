package org.anthezis.launcher

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.common.model.DownloadConditions
import java.util.Locale

/**
 * AOS Translate: a translator in the AOS style. Two modes: Text (type, translate, listen, copy) and Conversation, where the
 * screen is split in two halves: the upper half faces the other person (upside down), the lower one is you. Each person has
 * a language, types or speaks, and the translation appears on the other side and is read aloud.
 * Translation runs on the phone with ML Kit (language packs download once, then it works offline).
 */
class TranslatorActivity : Activity() {
    private lateinit var panel: TranslatorPanel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panel = TranslatorPanel(this)
        setContentView(panel.createView(false, intent?.getIntExtra("mode", 0) ?: 0))
        addAosTopBar(Palette(this))
    }
    override fun onStop() { panel.pause(); super.onStop() }
    override fun onDestroy() { panel.close(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        panel.onPermissionsResult(requestCode, grantResults)
    }
}

/** The same translator can be used directly in the left home panel or as a standalone app. */
class TranslatorPanel(private val host: Activity) : android.content.ContextWrapper(host) {
    private lateinit var p: Palette
    private lateinit var body: FrameLayout
    private lateinit var tabs: LinearLayout
    private var mode = 0
    private var tts: TextToSpeech? = null
    private val translators = HashMap<String, Translator>()

    // Every language ML Kit can translate (about 59), the common ones first.
    private val popular = listOf("en", "uk", "ru", "de", "fr", "es", "it", "pl", "pt", "tr", "ar", "zh", "ja", "ko")
    private val codes: List<String> by lazy {
        val all = TranslateLanguage.getAllLanguages()
        popular.filter { it in all } + all.filter { it !in popular }.sortedBy { Locale(it).getDisplayLanguage(Locale.ENGLISH) }
    }
    private fun nameOf(code: String) = Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
    private var langA = "en"      // you (lower half) / the source in Text mode
    private var langB = "uk"      // the other person (upper half) / the target in Text mode

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    private var closed = false
    fun createView(embedded: Boolean, initialMode: Int = 0): View {
        p = Palette(this)
        langA = if (Locale.getDefault().language in codes) Locale.getDefault().language else "en"
        langB = if (langA == "en") "uk" else "en"
        tts = TextToSpeech(this) { }
        body = FrameLayout(this)
        tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (embedded) 0 else dp(16), if (embedded) 0 else dp(44), if (embedded) 0 else dp(16), if (embedded) dp(8) else dp(24))
            if (!embedded) setBackgroundColor(p.glassScreen)
            addView(tabs, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8); bottomMargin = dp(10) })
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        show(initialMode.coerceIn(0, 2))
        // Voice waves over the bottom of the translator while the user speaks; they let every touch through.
        waves = VoiceWaves(this, p)
        return FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -1))
            addView(waves, FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM))
        }
    }

    private var waves: VoiceWaves? = null

    fun pause() {
        if (::p.isInitialized) stopMicAnim()
        recActive = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        tts?.stop()
    }
    fun close() { closed = true; pause(); tts?.shutdown(); translators.values.forEach { it.close() }; translators.clear() }
    private fun runOnUiThread(action: () -> Unit) { host.runOnUiThread { if (!closed && !host.isDestroyed) action() } }

    private fun show(m: Int) {
        pause()
        mode = m
        tabs.removeAllViews()
        for ((i, name) in listOf(0 to "Text", 1 to "Dialogue", 3 to "Sections", 2 to "Languages")) tabs.addView(text(name, 14f, if (i == m) p.onAccent else p.text, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            background = if (i == m) shape(p.accent, 22) else glassShape(p, 22, true)
            setOnClickListener { show(i) }
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        body.removeAllViews()
        body.addView(when (m) { 0 -> textMode(); 1 -> conversationMode(false); 3 -> conversationMode(true); else -> languagesMode() })
    }

    // ---- translation ----

    private fun translate(src: String, from: String, to: String, done: (String?) -> Unit) {
        if (src.isBlank()) { done(""); return }
        if (from == to) { done(src); return }
        val f = TranslateLanguage.fromLanguageTag(from); val t = TranslateLanguage.fromLanguageTag(to)
        if (f == null || t == null) { done(null); return }
        val tr = translators.getOrPut("$f>$t") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(f).setTargetLanguage(t).build())
        }
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { if (!closed) tr.translate(src).addOnSuccessListener { if (!closed) done(it) }.addOnFailureListener { if (!closed) done(null) } }
            .addOnFailureListener { done(null) }
    }

    private fun speak(textToSay: String, lang: String) {
        val t = tts ?: return
        t.language = Locale(lang)
        t.speak(textToSay, TextToSpeech.QUEUE_FLUSH, null, "aos")
    }

    private fun pickLanguage(current: String, onPick: (String) -> Unit) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (c in codes) list.addView(text(nameOf(c), 16f, if (c == current) p.onAccent else p.text, c == current).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = if (c == current) shape(p.accent, 16) else null
            setOnClickListener { dlg.dismiss(); onPick(c) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        dlg.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(p.surface, 24, p.stroke); setPadding(dp(10), dp(14), dp(10), dp(10))
            addView(text("Language", 18f, p.text, true).apply { setPadding(dp(10), 0, 0, dp(8)) })
            addView(ScrollView(this@TranslatorPanel).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout((resources.displayMetrics.widthPixels * 0.82f).toInt(), (resources.displayMetrics.heightPixels * 0.7f).toInt())
            setDimAmount(0.5f)
        }
        dlg.show()
    }

    private fun langChip(code: () -> String, onChange: (String) -> Unit): TextView {
        lateinit var chip: TextView
        chip = text(nameOf(code()), 14f, p.text, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0); background = glassShape(p, 20, true)
            setOnClickListener { pickLanguage(code()) { c -> onChange(c); chip.text = nameOf(c) } }
        }
        return chip
    }

    // ---- speech input ----

    private var onHeard: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var recActive = false

    // ---- listening animation: rings spread out of the mic while it records, a stronger pulse when words arrive ----
    private var activeMic: View? = null
    private var micAnim: android.animation.ValueAnimator? = null
    /** The wide button beside the mic while it records: red, "■ Stop  0:07", a tap ends the recording. */
    private var stopBar: TextView? = null
    private var stopBarSaved: Triple<CharSequence, android.graphics.drawable.Drawable?, View.OnClickListener?>? = null
    private var recStarted = 0L
    private val recTick = object : Runnable {
        override fun run() {
            val bar = stopBar ?: return
            val sec = (android.os.SystemClock.elapsedRealtime() - recStarted) / 1000
            bar.text = String.format("■  Stop   %d:%02d", sec / 60, sec % 60)
            handler.postDelayed(this, 500)
        }
    }
    private fun startMicAnim(v: View) {
        stopMicAnim(); activeMic = v; waves?.show()
        (v.parent as? android.view.ViewGroup)?.getChildAt(0)?.let { it as? TextView }?.takeIf { it !== v }?.let { bar ->
            stopBarSaved = Triple(bar.text, bar.background, null)
            stopBar = bar
            bar.background = shape(0xFFE5484D.toInt(), 22); bar.setTextColor(0xFFFFFFFF.toInt())
            bar.setOnClickListener { v.performClick() }   // same as tapping the mic again: stop and translate
            recStarted = android.os.SystemClock.elapsedRealtime(); recTick.run()
        }
        v.background = circle(p.accent); (v as? TextView)?.apply { setTextColor(p.onAccent); text = "■" }   // stop
        val rings = List(3) { android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(2), p.accent) } }
        rings.forEach { v.overlay.add(it) }
        (v.parent as? android.view.ViewGroup)?.clipChildren = false
        micAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val t0 = a.animatedValue as Float
                rings.forEachIndexed { i, ring ->
                    val t = (t0 + i / 3f) % 1f
                    val grow = (v.width * 0.45f * t).toInt()
                    ring.setBounds(-grow, -grow, v.width + grow, v.height + grow); ring.alpha = ((1f - t) * 200).toInt()
                }
            }
            start()
        }
    }
    /** A word was heard: a quick bounce of the mic. */
    private fun bumpMic() { activeMic?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(90)?.withEndAction { activeMic?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start() }?.start() }
    private fun stopMicAnim() {
        waves?.hide()
        handler.removeCallbacks(recTick)
        stopBar?.let { bar -> stopBarSaved?.let { (t, bg, _) -> bar.text = t; bar.background = bg; bar.setTextColor(p.onAccent) }; bar.setOnClickListener { (bar.tag as? Runnable)?.run() } }
        stopBar = null; stopBarSaved = null
        micAnim?.cancel(); micAnim = null
        activeMic?.let { v -> v.overlay.clear(); v.background = circle(p.glassCircle); (v as? TextView)?.apply { setTextColor(p.text); text = "🎤" }; v.scaleX = 1f; v.scaleY = 1f }
        activeMic = null
    }
    private var heard = ""

    /** Language code of the translator (ru, uk, en, de...) as the BCP-47 tag speech recognisers expect (ru-RU, uk-UA...). */
    private fun speechTag(lang: String): String {
        if (lang.contains('-')) return lang
        val region = mapOf("ru" to "RU", "uk" to "UA", "en" to "US", "de" to "DE", "pl" to "PL", "fr" to "FR", "es" to "ES", "it" to "IT",
            "pt" to "BR", "tr" to "TR", "zh" to "CN", "ja" to "JP", "ko" to "KR", "ar" to "SA", "hi" to "IN", "kk" to "KZ", "be" to "BY",
            "cs" to "CZ", "nl" to "NL", "ro" to "RO", "he" to "IL", "el" to "GR", "sv" to "SE", "hu" to "HU", "bg" to "BG", "ka" to "GE")[lang]
        return if (region != null) "$lang-$region" else java.util.Locale(lang).toLanguageTag()
    }

    /**
     * Voice to text that keeps up with fast speech: it listens in rounds without a break, shows the words as they come, and
     * joins everything into one text. When you stop talking (or tap the mic again) the full text is handed over in [done],
     * and only then is it translated. Prefers the phone's own on-device recogniser, then the system one.
     */
    /** The mic button that started this listen (set by the click handlers), so it can animate. */
    private var pendingMic: View? = null

    /** [continuous] = keep listening sentence after sentence (Text mode); false = the first pause ends it (Dialogue). */
    private fun listen(lang: String, partial: ((String) -> Unit)? = null, continuous: Boolean = true, done: (String) -> Unit) {
        if (recActive) {   // a second tap = that was all: hand over what was heard
            stopMicAnim()
            recActive = false; recognizer?.destroy(); recognizer = null
            val text = heard.trim(); heard = ""
            if (text.isNotEmpty()) done(text)
            return
        }
        val online = runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
        // Online recognition hears better (bigger models, punctuation); on-device only when there is no internet.
        var onDevice = !(online && SpeechRecognizer.isRecognitionAvailable(this)) &&
            android.os.Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        var retries = 0
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "No speech recognition on this phone: type instead (install the Google app to enable the voice)", Toast.LENGTH_LONG).show(); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5); return
        }
        heard = ""; recActive = true
        // The rings and the timer start only when the recogniser is really listening (onReadyForSpeech): until then the
        // mic shows "…", so the first words are not spoken into the void.
        val micView = pendingMic; pendingMic = null
        (micView as? TextView)?.text = "…"
        var ready = false
        fun finish() {
            stopMicAnim()
            recActive = false; recognizer?.destroy(); recognizer = null
            val text = heard.trim(); heard = ""
            if (text.isNotEmpty()) done(text)
        }
        // One recognizer for the whole recording, reused between sentences (making a new one each time is what the
        // recogniser answers with error 10, "too many requests").
        lateinit var round: () -> Unit
        fun newRecognizer(): SpeechRecognizer {
            recognizer?.destroy()
            val rec = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
            recognizer = rec
            rec.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(b: Bundle?) {
                    if (!recActive || recognizer !== rec) return
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard = (heard + " " + it).trim(); partial?.invoke(heard) }
                    retries = 0
                    // Dialogue: the speaker paused, so the phrase is over: translate, show and say it to the other person now.
                    if (!continuous && heard.isNotBlank()) { finish(); return }
                    handler.postDelayed({ round() }, 150)   // the next sentence, with the same recognizer
                }
                override fun onPartialResults(b: Bundle?) {
                    if (!recActive || recognizer !== rec) return
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { bumpMic(); partial?.invoke((heard + " " + it).trim()) }
                }
                override fun onError(e: Int) {
                    if (!recActive || recognizer !== rec) return
                    when (e) {
                        // Too many requests / the recogniser went away: wait and try again; after three tries use the
                        // phone's regular recogniser instead of the on-device one.
                        10, 11, 13, 14 -> if (retries < 3) { retries++; handler.postDelayed({ round() }, 700) }
                            else if (onDevice) { onDevice = false; retries = 0; newRecognizer(); handler.postDelayed({ round() }, 300) }
                            else { Toast.makeText(this@TranslatorPanel, "The voice recogniser is busy, try again in a moment", Toast.LENGTH_SHORT).show(); if (heard.isNotBlank()) finish() else { recActive = false; stopMicAnim(); rec.destroy(); recognizer = null } }
                        // Silence after you spoke: the sentence is over, hand it over. Silence at the start: nothing to do.
                        // Silence never ends the recording: it keeps waiting for speech until you tap Stop. Only in Dialogue a
                        // pause after a phrase hands the phrase over (that is the end of a turn).
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            if (!continuous && heard.isNotBlank()) finish() else handler.postDelayed({ round() }, 120)
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (recActive) { rec.cancel(); handler.postDelayed({ round() }, 400) }
                        else -> {
                            val why = when (e) {
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No internet for the voice recogniser"
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This language is not available for voice"
                                else -> "Voice error $e"
                            }
                            Toast.makeText(this@TranslatorPanel, why, Toast.LENGTH_SHORT).show()
                            if (heard.isNotBlank()) finish() else { recActive = false; stopMicAnim(); rec.destroy(); recognizer = null }
                        }
                    }
                }
                override fun onReadyForSpeech(p: Bundle?) {
                    if (!ready && recActive && recognizer === rec) {
                        ready = true
                        micView?.let { startMicAnim(it); it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
                    }
                }
                override fun onBeginningOfSpeech() {}; override fun onRmsChanged(r: Float) { waves?.level(r) }; override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}; override fun onEvent(t: Int, b: Bundle?) {}
            })
            return rec
        }
        round = round@{
            if (!recActive) return@round
            val rec = recognizer ?: newRecognizer()
            rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // A full tag (ru-RU, not "ru"): with a bare code the online recogniser falls back to English and writes
                // "Голливуд" as "barrywood".
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag(lang))
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechTag(lang))
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Words exactly as spoken: no "f***" masking of swear words by the recogniser.
                .putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
                // Room to breathe between words: a short pause does not cut the phrase.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2600L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L))
        }
        newRecognizer()
        round()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun onPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == 5 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) Toast.makeText(this, "Tap the microphone again", Toast.LENGTH_SHORT).show()
    }


    // ---- Language library: the packs behind the offline translation ----

    private fun languagesMode(): View {
        val manager = RemoteModelManager.getInstance()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = text("Language library", 20f, p.text, true).apply { setPadding(dp(4), 0, 0, dp(2)) }
        val sub = text("Each language is a pack of about 30 MB. Download it once and translate without internet. English is built in.", 12f, p.sub).apply { setPadding(dp(4), 0, dp(4), dp(10)) }
        lateinit var refresh: () -> Unit
        fun fill(have: Set<String>) {
            list.removeAllViews()
            for (code in codes) {
                val builtIn = code == TranslateLanguage.ENGLISH
                val has = builtIn || code in have
                list.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 18); setPadding(dp(16), dp(10), dp(12), dp(10))
                    addView(LinearLayout(this@TranslatorPanel).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(nameOf(code), 16f, p.text, true))
                        addView(text(if (builtIn) "Built in" else if (has) "Downloaded · works offline" else "Not downloaded", 12f, if (has) p.accent else p.sub))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (!builtIn) addView(text(if (has) "Remove" else "Download", 13f, if (has) p.text else p.onAccent, true).apply {
                        gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
                        background = if (has) glassShape(p, 18, true) else shape(p.accent, 18)
                        setOnClickListener {
                            val model = TranslateRemoteModel.Builder(code).build()
                            text = if (has) "…" else "Downloading…"
                            if (has) manager.deleteDownloadedModel(model).addOnCompleteListener { refresh() }
                            else manager.download(model, DownloadConditions.Builder().build()).addOnCompleteListener { task ->
                                if (!task.isSuccessful) Toast.makeText(this@TranslatorPanel, "Download failed: check the internet", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                        }
                    }, LinearLayout.LayoutParams(-2, dp(38)))
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        refresh = {
            manager.getDownloadedModels(TranslateRemoteModel::class.java).addOnSuccessListener { models -> fill(models.map { it.language }.toSet()) }
                .addOnFailureListener { fill(emptySet()) }
        }
        fill(emptySet()); refresh()
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(this@TranslatorPanel).apply { orientation = LinearLayout.VERTICAL; addView(head); addView(sub); addView(list) })
        }
    }
    // ---- Text mode ----

    private fun textMode(): View {
        val input = EditText(this).apply {
            hint = "Type or paste text"; setHintTextColor(p.sub); setTextColor(p.text); textSize = 18f; background = null
            gravity = Gravity.TOP or Gravity.START; setPadding(dp(16), dp(14), dp(16), dp(14)); minLines = 4
        }
        val result = text("", 18f, p.text).apply { setPadding(dp(16), dp(14), dp(16), dp(14)); setTextIsSelectable(true) }
        val status = text("", 12f, p.sub).apply { setPadding(dp(4), dp(4), 0, 0) }
        fun run() {
            status.text = "Translating… (a language pack downloads the first time)"
            translate(input.text.toString(), langA, langB) { r ->
                runOnUiThread { result.text = r ?: ""; status.text = if (r == null) "Could not translate: check the internet for the first download" else "" }
            }
        }
        val a = langChip({ langA }) { langA = it }
        val b = langChip({ langB }) { langB = it }
        val swap = text("⇄", 20f, p.text, true).apply {
            gravity = Gravity.CENTER; background = circle(p.glassCircle)
            setOnClickListener { val t = langA; langA = langB; langB = t; show(0) }
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(this@TranslatorPanel).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@TranslatorPanel).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(a, LinearLayout.LayoutParams(0, dp(42), 1f))
                    addView(swap, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8); marginEnd = dp(8) })
                    addView(b, LinearLayout.LayoutParams(0, dp(42), 1f))
                })
                addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                input.background = glassShape(p, 22)
                addView(LinearLayout(this@TranslatorPanel).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text("Translate", 15f, p.onAccent, true).apply {
                        gravity = Gravity.CENTER; background = shape(p.accent, 22); setOnClickListener { run() }; pressFx(p, round = false)
                        tag = Runnable { run() }   // its own action, given back after the recording borrowed the button
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                    addView(text("🎤", 20f, p.text).apply {
                        gravity = Gravity.CENTER; background = circle(p.glassCircle)
                        // You speak: the words appear in the field as you go, and the translation follows when you stop.
                        // The translation follows the words as you speak (a short pause after the last word), not only at the end.
                        val live = Runnable { run() }
                        setOnClickListener { pendingMic = this; listen(langA, partial = { s -> runOnUiThread { input.setText(s); handler.removeCallbacks(live); handler.postDelayed(live, 700) } }) { s -> runOnUiThread { handler.removeCallbacks(live); input.setText(s); run() } } }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
                    addView(text("🔊", 20f, p.text).apply {
                        gravity = Gravity.CENTER; background = circle(p.glassCircle); contentDescription = "Listen to the typed text"
                        setOnClickListener { speak(input.text.toString(), langA) }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                addView(status)
                addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                result.background = glassShape(p, 22, true)
                addView(LinearLayout(this@TranslatorPanel).apply {
                    addView(text("🔊  Listen", 14f, p.text, true).apply {
                        gravity = Gravity.CENTER; background = glassShape(p, 20, true)
                        setOnClickListener { speak(result.text.toString(), langB) }
                    }, LinearLayout.LayoutParams(0, dp(42), 1f))
                    addView(text("⧉  Copy", 14f, p.text, true).apply {
                        gravity = Gravity.CENTER; background = glassShape(p, 20, true)
                        setOnClickListener {
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("translation", result.text))
                            Toast.makeText(this@TranslatorPanel, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            })
        }
    }

    // ---- Conversation mode ----

    /** One side of the conversation: a language, what the other person said (already translated), and a field to answer. */
    private fun panel(isUpper: Boolean, mine: () -> String, theirs: () -> String, onChangeMine: (String) -> Unit, sections: Boolean = false, send: (String) -> Unit): Pair<View, (String, Boolean) -> Unit> {
        fun list() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(6)) }
        fun scrollOf(l: View) = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(l) }
        // Sections: two clearly separate cards, the reply (the other person, translated) on top with an accent stripe, what you
        // say under it. Dialogue: one chat, your phrases on the right, the other person's on the left.
        fun card(title: String, reply: Boolean, l: LinearLayout): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = if (reply) shape((p.accent and 0xFFFFFF) or 0x2E000000, 18, p.accent, 2) else glassShape(p, 18, true)
            addView(text((if (reply) "⇣  " else "🎤  ") + title, 12f, if (reply) p.accent else p.sub, true).apply { setPadding(dp(2), 0, 0, dp(4)) })
            addView(scrollOf(l), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val ownList = list(); val replyList = list(); val mixed = list()
        val ownScroll = (if (sections) card("You say", false, ownList) else View(this))
        val content: View = if (sections) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(card("Reply", true, replyList), LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(12) })
            addView(ownScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else scrollOf(mixed)
        fun add(t: String, own: Boolean) {
            val target = if (!sections) mixed else if (own) ownList else replyList
            target.addView(text(t, if (sections) 17f else 18f, if (own) p.onAccent else p.text).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8)); background = if (own) shape(p.accent, 16) else glassShape(p, 16, true)
                setOnLongClickListener {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("phrase", t))
                    Toast.makeText(this@TranslatorPanel, "Copied", Toast.LENGTH_SHORT).show(); true
                }
            }, if (sections) LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
               else LinearLayout.LayoutParams(-2, -2).apply { gravity = if (own) Gravity.END else Gravity.START; topMargin = dp(6); if (own) marginStart = dp(40) else marginEnd = dp(40) })
            (target.parent as? ScrollView)?.let { sc -> sc.post { sc.fullScroll(View.FOCUS_DOWN) } }
        }
        val input = EditText(this).apply {
            hint = "Say or type…"; setHintTextColor(p.sub); setTextColor(p.text); textSize = 16f; setSingleLine(); background = glassShape(p, 22, true)
            setPadding(dp(16), 0, dp(16), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { v, _, _ -> val s = v.text.toString(); v.text = ""; send(s); true }
        }
        val mic = text("🎤", 20f, p.text).apply {
            gravity = Gravity.CENTER; background = circle(p.glassCircle)
            setOnClickListener { pendingMic = this; listen(mine(), continuous = false) { s -> runOnUiThread { send(s) } } }
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glassShape(p, 26); setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(langChip(mine, onChangeMine), LinearLayout.LayoutParams(-2, dp(38)))
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6); bottomMargin = dp(8) })
            addView(LinearLayout(this@TranslatorPanel).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(input, LinearLayout.LayoutParams(0, dp(46), 1f))
                addView(mic, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })
            })
            if (isUpper) rotation = 180f   // faces the other person
        }
        return view to { t: String, own: Boolean -> add(t, own) }
    }

    // ---- saved conversations ----
    private val dialogPrefs get() = getSharedPreferences("aos_translator", MODE_PRIVATE)
    /** The conversation in progress: id (start time) and its phrases. */
    private var dialogId = 0L
    private val dialogLines = org.json.JSONArray()

    private fun saveLine(who: String, orig: String, tr: String) {
        if (dialogId == 0L) dialogId = System.currentTimeMillis()
        dialogLines.put(org.json.JSONObject().put("w", who).put("o", orig).put("t", tr))
        val all = runCatching { org.json.JSONArray(dialogPrefs.getString("dialogs", "[]")) }.getOrDefault(org.json.JSONArray())
        val out = org.json.JSONArray()
        out.put(org.json.JSONObject().put("id", dialogId).put("a", langA).put("b", langB).put("m", dialogLines))
        for (i in 0 until all.length()) all.getJSONObject(i).let { if (it.optLong("id") != dialogId && out.length() < 50) out.put(it) }
        dialogPrefs.edit().putString("dialogs", out.toString()).apply()
    }

    /** Saved conversations: newest first; a tap shows the whole conversation (copy it from there). */
    private fun showSavedDialogs() {
        val all = runCatching { org.json.JSONArray(dialogPrefs.getString("dialogs", "[]")) }.getOrDefault(org.json.JSONArray())
        if (all.length() == 0) { Toast.makeText(this, "No saved conversations yet", Toast.LENGTH_SHORT).show(); return }
        val items = (0 until all.length()).map { all.getJSONObject(it) }
        val titles = items.map { d ->
            val m = d.optJSONArray("m") ?: org.json.JSONArray()
            android.text.format.DateFormat.format("d MMM, HH:mm", d.optLong("id")).toString() + "  ·  " + (if (m.length() > 0) m.getJSONObject(0).optString("o").take(40) else "")
        }
        android.app.AlertDialog.Builder(this).setTitle("Saved conversations").setItems(titles.toTypedArray()) { _, i ->
            val d = items[i]; val m = d.optJSONArray("m") ?: org.json.JSONArray()
            val text = (0 until m.length()).joinToString("\n\n") { k -> m.getJSONObject(k).let { l -> (if (l.optString("w") == "me") "Me: " else "They: ") + l.optString("o") + "\n→ " + l.optString("t") } }
            android.app.AlertDialog.Builder(this).setTitle(titles[i]).setMessage(text)
                .setPositiveButton("Copy") { _, _ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("conversation", text)) }
                .setNegativeButton("Close", null).show()
        }.setNegativeButton("Close", null).show()
    }

    private fun conversationMode(sections: Boolean): View {
        lateinit var addUpper: (String, Boolean) -> Unit
        lateinit var addLower: (String, Boolean) -> Unit
        // You (lower) speak langA; the translation goes to the upper half in langB, and the other way round. Every phrase stays
        // in both halves as a chat and is saved.
        val (lower, setLower) = panel(false, { langA }, { langB }, { langA = it }, sections) { s ->
            if (s.isNotBlank()) { addLower(s, true)
                translate(s, langA, langB) { r -> runOnUiThread { if (r != null) { addUpper(r, false); saveLine("me", s, r); speak(r, langB) } else Toast.makeText(this, "Could not translate", Toast.LENGTH_SHORT).show() } } }
        }
        val (upper, setUpper) = panel(true, { langB }, { langA }, { langB = it }, sections) { s ->
            if (s.isNotBlank()) { addUpper(s, true)
                translate(s, langB, langA) { r -> runOnUiThread { if (r != null) { addLower(r, false); saveLine("them", s, r); speak(r, langA) } else Toast.makeText(this, "Could not translate", Toast.LENGTH_SHORT).show() } } }
        }
        addUpper = setUpper; addLower = setLower
        dialogId = 0L
        while (dialogLines.length() > 0) dialogLines.remove(0)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(upper, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(6) })
            // Middle strip: saved conversations and a new one.
            addView(LinearLayout(this@TranslatorPanel).apply {
                gravity = Gravity.CENTER
                addView(text("🕘  Saved", 13f, p.text, true).apply { gravity = Gravity.CENTER; background = glassShape(p, 16, true); setPadding(dp(14), 0, dp(14), 0); setOnClickListener { showSavedDialogs() } }, LinearLayout.LayoutParams(-2, dp(34)))
                addView(text("＋  New", 13f, p.text, true).apply { gravity = Gravity.CENTER; background = glassShape(p, 16, true); setPadding(dp(14), 0, dp(14), 0); setOnClickListener { show(mode) } }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            addView(lower, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }
}
