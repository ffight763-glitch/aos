package org.anthezis.launcher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * AOS AI: a voice assistant over any app. A floating mic button listens to a command, takes a picture of the screen
 * (MediaProjection, allowed once per start) and asks Claude; the answer is shown in a card and read aloud, with the time
 * every step took. The API key is typed by the user in the AOS AI screen and stays on the phone.
 */
object AiBrain {
    const val MODEL = "claude-opus-5-5"
    private const val SYSTEM = "You are AOS AI, a voice assistant on an Android phone. The user speaks a short command; " +
        "often a screenshot of what is on their screen comes with it. Answer in the language the user spoke, in 1-3 short " +
        "sentences that sound natural when read aloud: no lists, no markdown, no emoji. If the command is about the screen, " +
        "use the screenshot; if something is not visible or unclear, say so briefly."

    private var client: AnthropicClient? = null
    private var clientKey = ""
    /** The last turns as plain text (no pictures), so follow-up questions work without paying for old screenshots again. */
    private val history = ArrayList<Pair<String, String>>()

    fun prefs(c: Context) = c.getSharedPreferences("aos_ai", Context.MODE_PRIVATE)
    /** The key typed in AOS AI wins; otherwise the one built in from the developer's local.properties (empty in public builds). */
    fun key(c: Context) = prefs(c).getString("api_key", "").orEmpty().trim().ifBlank { BuildConfig.ANTHROPIC_KEY }

    private fun client(key: String): AnthropicClient {
        if (client == null || clientKey != key) { client = AnthropicOkHttpClient.builder().apiKey(key).build(); clientKey = key }
        return client!!
    }

    fun clearHistory() = synchronized(history) { history.clear() }

    /** A short, human message for an API failure instead of the raw JSON. */
    fun explain(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            m.contains("credit balance is too low", true) -> "No credits on the Anthropic account. Add them in console.anthropic.com → Plans & Billing, then try again."
            e is com.anthropic.errors.UnauthorizedException || m.contains("authentication", true) || m.contains("invalid x-api-key", true) ->
                "The API key is not valid. Create one in console.anthropic.com → API Keys."
            e is com.anthropic.errors.RateLimitException -> "Too many requests right now. Wait a few seconds and try again."
            e is com.anthropic.errors.AnthropicIoException || e is java.io.IOException -> "No connection to the Anthropic API. Check the internet."
            e is com.anthropic.errors.InternalServerException -> "The Anthropic API is busy. Try again in a moment."
            else -> "Error: " + m.take(160)
        }
    }

    /** Sends the command (and the screenshot) to Claude; returns the answer text. Blocking: call it off the main thread. */
    fun ask(c: Context, command: String, jpeg: ByteArray?): String {
        val key = key(c)
        if (key.isBlank()) return "Add your Anthropic API key in AOS AI first."
        val b = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(2048L)
            .system(SYSTEM)
            // Effort low: a spoken answer should come back fast; Opus 5.5 keeps thinking on and decides how much it needs.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            // If a safety classifier declines the request, the server retries it on a fallback model chosen by category.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        synchronized(history) { for ((q, a) in history.takeLast(6)) { b.addUserMessage(q); b.addAssistantMessage(a) } }
        val content = ArrayList<ContentBlockParam>()
        if (jpeg != null) content += ContentBlockParam.ofImage(ImageBlockParam.builder()
            .source(Base64ImageSource.builder().data(android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build())
            .build())
        content += ContentBlockParam.ofText(TextBlockParam.builder().text(command).build())
        b.addUserMessageOfContentBlockParams(content)
        val msg = client(key).messages().create(b.build())
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) return "I can't help with that one."
        val text = msg.content().flatMap { it.text().map { t -> listOf(t.text()) }.orElse(emptyList()) }.joinToString(" ").trim()
            .ifBlank { "No answer." }
        synchronized(history) { history += command to text; while (history.size > 12) history.removeAt(0) }
        return text
    }
}

