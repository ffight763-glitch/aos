package org.anthezis.launcher

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AOS Camera, laid out like the Realme camera: flash on top, a 3:4 viewfinder, a zoom pill, a mode strip, and below it
 * the gallery thumbnail, a round shutter and the camera switch. Built on Camera2, photos go to DCIM/Camera.
 */
class CameraActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var tv: TextureView
    private lateinit var thumb: ImageView
    private lateinit var flashIcon: ImageView
    private lateinit var flashText: TextView
    private lateinit var zoomOne: TextView
    private lateinit var zoomTwo: TextView
    private lateinit var shutterRing: View

    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var chars: CameraCharacteristics? = null
    private var preview = Size(1440, 1080)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var flashMode = 1   // 0 off, 1 auto, 2 on
    private var zoom = 1f
    private var lastUri: android.net.Uri? = null
    private var mode = 0                // 0 photo, 1 video
    private var recording = false
    private var recorder: android.media.MediaRecorder? = null
    private var videoFd: android.os.ParcelFileDescriptor? = null
    private var timerSec = 0            // self-timer: 0, 3 or 10 seconds
    private var previewSurface: Surface? = null
    private lateinit var photoLabel: TextView
    private lateinit var videoLabel: TextView
    private lateinit var shutterDisc: View
    private lateinit var timerText: TextView
    private lateinit var countdown: TextView
    // Features borrowed from iOS (tap to focus + exposure slider), Samsung and Xiaomi (grid, HDR, filters, Pro mode).
    private var gridOn = false
    private var hdrOn = false
    private var effectIdx = 0
    private var evValue = 0.5f                 // 0..1, 0.5 = no exposure compensation
    private var focusRect: android.hardware.camera2.params.MeteringRectangle? = null
    private var proIso = 0.25f
    private var proShutter = 0.45f
    private lateinit var proLabel: TextView
    private lateinit var gridView: View
    private lateinit var focusRing: View
    private lateinit var evSlider: VerticalSlider
    private lateinit var proBox: LinearLayout
    private lateinit var toolGrid: TextView
    private lateinit var toolHdr: TextView
    private lateinit var toolFilter: TextView
    private lateinit var proIsoText: TextView
    private lateinit var proShutterText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        window.statusBarColor = 0; window.navigationBarColor = 0xFF000000.toInt()
        val w = resources.displayMetrics.widthPixels
        val viewH = w * 4 / 3

        tv = TextureView(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, width: Int, height: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(zoom * d.scaleFactor); return true }
        })
        val tap = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { focusAt(e.x, e.y); return true }
        })
        tv.setOnTouchListener { _, ev -> pinch.onTouchEvent(ev); if (!pinch.isInProgress) tap.onTouchEvent(ev); true }

        fun label(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400); gravity = Gravity.CENTER
        }
        flashIcon = ImageView(this).apply { setImageResource(R.drawable.ic_boost); setColorFilter(0xFFFFFFFF.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        flashText = label("Auto", 11f, 0xFFFFFFFF.toInt())
        val flash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(flashIcon, LinearLayout.LayoutParams(dp(40), dp(40))); addView(flashText)
            setOnClickListener { flashMode = (flashMode + 1) % 3; paintFlash() }
        }
        val menu = ImageView(this).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(0xFFFFFFFF.toInt()); setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Close"; setOnClickListener { finish() }
        }
        timerText = label("Timer off", 11f, 0xFFFFFFFF.toInt())
        val timer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ImageView(this@CameraActivity).apply { setImageResource(R.drawable.ic_history); setColorFilter(0xFFFFFFFF.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8)) },
                LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(timerText)
            setOnClickListener { timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }; timerText.text = if (timerSec == 0) "Timer off" else "${timerSec} s" }
        }
        val top = FrameLayout(this).apply {
            addView(flash, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(18) })
            addView(timer, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(78) })
            addView(label("AOS Camera", 14f, 0xFFFFFFFF.toInt(), true), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(menu, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(14) })
        }

        zoomOne = label("1×", 14f, 0xFFFFFFFF.toInt(), true).apply { setOnClickListener { setZoom(1f) } }
        zoomTwo = label("2", 14f, 0xFFFFFFFF.toInt(), true).apply { setOnClickListener { setZoom(2f) } }
        val zoomPill = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = shape(0x99000000.toInt(), 22); setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(zoomOne, LinearLayout.LayoutParams(dp(44), dp(36))); addView(zoomTwo, LinearLayout.LayoutParams(dp(44), dp(36)))
        }

        photoLabel = label("PHOTO", 15f, p.accent, true).apply { setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { setMode(0) } }
        videoLabel = label("VIDEO", 15f, 0xFFFFFFFF.toInt(), true).apply { setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { setMode(1) } }
        proLabel = label("PRO", 15f, 0xFFFFFFFF.toInt(), true).apply { setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { setMode(2) } }
        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER; addView(photoLabel); addView(videoLabel); addView(proLabel) }
        countdown = label("", 96f, 0xFFFFFFFF.toInt(), true).apply { visibility = View.GONE }

        // Tools row: grid lines, HDR and live filters.
        fun tool(t: String, onClick: () -> Unit) = label(t, 12f, 0xFFFFFFFF.toInt(), true).apply {
            background = shape(0x33FFFFFF, 16); setPadding(dp(14), dp(7), dp(14), dp(7)); setOnClickListener { onClick() }
        }
        toolGrid = tool("Grid") { gridOn = !gridOn; paintTools() }
        toolHdr = tool("HDR") { hdrOn = !hdrOn; paintTools(); updatePreview() }
        toolFilter = tool("Filter") { effectIdx = (effectIdx + 1) % effectList().size; paintTools(); updatePreview() }
        val tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            for (v in listOf(toolGrid, toolHdr, toolFilter)) addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5); marginEnd = dp(5) })
        }
        // Pro mode: manual ISO and shutter speed.
        proIsoText = label("ISO", 12f, 0xFFFFFFFF.toInt(), true)
        proShutterText = label("Shutter", 12f, 0xFFFFFFFF.toInt(), true)
        proBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(24), dp(4), dp(24), dp(4))
            addView(LinearLayout(this@CameraActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(proIsoText, LinearLayout.LayoutParams(dp(78), -2))
                addView(SegmentedBar(this@CameraActivity, p, 14) { v -> proIso = v; paintPro(); updatePreview() }.also { it.value = proIso }, LinearLayout.LayoutParams(0, dp(30), 1f))
            })
            addView(LinearLayout(this@CameraActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(proShutterText, LinearLayout.LayoutParams(dp(78), -2))
                addView(SegmentedBar(this@CameraActivity, p, 14) { v -> proShutter = v; paintPro(); updatePreview() }.also { it.value = proShutter }, LinearLayout.LayoutParams(0, dp(30), 1f))
            })
        }
        gridView = object : View(this) {
            private val line = android.graphics.Paint().apply { color = 0x66FFFFFF; strokeWidth = resources.displayMetrics.density }
            override fun onDraw(c: android.graphics.Canvas) {
                for (i in 1..2) { c.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), line); c.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, line) }
            }
        }.apply { visibility = View.GONE }
        focusRing = View(this).apply { background = circle(0x00000000, p.accent, 2); visibility = View.GONE }
        evSlider = VerticalSlider(this, p, R.drawable.ic_style) { v -> evValue = v; updatePreview() }.apply { value = 0.5f; visibility = View.GONE }

        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(0xFF262626.toInt(), 12); clipToOutline = true
            // The thumbnail opens our own Gallery.
            setOnClickListener { startActivity(Intent(this@CameraActivity, GalleryActivity::class.java)) }
        }
        shutterRing = View(this).apply { background = circle(0x00000000, 0xFFFFFFFF.toInt(), 4) }
        shutterDisc = View(this).apply { background = circle(0xFFFFFFFF.toInt()) }
        val shutter = FrameLayout(this).apply {
            addView(shutterRing, FrameLayout.LayoutParams(-1, -1))
            addView(shutterDisc, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
            setOnClickListener { onShutter() }
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> shutterDisc.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> shutterDisc.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
        }
        val flip = ImageView(this).apply {
            setImageResource(R.drawable.ic_flip); setColorFilter(0xFFFFFFFF.toInt()); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = circle(0xFF262626.toInt()); contentDescription = "Switch camera"
            setOnClickListener {
                facing = if (facing == CameraCharacteristics.LENS_FACING_BACK) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                zoom = 1f; closeCamera(); openCamera()
            }
        }
        val controls = FrameLayout(this).apply {
            addView(thumb, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(36) })
            addView(shutter, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
            addView(flip, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(36) })
        }

        val finder = FrameLayout(this).apply {
            addView(tv, FrameLayout.LayoutParams(-1, -1))
            addView(zoomPill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })
            addView(countdown, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(gridView, FrameLayout.LayoutParams(-1, -1))
            addView(focusRing, FrameLayout.LayoutParams(dp(64), dp(64)))
            addView(evSlider, FrameLayout.LayoutParams(dp(40), dp(150), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(10) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt()); setPadding(0, dp(30), 0, dp(22))
            addView(top, LinearLayout.LayoutParams(-1, dp(64)))
            addView(finder, LinearLayout.LayoutParams(w, viewH))
            addView(tools, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
            addView(proBox, LinearLayout.LayoutParams(-1, -2))
            addView(modes, LinearLayout.LayoutParams(-1, dp(46)))
            addView(View(this@CameraActivity), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, dp(96)))
            addView(View(this@CameraActivity), LinearLayout.LayoutParams(-1, 0, 1f))
        })
        paintFlash(); paintZoom(); paintTools(); paintPro()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    private fun circle(color: Int, stroke: Int? = null, strokeDp: Int = 2) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color); if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

    private fun paintFlash() {
        flashText.text = listOf("Off", "Auto", "On")[flashMode]
        flashIcon.setColorFilter(if (flashMode == 0) 0xFFAAAAAA.toInt() else p.accent)
    }

    private fun paintZoom() {
        val two = zoom >= 1.5f
        zoomOne.setTextColor(if (!two) p.accent else 0xFFFFFFFF.toInt()); zoomTwo.setTextColor(if (two) p.accent else 0xFFFFFFFF.toInt())
        zoomOne.text = if (!two) "${"%.1f".format(Locale.US, zoom).removeSuffix(".0")}×" else "1"
        zoomTwo.text = if (two) "${"%.1f".format(Locale.US, zoom).removeSuffix(".0")}×" else "2"
    }

    private fun maxZoom() = (chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtMost(8f)

    private fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, maxZoom()); paintZoom(); updatePreview()
    }

    private fun cropFor(z: Float): Rect? {
        val a = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val w = (a.width() / z).toInt(); val h = (a.height() / z).toInt()
        val x = a.left + (a.width() - w) / 2; val y = a.top + (a.height() - h) / 2
        return Rect(x, y, x + w, y + h)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 2) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) Toast.makeText(this, "Microphone access is needed for video sound", Toast.LENGTH_LONG).show()
            return
        }
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else { Toast.makeText(this, "Camera access is needed to take photos", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onResume() {
        super.onResume()
        thread = HandlerThread("aos-camera").also { it.start(); handler = Handler(it.looper) }
        if (tv.isAvailable) openCamera()
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(gyro, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        getSystemService(android.hardware.SensorManager::class.java).unregisterListener(gyro)
        closeCamera()
        thread?.quitSafely(); thread = null; handler = null
        super.onPause()
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        reader?.close()
        session = null; camera = null; reader = null
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || camera != null || handler == null) return
        try {
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
                ?: manager.cameraIdList.firstOrNull() ?: return
            val c = manager.getCameraCharacteristics(id); chars = c
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            preview = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width } ?: Size(1280, 960)
            // Full-resolution 4:3 stills (the sharpest the sensor offers up to ~32 MP); 4:3 matches the viewfinder.
            val jpeg = map.getOutputSizes(ImageFormat.JPEG).filter { it.width * 3 == it.height * 4 && it.width <= 6600 }.maxByOrNull { it.width } ?: Size(1280, 960)
            reader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { img ->
                    val buf = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()); buf.get(bytes); save(bytes)
                } }, handler)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { camera = d; startPreview() }
                override fun onDisconnected(d: CameraDevice) { d.close(); camera = null }
                override fun onError(d: CameraDevice, e: Int) { d.close(); camera = null }
            }, handler)
        } catch (e: CameraAccessException) { Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show() }
        catch (_: SecurityException) { }
    }

    private fun startPreview() {
        val cam = camera ?: return
        val st = tv.surfaceTexture ?: return
        st.setDefaultBufferSize(preview.width, preview.height)
        val surface = Surface(st)
        previewSurface = surface
        @Suppress("DEPRECATION")
        cam.createCaptureSession(listOf(surface, reader!!.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; updatePreview() }
            override fun onConfigureFailed(s: CameraCaptureSession) {}
        }, handler)
    }

    /** Optical stabilization when the lens has it, and electronic stabilization of the viewfinder when the camera offers it. */
    private fun stabilize(b: CaptureRequest.Builder, preview: Boolean, video: Boolean = false) {
        val c = chars ?: return
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (ois != null && ois.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON))
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        if (!preview) return
        val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        if (video && eis?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
            // Recording: electronic stabilization of the recorded stream (the strongest mode the camera offers).
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            return
        }
        when {
            android.os.Build.VERSION.SDK_INT >= 33 && eis?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true ->
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            eis?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true ->
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
    }

    // Steady shutter: the gyroscope tells how much the phone is moving; a shot waits (at most 0.45 s) until the hand settles.
    private var motion = 0f
    private val gyro = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val m = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()).toFloat()
            motion = motion * 0.6f + m * 0.4f
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private fun updatePreview() {
        val cam = camera ?: return; val s = session ?: return; val ps = previewSurface ?: return
        if (recording) return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(ps)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                cropFor(zoom)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                stabilize(this, true)
                applyCommon(this, false)
            }
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: Exception) { }
    }

    private var waiting = false

    private fun effectList(): List<Int> {
        val avail = chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)?.toList().orEmpty()
        return listOf(CameraMetadata.CONTROL_EFFECT_MODE_OFF) + listOf(CameraMetadata.CONTROL_EFFECT_MODE_MONO, CameraMetadata.CONTROL_EFFECT_MODE_SEPIA,
            CameraMetadata.CONTROL_EFFECT_MODE_AQUA, CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE, CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE).filter { it in avail }
    }

    private fun paintTools() {
        fun on(v: TextView, active: Boolean) { v.background = shape(if (active) p.accent else 0x33FFFFFF, 16); v.setTextColor(if (active) p.onAccent else 0xFFFFFFFF.toInt()) }
        on(toolGrid, gridOn); on(toolHdr, hdrOn); on(toolFilter, effectIdx > 0)
        val names = mapOf(CameraMetadata.CONTROL_EFFECT_MODE_MONO to "Mono", CameraMetadata.CONTROL_EFFECT_MODE_SEPIA to "Sepia",
            CameraMetadata.CONTROL_EFFECT_MODE_AQUA to "Aqua", CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE to "Poster", CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE to "Negative")
        toolFilter.text = effectList().getOrNull(effectIdx)?.let { names[it] } ?: "Filter"
        gridView.visibility = if (gridOn) View.VISIBLE else View.GONE
    }

    private fun paintPro() {
        val r = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val iso = r?.let { (it.lower + (minOf(it.upper, 3200) - it.lower) * proIso).toInt() }
        proIsoText.text = "ISO " + (iso ?: "")
        val er = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        proShutterText.text = er?.let {
            val lo = maxOf(it.lower, 100_000L).toDouble(); val hi = minOf(it.upper, 250_000_000L).toDouble()
            val ns = lo * Math.pow(hi / lo, proShutter.toDouble())
            if (ns >= 1e9 / 2) "%.1f s".format(Locale.US, ns / 1e9) else "1/" + Math.round(1e9 / ns)
        } ?: "Shutter"
    }

    /** Tap to focus: a ring where you tapped, the camera meters and focuses there, and an exposure slider appears at the side. */
    private fun focusAt(x: Float, y: Float) {
        val a = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val so = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val vw = tv.width.toFloat(); val vh = tv.height.toFloat()
        val sx: Float; val sy: Float
        if (so == 270) { sx = (1 - y / vh) * a.width(); sy = x / vw * a.height() } else { sx = y / vh * a.width(); sy = (1 - x / vw) * a.height() }
        val half = (0.08f * minOf(a.width(), a.height())).toInt()
        val left = (a.left + sx - half).toInt().coerceIn(a.left, a.right - 2 * half); val top = (a.top + sy - half).toInt().coerceIn(a.top, a.bottom - 2 * half)
        focusRect = android.hardware.camera2.params.MeteringRectangle(left, top, 2 * half, 2 * half, 999)
        focusRing.visibility = View.VISIBLE
        focusRing.x = x - dp(32) + (tv.parent as View).left; focusRing.y = y - dp(32)
        focusRing.scaleX = 1.5f; focusRing.scaleY = 1.5f; focusRing.alpha = 1f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction { focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction { focusRing.visibility = View.GONE; focusRing.alpha = 1f }.start() }.start()
        evSlider.visibility = View.VISIBLE; evSlider.alpha = 1f
        evSlider.animate().cancel(); evSlider.animate().alpha(0f).setStartDelay(3500).setDuration(400).withEndAction { evSlider.visibility = View.GONE }.start()
        updatePreview()
        try {
            val cam = camera ?: return; val ps = previewSurface ?: return
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(ps); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect)); set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            session?.capture(b.build(), null, handler)
        } catch (_: Exception) { }
    }

    /** Settings shared by the viewfinder and the photo: exposure compensation, HDR, filter, focus point and Pro manual exposure. */
    private fun applyCommon(b: CaptureRequest.Builder, still: Boolean) {
        val c = chars ?: return
        if (mode == 2) {
            val isoR = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE); val expR = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (isoR != null && expR != null) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, (isoR.lower + (minOf(isoR.upper, 3200) - isoR.lower) * proIso).toInt())
                val lo = maxOf(expR.lower, 100_000L).toDouble(); val hi = minOf(expR.upper, 250_000_000L).toDouble()
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (lo * Math.pow(hi / lo, proShutter.toDouble())).toLong())
            }
            return
        }
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (ev != null) {
            val t = (evValue - 0.5f) * 2f
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (if (t >= 0) t * ev.upper else t * -ev.lower).toInt().coerceIn(ev.lower, ev.upper))
        }
        val scenes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
        if (hdrOn && scenes?.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR) == true) {
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE); b.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
        } else effectList().getOrNull(effectIdx)?.takeIf { it != CameraMetadata.CONTROL_EFFECT_MODE_OFF }?.let { b.set(CaptureRequest.CONTROL_EFFECT_MODE, it) }
        if (!still) focusRect?.let { b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)); b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
    }

    private fun setMode(m: Int) {
        if (recording || m == mode) return
        mode = m
        photoLabel.setTextColor(if (m == 0) p.accent else 0xFFFFFFFF.toInt())
        videoLabel.setTextColor(if (m == 1) p.accent else 0xFFFFFFFF.toInt())
        proLabel.setTextColor(if (m == 2) p.accent else 0xFFFFFFFF.toInt())
        proBox.visibility = if (m == 2) View.VISIBLE else View.GONE
        paintPro(); updatePreview()
        shutterDisc.background = circle(if (m == 1) 0xFFE5484D.toInt() else 0xFFFFFFFF.toInt())
        if (m == 1 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    private fun onShutter() {
        if (mode == 1) { if (recording) stopVideo() else startWithTimer { startVideo() }; return }
        startWithTimer { takePhoto() }
    }

    /** Self-timer: shows a big countdown, then runs [go]. */
    private fun startWithTimer(go: () -> Unit) {
        if (timerSec == 0) { go(); return }
        var left = timerSec
        countdown.visibility = View.VISIBLE
        val tick = object : Runnable {
            override fun run() {
                if (left <= 0) { countdown.visibility = View.GONE; go(); return }
                countdown.text = left.toString(); left--
                tv.postDelayed(this, 1000)
            }
        }
        tick.run()
    }

    private fun takePhoto() {
        if (waiting) return
        waiting = true
        val start = android.os.SystemClock.uptimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (motion < 0.12f || android.os.SystemClock.uptimeMillis() - start > 450) { waiting = false; lockFocusThenShoot() }
                else tv.postDelayed(this, 30)
            }
        }
        poll.run()
    }

    /** Daytime sharpness: ask the lens to settle and lock focus before the exposure instead of shooting mid-hunt. */
    private fun lockFocusThenShoot() {
        val cam = camera ?: return; val s = session ?: return; val ps = previewSurface ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(ps)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                cropFor(zoom)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
            }
            s.capture(b.build(), null, handler)
            handler?.postDelayed({ shoot() }, 380)
        } catch (_: Exception) { shoot() }
    }

    private fun startVideo() {
        val cam = camera ?: return; val ps = previewSurface ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return }
        try {
            val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val vs = map.getOutputSizes(android.media.MediaRecorder::class.java).filter { it.width * 9 == it.height * 16 && it.width <= 1920 }.maxByOrNull { it.width } ?: Size(1280, 720)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "AOS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            lastUri = uri
            val fd = contentResolver.openFileDescriptor(uri, "w") ?: return
            videoFd = fd
            val rec = android.media.MediaRecorder(this).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fd.fileDescriptor)
                setVideoEncodingBitRate(14_000_000); setVideoFrameRate(30); setVideoSize(vs.width, vs.height)
                setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(128_000); setAudioSamplingRate(44100)
                setOrientationHint(chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
                prepare()
            }
            recorder = rec
            try { session?.close() } catch (_: Exception) {}
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(ps, rec.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(ps); addTarget(rec.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        cropFor(zoom)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                        stabilize(this, true, video = true)
                    }
                    s.setRepeatingRequest(b.build(), null, handler)
                    rec.start(); recording = true
                    runOnUiThread { shutterDisc.background = shape(0xFFE5484D.toInt(), 8); shutterDisc.scaleX = 0.6f; shutterDisc.scaleY = 0.6f }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, handler)
        } catch (e: Exception) { Toast.makeText(this, "Video failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun stopVideo() {
        val rec = recorder
        recording = false
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        recorder = null
        try { videoFd?.close() } catch (_: Exception) {}
        videoFd = null
        shutterDisc.scaleX = 1f; shutterDisc.scaleY = 1f; shutterDisc.background = circle(0xFFE5484D.toInt())
        Toast.makeText(this, "Video saved to DCIM/Camera", Toast.LENGTH_SHORT).show()
        try { session?.close() } catch (_: Exception) {}
        startPreview()
    }

    private fun shoot() {
        val cam = camera ?: return; val s = session ?: return; val r = reader ?: return
        val c = chars
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                // Best noise reduction and sharpening when the camera supports them (the default is a fast, softer pass).
                if (c?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                if (c?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) == true)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.CONTROL_AE_MODE, when (flashMode) {
                    2 -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    1 -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    else -> CaptureRequest.CONTROL_AE_MODE_ON })
                if (flashMode == 0) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.JPEG_ORIENTATION, chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
                cropFor(zoom)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                stabilize(this, false)
                applyCommon(this, true)
            }
            s.capture(b.build(), null, handler)
            // Back to continuous focus after the locked shot.
            handler?.postDelayed({
                try {
                    val cancel = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        previewSurface?.let { addTarget(it) }
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                    }
                    session?.capture(cancel.build(), null, handler)
                } catch (_: Exception) {}
                updatePreview()
            }, 700)
            shutterRing.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).withEndAction { shutterRing.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
        } catch (_: Exception) { }
    }

    private fun save(bytes: ByteArray) {
        val name = "AOS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        lastUri = uri
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        runOnUiThread { thumb.setImageBitmap(bmp); Toast.makeText(this, "Saved to DCIM/Camera", Toast.LENGTH_SHORT).show() }
    }
}
