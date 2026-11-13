package com.yomu.reader.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Sauvegarde automatique quotidienne dans le dossier choisi (garde les 7 dernières). */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        backupNow(applicationContext)
        Result.success()
    } catch (e: Exception) {
        CrashLog.record("Sauvegarde automatique", e)
        Result.retry()
    }

    companion object {
        private const val WORK_NAME = "auto_backup"
        private const val PREFIX = "yomu-auto-"

        fun schedule(context: Context, replace: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            if (Prefs.backupTreeUri == null) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Écrit une sauvegarde maintenant. Renvoie le nom du fichier. */
        fun backupNow(context: Context): String {
            val tree = Prefs.backupTreeUri ?: error("Aucun dossier de sauvegarde choisi")
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: error("Dossier introuvable")
            val name = PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date()) + ".json"
            dir.findFile(name)?.delete()
            val file = dir.createFile("application/json", name) ?: error("Impossible de créer le fichier")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(Library.exportJson().toByteArray()) }
                ?: error("Écriture impossible")
            dir.listFiles()
                .filter { it.name.orEmpty().startsWith(PREFIX) }
                .sortedByDescending { it.name }
                .drop(7)
                .forEach { it.delete() }
            return name
        }
    }
}
