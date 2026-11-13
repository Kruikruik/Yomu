package com.yomu.reader.source

import android.text.Html
import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.MangasPage
import com.yomu.reader.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.Calendar

/**
 * Source WEBTOON (webtoons.com, plateforme officielle, en anglais).
 * Catalogue : pages publiques du site. Épisodes : API mobile publique. Images : page de lecture.
 */
class WebtoonSource : Source {
    override val id: String = "webtoons-en"
    override val name: String = "Webtoon"
    override val lang: String = "en"
    override val imageHeaders: Map<String, String> = mapOf("Referer" to "https://www.webtoons.com/")

    private val base = "https://www.webtoons.com"
    private val headers = mapOf(
        "Referer" to "$base/",
        "Cookie" to "ageGatePass=true; locale=en; needGDPR=false; needCCPA=false; needCOPPA=false",
    )
    private val days = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    private suspend fun html(url: String): String = Http.getString(url.toHttpUrl(), headers)

    override suspend fun popular(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(parseList(html("$base/en/genre")), false)
    }

    /** « Récents » : les séries du jour, puis de la veille, etc. (7 pages = une semaine). */
    override suspend fun latest(page: Int): MangasPage {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1 // 0 = dimanche
        val day = days[((today - (page - 1)) % 7 + 7) % 7]
        val list = parseList(html("$base/en/originals/$day?sortOrder=UPDATE"))
        return MangasPage(list, page < 7)
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val url = "$base/en/search".toHttpUrl().newBuilder().addQueryParameter("keyword", query).build()
        return MangasPage(parseList(Http.getString(url, headers)), false)
    }

    override suspend fun findByTitle(title: String): Manga? {
        val target = normalizeTitle(title)
        if (target.isEmpty()) return null
        return search(title, 1).mangas.firstOrNull { normalizeTitle(it.title) == target }
    }

    override suspend fun details(mangaId: String): Manga {
        val page = html(base + mangaId)
        val genre = mangaId.split('/').getOrNull(2)?.replace('-', ' ')?.replaceFirstChar { it.uppercase() }
        val author = AUTHOR.find(page)?.groupValues?.get(1)?.let { clean(it) }
            ?.substringBefore("author info")?.trim()?.ifEmpty { null }
        return Manga(
            sourceId = id,
            id = mangaId,
            title = meta(page, "og:title") ?: "Sans titre",
            cover = meta(page, "og:image"),
            author = author,
            description = meta(page, "og:description"),
            status = if (page.contains("COMPLETED", ignoreCase = false)) "Terminé" else null,
            genres = listOfNotNull(genre),
        )
    }

    override suspend fun chapters(mangaId: String): List<Chapter> {
        val titleNo = TITLE_NO.find(mangaId)?.groupValues?.get(1) ?: throw IOException("Identifiant Webtoon invalide")
        val type = if (mangaId.contains("/canvas/")) "canvas" else "webtoon"
        val url = "https://m.webtoons.com/api/v1/$type/$titleNo/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("pageSize", "99999")
            .build()
        val json = Http.getJson(url)
        val list = json.getJSONObject("result").getJSONArray("episodeList")
        val out = ArrayList<Chapter>(list.length())
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val link = o.optString("viewerLink")
            if (link.isEmpty()) continue
            val no = o.optInt("episodeNo", i + 1)
            out += Chapter(
                id = link,
                title = o.optString("episodeTitle").ifEmpty { "Épisode $no" },
                number = no.toFloat(),
                date = o.optLong("exposureDateMillis").takeIf { it > 0 },
            )
        }
        return out.sortedByDescending { it.number ?: 0f }
    }

    override suspend fun pages(chapterId: String): List<String> {
        val url = if (chapterId.startsWith("http")) chapterId else base + chapterId
        val page = html(url)
        val start = page.indexOf("_imageList")
        val zone = if (start >= 0) page.substring(start, minOf(page.length, start + 400_000)).substringBefore("</div>") else page
        var urls = DATA_URL.findAll(zone).map { unescape(it.groupValues[1]) }.toList()
        if (urls.isEmpty()) {
            urls = DATA_URL.findAll(page).map { unescape(it.groupValues[1]) }.filter { it.contains("pstatic") }.toList()
        }
        if (urls.isEmpty()) throw IOException("Aucune image trouvée (épisode payant ou page modifiée)")
        return urls.distinct()
    }

    // ---------------------------------------------------------------- parsing

    private fun parseList(page: String): List<Manga> {
        val seen = HashSet<String>()
        val out = mutableListOf<Manga>()
        for (m in LINK.findAll(page)) {
            val href = m.groupValues[1].removePrefix(base)
            val titleNo = m.groupValues[2]
            if (!seen.add(titleNo)) continue
            val inner = m.groupValues[3]
            val title = TITLE.find(inner)?.groupValues?.get(1)?.let { clean(it) }?.ifEmpty { null }
                ?: ALT.find(inner)?.groupValues?.get(1)?.let { clean(it) }?.ifEmpty { null }
                ?: clean(inner).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: continue
            val cover = IMAGE.find(inner)?.groupValues?.get(1)?.let { unescape(it) }
            out += Manga(sourceId = id, id = unescape(href), title = title, cover = cover)
        }
        return out
    }

    private fun meta(page: String, property: String): String? {
        val p = Regex.escape(property)
        val a = Regex("<meta[^>]+property=\"$p\"[^>]+content=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(page)
        val b = Regex("<meta[^>]+content=\"([^\"]*)\"[^>]+property=\"$p\"", RegexOption.IGNORE_CASE).find(page)
        return (a ?: b)?.groupValues?.get(1)?.let { unescape(it) }?.trim()?.ifEmpty { null }
    }

    private fun unescape(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun clean(s: String): String =
        unescape(s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(TAGS, " "))
            .replace(Regex("[ \\t]+"), " ")
            .trim()

    companion object {
        private val OPTS = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        private val LINK = Regex(
            "<a[^>]+href=\"((?:https://www\\.webtoons\\.com)?/en/[^\"]+?/list\\?title_no=(\\d+))\"[^>]*>(.*?)</a>",
            OPTS,
        )
        private val TITLE = Regex("class=\"[^\"]*(?:title|subj)[^\"]*\"[^>]*>\\s*([^<]+?)\\s*<", OPTS)
        private val ALT = Regex("<img[^>]+alt=\"([^\"]+)\"", OPTS)
        private val IMAGE = Regex("(https?://[^\"'\\s)]*pstatic\\.net[^\"'\\s)]*)", OPTS)
        private val AUTHOR = Regex("class=\"[^\"]*author[^\"]*\"[^>]*>(.*?)</(?:div|a|span|p|h\\d)>", OPTS)
        private val DATA_URL = Regex("data-url=\"([^\"]+)\"", OPTS)
        private val TITLE_NO = Regex("title_no=(\\d+)")
        private val TAGS = Regex("<[^>]+>")
    }
}
