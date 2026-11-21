package org.anthezis.launcher

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * The AOS Control Center over any app: the same [ControlCenter] the Home shows, in a transparent window, so a swipe down from
 * the top edge (SplitService) opens it right over the app you are in instead of switching to the Home first. It is never shown
 * over the lock screen: there the system shade stays in charge (SplitService lets the touch through while the phone is locked).
 */
class ControlCenterActivity : Activity() {
    private lateinit var cc: ControlCenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Palette(this)
        cc = ControlCenter(this, p) { open ->
            // Closed (tap outside, back, a tile that opens an app): leave once its fade-out is done.
            if (!open) window.decorView.postDelayed({ if (!isFinishing) { finish(); overridePendingTransition(0, 0) } }, 140)
        }
        setContentView(FrameLayout(this).apply { addView(cc.view, FrameLayout.LayoutParams(-1, -1)) })
        val panel = intent?.getIntExtra(EXTRA_PANEL, 0) ?: 0
        window.decorView.post { cc.show(panel) }
    }

    override fun onPause() {
        super.onPause()
        // Another app came over it (a tile opened Settings or the camera): the panel is done.
        if (!isChangingConfigurations) finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (cc.isOpen) cc.close() else super.onBackPressed() }

    companion object {
        /** 1 = notifications, 0 = controls (the same numbers as [ControlCenter.show]). */
        const val EXTRA_PANEL = "panel"
    }
}
