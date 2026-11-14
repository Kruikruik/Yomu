package com.yomu.reader.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Un lien enregistré par l'utilisateur (aucun site n'est fourni par l'appli). */
data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    /** Dossier libre pour ranger ses liens ("" = sans dossier). */
    val folder: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val lastVisited: Long = 0L,
)

/**
 * Transforme ce que l'utilisateur a tapé en adresse web valide.
 * « exemple.com/page » → « https://exemple.com/page ». Renvoie null si ce n'est pas une adresse http(s).
 */
fun parseUserUrl(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text else "https://$text"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.scheme != "http" && url.scheme != "https") return null
    // Il faut au moins un point dans le nom d'hôte (ou localhost / une IP).
    if (!url.host.contains('.') && url.host != "localhost" && !url.host.contains(':')) return null
    return url.toString()
}

/** Clé de comparaison : même lien même avec / sans « / » final, http ou https, www. ou pas. */
private fun sameUrlKey(url: String): String =
    url.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .trimEnd('/')

/**
 * Favoris et liens personnalisés, 100 % locaux.
 * Sauvegardés dans filesDir/bookmarks.json (écriture atomique, comme library.json).
 */
object Bookmarks {
    private const val VERSION = 1

    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val writeLock = Any()

    private val _items = MutableStateFlow<List<Bookmark>>(emptyList())
    val items: StateFlow<List<Bookmark>> = _items.asStateFlow()

    /** Dernière page ouverte dans le navigateur : permet de reprendre là où on s'était arrêté. */
    private val _lastUrl = MutableStateFlow<String?>(null)
    val lastUrl: StateFlow<String?> = _lastUrl.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "bookmarks.json")
        runCatching {
            if (file.exists()) fromJson(JSONObject(file.readText()), replace = true)
        }.onFailure { CrashLog.record("Bookmarks.init", it) }
    }

    // ------------------------------------------------------------ lecture

    fun find(url: String): Bookmark? {
        val key = sameUrlKey(url)
        return _items.value.firstOrNull { sameUrlKey(it.url) == key }
    }

    fun isBookmarked(url: String): Boolean = find(url) != null

    /** Dossiers existants, triés, pour proposer un choix dans l'éditeur. */
    fun folders(): List<String> = _items.value.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()

    // ------------------------------------------------------------ écriture

    /** Ajoute un favori (ou met à jour le titre s'il existe déjà). Renvoie null si l'URL est invalide. */
    fun add(title: String, url: String, folder: String = ""): Bookmark? {
        val clean = parseUserUrl(url) ?: return null
        val existing = find(clean)
        if (existing != null) {
            update(existing.id, title.ifBlank { existing.title }, clean, folder.ifBlank { existing.folder })
            return find(clean)
        }
        val b = Bookmark(title = title.trim().ifBlank { hostOf(clean) }, url = clean, folder = folder.trim())
        _items.update { it + b }
        save()
        return b
    }

    /** Renvoie false si la nouvelle URL est invalide. */
    fun update(id: String, title: String, url: String, folder: String): Boolean {
        val clean = parseUserUrl(url) ?: return false
        _items.update { list ->
            list.map { if (it.id == id) it.copy(title = title.trim().ifBlank { hostOf(clean) }, url = clean, folder = folder.trim()) else it }
        }
        save()
        return true
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** Ajoute ou retire la page courante des favoris. Renvoie true si elle est maintenant en favori. */
    fun toggle(title: String, url: String): Boolean {
        val existing = find(url)
        return if (existing != null) {
            remove(existing.id)
            false
        } else {
            add(title, url) != null
        }
    }

    /** À appeler à chaque page chargée : mémorise la dernière page et la date de visite des favoris. */
    fun onVisited(url: String) {
        if (parseUserUrl(url) == null) return
        _lastUrl.value = url
        val key = sameUrlKey(url)
        val now = System.currentTimeMillis()
        _items.update { list -> list.map { if (sameUrlKey(it.url) == key) it.copy(lastVisited = now) else it } }
        save()
    }

    // ------------------------------------------------------- import / export

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("lastUrl", _lastUrl.value ?: JSONObject.NULL)
        put("items", JSONArray().apply {
            _items.value.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id)
                    put("title", b.title)
                    put("url", b.url)
                    put("folder", b.folder)
                    put("addedAt", b.addedAt)
                    put("lastVisited", b.lastVisited)
                })
            }
        })
    }

    /** Exporte en texte (pour un fichier de sauvegarde ou un partage). */
    fun exportText(): String = toJson().toString(2)

    /** Importe un export : fusionne avec l'existant sans doublons. Renvoie le nombre de liens ajoutés. */
    fun importText(text: String): Int {
        val before = _items.value.size
        fromJson(JSONObject(text), replace = false)
        save()
        return _items.value.size - before
    }

    private fun fromJson(obj: JSONObject, replace: Boolean) {
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val parsed = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = parseUserUrl(o.optString("url")) ?: return@mapNotNull null
            Bookmark(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                title = o.optString("title").ifBlank { hostOf(url) },
                url = url,
                folder = o.optString("folder"),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                lastVisited = o.optLong("lastVisited", 0L),
            )
        }
        if (replace) {
            _items.value = parsed
            _lastUrl.value = obj.optString("lastUrl").takeIf { it.isNotBlank() && it != "null" }
        } else {
            _items.update { current ->
                val known = current.map { sameUrlKey(it.url) }.toMutableSet()
                current + parsed.filter { known.add(sameUrlKey(it.url)) }
                    .map { it.copy(id = UUID.randomUUID().toString()) }
            }
        }
    }

    // ------------------------------------------------------- persistance

    private fun save() {
        if (!::file.isInitialized) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val text = toJson().toString()
            synchronized(writeLock) {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
        }
    }
}

fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url
