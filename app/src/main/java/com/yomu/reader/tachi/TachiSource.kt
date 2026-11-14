package com.yomu.reader.tachi

import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.MangasPage
import com.yomu.reader.source.Source
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import eu.kanade.tachiyomi.source.model.MangasPage as TachiMangasPage

/**
 * Adapte une source d'extension Tachiyomi/Mihon à l'interface [Source] de Yomu.
 * Identifiants : l'URL relative de la série / du chapitre, comme dans Mihon.
 */
class TachiSource(val tachi: CatalogueSource, val pkgName: String) : Source {
    override val id: String = sourceIdOf(tachi.id)
    override val name: String = tachi.name
    override val lang: String = tachi.lang

    private val http: HttpSource? = tachi as? HttpSource

    /**
     * Les images passent par le client de l'extension (en-têtes, intercepteurs, déchiffrement…).
     * L'en-tête [ROUTE_HEADER] dit à [TachiImageInterceptor] quelle source utiliser.
     */
    override val imageHeaders: Map<String, String> get() = mapOf(ROUTE_HEADER to id)

    /** URL renvoyée au lecteur → page d'origine (pour imageRequest / getImageUrl). */
    private val pagesByUrl = object : LinkedHashMap<String, Page>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Page>?) = size > 3000
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun popular(page: Int): MangasPage = io { tachi.getPopularManga(page).toYomu() }

    override suspend fun latest(page: Int): MangasPage = io {
        if (tachi.supportsLatest) tachi.getLatestUpdates(page).toYomu() else tachi.getPopularManga(page).toYomu()
    }

    override suspend fun search(query: String, page: Int): MangasPage =
        io { tachi.getSearchManga(page, query, tachi.getFilterList()).toYomu() }

    override suspend fun findByTitle(title: String): Manga? =
        search(title, 1).mangas.firstOrNull { it.title.equals(title, ignoreCase = true) }

    // ------------------------------------------------------------------ fiche et chapitres

    override suspend fun details(mangaId: String): Manga = io {
        val update = tachi.getMangaUpdate(sManga(mangaId), emptyList(), fetchDetails = true, fetchChapters = false)
        update.manga.toYomu(mangaId)
    }

    override suspend fun chapters(mangaId: String): List<Chapter> = io {
        val manga = sManga(mangaId)
        val update = tachi.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)
        update.chapters.map { ch ->
            http?.prepareNewChapter(ch, manga)
            Chapter(
                id = ch.url,
                title = ch.name.ifBlank { "Chapitre" },
                number = ch.chapter_number.takeIf { it >= 0f },
                date = ch.date_upload.takeIf { it > 0L },
                scanlator = ch.scanlator?.ifBlank { null },
            )
        }
    }

    // ------------------------------------------------------------------ images

    override suspend fun pages(chapterId: String): List<String> = io {
        val chapter = SChapter.create().apply {
            url = chapterId
            name = ""
        }
        val pages = tachi.getPageList(chapter)
        if (pages.isEmpty()) throw IOException("Aucune page renvoyée par l'extension")
        pages.mapNotNull { p ->
            // Sans imageUrl, on renvoie l'URL de la page : l'image sera résolue au moment de l'afficher.
            val key = p.imageUrl?.takeIf { it.isNotBlank() } ?: p.url.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            synchronized(pagesByUrl) { pagesByUrl[key] = p }
            key
        }
    }

    /** Télécharge une image avec le client et les règles de l'extension. */
    internal fun fetchImage(url: String): Response {
        val source = http ?: throw IOException("Cette extension ne fournit pas d'images HTTP")
        val known = synchronized(pagesByUrl) { pagesByUrl[url] }
        val page = known ?: Page(0, "", url)
        return runBlocking {
            if (page.imageUrl.isNullOrBlank()) page.imageUrl = source.getImageUrl(page)
            source.getImage(page)
        }
    }

    // ------------------------------------------------------------------ conversions

    private fun sManga(url: String) = SManga.create().apply {
        this.url = url
        title = ""
    }

    private fun TachiMangasPage.toYomu() = MangasPage(
        mangas = mangas.distinctBy { it.url }.map { it.toYomu(it.url) },
        hasNextPage = hasNextPage,
    )

    private fun SManga.toYomu(mangaId: String): Manga {
        // Les champs d'un SManga non initialisé lèvent une exception : on lit prudemment.
        val t = runCatching { title }.getOrDefault("")
        return Manga(
            sourceId = id,
            id = mangaId,
            title = t.ifBlank { mangaId },
            cover = thumbnail_url?.ifBlank { null },
            author = listOfNotNull(author, artist).flatMap { it.split(',') }.map { it.trim() }
                .filter { it.isNotEmpty() }.distinct().joinToString(", ").ifBlank { null },
            description = description?.ifBlank { null },
            status = statusLabel(status),
            genres = getGenres().orEmpty(),
        )
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val ROUTE_HEADER = "X-Yomu-Tachi-Source"
        fun sourceIdOf(tachiId: Long) = "tachi-$tachiId"

        fun statusLabel(status: Int): String? = when (status) {
            SManga.ONGOING -> "En cours"
            SManga.COMPLETED -> "Terminé"
            SManga.LICENSED -> "Licencié"
            SManga.PUBLISHING_FINISHED -> "Publication terminée"
            SManga.CANCELLED -> "Annulé"
            SManga.ON_HIATUS -> "En pause"
            else -> null
        }
    }
}

/**
 * Branché sur [com.yomu.reader.net.Http.client] : les images des sources Tachiyomi (lecteur,
 * téléchargements, couvertures) sont chargées par le client de l'extension.
 */
object TachiImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val sourceId = request.header(TachiSource.ROUTE_HEADER) ?: return chain.proceed(request)
        val source = TachiExtensions.tachiSource(sourceId)
            ?: return chain.proceed(request.newBuilder().removeHeader(TachiSource.ROUTE_HEADER).build())
        return source.fetchImage(request.url.toString())
    }
}
