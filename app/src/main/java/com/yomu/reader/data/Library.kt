package com.yomu.reader.data

import android.content.Context
import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.source.SourceManager
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class LibraryEntry(val manga: Manga, val addedAt: Long, val unread: Int = 0)

data class Progress(
    val read: Set<String> = emptySet(),
    val lastChapterId: String? = null,
    val lastPage: Int = 0,
    val lastReadAt: Long = 0L,
)

data class UpdateItem(val key: String, val chapterId: String, val chapterTitle: String, val foundAt: Long)

data class RefreshResult(val newChapters: Int, val mangas: List<String>)

fun splitKey(key: String): Pair<String, String> = key.substringBefore("::") to key.substringAfter("::")

/**
 * Bibliothèque, progression, historique et nouveautés.
 * Sauvegardé dans library.json ; les listes de chapitres des séries suivies dans chapters.json.
 */
object Library {
    private const val MAX_UPDATES = 400

    private lateinit var file: File
    private lateinit var chaptersFile: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var chaptersSaveJob: Job? = null
    private val writeLock = Any()
    private val refreshMutex = Mutex()

    private val _entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    /** Infos des séries lues (pour l'historique, même hors bibliothèque). */
    private val _mangas = MutableStateFlow<Map<String, Manga>>(emptyMap())

    private val _updates = MutableStateFlow<List<UpdateItem>>(emptyList())
    val updates: StateFlow<List<UpdateItem>> = _updates.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val storedChapters = ConcurrentHashMap<String, List<Chapter>>()

    fun init(context: Context) {
        file = File(context.filesDir, "library.json")
        chaptersFile = File(context.filesDir, "chapters.json")
        if (file.exists()) runCatching { load(JSONObject(file.readText())) }
        if (chaptersFile.exists()) runCatching { loadChapters(JSONObject(chaptersFile.readText())) }
        storedChapters.forEach { (k, v) -> ChapterCache.put(k, v) }
    }

    // ------------------------------------------------------------ lecture

    fun get(key: String): LibraryEntry? = _entries.value.firstOrNull { it.manga.key == key }

    fun contains(key: String): Boolean = get(key) != null

    fun mangaFor(key: String): Manga? = get(key)?.manga ?: _mangas.value[key] ?: MangaCache.get(key)

    fun chaptersFor(key: String): List<Chapter>? = ChapterCache.get(key) ?: storedChapters[key]

    /** Chapitre à ouvrir avec « Continuer ». La liste va du plus récent au plus ancien. */
    fun resumeChapter(key: String, list: List<Chapter>? = chaptersFor(key)): Chapter? {
        if (list.isNullOrEmpty()) return null
        val p = _progress.value[key] ?: Progress()
        val idx = list.indexOfFirst { it.id == p.lastChapterId }
        if (idx >= 0) {
            if (list[idx].id !in p.read) return list[idx]
            if (idx > 0) return list[idx - 1]
            return list[idx]
        }
        return list.lastOrNull { it.id !in p.read } ?: list.first()
    }

    // ------------------------------------------------------- bibliothèque

    fun toggle(manga: Manga, chapters: List<Chapter>?) {
        if (contains(manga.key)) {
            _entries.update { list -> list.filterNot { it.manga.key == manga.key } }
            storedChapters.remove(manga.key)
        } else {
            _entries.update { list -> list + LibraryEntry(manga, System.currentTimeMillis()) }
            rememberManga(manga)
            if (chapters != null) {
                storedChapters[manga.key] = chapters
                ChapterCache.put(manga.key, chapters)
            }
            recomputeUnread(manga.key)
        }
        saveChapters()
        save()
    }

    fun updateManga(manga: Manga) {
        MangaCache.put(manga)
        if (_mangas.value.containsKey(manga.key)) rememberManga(manga)
        if (!contains(manga.key)) return
        _entries.update { list -> list.map { if (it.manga.key == manga.key) it.copy(manga = manga) else it } }
        save()
    }

    private fun rememberManga(manga: Manga) {
        if (_mangas.value[manga.key] != manga) _mangas.update { it + (manga.key to manga) }
    }

    /** Enregistre une nouvelle liste de chapitres. Renvoie le nombre de nouveaux chapitres. */
    fun updateChapters(key: String, chapters: List<Chapter>): Int {
        ChapterCache.put(key, chapters)
        if (!contains(key)) return 0
        val previous = storedChapters[key]
        storedChapters[key] = chapters
        var fresh = emptyList<Chapter>()
        if (previous != null) {
            val old = previous.mapTo(HashSet()) { it.id }
            fresh = chapters.filter { it.id !in old }
            if (fresh.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val items = fresh.map { UpdateItem(key, it.id, it.title, now) }
                _updates.update { list -> (items + list).distinctBy { it.key + "|" + it.chapterId }.take(MAX_UPDATES) }
            }
        }
        recomputeUnread(key)
        saveChapters()
        save()
        return fresh.size
    }