class AiAssistantService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var running = false
        private const val MIN_GAP_MS = 2500L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var p: Palette
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var bubble: View? = null
    private var closeBadge: View? = null
    private var closeLp: WindowManager.LayoutParams? = null
    private var card: View? = null
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var busy = false
    private var lastAsk = 0L

    override fun onBind(intent: Intent?): IBinder? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("aos_ai", "AOS AI", NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(this, 1, Intent(this, AiAssistantService::class.java).setAction("stop"),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "aos_ai").setSmallIcon(R.drawable.ic_search).setContentTitle("AOS AI is listening for your tap")
            .setContentText("Tap the floating mic, speak a command. Hold the mic to stop.").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        wm = getSystemService(WindowManager::class.java)
        p = Palette(this)
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)?.apply {
            registerCallback(object : MediaProjection.Callback() { override fun onStop() { main.post { stopSelf() } } }, main)
        }
        setUpCapture()
        tts = TextToSpeech(this) { }
        showBubble()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { recognizer?.destroy() }; recognizer = null
        runCatching { tts?.shutdown() }
        runCatching { display?.release() }; runCatching { reader?.close() }; runCatching { projection?.stop() }
        projection = null
        listOfNotNull(bubble, card, closeBadge).forEach { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    // ---- screen ----

    private fun setUpCapture() {
        val m = resources.displayMetrics
        // Half resolution (max 720 px wide) is plenty for Claude to read the screen and keeps the upload small.
        val w = minOf(720, m.widthPixels); val h = m.heightPixels * w / m.widthPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay("aos-ai", w, h, m.densityDpi * w / m.widthPixels,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    private fun grab(): ByteArray? {
        val img = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            val plane = img.planes[0]
            val pad = (plane.rowStride - plane.pixelStride * img.width) / plane.pixelStride
            val full = Bitmap.createBitmap(img.width + pad, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val bmp = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
        } catch (_: Exception) { null } finally { img.close() }
    }

    // ---- floating mic ----

    private fun overlayParams(w: Int, h: Int, focusable: Boolean = false) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT)

    private fun showBubble() {
        val size = dp(58)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_aos_mic)
            setColorFilter(p.onAccent); setPadding(dp(15), dp(15), dp(15), dp(15))
            background = circle(p.accent); elevation = dp(8).toFloat()
        }
        val lp = overlayParams(size, size).apply { gravity = Gravity.TOP or Gravity.START; x = resources.displayMetrics.widthPixels - size - dp(12); y = resources.displayMetrics.heightPixels / 2 }
        var dx = 0f; var dy = 0f; var moved = false; var downAt = 0L
        icon.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = lp.x - e.rawX; dy = lp.y - e.rawY; moved = false; downAt = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(lp.x - (e.rawX + dx)) > dp(6) || Math.abs(lp.y - (e.rawY + dy)) > dp(6)) moved = true
                    if (moved) {
                        lp.x = (e.rawX + dx).toInt(); lp.y = (e.rawY + dy).toInt(); runCatching { wm.updateViewLayout(v, lp) }
                        closeLp?.let { c -> c.x = lp.x + size - (closeBadge?.width ?: 0) + dp(4); c.y = lp.y - dp(4); runCatching { wm.updateViewLayout(closeBadge, c) } }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (SystemClock.uptimeMillis() - downAt > 600) stopSelf() else onMic()
                }
            }
            true
        }
        bubble = icon
        wm.addView(icon, lp)
        // A small "x" next to the mic: turns AOS AI off (holding the mic does the same).
        val xBtn = TextView(this).apply {
            text = "✕"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); background = circle(0xFFE5484D.toInt())
            contentDescription = "Turn AOS AI off"; setOnClickListener { stopSelf() }
        }
        val xs = dp(22)
        val xlp = overlayParams(xs, xs).apply { gravity = Gravity.TOP or Gravity.START; x = lp.x + size - xs + dp(4); y = lp.y - dp(4) }
        closeBadge = xBtn; closeLp = xlp
        wm.addView(xBtn, xlp)
    }

    private fun setBubbleBusy(on: Boolean) { bubble?.animate()?.scaleX(if (on) 0.85f else 1f)?.scaleY(if (on) 0.85f else 1f)?.alpha(if (on) 0.6f else 1f)?.setDuration(150)?.start() }

    private fun onMic() {
        if (busy) return
        val wait = lastAsk + MIN_GAP_MS - SystemClock.uptimeMillis()
        if (wait > 0) { showCard("…", "Wait ${(wait + 999) / 1000} s before the next request", ""); return }
        busy = true; setBubbleBusy(true)
        tts?.stop()
        card?.let { runCatching { wm.removeView(it) } }; card = null
        // Picture first, without our own mic on it: hide the bubble for one frame.
        bubble?.visibility = View.INVISIBLE
        main.postDelayed({
            val t0 = SystemClock.uptimeMillis()
            val shot = grab()
            val shotMs = SystemClock.uptimeMillis() - t0
            bubble?.visibility = View.VISIBLE
            listen { said, sttMs ->
                if (said.isNullOrBlank()) { busy = false; setBubbleBusy(false); showCard("", "Did not catch that", "🎙 ${sttMs} ms"); return@listen }
                showCard(said, "Thinking…", "🎙 ${sttMs} ms  ·  📷 ${shotMs} ms")
                lastAsk = SystemClock.uptimeMillis()
                Thread {
                    val t1 = SystemClock.uptimeMillis()
                    val answer = try { AiBrain.ask(this, said, shot) } catch (e: Throwable) {
                        android.util.Log.e("AosAI", "request failed", e)
                        AiBrain.prefs(this).edit().putString("last_error", e.javaClass.simpleName + ": " + e.message).apply()
                        AiBrain.explain(e)
                    }
                    val aiMs = SystemClock.uptimeMillis() - t1
                    main.post {
                        busy = false; setBubbleBusy(false)
                        val timing = "🎙 ${sttMs} ms  ·  📷 ${shotMs} ms  ·  🤖 ${aiMs} ms  ·  Σ ${sttMs + shotMs + aiMs} ms"
                        AiBrain.prefs(this).edit().putString("last_timing", timing).putLong("requests", AiBrain.prefs(this).getLong("requests", 0) + 1).apply()
                        showCard(said, answer, timing)
                        tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "aos_ai")
                    }
                }.start()
            }
        }, 120)
    }

    private fun listen(done: (String?, Long) -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !SpeechRecognizer.isRecognitionAvailable(this)) {
            done(null, 0); return
        }
        val start = SystemClock.uptimeMillis()
        showCard("", "Listening…", "")
        runCatching { recognizer?.destroy() }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        var finished = false
        fun finish(text: String?) { if (finished) return; finished = true; done(text, SystemClock.uptimeMillis() - start); runCatching { r.destroy() }; if (recognizer === r) recognizer = null }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) { finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()) }
            override fun onError(error: Int) { finish(null) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { bubble?.let { val s = 1f + (rmsdB.coerceIn(0f, 10f) / 40f); it.scaleX = s; it.scaleY = s } }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()))
    }

    // ---- answer card ----

    private fun showCard(question: String, answer: String, timing: String) {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glassShape(p, 24, true); setPadding(dp(18), dp(14), dp(18), dp(14))
            if (question.isNotBlank()) addView(TextView(this@AiAssistantService).apply { text = question; textSize = 13f; setTextColor(p.sub); maxLines = 2 })
            addView(TextView(this@AiAssistantService).apply { text = answer; textSize = 16f; setTextColor(p.text); typeface = appFont(500); setPadding(0, dp(4), 0, 0) })
            if (timing.isNotBlank()) addView(TextView(this@AiAssistantService).apply { text = timing; textSize = 11f; setTextColor(p.accent); setPadding(0, dp(8), 0, 0) })
            setOnClickListener { tts?.stop(); runCatching { wm.removeView(this) }; if (card === this) card = null }
        }
        val wrap = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -2))
            addView(TextView(this@AiAssistantService).apply {
                text = "✕"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(p.text); background = circle(p.glassCircle)
                contentDescription = "Close"
                setOnClickListener { tts?.stop(); card?.let { runCatching { wm.removeView(it) } }; card = null }
            }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
        }
        card?.let { runCatching { wm.removeView(it) } }
        card = wrap
        wm.addView(wrap, overlayParams(resources.displayMetrics.widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48)
        })
        if (answer != "Listening…" && answer != "Thinking…") main.postDelayed({ if (card === wrap && !busy) { runCatching { wm.removeView(wrap) }; card = null } }, 9000L + answer.length * 45L)
    }

}

