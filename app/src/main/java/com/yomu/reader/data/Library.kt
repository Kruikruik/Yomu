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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibraryEntry(val manga: Manga, val addedAt: Long, val unread: Int = 0)

data class Progress(
    val read: Set<String> = emptySet(),
    val lastChapterId: String? = null,
    val lastPage: Int = 0,
)

/** Bibliothèque + progression de lecture, sauvegardées dans un fichier JSON. */
object Library {
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    private val _entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "library.json")
        if (file.exists()) runCatching { load(JSONObject(file.readText())) }
    }

    fun get(key: String): LibraryEntry? = _entries.value.firstOrNull { it.manga.key == key }

    fun contains(key: String): Boolean = get(key) != null

    fun toggle(manga: Manga, chapters: List<Chapter>?) {
        if (contains(manga.key)) {
            _entries.update { list -> list.filterNot { it.manga.key == manga.key } }
        } else {
            val entry = LibraryEntry(manga, System.currentTimeMillis(), unreadOf(manga.key, chapters))
            _entries.update { list -> list + entry }
        }
        save()
    }

    fun updateManga(manga: Manga) {
        if (!contains(manga.key)) return
        _entries.update { list -> list.map { if (it.manga.key == manga.key) it.copy(manga = manga) else it } }
        save()
    }

    fun updateChapters(key: String, chapters: List<Chapter>) {
        if (!contains(key)) return
        val unread = unreadOf(key, chapters)
        _entries.update { list -> list.map { if (it.manga.key == key) it.copy(unread = unread) else it } }
        save()
    }

    private fun unreadOf(key: String, chapters: List<Chapter>?): Int {
        if (chapters == null) return 0
        val read = _progress.value[key]?.read ?: emptySet()
        return chapters.count { it.id !in read }
    }

    fun setRead(key: String, chapterIds: Collection<String>, read: Boolean) {
        _progress.update { map ->
            val p = map[key] ?: Progress()
            val newRead = if (read) p.read + chapterIds else p.read - chapterIds.toSet()
            map + (key to p.copy(read = newRead))
        }
        ChapterCache.get(key)?.let { updateChapters(key, it) }
        save()
    }

    fun saveProgress(key: String, chapterId: String, page: Int, total: Int) {
        val finished = total > 0 && page >= total - 1
        val wasRead = _progress.value[key]?.read?.contains(chapterId) == true
        _progress.update { map ->
            val p = map[key] ?: Progress()
            map + (key to p.copy(
                lastChapterId = chapterId,
                lastPage = page,
                read = if (finished) p.read + chapterId else p.read,
            ))
        }
        if (finished && !wasRead) ChapterCache.get(key)?.let { updateChapters(key, it) }
        save()
    }

    /** Recharge les chapitres de chaque série. Renvoie le nombre de séries avec du nouveau. */
    suspend fun refreshAll(): Int {
        var updated = 0
        for (entry in _entries.value) {
            val source = SourceManager.get(entry.manga.sourceId) ?: continue
            try {
                val chapters = source.chapters(entry.manga.id)
                ChapterCache.put(entry.manga.key, chapters)
                val before = get(entry.manga.key)?.unread ?: 0
                updateChapters(entry.manga.key, chapters)
                val after = get(entry.manga.key)?.unread ?: 0
                if (after > before) updated++
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // on passe à la série suivante
            }
            delay(300) // reste gentil avec l'API
        }
        return updated
    }

    // ------------------------------------------------------------ persistance

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            val text = toJson().toString()
            synchronized(this@Library) {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
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
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("entries", entriesArr)
            put("progress", progressObj)
        }
    }

    private fun load(json: JSONObject) {
        val entries = mutableListOf<LibraryEntry>()
        val arr = json.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val manga = mangaFromJson(o.getJSONObject("manga")) ?: continue
            entries += LibraryEntry(manga, o.optLong("addedAt"), o.optInt("unread"))
        }
        val progress = mutableMapOf<String, Progress>()
        val pObj = json.optJSONObject("progress") ?: JSONObject()
        val keys = pObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = pObj.getJSONObject(key)
            val readArr = o.optJSONArray("read") ?: JSONArray()
            val read = (0 until readArr.length()).map { readArr.getString(it) }.toSet()
            progress[key] = Progress(read, o.str("lastChapterId"), o.optInt("lastPage"))
        }
        _entries.value = entries
        _progress.value = progress
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
