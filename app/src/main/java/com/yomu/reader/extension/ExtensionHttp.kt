package com.yomu.reader.extension

import android.webkit.WebSettings
import com.yomu.reader.YomuApplication
import com.yomu.reader.net.Http
import com.yomu.reader.net.WebCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/**
 * Client réseau d'une extension : en-têtes personnalisés, limite de débit,
 * cookies persistants (partagés avec le navigateur intégré via [WebCookies]).
 */
class ExtensionHttp(private val c: ExtensionConfig) {
    private val mutex = Mutex()
    private var lastRequest = 0L
    @Volatile private var cookiesSeeded = false

    val userAgent: String by lazy {
        val ua = c.userAgent
        when {
            ua.isNullOrBlank() -> Http.USER_AGENT
            ua.equals("webview", ignoreCase = true) ->
                runCatching { WebSettings.getDefaultUserAgent(YomuApplication.instance) }.getOrDefault(Http.USER_AGENT)
            else -> ua
        }
    }

    private val baseVars = mapOf("baseUrl" to c.baseUrl)

    /** En-têtes de toutes les requêtes : User-Agent, Referer par défaut, puis ceux du fichier. */
    fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Referer", "${c.baseUrl}/")
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        (c.headers + extra).forEach { (k, v) -> put(k, fillTemplate(v, baseVars, encodeQuery = false)) }
    }

    /** En-têtes des images (lecteur, téléchargements). */
    fun imageHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Referer", "${c.baseUrl}/")
        c.imageHeaders.forEach { (k, v) -> put(k, fillTemplate(v, baseVars, encodeQuery = false)) }
    }

    /** Télécharge une page et la renvoie analysée par Jsoup (avec la bonne adresse de base pour les liens). */
    suspend fun document(spec: RequestSpec, vars: Map<String, String>): Document {
        val all = baseVars + vars
        val url = resolveUrl(c.baseUrl + "/", fillTemplate(spec.url, all))
            ?: throw IOException("Adresse invalide : ${spec.url}")
        val body = spec.body?.let { fillTemplate(it, all) }
        val (finalUrl, html) = fetch(url, spec.method, body, spec.contentType, spec.headers)
        return withContext(Dispatchers.Default) { Jsoup.parse(html, finalUrl) }
    }

    suspend fun document(url: String): Document = document(RequestSpec(url), emptyMap())

    private suspend fun fetch(
        url: String,
        method: String,
        body: String?,
        contentType: String,
        extraHeaders: Map<String, String>,
    ): Pair<String, String> {
        seedCookies()
        throttle()
        val allHeaders = headers(extraHeaders)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply { allHeaders.forEach { (k, v) -> header(k, v) } }
                .apply {
                    if (method == "POST") post((body ?: "").toRequestBody(contentType.toMediaTypeOrNull()))
                }
                .build()
            Http.client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 403 || resp.code == 503 -> throw IOException(
                        "Accès refusé par le site (HTTP ${resp.code}). Ouvre-le dans le navigateur intégré " +
                            "(connexion, vérification), puis réessaie.",
                    )
                    !resp.isSuccessful -> throw IOException("Erreur HTTP ${resp.code} sur $url")
                }
                resp.request.url.toString() to text
            }
        }
    }

    /** Respecte [ExtensionConfig.rateLimitMs] entre deux requêtes vers le site. */
    private suspend fun throttle() {
        if (c.rateLimitMs <= 0) return
        mutex.withLock {
            val wait = lastRequest + c.rateLimitMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequest = System.currentTimeMillis()
        }
    }

    private fun seedCookies() {
        if (cookiesSeeded) return
        cookiesSeeded = true
        c.cookies.forEach { (name, value) -> WebCookies.seed(c.baseUrl, name, value) }
    }
}