    private fun recomputeUnread(key: String) {
        val chapters = storedChapters[key] ?: ChapterCache.get(key) ?: return
        val read = _progress.value[key]?.read ?: emptySet()
        val unread = chapters.count { it.id !in read }
        _entries.update { list -> list.map { if (it.manga.key == key) it.copy(unread = unread) else it } }
    }

    suspend fun refreshAll(): RefreshResult = refreshMutex.withLock {
        _refreshing.value = true
        var total = 0
        val names = mutableListOf<String>()
        try {
            for (entry in _entries.value) {
                val source = SourceManager.get(entry.manga.sourceId) ?: continue
                try {
                    val n = updateChapters(entry.manga.key, source.chapters(entry.manga.id))
                    if (n > 0) {
                        total += n
                        names += entry.manga.title
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // série suivante
                }
                delay(250) // reste gentil avec l'API
            }
        } finally {
            _refreshing.value = false
        }
        RefreshResult(total, names)
    }

    fun clearUpdates() {
        _updates.value = emptyList()
        save()
    }

    // ---------------------------------------------------------- lecture

    fun setRead(key: String, chapterIds: Collection<String>, read: Boolean) {
        val ids = chapterIds.toSet()
        _progress.update { map ->
            val p = map[key] ?: Progress()
            map + (key to p.copy(read = if (read) p.read + ids else p.read - ids))
        }
        recomputeUnread(key)
        save()
    }

    fun saveProgress(key: String, chapterId: String, page: Int, total: Int) {
        val finished = total > 0 && page >= total - 1
        _progress.update { map ->
            val p = map[key] ?: Progress()
            map + (key to p.copy(
                lastChapterId = chapterId,
                lastPage = page,
                lastReadAt = System.currentTimeMillis(),
                read = if (finished) p.read + chapterId else p.read,
            ))
        }
        mangaFor(key)?.let { rememberManga(it) }
        if (finished) recomputeUnread(key)
        save()
    }

    fun removeFromHistory(key: String) {
        _progress.update { map ->
            val p = map[key] ?: return@update map
            map + (key to p.copy(lastReadAt = 0L))
        }
        save()
    }

    fun clearHistory() {
        _progress.update { map -> map.mapValues { it.value.copy(lastReadAt = 0L) } }
        save()
    }

    // ------------------------------------------------------- sauvegarde

    fun exportJson(): String = toJson().toString(2)

    /** Fusionne une sauvegarde avec les données actuelles. Renvoie le nombre de séries ajoutées. */
    fun importJson(text: String): Int {
        val json = JSONObject(text)
        val imported = parseEntries(json)
        val existing = _entries.value.map { it.manga.key }.toSet()
        val added = imported.filter { it.manga.key !in existing }
        _entries.update { it + added }
        val importedProgress = parseProgress(json)
        _progress.update { map ->
            val out = map.toMutableMap()
            importedProgress.forEach { (k, p) ->
                val cur = out[k]
                out[k] = if (cur == null) p else cur.copy(
                    read = cur.read + p.read,
                    lastChapterId = if (p.lastReadAt > cur.lastReadAt) p.lastChapterId else cur.lastChapterId,
                    lastPage = if (p.lastReadAt > cur.lastReadAt) p.lastPage else cur.lastPage,
                    lastReadAt = maxOf(p.lastReadAt, cur.lastReadAt),
                )
            }
            out
        }
        _mangas.update { it + parseMangas(json) }
        _entries.value.forEach { recomputeUnread(it.manga.key) }
        save()
        return added.size
    }

    // ------------------------------------------------------- persistance

    private fun writeAtomic(target: File, text: String) {
        synchronized(writeLock) {
            val tmp = File(target.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }
    }

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            writeAtomic(file, toJson().toString())
        }
    }

    private fun saveChapters() {
        chaptersSaveJob?.cancel()
        chaptersSaveJob = scope.launch {
            delay(1000)
            val obj = JSONObject()
            val keys = _entries.value.map { it.manga.key }.toSet()
            storedChapters.forEach { (k, list) ->
                if (k in keys) {
                    val arr = JSONArray()
                    list.forEach { arr.put(chapterToJson(it)) }
                    obj.put(k, arr)
                }
            }
            writeAtomic(chaptersFile, obj.toString())
        }
    }

