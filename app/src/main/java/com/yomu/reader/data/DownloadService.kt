package com.yomu.reader.data

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
import com.yomu.reader.MainActivity
import com.yomu.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Garde les téléchargements actifs quand l'appli est fermée ou en arrière-plan,
 * avec une notification de progression.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val first = build("Préparation des téléchargements…", 0, 0)
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, first,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            CrashLog.record("Service de téléchargement", e)
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            Downloads.queue.collectLatest { queue ->
                val active = queue.filter { it.state != DownloadState.ERROR }
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val current = active.firstOrNull { it.state == DownloadState.RUNNING } ?: active.first()
                val text = "${current.mangaTitle} • ${current.chapter.title}"
                val title = if (active.size == 1) "Téléchargement en cours" else "${active.size} chapitres à télécharger"
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID, build("$title\n$text", current.done, current.total))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "more"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lines = text.split("\n")
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lines.first())
            .setContentText(lines.getOrNull(1).orEmpty())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(total, done, total == 0)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "downloads"
        private const val NOTIF_ID = 2

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                // Démarrage refusé en arrière-plan : les téléchargements continuent tant que l'appli vit.
                CrashLog.record("Démarrage du service de téléchargement", e)
            }
        }
    }
}
