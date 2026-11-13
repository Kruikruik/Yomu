package com.yomu.reader.source

import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.MangasPage
import com.yomu.reader.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * MANGA Plus (Shueisha, officiel et gratuit) en anglais.
 * API publique jumpg-webapi en JSON. Les images sont chiffrées (XOR) : la clé est ajoutée
 * à l'adresse de l'image (#yomukey=…) et le client HTTP les déchiffre (voir Http).
 */
class MangaPlusSource : Source {
    override val id: String = "mangaplus-en"
    override val name: String = "MANGA Plus"
    override val lang: String = "en"
    override val imageHeaders: Map<String, String> = mapOf("Referer" to "https://mangaplus.shueisha.co.jp/")

    private val api = "https://jumpg-webapi.tokyo-cdn.com/api"
    private val pageSize = 30

    private class Title(val manga: Manga, val views: Long)

    @Volatile
    private var catalog: List<Title>? = null

    private suspend fun json(path: String, vararg params: Pair<String, String>): JSONObject {
        val url = "$api/$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("format", "json")
            .build()
        val obj = Http.getJson(url)
        obj.optJSONObject("error")?.let { err ->
            val msg = err.optJSONObject("englishPopup")?.optString("body")?.ifEmpty { null }
                ?: err.optJSONObject("popups")?.toString()
            throw IOException(msg ?: "Erreur MANGA Plus")
        }
        return obj.optJSONObject("success") ?: throw IOException("Réponse MANGA Plus inattendue")
    }

    private fun parseTitle(t: JSONObject): Manga = Manga(
        sourceId = id,
        id = t.optLong("titleId").toString(),
        title = t.optString("name").ifEmpty { "Sans titre" },
        cover = t.optString("portraitImageUrl").ifEmpty { null },
        author = t.optString("author").ifEmpty { null },
    )

    private fun isEnglish(t: JSONObject): Boolean {
        val l = t.optString("language")
        return l.isEmpty() || l.equals("ENGLISH", ignoreCase = true)
    }

    /** Tout le catalogue anglais (une seule requête, gardée en mémoire). */
    private suspend fun all(): List<Title> {
        catalog?.let { return it }
        val s = json("title_list/allV2")
        val view = s.optJSONObject("allTitlesViewV2") ?: throw IOException("Catalogue MANGA Plus introuvable")
        val groups = view.optJSONArray("AllTitlesGroup") ?: view.optJSONArray("allTitlesGroup") ?: JSONArray()
        val out = mutableListOf<Title>()
        for (i in 0 until groups.length()) {
            val titles = groups.getJSONObject(i).optJSONArray("titles") ?: continue
            for (j in 0 until titles.length()) {
                val t = titles.getJSONObject(j)
                if (!isEnglish(t)) continue
                out += Title(parseTitle(t), t.optLong("viewCount"))
            }
        }
        val unique = out.distinctBy { it.manga.id }
        catalog = unique
        return unique
    }

    private fun pageOf(list: List<Manga>, page: Int): MangasPage {
        val from = (page - 1) * pageSize
        if (from >= list.size) return MangasPage(emptyList(), false)
        val to = minOf(list.size, from + pageSize)
        return MangasPage(list.subList(from, to), to < list.size)
    }

    override suspend fun popular(page: Int): MangasPage =
        pageOf(all().sortedByDescending { it.views }.map { it.manga }, page)

    /** « Récents » : les séries ajoutées le plus récemment (identifiants les plus élevés). */
    override suspend fun latest(page: Int): MangasPage =
        pageOf(all().sortedByDescending { it.manga.id.toLongOrNull() ?: 0L }.map { it.manga }, page)

    override suspend fun search(query: String, page: Int): MangasPage {
        val q = normalizeTitle(query)
        val list = all().filter {
            normalizeTitle(it.manga.title).contains(q) || normalizeTitle(it.manga.author.orEmpty()).contains(q)
        }
        return pageOf(list.sortedByDescending { it.views }.map { it.manga }, page)
    }

    override suspend fun findByTitle(title: String): Manga? {
        val target = normalizeTitle(title)
        if (target.isEmpty()) return null
        return all().firstOrNull { normalizeTitle(it.manga.title) == target }?.manga
    }

    override suspend fun filters(): List<FilterGroup> = listOf(
        FilterGroup(
            "sort", "Trier par",
            listOf(
                FilterOption("views", "Popularité"),
                FilterOption("new", "Ajouts récents"),
                FilterOption("title", "Titre (A → Z)"),
            ),
        ),
    )

    override suspend fun browse(page: Int, query: String, filters: FilterState): MangasPage {
        val q = normalizeTitle(query)
        var list = all()
        if (q.isNotEmpty()) {
            list = list.filter {
                normalizeTitle(it.manga.title).contains(q) || normalizeTitle(it.manga.author.orEmpty()).contains(q)
            }
        }
        val sorted = when (filters.single("sort")) {
            "new" -> list.sortedByDescending { it.manga.id.toLongOrNull() ?: 0L }
            "title" -> list.sortedBy { it.manga.title.lowercase() }
            else -> list.sortedByDescending { it.views }
        }
        return pageOf(sorted.map { it.manga }, page)
    }

    override suspend fun details(mangaId: String): Manga {
        val view = json("title_detailV3", "title_id" to mangaId).getJSONObject("titleDetailView")
        val base = view.optJSONObject("title")?.let { parseTitle(it) }
            ?: Manga(sourceId = id, id = mangaId, title = "Sans titre")
        return base.copy(
            description = view.optString("overview").ifEmpty { null },
            genres = listOf("Shueisha"),
        )
    }

    override suspend fun chapters(mangaId: String): List<Chapter> {
        val view = json("title_detailV3", "title_id" to mangaId).getJSONObject("titleDetailView")
        val out = mutableListOf<Chapter>()
        val groups = view.optJSONArray("chapterListGroup") ?: JSONArray()
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            // Les chapitres « du milieu » sont réservés aux abonnés : on garde les gratuits.
            for (listName in listOf("firstChapterList", "lastChapterList")) {
                val arr = g.optJSONArray(listName) ?: continue
                for (j in 0 until arr.length()) parseChapter(arr.getJSONObject(j))?.let { out += it }
            }
        }
        // Anciennes réponses : listes directement dans la vue.
        for (listName in listOf("firstChapterList", "lastChapterList")) {
            val arr = view.optJSONArray(listName) ?: continue
            for (j in 0 until arr.length()) parseChapter(arr.getJSONObject(j))?.let { out += it }
        }
        return out.distinctBy { it.id }.sortedWith(
            compareByDescending<Chapter> { it.number ?: -1f }.thenByDescending { it.date ?: 0L }
        )
    }

    private fun parseChapter(c: JSONObject): Chapter? {
        val chapterId = c.optLong("chapterId").takeIf { it > 0 } ?: return null
        val name = c.optString("name") // ex. « #012 »
        val sub = c.optString("subTitle")
        val number = Regex("(\\d+(?:\\.\\d+)?)").find(name)?.value?.toFloatOrNull()
        val title = when {
            number != null && sub.isNotEmpty() -> "Ch. ${name.trimStart('#').trimStart('0').ifEmpty { "0" }} - $sub"
            sub.isNotEmpty() -> sub
            name.isNotEmpty() -> name
            else -> "Chapitre"
        }
        return Chapter(
            id = chapterId.toString(),
            title = title,
            number = number,
            date = c.optLong("startTimeStamp").takeIf { it > 0 }?.times(1000),
        )
    }

    override suspend fun pages(chapterId: String): List<String> {
        val viewer = json(
            "manga_viewer",
            "chapter_id" to chapterId,
            "split" to "yes",
            "img_quality" to "super_high",
        ).getJSONObject("mangaViewer")
        val pages = viewer.optJSONArray("pages") ?: JSONArray()
        val out = mutableListOf<String>()
        for (i in 0 until pages.length()) {
            val p = pages.getJSONObject(i).optJSONObject("mangaPage") ?: continue
            val url = p.optString("imageUrl").ifEmpty { continue }
            val key = p.optString("encryptionKey")
            out += if (key.isNotEmpty()) "$url#yomukey=$key" else url
        }
        if (out.isEmpty()) throw IOException("Chapitre indisponible (réservé aux abonnés ou expiré)")
        return out
    }
}
