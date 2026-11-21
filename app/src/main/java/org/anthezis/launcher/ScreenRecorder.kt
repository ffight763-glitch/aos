package org.anthezis.launcher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * AOS Recorder: a floating round button over any app. A tap opens a ring menu (record / stop, pause / resume, recordings,
 * close); while recording the button turns red and counts the time. Videos go to Movies/AOS Recorder.
 * One screen-capture permission per start: every recording reuses the same virtual display (Android 14 allows one per token).
 */
class ScreenRecordService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var p: Palette
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var recording = false
    private var paused = false
    private var startedAt = 0L
    private var pausedTotal = 0L
    private var pausedAt = 0L
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var ring: View? = null
    private var w = 0; private var h = 0; private var dpi = 0

    override fun onBind(intent: Intent?): IBinder? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("aos_rec", "AOS Recorder", NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(this, 2, Intent(this, ScreenRecordService::class.java).setAction("stop"), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "aos_rec").setSmallIcon(R.drawable.ic_aos_rec).setContentTitle("AOS Recorder")
            .setContentText("Tap the floating button to record the screen").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Close", stop).build()).build()
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
        running = true
        wm = getSystemService(WindowManager::class.java); p = Palette(this)
        val m = resources.displayMetrics
        // Full width up to 1080 px, even sizes (encoders need them).
        w = (minOf(1080, m.widthPixels) / 2) * 2; h = (m.heightPixels * w / m.widthPixels / 2) * 2; dpi = m.densityDpi
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)?.apply {
            registerCallback(object : MediaProjection.Callback() { override fun onStop() { main.post { stopSelf() } } }, main)
        }
        display = projection?.createVirtualDisplay("aos-rec", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null)
        showBubble()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (recording) stopRecording()
        running = false
        runCatching { display?.release() }; runCatching { projection?.stop() }; projection = null
        listOfNotNull(bubble, ring).forEach { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    // ---- recording ----

    private fun startRecording() {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "AOS_" + android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis()) + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AOS Recorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return toast("Cannot create the video file")
        val fd = contentResolver.openFileDescriptor(u, "w") ?: return toast("Cannot open the video file")
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val r = MediaRecorder(this)
        try {
            if (mic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(fd.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, h); r.setVideoFrameRate(30); r.setVideoEncodingBitRate(8_000_000)
            if (mic) { r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioEncodingBitRate(128_000); r.setAudioSamplingRate(44100) }
            r.prepare()
            display?.surface = r.surface
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }; runCatching { fd.close() }; contentResolver.delete(u, null, null)
            return toast("Recording failed: ${e.message}")
        }
        recorder = r; pfd = fd; uri = u
        recording = true; paused = false; startedAt = SystemClock.elapsedRealtime(); pausedTotal = 0
        tick()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        runCatching { r.stop() }; runCatching { r.release() }
        display?.surface = null
        runCatching { pfd?.close() }
        uri?.let { contentResolver.update(it, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
        recorder = null; pfd = null; recording = false; paused = false
        paintBubble()
        toast("Saved to Movies/AOS Recorder")
    }

    private fun togglePause() {
        val r = recorder ?: return
        if (paused) { runCatching { r.resume() }; pausedTotal += SystemClock.elapsedRealtime() - pausedAt; paused = false }
        else { runCatching { r.pause() }; pausedAt = SystemClock.elapsedRealtime(); paused = true }
        paintBubble()
    }

    private fun elapsed(): Long = (if (paused) pausedAt else SystemClock.elapsedRealtime()) - startedAt - pausedTotal

    private fun tick() { if (!recording) return; paintBubble(); main.postDelayed({ tick() }, 500) }

    private fun toast(t: String) { main.post { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show() } }

    // ---- floating button and ring menu ----

    private fun overlay(width: Int, height: Int) = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)

    private fun paintBubble() {
        val b = bubble ?: return
        if (recording) {
            val s = elapsed() / 1000
            b.text = if (paused) "❚❚" else String.format("%d:%02d", s / 60, s % 60)
            b.textSize = if (paused) 16f else 13f
            b.background = circle(0xFFE5484D.toInt(), 0xFFFFFFFF.toInt(), 2)
        } else { b.text = "●"; b.textSize = 22f; b.background = circle(0xCC101418.toInt(), 0xFFE5484D.toInt(), 3) }
    }

    private fun showBubble() {
        val size = dp(56)
        val b = TextView(this).apply { gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); typeface = appFont(700); elevation = dp(8).toFloat() }
        val lp = overlay(size, size).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = resources.displayMetrics.heightPixels / 3 }
        var dx = 0f; var dy = 0f; var moved = false
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = lp.x - e.rawX; dy = lp.y - e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(lp.x - (e.rawX + dx)) > dp(6) || Math.abs(lp.y - (e.rawY + dy)) > dp(6)) moved = true
                    if (moved) { closeRing(); lp.x = (e.rawX + dx).toInt(); lp.y = (e.rawY + dy).toInt(); runCatching { wm.updateViewLayout(v, lp) } }
                }
                MotionEvent.ACTION_UP -> if (!moved) { if (ring != null) closeRing() else openRing() }
            }
            true
        }
        bubble = b; bubbleLp = lp
        paintBubble()
        wm.addView(b, lp)
    }

    private fun closeRing() { ring?.let { runCatching { wm.removeView(it) } }; ring = null }

    /** Four small round buttons on a quarter circle around the main button, on the side with more room. */
    private fun openRing() {
        val lp = bubbleLp ?: return
        val area = dp(200); val btn = dp(48); val main0 = dp(56)
        val toRight = lp.x < resources.displayMetrics.widthPixels / 2
        val frame = FrameLayout(this)
        val items = listOf(
            (if (recording) "■" else "●") to { if (recording) stopRecording() else startRecording() },
            (if (paused) "▶" else "❚❚") to { if (recording) togglePause() else toast("Start a recording first") },
            "📁" to { startActivity(Intent(this, ScreenRecorderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            "✕" to { stopSelf() })
        val cx = if (toRight) main0 / 2 else area - main0 / 2; val cy = area / 2
        val radius = dp(80)
        items.forEachIndexed { i, (label, action) ->
            // Angles from -70° to +70° around the side that faces the screen.
            val a = Math.toRadians(-70.0 + i * 140.0 / (items.size - 1))
            val x = cx + (if (toRight) 1 else -1) * (radius * Math.cos(a)).toInt() - btn / 2
            val y = cy + (radius * Math.sin(a)).toInt() - btn / 2
            frame.addView(TextView(this).apply {
                text = label; gravity = Gravity.CENTER; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
                background = circle(if (i == 0) 0xFFE5484D.toInt() else 0xE6202429.toInt()); elevation = dp(6).toFloat()
                setOnClickListener { closeRing(); action() }
                scaleX = 0.3f; scaleY = 0.3f; alpha = 0f
                animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(i * 30L).setDuration(160).start()
            }, FrameLayout.LayoutParams(btn, btn).apply { leftMargin = x; topMargin = y })
        }
        val rlp = overlay(area, area).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (toRight) lp.x else lp.x - (area - main0); y = lp.y - (area - main0) / 2
        }
        ring = frame
        wm.addView(frame, rlp)
        // The main button stays above the ring.
        bubble?.let { runCatching { wm.removeView(it); wm.addView(it, lp) } }
    }
}

/** AOS Recorder screen: start the floating button, and the list of recordings (tap to play). */
class ScreenRecorderActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(t: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 16f; gravity = Gravity.CENTER; typeface = appFont(700)
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else p.text); background = shape(if (primary) 0xFFE5484D.toInt() else p.surfaceHigh, 26)
            setOnClickListener { onClick() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(64), dp(20), dp(16)); setBackgroundColor(p.bg)
            addView(TextView(this@ScreenRecorderActivity).apply { text = "AOS Recorder"; textSize = 30f; setTextColor(p.text); typeface = appFont(700) })
            addView(TextView(this@ScreenRecorderActivity).apply {
                text = "A round button floats over your apps. Tap it: ● record, ❚❚ pause, 📁 recordings, ✕ close."
                textSize = 14f; setTextColor(p.sub); setPadding(0, dp(8), 0, dp(16))
            })
            addView(button(if (ScreenRecordService.running) "Button is on" else "Show the record button", true) { start() }, LinearLayout.LayoutParams(-1, dp(52)))
            addView(TextView(this@ScreenRecorderActivity).apply { text = "Recordings"; textSize = 18f; setTextColor(p.text); typeface = appFont(700); setPadding(dp(2), dp(22), 0, dp(8)) })
            addView(ScrollView(this@ScreenRecorderActivity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    override fun onResume() { super.onResume(); fillList() }

    private fun fillList() {
        list.removeAllViews()
        val cols = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE)
        runCatching {
            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                arrayOf("Movies/AOS Recorder%"), MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                while (c.moveToNext()) {
                    val u = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    val sec = c.getLong(2) / 1000; val mb = c.getLong(3) / 1048576.0
                    list.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; background = shape(p.surface, 16, p.stroke); setPadding(dp(14), dp(10), dp(14), dp(10))
                        addView(TextView(this@ScreenRecorderActivity).apply { text = c.getString(1); textSize = 15f; setTextColor(p.text) })
                        addView(TextView(this@ScreenRecorderActivity).apply { text = String.format("%d:%02d  ·  %.1f MB", sec / 60, sec % 60, mb); textSize = 12f; setTextColor(p.sub) })
                        setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
            }
        }
        if (list.childCount == 0) list.addView(TextView(this).apply { text = "No recordings yet."; setTextColor(p.sub); textSize = 14f })
    }

    private fun start() {
        if (ScreenRecordService.running) return
        val need = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty() && !asked) { asked = true; requestPermissions(need.toTypedArray(), 4); return }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 6)
    }
    private var asked = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { if (requestCode == 4) start() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 6 || resultCode != RESULT_OK || data == null) return
        startForegroundService(Intent(this, ScreenRecordService::class.java).putExtra(ScreenRecordService.EXTRA_CODE, resultCode).putExtra(ScreenRecordService.EXTRA_DATA, data))
        moveTaskToBack(true)
    }
}
