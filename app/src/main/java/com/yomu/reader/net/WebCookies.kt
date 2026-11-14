package com.yomu.reader.net

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Cookies et sessions partagés entre le navigateur intégré et OkHttp.
 *
 * - Le CookieManager d'Android enregistre les cookies sur le disque : une connexion faite
 *   dans le navigateur est toujours là au prochain lancement de l'appli.
 * - Branché sur OkHttp ([Http.client]), il permet aussi aux requêtes de l'appli
 *   (chargement des pages, des images…) de réutiliser la session ouverte dans le navigateur.
 */
object WebCookies : CookieJar {

    /** Null si le composant WebView du système est absent ou désactivé. */
    private val manager: CookieManager? by lazy {
        runCatching { CookieManager.getInstance().apply { setAcceptCookie(true) } }.getOrNull()
    }

    val available: Boolean get() = manager != null

    /** À appeler sur chaque WebView créée. */
    fun attach(webView: WebView) {
        manager?.let {
            it.setAcceptCookie(true)
            // Nécessaire pour les connexions qui passent par un autre domaine (SSO, captcha…).
            it.setAcceptThirdPartyCookies(webView, true)
        }
    }

    /** Force l'écriture sur le disque (sinon Android le fait toutes les ~30 s). */
    fun flush() {
        runCatching { manager?.flush() }
    }

    /** Cookies bruts d'une page, au format « nom=valeur; nom2=valeur2 ». */
    fun rawFor(url: String): String? = runCatching { manager?.getCookie(url) }.getOrNull()

    /** Pose un cookie s'il n'existe pas encore (ex. cookies par défaut d'une extension). */
    fun seed(url: String, name: String, value: String) {
        val m = manager ?: return
        val existing = rawFor(url).orEmpty().split(";").map { it.substringBefore("=").trim() }
        if (name !in existing) m.setCookie(url, "$name=$value; Path=/")
    }

    /** Déconnecte d'un site : supprime ses cookies (domaine et sous-domaines). */
    fun clearFor(url: String): Int {
        val m = manager ?: return 0
        val http = url.toHttpUrlOrNull() ?: return 0
        val host = http.host
        // Le site peut avoir posé ses cookies sur « exemple.com » ou « .exemple.com ».
        val domains = buildSet {
            add(host)
            val parts = host.split('.')
            for (i in 1 until parts.size - 1) add(parts.drop(i).joinToString("."))
        }
        var removed = 0
        domains.forEach { domain ->
            val base = "https://$domain"
            val raw = m.getCookie(base) ?: return@forEach
            raw.split(";").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }.forEach { name ->
                m.setCookie(base, "$name=; Max-Age=-1; Path=/")
                m.setCookie(base, "$name=; Max-Age=-1; Path=/; Domain=.$domain")
                removed++
            }
        }
        flush()
        return removed
    }

    /** Efface toutes les sessions du navigateur : cookies + stockage local des sites. */
    fun clearAll(onDone: (Boolean) -> Unit = {}) {
        val m = manager ?: return onDone(false)
        runCatching { WebStorage.getInstance().deleteAllData() }
        m.removeAllCookies { ok ->
            flush()
            onDone(ok)
        }
    }

    // ---------------------------------------------------------- CookieJar (OkHttp)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = manager ?: return
        val target = url.toString()
        cookies.forEach { m.setCookie(target, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = rawFor(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
