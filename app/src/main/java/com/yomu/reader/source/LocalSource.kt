package com.yomu.reader.source

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.yomu.reader.YomuApplication
import com.yomu.reader.data.Prefs
import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.MangasPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Fichiers du téléphone, comme la source « Local » de Tachiyomi.
 * Dossier choisi dans Plus → Fichiers locaux :
 *   Dossier choisi / Série / Chapitre 1 (dossier d'images, ou fichier .cbz / .zip)
 * Une image « cover.jpg » (ou .png/.webp) dans le dossier d'une série sert de couverture.
 */
class LocalSource : Source {
    override val id: String = "local"
    override val name: String = "Fichiers locaux"
    override val lang: String = "local"

    private val context get() = YomuApplication.instance
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")
    private val archiveExt = setOf("cbz", "zip")

    private fun root(): DocumentFile {
        val uri = Prefs.localTreeUri ?: throw IOException("Choisis d'abord ton dossier dans Plus → Fichiers locaux")
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))
            ?.takeIf { it.exists() }
            ?: throw IOException("Dossier introuvable : choisis-le à nouveau dans Plus → Fichiers locaux")
    }

    private fun doc(id: String): DocumentFile =
        DocumentFile.fromTreeUri(context, Uri.parse(id)) ?: throw IOException("Fichier introuvable")

    private fun ext(f: DocumentFile) = f.name.orEmpty().substringAfterLast('.', "").lowercase()

    private suspend fun series(): List<DocumentFile> = withContext(Dispatchers.IO) {
        root().listFiles().filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
    }

    private fun toManga(dir: DocumentFile): Manga {
        val cover = dir.listFiles().firstOrNull {
            it.isFile && it.name.orEmpty().substringBeforeLast('.').equals("cover", ignoreCase = true) && ext(it) in imageExt
        }
        return Manga(
            sourceId = id,
            id = dir.uri.toString(),
            title = dir.name ?: "Sans titre",
            cover = cover?.uri?.toString(),
        )
    }

    override suspend fun popular(page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangasPage(emptyList(), false)
        MangasPage(series().sortedWith(naturalOrder { it.name.orEmpty() }).map { toManga(it) }, false)
    }

    override suspend fun latest(page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangasPage(emptyList(), false)
        MangasPage(series().sortedByDescending { it.lastModified() }.map { toManga(it) }, false)
    }

    override suspend fun search(query: String, page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangasPage(emptyList(), false)
        val list = series().filter { it.name.orEmpty().contains(query.trim(), ignoreCase = true) }
        MangasPage(list.sortedWith(naturalOrder { it.name.orEmpty() }).map { toManga(it) }, false)
    }

    override suspend fun filters(): List<FilterGroup> = listOf(
        FilterGroup(
            "sort", "Trier par",
            listOf(FilterOption("name", "Nom"), FilterOption("recent", "Modifiés récemment")),
        ),
    )

    override suspend fun browse(page: Int, query: String, filters: FilterState): MangasPage =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext MangasPage(emptyList(), false)
            var list = series()
            if (query.isNotBlank()) list = list.filter { it.name.orEmpty().contains(query.trim(), ignoreCase = true) }
            list = if (filters.single("sort") == "recent") {
                list.sortedByDescending { it.lastModified() }
            } else {
                list.sortedWith(naturalOrder { it.name.orEmpty() })
            }
            MangasPage(list.map { toManga(it) }, false)
        }

    override suspend fun details(mangaId: String): Manga = withContext(Dispatchers.IO) {
        val dir = doc(mangaId)
        val info = dir.listFiles().firstOrNull { it.name.equals("details.txt", ignoreCase = true) }
        val description = info?.let { f ->
            runCatching { context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        }
        toManga(dir).copy(description = description, author = "Fichiers du téléphone")
    }

    override suspend fun chapters(mangaId: String): List<Chapter> = withContext(Dispatchers.IO) {
        doc(mangaId).listFiles()
            .filter { f ->
                (f.isDirectory && !f.name.orEmpty().startsWith(".")) || (f.isFile && ext(f) in archiveExt)
            }
            .map { f ->
                val name = f.name.orEmpty()
                val title = if (f.isFile) name.substringBeforeLast('.') else name
                Chapter(
                    id = f.uri.toString(),
                    title = title,
                    number = chapterNumber(title),
                    date = f.lastModified().takeIf { it > 0 },
                )
            }
            .sortedWith(naturalOrder<Chapter> { it.title }.reversed())
    }

    override suspend fun pages(chapterId: String): List<String> = withContext(Dispatchers.IO) {
        val f = doc(chapterId)
        if (f.isDirectory) {
            val images = f.listFiles().filter { it.isFile && ext(it) in imageExt }
                .sortedWith(naturalOrder { it.name.orEmpty() })
            if (images.isEmpty()) throw IOException("Aucune image dans ce dossier")
            images.map { it.uri.toString() }
        } else {
            extract(f)
        }
    }

    /** Décompresse un .cbz/.zip dans le cache (une seule fois) et renvoie les images. */
    private fun extract(file: DocumentFile): List<String> {
        val hash = MessageDigest.getInstance("SHA-1").digest(file.uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "local/$hash")
        val done = File(dir, ".done")
        if (!done.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            val input = context.contentResolver.openInputStream(file.uri) ?: throw IOException("Archive illisible")
            ZipInputStream(input.buffered()).use { zip ->
                var index = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name.substringAfterLast('/')
                    val e = entryName.substringAfterLast('.', "").lowercase()
                    if (!entry.isDirectory && e in imageExt && !entryName.startsWith(".")) {
                        // garde l'ordre naturel des noms d'origine
                        val out = File(dir, "%s__%05d.%s".format(entryName.substringBeforeLast('.'), index++, e))
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            done.writeText("ok")
        }
        val images = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedWith(naturalOrder { it.name.substringBeforeLast("__") })
            ?: emptyList()
        if (images.isEmpty()) throw IOException("Aucune image dans l'archive")
        return images.map { "file://" + it.absolutePath }
    }

    private fun chapterNumber(name: String): Float? {
        val tagged = Regex("(?i)(?:chapter|chapitre|ch\\.?|ep\\.?|episode)\\s*(\\d+(?:\\.\\d+)?)").find(name)
        if (tagged != null) return tagged.groupValues[1].toFloatOrNull()
        return Regex("(\\d+(?:\\.\\d+)?)").findAll(name).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull()
    }

    /** Tri « naturel » : « 2 » avant « 10 ». */
    private fun <T> naturalOrder(key: (T) -> String): Comparator<T> = Comparator { a, b ->
        val x = key(a).lowercase()
        val y = key(b).lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                val si = i
                val sj = j
                while (i < x.length && x[i].isDigit()) i++
                while (j < y.length && y[j].isDigit()) j++
                val nx = x.substring(si, i).trimStart('0').ifEmpty { "0" }
                val ny = y.substring(sj, j).trimStart('0').ifEmpty { "0" }
                if (nx.length != ny.length) return@Comparator nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return@Comparator c
            } else {
                if (cx != cy) return@Comparator cx - cy
                i++
                j++
            }
        }
        (x.length - i) - (y.length - j)
    }
}
