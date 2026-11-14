package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.WebSettings
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Client réseau fourni aux extensions Tachiyomi/Mihon (API publique identique à celle de Mihon).
 * Les cookies sont ceux du navigateur intégré de Yomu (CookieManager d'Android).
 *
 * Contrairement à Mihon, aucune résolution automatique des vérifications anti-robot :
 * si un site en demande une, l'utilisateur l'ouvre dans le navigateur intégré.
 */
class NetworkHelper(private val context: Context) {

    val cookieJar = AndroidCookieJar()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30.seconds)
            .readTimeout(30.seconds)
            .callTimeout(2.minutes)
            .cache(Cache(File(context.cacheDir, "tachi_network_cache"), 5L * 1024 * 1024))
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            .build()
    }

    /** @deprecated depuis extensions-lib 1.5 */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient get() = client

    private val userAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrDefault("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
    }

    /** Même user-agent que le navigateur intégré : une session ouverte là-bas reste valable ici. */
    fun defaultUserAgentProvider(): String = userAgent
}
