package com.yomu.reader.data

import com.yomu.reader.source.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import com.yomu.reader.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.zip.GZIPInputStream

data class ImportState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val imported: Int = 0,
    val notFound: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Import d'une sauvegarde Tachiyomi / Mihon (.tachibk ou .proto.gz).
 * Chaque série de la bibliothèque est retrouvée sur MangaDex (directement si elle venait
 * de MangaDex, sinon par son titre) et les chapitres lus sont recopiés d'après leur numéro.
 */
object TachiImport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private class BackupEntry(
        val title: String,
        val url: String,
        val sourceName: String,
        val favorite: Boolean,
        val dateAdded: Long,
        val maxRead: Float?,
        val lastRead: Long,
        val altTitles: List<String> = emptyList(),
        val status: ReadingStatus? = null,
        val rating: Int = 0,
    ) {
        val mangaDexId: String?
            get() = if (sourceName.contains("mangadex", ignoreCase = true)) {
                TachiImport.UUID.find(url)?.value
            } else {
                null
            }
    }

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val CHAPTER_NUM = Regex("(?i)(?:chapter|chapitre|ch\\.?|episode|ep\\.?)\\s*(\\d+(?:\\.\\d+)?)")

    /** Fichier d'import : sauvegarde Tachiyomi/Mihon, export MyAnimeList (.xml) ou liste CSV (Kenmei…). */
    fun start(bytes: ByteArray) {
        if (_state.value.running) return
        scope.launch {
            val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                runCatching { GZIPInputStream(bytes.inputStream()).use { it.readBytes() } }.getOrDefault(bytes)
            } else {
                bytes
            }
            val head = String(raw, 0, minOf(raw.size, 2000), Charsets.UTF_8)
            when {
                head.contains("<myanimelist", ignoreCase = true) || head.trimStart().startsWith("<?xml") -> {
                    _state.value = ImportState(running = true, current = "Lecture de l'export MyAnimeList…")
                    process(parseMal(String(raw, Charsets.UTF_8)))
                }
                looksLikeCsv(head) -> {
                    _state.value = ImportState(running = true, current = "Lecture de la liste…")
                    val entries = parseCsv(String(raw, Charsets.UTF_8))
                    if (entries.isEmpty()) {
                        _state.value = ImportState(finished = true, error = "Aucune série trouvée dans ce fichier.")
                    } else {
                        process(entries)
                    }
                }
                else -> run(bytes)
            }
        }
    }

    /** Import de la liste manga publique d'un compte AniList. */
    fun startAniList(userName: String) {
        if (_state.value.running) return
        scope.launch {
            _state.value = ImportState(running = true, current = "Connexion à AniList…")
            val entries = try {
                fetchAniList(userName.trim())
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _state.value = ImportState(finished = true, error = "AniList : ${e.message}")
                return@launch
            }
            process(entries)
        }
    }

    // ------------------------------------------------------------- AniList

    private suspend fun fetchAniList(userName: String): List<BackupEntry> {
        val query = """
            query (${'$'}name: String) {
              MediaListCollection(userName: ${'$'}name, type: MANGA) {
                lists { entries {
                  status progress score(format: POINT_10)
                  media { title { romaji english native } synonyms }
                } }
              }
            }
        """.trimIndent()
        val body = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("name", userName))
        val json = Http.postJson("https://graphql.anilist.co".toHttpUrl(), body)
        json.optJSONArray("errors")?.let { errs ->
            throw IOException(errs.optJSONObject(0)?.optString("message") ?: "compte introuvable")
        }
        val lists = json.getJSONObject("data").getJSONObject("MediaListCollection").getJSONArray("lists")
        val out = mutableListOf<BackupEntry>()
        for (i in 0 until lists.length()) {
            val entries = lists.getJSONObject(i).optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                val media = e.getJSONObject("media")
                val t = media.getJSONObject("title")
                val names = listOf("english", "romaji", "native").mapNotNull { k ->
                    if (t.isNull(k)) null else t.optString(k).ifEmpty { null }
                }
                val synonyms = media.optJSONArray("synonyms")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
                if (names.isEmpty()) continue
                val progress = e.optInt("progress", 0)
                out += BackupEntry(
                    title = names.first(),
                    url = "",
                    sourceName = "",
                    favorite = true,
                    dateAdded = 0L,
                    maxRead = progress.takeIf { it > 0 }?.toFloat(),
                    lastRead = 0L,
                    altTitles = (names.drop(1) + synonyms).distinct(),
                    status = when (e.optString("status")) {
                        "CURRENT", "REPEATING" -> ReadingStatus.READING
                        "PLANNING" -> ReadingStatus.PLANNED
                        "COMPLETED" -> ReadingStatus.COMPLETED
                        "PAUSED" -> ReadingStatus.ON_HOLD
                        "DROPPED" -> ReadingStatus.DROPPED
                        else -> null
                    },
                    rating = e.optDouble("score", 0.0).toInt(),
                )
            }
        }
        return out
    }

    // ------------------------------------------------------- MyAnimeList

    private val MAL_ITEM = Regex("<manga>(.*?)</manga>", RegexOption.DOT_MATCHES_ALL)

    private fun malField(block: String, name: String): String? =
        Regex("<$name>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private fun parseMal(xml: String): List<BackupEntry> = MAL_ITEM.findAll(xml).mapNotNull { m ->
        val b = m.groupValues[1]
        val title = malField(b, "manga_title") ?: return@mapNotNull null
        BackupEntry(
            title = android.text.Html.fromHtml(title, android.text.Html.FROM_HTML_MODE_LEGACY).toString(),
            url = "",
            sourceName = "",
            favorite = true,
            dateAdded = 0L,
            maxRead = malField(b, "my_read_chapters")?.toFloatOrNull()?.takeIf { it > 0f },
            lastRead = 0L,
            status = statusFromText(malField(b, "my_status")),
            rating = malField(b, "my_score")?.toIntOrNull() ?: 0,
        )
    }.toList()

    // ---------------------------------------------------------- CSV (Kenmei…)

    private fun looksLikeCsv(head: String): Boolean {
        val first = head.lineSequence().firstOrNull().orEmpty().lowercase()
        return (first.contains(',') || first.contains(';') || first.contains('\t')) &&
            (first.contains("title") || first.contains("titre") || first.contains("name"))
    }

    private fun splitCsvLine(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out.map { it.trim() }
    }

    private fun parseCsv(text: String): List<BackupEntry> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val sep = listOf(',', ';', '\t').maxBy { c -> lines[0].count { it == c } }
        val header = splitCsvLine(lines[0], sep).map { it.lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { h -> names.any { h.contains(it) } }
        val titleCol = col("title", "titre", "name").takeIf { it >= 0 } ?: return emptyList()
        val chapterCol = col("last read", "last_read", "lastread", "progress", "read chapter", "chapter", "chapitre")
        val statusCol = col("status", "statut")
        val ratingCol = col("rating", "score", "note")
        return lines.drop(1).mapNotNull { line ->
            val cells = splitCsvLine(line, sep)
            val title = cells.getOrNull(titleCol)?.ifEmpty { null } ?: return@mapNotNull null
            val chapter = cells.getOrNull(chapterCol)?.let { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.value?.toFloatOrNull() }
            BackupEntry(
                title = title,
                url = "",
                sourceName = "",
                favorite = true,
                dateAdded = 0L,
                maxRead = chapter?.takeIf { it > 0f },
                lastRead = 0L,
                status = statusFromText(cells.getOrNull(statusCol)),
                rating = cells.getOrNull(ratingCol)?.toFloatOrNull()?.toInt()?.coerceIn(0, 10) ?: 0,
            )
        }.distinctBy { it.title.lowercase() }
    }

    private fun statusFromText(s: String?): ReadingStatus? {
        val t = s?.lowercase()?.replace('_', ' ')?.replace('-', ' ') ?: return null
        return when {
            t.contains("plan") || t.contains("à lire") -> ReadingStatus.PLANNED
            t.contains("complet") || t.contains("termin") || t.contains("finished") -> ReadingStatus.COMPLETED
            t.contains("hold") || t.contains("pause") -> ReadingStatus.ON_HOLD
            t.contains("drop") || t.contains("abandon") -> ReadingStatus.DROPPED
            t.contains("read") || t.contains("current") || t.contains("lecture") -> ReadingStatus.READING
            else -> null
        }
    }

    /**
     * Import d'une liste de titres collée à la main (une série par ligne).
     * Format accepté : « Titre » ou « Titre | 45 » (= lu jusqu'au chapitre 45).
     */
    fun startList(text: String) {
        if (_state.value.running) return
        val entries = text.lines().mapNotNull { line ->
            val m = LIST_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val title = m.groupValues[1].trim()
            if (title.isEmpty()) return@mapNotNull null
            BackupEntry(
                title = title,
                url = "",
                sourceName = "",
                favorite = true,
                dateAdded = 0L,
                maxRead = m.groupValues[2].toFloatOrNull(),
                lastRead = 0L,
            )
        }.distinctBy { it.title.lowercase() }
        scope.launch {
            _state.value = ImportState(running = true)
            process(entries)
        }
    }

    private val LIST_LINE = Regex("(?i)^(.*?)(?:\\s*[|;\\t]\\s*(?:ch(?:apter|apitre|\\.)?\\s*)?(\\d+(?:\\.\\d+)?))?\\s*$")

    fun dismiss() {
        if (!_state.value.running) _state.value = ImportState()
    }

    private suspend fun run(bytes: ByteArray) {
        _state.value = ImportState(running = true, current = "Lecture du fichier…")
        val entries = try {
            parseBackup(bytes)
        } catch (e: Exception) {
            _state.value = ImportState(
                finished = true,
                error = "Fichier illisible (${e.message}). Il faut une sauvegarde .tachibk ou .proto.gz.",
            )
            return
        }
        process(entries.filter { it.favorite })
    }

    private suspend fun process(favorites: List<BackupEntry>) {
        val source = SourceManager.get("mangadex-en")
        if (source == null) {
            _state.value = ImportState(finished = true, error = "Aucune source MangaDex disponible.")
            return
        }
        if (favorites.isEmpty()) {
            _state.value = ImportState(finished = true, error = "Aucune série à importer.")
            return
        }

        val notFound = java.util.Collections.synchronizedList(mutableListOf<String>())
        val imported = AtomicInteger(0)
        val done = AtomicInteger(0)
        _state.update { it.copy(done = 0, total = favorites.size) }
        // 3 séries en parallèle : beaucoup plus rapide pour les grosses bibliothèques,
        // tout en restant raisonnable pour les sites.
        val gate = Semaphore(3)
        coroutineScope {
            favorites.map { entry ->
                async {
                    gate.withPermit {
                        importOne(entry, source, notFound, imported)
                        delay(250)
                    }
                    val n = done.incrementAndGet()
                    _state.update { it.copy(done = n, current = entry.title) }
                }
            }.awaitAll()
        }
        _state.value = ImportState(
            finished = true,
            done = favorites.size,
            total = favorites.size,
            imported = imported.get(),
            notFound = notFound.toList(),
        )
    }

    private suspend fun importOne(
        entry: BackupEntry,
        source: com.yomu.reader.source.Source,
        notFound: MutableList<String>,
        imported: AtomicInteger,
    ) {
        try {
            val manga = entry.mangaDexId?.let { id ->
                try {
                    source.details(id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }
            } ?: findAnywhere(listOf(entry.title) + entry.altTitles)
            val mangaSource = manga?.let { SourceManager.get(it.sourceId) }
            if (manga == null || mangaSource == null) {
                notFound += entry.title
                keepPending(entry)
                return
            }
            val chapters = mangaSource.chapters(manga.id)
            val max = entry.maxRead
            val readIds = if (max == null) {
                emptyList()
            } else {
                chapters.filter { c -> val n = c.number; n != null && n <= max }.map { it.id }
            }
            Library.importEntry(
                manga, chapters, readIds, entry.lastRead, entry.dateAdded,
                status = entry.status,
                rating = entry.rating,
            )
            imported.incrementAndGet()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            CrashLog.record("Import « ${entry.title} »", e)
            notFound += entry.title
            keepPending(entry)
        }
    }

    /** Cherche la série sur toutes les sources en ligne, dans l'ordre (MangaDex d'abord). */
    private fun keepPending(entry: BackupEntry) {
        Library.addPending(PendingEntry(entry.title, entry.maxRead, entry.status, entry.rating))
    }

    /** Retente les séries « À retrouver ». Renvoie les titres retrouvés et ajoutés. */
    suspend fun retryPending(): List<String> {
        val found = mutableListOf<String>()
        for (p in Library.pending.value) {
            val manga = findAnywhere(listOf(p.title)) ?: continue
            val src = SourceManager.get(manga.sourceId) ?: continue
            try {
                val chapters = src.chapters(manga.id)
                val max = p.maxRead
                val readIds = if (max == null) emptyList() else chapters.filter { c -> val n = c.number; n != null && n <= max }.map { it.id }
                Library.importEntry(manga, chapters, readIds, 0L, 0L, status = p.status, rating = p.rating)
                Library.removePending(p.title)
                found += manga.title
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                CrashLog.record("À retrouver « ${p.title} »", e)
            }
            delay(250)
        }
        return found
    }

    private suspend fun findAnywhere(titles: List<String>): com.yomu.reader.model.Manga? {
        for (src in SourceManager.all) {
            if (src.id == "local") continue
            for (title in titles.take(4)) {
                try {
                    src.findByTitle(title)?.let { return it }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    // --------------------------------------------------- lecture protobuf

    private fun parseBackup(raw: ByteArray): List<BackupEntry> {
        val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        } else {
            raw
        }
        val r = Proto(bytes, 0, bytes.size)
        val mangas = mutableListOf<Proto>()
        val sourceNames = mutableMapOf<Long, String>()
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> mangas += r.message()
                field == 101 && wire == 2 -> {
                    val s = r.message()
                    var name = ""
                    var id = 0L
                    while (s.hasMore()) {
                        val (f, w) = s.tag()
                        when {
                            f == 1 && w == 2 -> name = s.string()
                            f == 2 && w == 0 -> id = s.varint()
                            else -> s.skip(w)
                        }
                    }
                    sourceNames[id] = name
                }
                else -> r.skip(wire)
            }
        }
        if (mangas.isEmpty() && sourceNames.isEmpty()) throw IOException("format inconnu")
        return mangas.map { parseManga(it, sourceNames) }
    }

    private fun parseManga(m: Proto, sourceNames: Map<Long, String>): BackupEntry {
        var sourceId = 0L
        var url = ""
        var title = ""
        var dateAdded = 0L
        var favorite = true // valeur par défaut de Tachiyomi quand le champ est absent
        var maxRead: Float? = null
        var lastRead = 0L
        while (m.hasMore()) {
            val (f, w) = m.tag()
            when {
                f == 1 && w == 0 -> sourceId = m.varint()
                f == 2 && w == 2 -> url = m.string()
                f == 3 && w == 2 -> title = m.string()
                f == 13 && w == 0 -> dateAdded = m.varint()
                f == 100 && w == 0 -> favorite = m.varint() != 0L
                f == 16 && w == 2 -> {
                    val c = m.message()
                    var name = ""
                    var read = false
                    var number = -1f
                    while (c.hasMore()) {
                        val (cf, cw) = c.tag()
                        when {
                            cf == 2 && cw == 2 -> name = c.string()
                            cf == 4 && cw == 0 -> read = c.varint() != 0L
                            cf == 9 && cw == 5 -> number = Float.fromBits(c.fixed32())
                            else -> c.skip(cw)
                        }
                    }
                    if (number < 0f) number = CHAPTER_NUM.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                    if (read && number >= 0f) {
                        val current = maxRead
                        if (current == null || number > current) maxRead = number
                    }
                }
                f == 104 && w == 2 -> {
                    val h = m.message()
                    while (h.hasMore()) {
                        val (hf, hw) = h.tag()
                        if (hf == 2 && hw == 0) lastRead = maxOf(lastRead, h.varint()) else h.skip(hw)
                    }
                }
                else -> m.skip(w)
            }
        }
        return BackupEntry(
            title = title,
            url = url,
            sourceName = sourceNames[sourceId] ?: if (UUID.containsMatchIn(url) && url.startsWith("/manga/")) "MangaDex" else "",
            favorite = favorite,
            dateAdded = dateAdded,
            maxRead = maxRead,
            lastRead = lastRead,
        )
    }

    /** Lecteur protobuf minimal (format binaire de Tachiyomi). */
    private class Proto(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw IOException("fin de fichier inattendue")
                val b = buf[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("entier invalide")
            }
        }

        fun tag(): Pair<Int, Int> {
            val t = varint().toInt()
            return (t ushr 3) to (t and 7)
        }

        private fun length(): Int {
            val len = varint().toInt()
            if (len < 0 || pos + len > end) throw IOException("longueur invalide")
            return len
        }

        fun message(): Proto {
            val len = length()
            val p = Proto(buf, pos, pos + len)
            pos += len
            return p
        }

        fun string(): String {
            val len = length()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun fixed32(): Int {
            if (pos + 4 > end) throw IOException("fin de fichier inattendue")
            val v = (buf[pos].toInt() and 0xff) or
                ((buf[pos + 1].toInt() and 0xff) shl 8) or
                ((buf[pos + 2].toInt() and 0xff) shl 16) or
                ((buf[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> {
                    // length() avance déjà « pos » : il faut le lire avant d'additionner.
                    val len = length()
                    pos += len
                }
                5 -> pos += 4
                else -> throw IOException("type protobuf inconnu ($wire)")
            }
        }
    }
}
