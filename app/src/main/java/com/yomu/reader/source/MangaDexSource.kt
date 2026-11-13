package com.yomu.reader.source

import com.yomu.reader.data.Prefs
import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.MangasPage
import com.yomu.reader.net.Http
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** Source basée sur l'API publique officielle de MangaDex (api.mangadex.org). */
class MangaDexSource(override val lang: String) : Source {

    override val id: String = "mangadex-$lang"
    override val name: String = "MangaDex"

    private val api = "https://api.mangadex.org".toHttpUrl()
    private val listRatings = listOf("safe", "suggestive")
    private val feedRatings = listOf("safe", "suggestive", "erotica", "pornographic")

    override suspend fun popular(page: Int): MangasPage =
        list(page) { addQueryParameter("order[followedCount]", "desc") }

    override suspend fun latest(page: Int): MangasPage =
        list(page) { addQueryParameter("order[latestUploadedChapter]", "desc") }

    override suspend fun search(query: String, page: Int): MangasPage =
        list(page) {
            addQueryParameter("title", query)
            addQueryParameter("order[relevance]", "desc")
        }

    private suspend fun list(page: Int, extra: HttpUrl.Builder.() -> Unit): MangasPage {
        val url = api.newBuilder()
            .addPathSegment("manga")
            .addQueryParameter("limit", LIMIT.toString())
            .addQueryParameter("offset", ((page - 1) * LIMIT).toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("availableTranslatedLanguage[]", lang)
            .apply { listRatings.forEach { addQueryParameter("contentRating[]", it) } }
            .apply(extra)
            .build()
        val json = Http.getJson(url)
        val data = json.getJSONArray("data")
        val mangas = (0 until data.length()).map { parseManga(data.getJSONObject(it)) }
        val total = json.optInt("total", 0)
        val loaded = page * LIMIT
        return MangasPage(mangas, loaded < total && loaded + LIMIT <= 10_000)
    }

    override suspend fun details(mangaId: String): Manga {
        val url = api.newBuilder()
            .addPathSegment("manga")
            .addPathSegment(mangaId)
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .build()
        return parseManga(Http.getJson(url).getJSONObject("data"))
    }

    override suspend fun chapters(mangaId: String): List<Chapter> {
        val out = mutableListOf<Chapter>()
        var offset = 0
        while (true) {
            val url = api.newBuilder()
                .addPathSegment("manga")
                .addPathSegment(mangaId)
                .addPathSegment("feed")
                .addQueryParameter("limit", FEED_LIMIT.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("translatedLanguage[]", lang)
                .addQueryParameter("order[volume]", "desc")
                .addQueryParameter("order[chapter]", "desc")
                .addQueryParameter("includes[]", "scanlation_group")
                .apply { feedRatings.forEach { addQueryParameter("contentRating[]", it) } }
                .build()
            val json = Http.getJson(url)
            val data = json.getJSONArray("data")
            for (i in 0 until data.length()) {
                parseChapter(data.getJSONObject(i))?.let { out += it }
            }
            offset += FEED_LIMIT
            if (data.length() == 0 || offset >= json.optInt("total", 0) || offset >= 5_000) break
        }
        return out.sortedWith(
            compareByDescending<Chapter> { it.number ?: -1f }.thenByDescending { it.date ?: 0L }
        )
    }

    override suspend fun pages(chapterId: String): List<String> {
        val url = api.newBuilder()
            .addPathSegment("at-home")
            .addPathSegment("server")
            .addPathSegment(chapterId)
            .build()
        val json = Http.getJson(url)
        val base = json.getString("baseUrl")
        val chapter = json.getJSONObject("chapter")
        val hash = chapter.getString("hash")
        val saver = Prefs.dataSaver
        val files = chapter.getJSONArray(if (saver) "dataSaver" else "data")
        val folder = if (saver) "data-saver" else "data"
        return (0 until files.length()).map { "$base/$folder/$hash/${files.getString(it)}" }
    }

    /**
     * Retrouve une série par son titre (titre principal ou titres alternatifs).
     * Sert à l'import des sauvegardes Tachiyomi / Mihon.
     */
    suspend fun findByTitle(title: String): Manga? {
        val target = normalize(title)
        if (target.isEmpty()) return null
        val url = api.newBuilder()
            .addPathSegment("manga")
            .addQueryParameter("limit", "10")
            .addQueryParameter("title", title)
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("availableTranslatedLanguage[]", lang)
            .addQueryParameter("order[relevance]", "desc")
            .apply { feedRatings.forEach { addQueryParameter("contentRating[]", it) } }
            .build()
        val data = Http.getJson(url).getJSONArray("data")
        var fallback: JSONObject? = null
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val a = o.getJSONObject("attributes")
            val names = mutableListOf<String>()
            a.optJSONObject("title")?.let { t -> t.keys().forEach { names += t.optString(it) } }
            a.optJSONArray("altTitles")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.let { t -> t.keys().forEach { names += t.optString(it) } }
                }
            }
            val normalized = names.map { normalize(it) }.filter { it.isNotEmpty() }
            if (normalized.any { it == target }) return parseManga(o)
            if (fallback == null && target.length >= 6 &&
                normalized.any { it.length >= 6 && (it.contains(target) || target.contains(it)) }
            ) fallback = o
        }
        return fallback?.let { parseManga(it) }
    }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- parsing

    private fun parseManga(o: JSONObject): Manga {
        val mangaId = o.getString("id")
        val a = o.getJSONObject("attributes")

        var cover: String? = null
        val authors = mutableListOf<String>()
        val rels = o.optJSONArray("relationships") ?: JSONArray()
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            val attrs = r.optJSONObject("attributes") ?: continue
            when (r.optString("type")) {
                "cover_art" -> {
                    val file = attrs.optString("fileName")
                    if (file.isNotEmpty()) cover = "https://uploads.mangadex.org/covers/$mangaId/$file.512.jpg"
                }
                "author", "artist" -> {
                    val n = attrs.optString("name")
                    if (n.isNotEmpty() && n !in authors) authors += n
                }
            }
        }

        val titles = a.optJSONObject("title")
        val altTitles = a.optJSONArray("altTitles")
        var altLocal: String? = null
        if (altTitles != null) {
            for (i in 0 until altTitles.length()) {
                val t = altTitles.optJSONObject(i)?.optString(lang).orEmpty()
                if (t.isNotEmpty()) { altLocal = t; break }
            }
        }
        val mainLocal = titles?.optString(lang).orEmpty().ifEmpty { null }
        val title = mainLocal ?: altLocal ?: localized(titles) ?: "Sans titre"

        val status = when (a.optString("status")) {
            "ongoing" -> "En cours"
            "completed" -> "Terminé"
            "hiatus" -> "En pause"
            "cancelled" -> "Annulé"
            else -> null
        }

        val tags = mutableListOf<String>()
        val tagArr = a.optJSONArray("tags")
        if (tagArr != null) {
            for (i in 0 until tagArr.length()) {
                val n = tagArr.optJSONObject(i)?.optJSONObject("attributes")?.optJSONObject("name")
                localized(n)?.let { tags += it }
            }
        }

        return Manga(
            sourceId = id,
            id = mangaId,
            title = title,
            cover = cover,
            author = authors.joinToString(", ").ifEmpty { null },
            description = localized(a.optJSONObject("description")),
            status = status,
            genres = tags,
        )
    }

    private fun parseChapter(o: JSONObject): Chapter? {
        val a = o.getJSONObject("attributes")
        if (!a.isNull("externalUrl")) return null
        if (a.optInt("pages", 0) == 0) return null

        val vol = a.str("volume")
        val num = a.str("chapter")
        val name = a.str("title")
        val title = buildString {
            if (vol != null) append("Vol. ").append(vol).append(' ')
            if (num != null) append("Ch. ").append(num) else if (vol == null) append("Oneshot")
            if (name != null) {
                if (isNotEmpty()) append(" - ")
                append(name)
            }
        }.trim()

        val date = a.str("publishAt")?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }

        var group: String? = null
        val rels = o.optJSONArray("relationships")
        if (rels != null) {
            for (i in 0 until rels.length()) {
                val r = rels.getJSONObject(i)
                if (r.optString("type") == "scanlation_group") {
                    group = r.optJSONObject("attributes")?.str("name")
                    if (group != null) break
                }
            }
        }

        return Chapter(
            id = o.getString("id"),
            title = title.ifEmpty { "Chapitre" },
            number = num?.toFloatOrNull(),
            date = date,
            scanlator = group,
        )
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun localized(obj: JSONObject?): String? {
        if (obj == null) return null
        for (k in listOf(lang, "en", "ja-ro", "ko-ro", "ja", "ko")) {
            val v = obj.optString(k)
            if (v.isNotEmpty()) return v
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val v = obj.optString(keys.next())
            if (v.isNotEmpty()) return v
        }
        return null
    }

    companion object {
        private const val LIMIT = 24
        private const val FEED_LIMIT = 500
    }
}
