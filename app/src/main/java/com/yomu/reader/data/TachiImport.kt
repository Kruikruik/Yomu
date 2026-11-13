package com.yomu.reader.data

import com.yomu.reader.source.MangaDexSource
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
import kotlinx.coroutines.launch
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

    fun start(bytes: ByteArray) {
        if (_state.value.running) return
        scope.launch { run(bytes) }
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
        val source = SourceManager.all.filterIsInstance<MangaDexSource>().firstOrNull()
        if (source == null) {
            _state.value = ImportState(finished = true, error = "Aucune source MangaDex disponible.")
            return
        }
        if (favorites.isEmpty()) {
            _state.value = ImportState(finished = true, error = "Aucune série à importer.")
            return
        }

        val notFound = mutableListOf<String>()
        var imported = 0
        favorites.forEachIndexed { i, entry ->
            _state.update { it.copy(done = i, total = favorites.size, current = entry.title) }
            try {
                val manga = entry.mangaDexId?.let { id ->
                    try {
                        source.details(id)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        null
                    }
                } ?: source.findByTitle(entry.title)
                if (manga == null) {
                    notFound += entry.title
                } else {
                    val chapters = source.chapters(manga.id)
                    val max = entry.maxRead
                    val readIds = if (max == null) {
                        emptyList()
                    } else {
                        chapters.filter { c -> val n = c.number; n != null && n <= max }.map { it.id }
                    }
                    Library.importEntry(manga, chapters, readIds, entry.lastRead, entry.dateAdded)
                    imported++
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                notFound += entry.title
            }
            delay(300) // reste gentil avec l'API MangaDex
        }
        _state.value = ImportState(
            finished = true,
            done = favorites.size,
            total = favorites.size,
            imported = imported,
            notFound = notFound,
        )
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
                2 -> pos += length()
                5 -> pos += 4
                else -> throw IOException("type protobuf inconnu ($wire)")
            }
        }
    }
}
