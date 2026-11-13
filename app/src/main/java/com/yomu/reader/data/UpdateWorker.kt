package com.yomu.reader.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yomu.reader.MainActivity
import com.yomu.reader.R
import java.util.concurrent.TimeUnit

/** Vérifie en arrière-plan les nouveaux chapitres des séries de la bibliothèque. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = try {
            Library.refreshAll()
        } catch (e: Exception) {
            return Result.retry()
        }
        if (result.newChapters > 0) notifyNew(applicationContext, result)
        return Result.success()
    }

    companion object {
        const val CHANNEL = "updates"
        private const val WORK_NAME = "library_update"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Nouveaux chapitres", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        fun schedule(context: Context, replace: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            val hours = Prefs.updateInterval
            if (hours <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        private fun notifyNew(context: Context, result: RefreshResult) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_TAB, "updates")
            val pending = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = result.mangas.joinToString(", ")
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    if (result.newChapters == 1) "1 nouveau chapitre" else "${result.newChapters} nouveaux chapitres"
                )
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(1, notification)
            } catch (_: SecurityException) {
            }
        }
    }
}