/** AOS AI screen: the API key, the start button (screen access + floating mic) and the timing of the last request. */
class AiAssistantActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == "stop") { stopService(Intent(this, AiAssistantService::class.java)); finish(); return }
        p = Palette(this)
        val prefs = AiBrain.prefs(this)
        val keyBox = EditText(this).apply {
            hint = "Anthropic API key (sk-ant-…)"; setHintTextColor(p.sub); setTextColor(p.text); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = shape(p.surface, 18, p.stroke); setPadding(dp(16), dp(12), dp(16), dp(12))
            if (AiBrain.key(this@AiAssistantActivity).isNotBlank()) hint = "Key saved · type to replace"
        }
        info = TextView(this).apply { textSize = 13f; setTextColor(p.sub); setPadding(dp(4), dp(14), dp(4), 0) }
        fun button(t: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 16f; gravity = Gravity.CENTER; typeface = appFont(700)
            setTextColor(if (primary) p.onAccent else p.text); background = shape(if (primary) p.accent else p.surfaceHigh, 26)
            setOnClickListener { onClick() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(64), dp(20), dp(24)); setBackgroundColor(p.bg)
            addView(TextView(this@AiAssistantActivity).apply { text = "AOS AI"; textSize = 30f; setTextColor(p.text); typeface = appFont(700) })
            addView(TextView(this@AiAssistantActivity).apply {
                text = "A floating mic over any app. Tap it and speak: AOS AI looks at your screen and answers out loud. Hold the mic to stop.\n\nModel: ${AiBrain.MODEL}. Your key stays on this phone."
                textSize = 14f; setTextColor(p.sub); setPadding(0, dp(8), 0, dp(18))
            })
            addView(keyBox, LinearLayout.LayoutParams(-1, -2))
            addView(button("Start AOS AI", true) {
                val typed = keyBox.text.toString().trim()
                if (typed.isNotBlank()) { prefs.edit().putString("api_key", typed).apply(); keyBox.setText(""); keyBox.hint = "Key saved · type to replace" }
                start()
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
            addView(button("Stop", false) { stopService(Intent(this@AiAssistantActivity, AiAssistantService::class.java)); AiBrain.clearHistory(); paintInfo() },
                LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
            addView(info)
        })
        paintInfo()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun paintInfo() {
        val prefs = AiBrain.prefs(this)
        info.text = (if (AiAssistantService.running) "Running: the mic floats over your apps." else "Not running.") +
            "\nRequests: ${prefs.getLong("requests", 0)}" +
            (prefs.getString("last_timing", null)?.let { "\nLast: $it" } ?: "")
    }

    override fun onResume() { super.onResume(); if (::info.isInitialized) paintInfo() }

    private fun start() {
        if (AiBrain.key(this).isBlank()) { android.widget.Toast.makeText(this, "Enter your Anthropic API key first", android.widget.Toast.LENGTH_SHORT).show(); return }
        val need = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 3); return }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this, "Allow AOS to show the mic over other apps", android.widget.Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))); return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 5)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 3 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 5) return
        if (resultCode != RESULT_OK || data == null) { android.widget.Toast.makeText(this, "Screen access is needed for AOS AI", android.widget.Toast.LENGTH_SHORT).show(); return }
        startForegroundService(Intent(this, AiAssistantService::class.java).putExtra(AiAssistantService.EXTRA_CODE, resultCode).putExtra(AiAssistantService.EXTRA_DATA, data))
        moveTaskToBack(true)
    }
}