    private fun toJson(): JSONObject {
        val entriesArr = JSONArray()
        _entries.value.forEach { e ->
            entriesArr.put(JSONObject().apply {
                put("manga", mangaToJson(e.manga))
                put("addedAt", e.addedAt)
                put("unread", e.unread)
            })
        }
        val progressObj = JSONObject()
        _progress.value.forEach { (key, p) ->
            progressObj.put(key, JSONObject().apply {
                put("read", JSONArray(p.read.toList()))
                put("lastChapterId", p.lastChapterId)
                put("lastPage", p.lastPage)
                put("lastReadAt", p.lastReadAt)
            })
        }
        val mangasObj = JSONObject()
        _mangas.value.forEach { (key, m) -> mangasObj.put(key, mangaToJson(m)) }
        val updatesArr = JSONArray()
        _updates.value.forEach { u ->
            updatesArr.put(JSONObject().apply {
                put("key", u.key)
                put("chapterId", u.chapterId)
                put("chapterTitle", u.chapterTitle)
                put("foundAt", u.foundAt)
            })
        }
        return JSONObject().apply {
            put("version", 2)
            put("entries", entriesArr)
            put("progress", progressObj)
            put("mangas", mangasObj)
            put("updates", updatesArr)
        }
    }

    private fun load(json: JSONObject) {
        _entries.value = parseEntries(json)
        _progress.value = parseProgress(json)
        _mangas.value = parseMangas(json)
        val arr = json.optJSONArray("updates") ?: JSONArray()
        _updates.value = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val key = o.str("key") ?: return@mapNotNull null
            val id = o.str("chapterId") ?: return@mapNotNull null
            UpdateItem(key, id, o.str("chapterTitle").orEmpty(), o.optLong("foundAt"))
        }
    }

    private fun parseEntries(json: JSONObject): List<LibraryEntry> {
        val out = mutableListOf<LibraryEntry>()
        val arr = json.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val manga = mangaFromJson(o.optJSONObject("manga") ?: continue) ?: continue
            out += LibraryEntry(manga, o.optLong("addedAt"), o.optInt("unread"))
        }
        return out.distinctBy { it.manga.key }
    }

    private fun parseProgress(json: JSONObject): Map<String, Progress> {
        val out = mutableMapOf<String, Progress>()
        val pObj = json.optJSONObject("progress") ?: return out
        val keys = pObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = pObj.optJSONObject(key) ?: continue
            val readArr = o.optJSONArray("read") ?: JSONArray()
            val read = (0 until readArr.length()).map { readArr.getString(it) }.toSet()
            out[key] = Progress(read, o.str("lastChapterId"), o.optInt("lastPage"), o.optLong("lastReadAt"))
        }
        return out
    }

    private fun parseMangas(json: JSONObject): Map<String, Manga> {
        val out = mutableMapOf<String, Manga>()
        val obj = json.optJSONObject("mangas") ?: return out
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            mangaFromJson(obj.optJSONObject(key) ?: continue)?.let { out[key] = it }
        }
        return out
    }

    private fun loadChapters(json: JSONObject) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = json.optJSONArray(key) ?: continue
            storedChapters[key] = (0 until arr.length()).mapNotNull { chapterFromJson(arr.getJSONObject(it)) }
        }
    }

    private fun chapterToJson(c: Chapter) = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        c.number?.let { put("number", it.toDouble()) }
        put("date", c.date)
        put("scanlator", c.scanlator)
    }

    private fun chapterFromJson(o: JSONObject): Chapter? {
        val id = o.str("id") ?: return null
        val num = o.optDouble("number", Double.NaN)
        return Chapter(
            id = id,
            title = o.str("title").orEmpty(),
            number = if (num.isNaN()) null else num.toFloat(),
            date = if (o.isNull("date")) null else o.optLong("date"),
            scanlator = o.str("scanlator"),
        )
    }

    private fun mangaToJson(m: Manga) = JSONObject().apply {
        put("sourceId", m.sourceId)
        put("id", m.id)
        put("title", m.title)
        put("cover", m.cover)
        put("author", m.author)
        put("description", m.description)
        put("status", m.status)
        put("genres", JSONArray(m.genres))
    }

    private fun mangaFromJson(o: JSONObject): Manga? {
        val sourceId = o.str("sourceId") ?: return null
        val id = o.str("id") ?: return null
        val genresArr = o.optJSONArray("genres") ?: JSONArray()
        return Manga(
            sourceId = sourceId,
            id = id,
            title = o.str("title") ?: "Sans titre",
            cover = o.str("cover"),
            author = o.str("author"),
            description = o.str("description"),
            status = o.str("status"),
            genres = (0 until genresArr.length()).map { genresArr.getString(it) },
        )
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}
