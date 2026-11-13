package com.yomu.reader.data

import android.content.Context
import android.os.Build
import com.yomu.reader.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal des plantages et erreurs : enregistré sur le téléphone, consultable et copiable
 * depuis Plus → Journal des erreurs (pour me l'envoyer).
 */
object CrashLog {
    private const val MAX_SIZE = 64 * 1024
    private lateinit var file: File
    private val format = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)

    fun install(context: Context) {
        file = File(context.filesDir, "crash-log.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write("PLANTAGE (${thread.name})", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Erreur non bloquante à garder pour le diagnostic. */
    fun record(where: String, error: Throwable) {
        runCatching { write(where, error) }
    }

    @Synchronized
    private fun write(title: String, error: Throwable) {
        if (!::file.isInitialized) return
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().lines().take(40).joinToString("\n")
        val entry = "── ${format.format(Date())} • $title\n$trace\n\n"
        val old = if (file.exists()) file.readText() else ""
        file.writeText((old + entry).takeLast(MAX_SIZE))
    }

    fun read(): String {
        if (!::file.isInitialized || !file.exists()) return ""
        val header = "Yomu ${BuildConfig.VERSION_NAME} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}\n\n"
        return header + file.readText()
    }

    fun hasEntries(): Boolean = ::file.isInitialized && file.exists() && file.length() > 0

    fun clear() {
        if (::file.isInitialized) file.delete()
    }
}