/**
 * AOS AI as a page of the left panel (Feed): a chat with Claude, typed or spoken, answers read aloud (speaker toggle).
 * The chat is kept on the phone; the API key is the one saved in the AOS AI app.
 */
class AiChatPanel(private val act: Activity, private val p: Palette) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
    private val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var waiting = false
    private val prefs get() = AiBrain.prefs(act)
    private var speakOn: Boolean
        get() = prefs.getBoolean("chat_speak", true)
        set(v) { prefs.edit().putBoolean("chat_speak", v).apply() }

    private fun bubble(t: String, mine: Boolean): TextView {
        val b = TextView(act).apply {
            text = t; textSize = 15f; setTextColor(if (mine) p.onAccent else p.text); typeface = appFont(400)
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = if (mine) act.shape(p.accent, 18) else act.glassShape(p, 18, true)
            setTextIsSelectable(!mine)
        }
        list.addView(b, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (mine) Gravity.END else Gravity.START; topMargin = dp(8); if (mine) marginStart = dp(48) else marginEnd = dp(24) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return b
    }

    private fun saved(): org.json.JSONArray = runCatching { org.json.JSONArray(prefs.getString("chat", "[]")) }.getOrDefault(org.json.JSONArray())
    private fun save(role: String, t: String) {
        val a = saved(); a.put(org.json.JSONObject().put("r", role).put("t", t))
        val out = org.json.JSONArray(); val from = maxOf(0, a.length() - 80)
        for (i in from until a.length()) out.put(a.get(i))
        prefs.edit().putString("chat", out.toString()).apply()
    }

    fun view(): View {
        list.removeAllViews()
        val a = saved()
        for (i in 0 until a.length()) a.getJSONObject(i).let { bubble(it.optString("t"), it.optString("r") == "u") }
        if (a.length() == 0) bubble("Hi! Ask me anything, by voice (mic) or text. I answer with Claude (${AiBrain.MODEL}).", false)
        if (AiBrain.key(act).isBlank()) bubble("To start, open the AOS AI app and paste your Anthropic API key. It stays on this phone. Tap here.", false).apply {
            setOnClickListener { act.startActivity(Intent(act, AiAssistantActivity::class.java)) }
        }
        val input = EditText(act).apply {
            hint = "Ask AOS AI…"; setHintTextColor(p.sub); setTextColor(p.text); textSize = 15f; maxLines = 4
            background = act.glassShape(p, 22, true); setPadding(dp(16), dp(10), dp(16), dp(10))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND; setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        }
        fun round(t: String, accent: Boolean, onClick: (TextView) -> Unit) = TextView(act).apply {
            text = t; textSize = 18f; gravity = Gravity.CENTER; setTextColor(if (accent) p.onAccent else p.text)
            background = act.circle(if (accent) p.accent else p.glassCircle); setOnClickListener { onClick(this) }
        }
        input.setOnEditorActionListener { v, _, _ -> val s = v.text.toString(); v.text = ""; send(s); true }
        val speakBtn = round(if (speakOn) "🔊" else "🔇", false) { b -> speakOn = !speakOn; b.text = if (speakOn) "🔊" else "🔇"; if (!speakOn) tts?.stop() }
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(act).apply { text = "AOS AI"; textSize = 20f; setTextColor(p.text); typeface = appFont(700) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(speakBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(round("🗑", false) { prefs.edit().remove("chat").apply(); AiBrain.clearHistory(); list.removeAllViews(); bubble("Chat cleared.", false) },
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
            })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(input, LinearLayout.LayoutParams(0, -2, 1f))
                addView(round("🎤", false) { b -> listen(b) }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })
                addView(round("➤", true) { val s = input.text.toString(); input.setText(""); send(s) }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || waiting) return
        waiting = true
        bubble(q, true); save("u", q)
        val pending = bubble("…", false)
        val t0 = SystemClock.uptimeMillis()
        Thread {
            val answer = try { AiBrain.ask(act, q, null) } catch (e: Throwable) {
                android.util.Log.e("AosAI", "chat failed", e); AiBrain.explain(e)
            }
            val ms = SystemClock.uptimeMillis() - t0
            main.post {
                waiting = false
                pending.text = answer + "\n\n" + "🤖 ${ms} ms"
                save("a", answer)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                if (speakOn && !answer.startsWith("Error") && !answer.startsWith("No ") && !answer.startsWith("The API") && !answer.startsWith("Too many")) {
                    val engine = tts
                    if (engine == null) tts = TextToSpeech(act) { st -> if (st == TextToSpeech.SUCCESS) tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "aos_ai_chat") }
                    else engine.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "aos_ai_chat")
                }
            }
        }.start()
    }

    private fun listen(btn: TextView) {
        if (recognizer != null) { recognizer?.stopListening(); return }
        if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 12); return
        }
        tts?.stop()
        val r = SpeechRecognizer.createSpeechRecognizer(act); recognizer = r
        btn.text = "…"
        fun done(t: String?) {
            btn.text = "🎤"; btn.background = act.circle(p.glassCircle)
            runCatching { r.destroy() }; recognizer = null
            if (!t.isNullOrBlank()) send(t)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { btn.text = "■"; btn.background = act.circle(0xFFE5484D.toInt()); btn.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
            override fun onResults(results: Bundle?) { done(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()) }
            override fun onError(error: Int) { done(null) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false))
    }

    fun pause() { tts?.stop(); runCatching { recognizer?.destroy() }; recognizer = null }
}
