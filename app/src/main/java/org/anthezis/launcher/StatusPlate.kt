package org.anthezis.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Date

/**
 * The glass status plate above the dock: time, date, Wi-Fi strength, operator with signal bars, battery level.
 * Live: it listens to the system (network, signal, battery, time) while it is on screen and redraws only on a change;
 * nothing polls. Detached, it unregisters everything.
 */
class StatusPlate(ctx: Context, private val p: Palette) : LinearLayout(ctx) {
    /** Set by Home: the time and date open the calendar panel (the right part keeps opening the Control Center). */
    var onCalendar: (() -> Unit)? = null
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val wm = ctx.getApplicationContext().getSystemService(WifiManager::class.java)

    private fun text(sp: Float, c: Int, w: Int) = TextView(context).apply { textSize = sp; setTextColor(c); typeface = appFont(w); maxLines = 1 }
    private val date = text(13f, p.sub, 400)
    private val wifi = Bars(ctx, wifi = true)
    private val signal = Bars(ctx, wifi = false)
    private val operator = text(13f, p.text, 400).apply { maxWidth = context.dp(110); ellipsize = android.text.TextUtils.TruncateAt.END }
    private val battery = Battery(ctx)
    private val percent = text(13f, p.text, 700)

    init {
        gravity = Gravity.CENTER_VERTICAL
        background = ctx.glassShape(p, 20); setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        addView(TextClock(ctx).apply { format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 15f; setTextColor(p.text); typeface = appFont(700) })
        addView(date, LayoutParams(-2, -2).apply { marginStart = ctx.dp(8) })
        addView(View(ctx).apply { setBackgroundColor((p.sub and 0xFFFFFF) or 0x55000000) }, LayoutParams(ctx.dp(1), ctx.dp(16)).apply { marginStart = ctx.dp(12); marginEnd = ctx.dp(12) })
        addView(wifi, LayoutParams(ctx.dp(16), ctx.dp(14)))
        addView(signal, LayoutParams(ctx.dp(15), ctx.dp(13)).apply { marginStart = ctx.dp(10) })
        addView(operator, LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(5) })   // the operator gives way first
        addView(battery, LayoutParams(ctx.dp(22), ctx.dp(11)).apply { marginStart = ctx.dp(10) })
        addView(percent, LayoutParams(-2, -2).apply { marginStart = ctx.dp(5) })
        operator.text = "…"
        // The time and the date open a small month calendar above them.
        for (i in 0..1) getChildAt(i).apply { isClickable = true; setOnClickListener { onCalendar?.invoke() ?: showCalendar(this@StatusPlate) } }
    }

    /** Mini calendar: month title with ‹ ›, weekday header (Monday first), the days with today in the accent. */
    private fun showCalendar(anchor: View) {
        val ctx = context
        val pop = android.widget.PopupWindow(ctx)
        val cal = java.util.Calendar.getInstance()
        val todayY = cal.get(java.util.Calendar.YEAR); val todayM = cal.get(java.util.Calendar.MONTH); val todayD = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val box = LinearLayout(ctx).apply {
            orientation = VERTICAL; background = ctx.glassShape(p, 24, true); setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(14))
        }
        val cellW = ctx.dp(38)
        fun paint() {
            box.removeAllViews()
            val title = text(15f, p.text, 700).apply {
                text = DateFormat.format("LLLL yyyy", cal.time).toString().replaceFirstChar { it.uppercase() }; gravity = Gravity.CENTER
            }
            fun nav(t: String, d: Int) = text(18f, p.accent, 700).apply {
                text = t; gravity = Gravity.CENTER; setOnClickListener { cal.add(java.util.Calendar.MONTH, d); paint() }
            }
            box.addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(nav("‹", -1), LayoutParams(ctx.dp(40), ctx.dp(36)))
                addView(title, LayoutParams(0, -2, 1f))
                addView(nav("›", 1), LayoutParams(ctx.dp(40), ctx.dp(36)))
            }, LayoutParams(cellW * 7, -2))
            val grid = android.widget.GridLayout(ctx).apply { columnCount = 7 }
            for (wd in listOf("M", "T", "W", "T", "F", "S", "S")) grid.addView(text(11f, p.sub, 700).apply { text = wd; gravity = Gravity.CENTER },
                android.widget.GridLayout.LayoutParams().apply { width = cellW; height = ctx.dp(24) })
            val first = (cal.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }
            val offset = (first.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
            for (k in 0 until offset) grid.addView(View(ctx), android.widget.GridLayout.LayoutParams().apply { width = cellW; height = ctx.dp(34) })
            val isThisMonth = cal.get(java.util.Calendar.YEAR) == todayY && cal.get(java.util.Calendar.MONTH) == todayM
            for (d in 1..cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)) {
                val today = isThisMonth && d == todayD
                grid.addView(text(13f, if (today) p.onAccent else p.text, if (today) 700 else 400).apply {
                    text = "$d"; gravity = Gravity.CENTER; if (today) background = ctx.circle(p.accent)
                }, android.widget.GridLayout.LayoutParams().apply { width = cellW; height = ctx.dp(34) })
            }
            box.addView(grid)
        }
        paint()
        pop.contentView = box; pop.isFocusable = true; pop.isOutsideTouchable = true
        pop.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); pop.elevation = ctx.dp(8).toFloat()
        box.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        pop.showAsDropDown(anchor, 0, -(anchor.height + box.measuredHeight + ctx.dp(8)))
    }

    // ---- live sources ----
    private val battReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0) return
            val pct = level * 100 / scale
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            percent.text = "$pct%"
            battery.set(pct, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        }
    }
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { date.text = DateFormat.format("EEE, d MMM", Date()) }
    }
    private val wifiCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            val lvl = wm?.calculateSignalLevel(caps.signalStrength) ?: 2
            post { wifi.set(lvl.coerceIn(0, 4), true) }
        }
        override fun onLost(n: Network) { post { wifi.set(0, false) } }
    }
    private val signalCallback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(s: SignalStrength) { post { readSim(s.level) } }
    }

    private fun readSim(level: Int?) {
        val ready = tm?.simState == TelephonyManager.SIM_STATE_READY
        signal.set(if (ready) (level ?: tm?.signalStrength?.level ?: 0) else 0, ready)
        operator.text = tm?.networkOperatorName.orEmpty().ifBlank { if (ready) "SIM" else "No SIM" }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val c = context
        c.registerReceiver(battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battReceiver.onReceive(c, it) }
        c.registerReceiver(timeReceiver, IntentFilter().apply { addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_LOCALE_CHANGED) })
        timeReceiver.onReceive(c, Intent())
        wifi.set(0, false)
        try { cm?.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback) } catch (_: Exception) {}
        readSim(null)
        try { tm?.registerTelephonyCallback(c.mainExecutor, signalCallback) } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        val c = context
        try { c.unregisterReceiver(battReceiver) } catch (_: Exception) {}
        try { c.unregisterReceiver(timeReceiver) } catch (_: Exception) {}
        try { cm?.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) {}
        try { tm?.unregisterTelephonyCallback(signalCallback) } catch (_: Exception) {}
        battery.set(battery.level, false)   // stop the charging pulse
        super.onDetachedFromWindow()
    }

    /** Signal bars (cellular: four rising bars) or a Wi-Fi fan (three arcs and a dot); lit up to the level, animated. */
    private inner class Bars(c: Context, private val wifi: Boolean) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var level = 0f
        private var on = false
        fun set(l: Int, connected: Boolean) {
            on = connected
            android.animation.ValueAnimator.ofFloat(level, l.toFloat()).apply {
                duration = Fx.dur(260); addUpdateListener { level = it.animatedValue as Float; invalidate() }
            }.start()
        }
        private fun col(i: Int) = if (on && level >= i - 0.5f) p.text else (p.sub and 0xFFFFFF) or 0x55000000
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (wifi) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = h * 0.13f; paint.strokeCap = Paint.Cap.ROUND
                for (i in 1..3) {
                    val r = h * (0.28f + 0.26f * i)
                    paint.color = col(i + 1)
                    c.drawArc(RectF(w / 2 - r, h - r, w / 2 + r, h + r), 225f, 90f, false, paint)
                }
                paint.style = Paint.Style.FILL; paint.color = col(1)
                c.drawCircle(w / 2, h - h * 0.12f, h * 0.12f, paint)
            } else {
                paint.style = Paint.Style.FILL
                val bw = w / 5.2f
                for (i in 0 until 4) {
                    paint.color = col(i + 1)
                    val bh = h * (0.3f + 0.7f * i / 3f); val x = i * (bw * 1.4f)
                    c.drawRoundRect(RectF(x, h - bh, x + bw, h), bw / 3, bw / 3, paint)
                }
            }
        }
    }

    /** Battery with its real fill: accent while charging (and a pulsing bolt), red when low. */
    private inner class Battery(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var level = 0; private set
        private var shown = 0f
        private var charging = false
        private var pulse = 1f
        private var pulser: android.animation.ValueAnimator? = null
        fun set(pct: Int, chg: Boolean) {
            level = pct
            android.animation.ValueAnimator.ofFloat(shown, pct.toFloat()).apply {
                duration = Fx.dur(400); addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            }.start()
            if (chg != charging || (chg && pulser == null)) {
                charging = chg
                pulser?.cancel(); pulser = null; pulse = 1f
                if (chg && isAttachedToWindow) pulser = android.animation.ValueAnimator.ofFloat(0.35f, 1f).apply {
                    duration = 1100; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
                    addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val s = h * 0.12f
            val body = RectF(s / 2, s / 2, w - h * 0.22f - s / 2, h - s / 2)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = s; paint.color = (p.text and 0xFFFFFF) or 0xAA000000.toInt()
            c.drawRoundRect(body, h * 0.25f, h * 0.25f, paint)
            paint.style = Paint.Style.FILL
            c.drawRoundRect(RectF(body.right + s, h * 0.32f, w, h * 0.68f), s, s, paint)
            val fill = when { charging -> p.accent; level <= 15 -> 0xFFE5484D.toInt(); else -> p.text }
            paint.color = fill
            val inner = RectF(body.left + s * 1.4f, body.top + s * 1.4f, body.right - s * 1.4f, body.bottom - s * 1.4f)
            c.drawRoundRect(RectF(inner.left, inner.top, inner.left + inner.width() * (shown / 100f).coerceIn(0.04f, 1f), inner.bottom), s, s, paint)
            if (charging) {
                // Bolt in the middle, breathing while the phone charges.
                paint.color = (p.onAccent and 0xFFFFFF) or ((pulse * 255).toInt() shl 24)
                val cx = body.centerX(); val cy = body.centerY(); val k = h * 0.36f
                val bolt = android.graphics.Path().apply {
                    moveTo(cx + k * 0.15f, cy - k); lineTo(cx - k * 0.55f, cy + k * 0.15f); lineTo(cx - k * 0.02f, cy + k * 0.15f)
                    lineTo(cx - k * 0.15f, cy + k); lineTo(cx + k * 0.55f, cy - k * 0.15f); lineTo(cx + k * 0.02f, cy - k * 0.15f); close()
                }
                c.drawPath(bolt, paint)
            }
        }
        override fun onDetachedFromWindow() { pulser?.cancel(); pulser = null; super.onDetachedFromWindow() }
    }
}
