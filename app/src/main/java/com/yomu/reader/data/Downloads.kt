package com.yomu.reader.data

import android.content.Context
import android.util.Base64
import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.keyOf
import com.yomu.reader.net.Http
import com.yomu.reader.source.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

enum class DownloadState { QUEUED, RUNNING, ERROR }

data class DownloadTask(
    val key: String,
    val sourceId: String,
    val mangaId: String,
    val mangaTitle: String,
    val chapter: Chapter,
    val state: DownloadState = DownloadState.QUEUED,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
) {
    val id: String get() = downloadId(key, chapter.id)
}

fun downloadId(key: String, chapterId: String) = "$key|$chapterId"

/** Téléchargement de chapitres pour la lecture hors-ligne (stockage interne de l'appli). */
object Downloads {
    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    private val _queue = MutableStateFlow<List<DownloadTask>>(emptyList())
    val queue: StateFlow<List<DownloadTask>> = _queue.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private class Removed : Exception()

    private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private fun enc(s: String) = Base64.encodeToString(s.toByteArray(), B64)
    private fun dec(s: String) = String(Base64.decode(s, B64))

    fun init(context: Context) {
        root = File(context.filesDir, "downloads")
        scope.launch { scan() }
    }

    private fun scan() {
        val found = mutableSetOf<String>()
        root.listFiles()?.forEach { sDir ->
            sDir.listFiles()?.forEach { mDir ->
                mDir.listFiles()?.forEach { cDir ->
                    if (File(cDir, ".done").exists()) {
                        runCatching {
                            found += downloadId(keyOf(dec(sDir.name), dec(mDir.name)), dec(cDir.name))
                        }
                    }
                }
            }
        }
        _downloaded.value = found
    }

    private fun mangaDir(key: String): File {
        val (s, m) = splitKey(key)
        return File(root, "${enc(s)}/${enc(m)}")
    }

    private fun chapterDir(key: String, chapterId: String) = File(mangaDir(key), enc(chapterId))

    fun isDownloaded(key: String, chapterId: String) = downloadId(key, chapterId) in _downloaded.value

    fun countFor(key: String): Int = _downloaded.value.count { it.startsWith("$key|") }

    /** Pages locales d'un chapitre téléchargé (URI file://), ou null. */
    fun localPages(key: String, chapterId: String): List<String>? {
        if (!isDownloaded(key, chapterId)) return null
        val files = chapterDir(key, chapterId).listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
            ?.sortedBy { it.name }
            ?: return null
        if (files.isEmpty()) return null
        return files.map { "file://" + it.absolutePath }
    }

    fun enqueue(manga: Manga, chapters: List<Chapter>) {
        _queue.update { q ->
            val existing = q.mapTo(HashSet()) { it.id }
            val add = chapters
                .filter { !isDownloaded(manga.key, it.id) }
                .map { DownloadTask(manga.key, manga.sourceId, manga.id, manga.title, it) }
                .filter { it.id !in existing }
            q + add
        }
        start()
    }

    fun cancel(id: String) {
        _queue.update { q -> q.filterNot { it.id == id } }
    }

    fun cancelAll() {
        _queue.value = emptyList()
    }

    fun retry(id: String) {
        _queue.update { q -> q.map { if (it.id == id) it.copy(state = DownloadState.QUEUED, error = null) else it } }
        start()
    }

    fun retryErrors() {
        _queue.update { q -> q.map { if (it.state == DownloadState.ERROR) it.copy(state = DownloadState.QUEUED, error = null) else it } }
        start()
    }

    fun delete(key: String, chapterIds: Collection<String>) {
        val ids = chapterIds.map { downloadId(key, it) }.toSet()
        _queue.update { q -> q.filterNot { it.id in ids } }
        _downloaded.update { it - ids }
        scope.launch { chapterIds.forEach { chapterDir(key, it).deleteRecursively() } }
    }

    fun deleteAll() {
        cancelAll()
        _downloaded.value = emptySet()
        scope.launch { root.deleteRecursively() }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        if (!root.exists()) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun start() {
        if (_queue.value.any { it.state == DownloadState.QUEUED }) {
            runCatching { DownloadService.start(com.yomu.reader.YomuApplication.instance) }
        }
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val task = _queue.value.firstOrNull { it.state == DownloadState.QUEUED } ?: break
                run(task)
            }
        }
    }

    private fun setTask(id: String, change: (DownloadTask) -> DownloadTask) {
        _queue.update { q -> q.map { if (it.id == id) change(it) else it } }
    }

    private fun stillQueued(id: String) = _queue.value.any { it.id == id }

    private suspend fun run(task: DownloadTask) {
        setTask(task.id) { it.copy(state = DownloadState.RUNNING, error = null) }
        val dir = chapterDir(task.key, task.chapter.id)
        try {
            val source = SourceManager.get(task.sourceId) ?: throw IOException("Source introuvable")
            val urls = source.pages(task.chapter.id)
            if (urls.isEmpty()) throw IOException("Chapitre vide")
            dir.mkdirs()
            setTask(task.id) { it.copy(total = urls.size) }
            urls.forEachIndexed { i, url ->
                if (!stillQueued(task.id)) throw Removed()
                val ext = url.substringBefore('#').substringBefore('?').substringAfterLast('.', "").lowercase()
                    .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: "img"
                val dest = File(dir, "%04d.%s".format(i, ext))
                if (!dest.exists() || dest.length() == 0L) downloadFile(url, source.imageHeaders, dest)
                setTask(task.id) { it.copy(done = i + 1) }
            }
            File(dir, ".done").writeText(task.chapter.title)
            _downloaded.update { it + task.id }
            _queue.update { q -> q.filterNot { it.id == task.id } }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Removed) {
            dir.deleteRecursively()
        } catch (e: Exception) {
            CrashLog.record("Téléchargement ${task.mangaTitle} / ${task.chapter.title}", e)
            setTask(task.id) { it.copy(state = DownloadState.ERROR, error = e.message ?: e.toString()) }
        }
    }

    private fun downloadFile(url: String, headers: Map<String, String>, dest: File) {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                Http.client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Réponse vide")
                    val tmp = File(dest.path + ".part")
                    body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (!tmp.renameTo(dest)) throw IOException("Écriture impossible")
                }
                return
            } catch (e: IOException) {
                last = e
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw last ?: IOException("Échec du téléchargement")
    }
}
