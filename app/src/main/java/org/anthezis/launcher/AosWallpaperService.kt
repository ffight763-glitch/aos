package org.anthezis.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * AOS animated theme as a system live wallpaper (for phones that have a live-wallpaper picker): draws the chosen AOS style and
 * animates it with [SceneAnimator]. Phones without the picker get the same animation inside the launcher ([SceneFx]).
 */
class AosWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = AosEngine()

    private inner class AosEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val t0 = SystemClock.uptimeMillis()
        private val anim = SceneAnimator()
        private var base: Bitmap? = null
        private var w = 1080; private var h = 2400
        private var style = ""
        private var dark = true
        private var visible = false
        private var tiltX = 0f
        private var tiltY = 0f
        private val tiltMotion = TiltMotion(this@AosWallpaperService) { x, y -> tiltX = x; tiltY = y }

        private val tick = object : Runnable {
            override fun run() { draw(); if (visible) handler.postDelayed(this, 33) }
        }

        private fun loadBase() {
            val s = AosStyles.selectedId(this@AosWallpaperService).let { if (it == "custom") AosStyles.DEFAULT else it }
            val d = Palette(this@AosWallpaperService).dark
            if (base != null && s == style && d == dark) return
            val changed = s != style || d != dark
            style = s; dark = d
            if (changed) notifyColorsChanged()   // Material You (Gboard, system panels) re-reads the theme colours
            val scene = AosStyles.bitmap(AosStyles.find(s), d)
                ?: Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.parseColor(if (d) "#10161F" else "#E8EEF8")) }
            base?.recycle()
            base = Bitmap.createScaledBitmap(scene, w, h, true).also { if (it !== scene) scene.recycle() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            w = width; h = height; base = null; loadBase(); draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(tick)
            if (v) { Fx.refresh(this@AosWallpaperService); loadBase(); tiltMotion.start(); handler.post(tick) }
            else tiltMotion.stop()
        }

        /**
         * The theme's own colours for Material You, so apps with dynamic colour (Gboard "Dynamic color", the system panels)
         * take the AOS accent and the light/dark tone of the scene. The theme accent is used as is, not the dynamic mix of
         * [Palette], which itself reads the system accent and would chase its own tail.
         */
        override fun onComputeColors(): android.app.WallpaperColors {
            val st = AosStyles.find(AosStyles.selectedId(this@AosWallpaperService).let { if (it == "custom") AosStyles.DEFAULT else it })
            val p = Palette(this@AosWallpaperService)
            val accent = Color.parseColor(if (p.uiDark) st.accentDark else st.accentLight)
            return android.app.WallpaperColors(Color.valueOf(accent), Color.valueOf(p.bg), Color.valueOf(p.surface),
                if (p.uiDark) 0 else android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT)
        }

        override fun onDestroy() { tiltMotion.stop(); handler.removeCallbacks(tick); base?.recycle(); base = null; super.onDestroy() }

        private fun draw() {
            val b = base ?: return
            val holder = surfaceHolder
            val c = holder.lockCanvas() ?: return
            try {
                c.save()
                if (Fx.on(this@AosWallpaperService, "tilt")) {
                    c.translate(-tiltX * w * 0.025f, -tiltY * h * 0.025f)
                    c.scale(1.06f, 1.06f, w / 2f, h / 2f)
                }
                c.drawBitmap(b, 0f, 0f, null)
                c.save(); c.scale(w / 1080f, h / 2400f)
                anim.draw(c, (SystemClock.uptimeMillis() - t0) / 1000f, style, dark)
                c.restore()
                c.restore()
            } finally { holder.unlockCanvasAndPost(c) }
        }
    }
}
