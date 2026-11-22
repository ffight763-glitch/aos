package com.dozy.pocketai.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dozy.pocketai.MainActivity
import com.dozy.pocketai.PocketApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps model downloads alive in the background and shows their progress. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузка моделей", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private var watching = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, ID, build("Подготовка загрузки…", 0, true),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        if (watching) return START_NOT_STICKY
        watching = true
        scope.launch {
            (application as PocketApp).models.downloads.collectLatest { map ->
                if (map.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val done = map.values.sumOf { it.bytes }
                val total = map.values.sumOf { it.total }
                val speed = map.values.sumOf { it.bytesPerSec }
                val title = if (map.size == 1) map.keys.first() else "Моделей: ${map.size}"
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                val text = "$title · ${done shr 20}/${total shr 20} МБ · ${"%.1f".format(speed / 1048576.0)} МБ/с"
                nm.notify(ID, build(text, pct, total <= 0))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, pct: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PocketAI: загрузка модели")
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 42

        fun start(context: Context) {
            // background starts can be refused by Android; the download itself still runs
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
