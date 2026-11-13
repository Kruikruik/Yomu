package com.yomu.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.yomu.reader.BuildConfig
import com.yomu.reader.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException

data class AppRelease(val versionCode: Int, val versionName: String, val apkUrl: String)

/** Mise à jour de l'appli depuis la release « latest » du dépôt GitHub. */
object AppUpdater {
    const val REPO = "Kruikruik/yomu"

    /** Renvoie la release si elle est plus récente que la version installée, sinon null. */
    suspend fun check(): AppRelease? {
        val json = Http.getJson("https://api.github.com/repos/$REPO/releases/tags/latest".toHttpUrl())
        val body = json.optString("body")
        val code = Regex("versionCode[:=]\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val name = Regex("versionName[:=]\\s*([\\w.\\-]+)").find(body)?.groupValues?.get(1) ?: code.toString()
        val assets = json.optJSONArray("assets") ?: return null
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                apk = a.optString("browser_download_url")
                break
            }
        }
        val url = apk?.takeIf { it.isNotEmpty() } ?: return null
        return if (code > BuildConfig.VERSION_CODE) AppRelease(code, name, url) else null
    }

    suspend fun download(context: Context, release: AppRelease, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val file = File(dir, "Yomu.apk")
            val request = Request.Builder().url(release.apkUrl).build()
            Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Erreur HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Réponse vide")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
            }
            file
        }

    /** Lance l'installateur Android (demande l'autorisation « sources inconnues » si besoin). */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
