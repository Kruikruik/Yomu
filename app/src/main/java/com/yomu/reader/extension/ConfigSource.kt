package com.yomu.reader.extension

import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.MangasPage
import com.yomu.reader.source.Source
import org.jsoup.nodes.Document
import java.io.IOException

/**
 * Source générique pilotée par un fichier d'extension JSON (le « scraper »).
 * Identifiants : chemin relatif à baseUrl (« /manga/abc ») ou adresse complète si autre domaine.
 */
class ConfigSource(val config: ExtensionConfig) : Source {
    override val id: String = sourceIdOf(config.id)
    override val name: String = config.name
    override val lang: String = config.lang

    private val net = ExtensionHttp(config)
    override val imageHeaders: Map<String, String> get() = net.imageHeaders()

    /** Petit cache : les fiches et chapitres sont souvent sur la même page (évite de la télécharger deux fois). */
    private val docCache = object : LinkedHashMap<String, Pair<Long, Document>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Document>>?) = size > 6
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun popular(page: Int): MangasPage {
        val spec = config.popular ?: config.latest ?: return MangasPage(emptyList(), false)
        return list(spec, page, "")
    }

    override suspend fun latest(page: Int): MangasPage {
        val spec = config.latest ?: config.popular ?: return MangasPage(emptyList(), false)
        return list(spec, page, "")
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        val spec = config.search ?: return MangasPage(emptyList(), false)
        return list(spec, page, query)
    }

    private suspend fun list(spec: ListSpec, page: Int, query: String): MangasPage {
        val doc = net.document(spec.request, mapOf("page" to "$page", "page0" to "${page - 1}", "query" to query))
        val seen = HashSet<String>()
        val mangas = doc.select(spec.item).mapNotNull { el ->
            val link = spec.link.extractOne(el, absolute = true) ?: return@mapNotNull null
            val title = spec.title.extractOne(el) ?: return@mapNotNull null
            val mid = toId(link)
            if (!seen.add(mid)) return@mapNotNull null
            Manga(sourceId = id, id = mid, title = title, cover = spec.cover?.extractOne(el, absolute = true))
        }
        val hasNext = spec.nextPage?.let { f ->
            // Simple sélecteur : il suffit que l'élément existe (un bouton « suivant » peut n'avoir qu'une icône).
            if (f.attr == "text" && f.regex == null && f.css != null) doc.selectFirst(f.css) != null else f.extractOne(doc) != null
        }
            ?: (mangas.isNotEmpty() && ("{page" in spec.request.url || spec.request.body?.contains("{page") == true))
        return MangasPage(mangas, hasNext)
    }

    // ------------------------------------------------------------------ fiche

    override suspend fun details(mangaId: String): Manga {
        val d = config.details
        val doc = page(d.request, mangaId)
        val title = d.title?.extractOne(doc)
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
            ?: doc.title().ifBlank { mangaId }
        val cover = d.cover?.extractOne(doc, absolute = true)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("abs:content")?.ifBlank { null }
        val rawStatus = d.status?.extractOne(doc)
        val status = rawStatus?.let { s ->
            d.statusMap.entries.firstOrNull { s.contains(it.key, ignoreCase = true) }?.value ?: s
        }
        val genres = d.genres?.extractAll(doc)
            ?.flatMap { it.split(',') }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        return Manga(
            sourceId = id,
            id = mangaId,
            title = title,
            cover = cover,
            author = d.author?.extractAll(doc)?.distinct()?.joinToString(", ")?.ifBlank { null },
            description = d.description?.extractOne(doc)
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null },
            status = status,
            genres = genres,
        )
    }

    // ------------------------------------------------------------------ chapitres

    override suspend fun chapters(mangaId: String): List<Chapter> {
        val c = config.chapters
        val out = ArrayList<Chapter>()
        val seen = HashSet<String>()
        var doc = page(c.request, mangaId)
        val visited = HashSet<String>()
        // Listes paginées : on suit « nextPage » (50 pages max, sans boucler).
        for (i in 0 until 50) {
            visited += doc.location()
            for (el in doc.select(c.item)) {
                val link = c.link.extractOne(el, absolute = true) ?: continue
                val cid = toId(link)
                if (!seen.add(cid)) continue
                val title = c.title?.extractOne(el)
                    ?: c.link.copy(attr = "text").extractOne(el)
                    ?: "Chapitre ${out.size + 1}"
                out += Chapter(
                    id = cid,
                    title = title,
                    number = c.number?.extractOne(el)?.let(::parseNumber) ?: guessChapterNumber(title),
                    date = c.date?.extractOne(el)?.let { parseDate(it, c.dateFormat, c.dateLocale) },
                    scanlator = c.scanlator?.extractOne(el),
                )
            }
            val next = c.nextPage?.asLink()?.extractOne(doc, absolute = true) ?: break
            if (next in visited) break
            doc = net.document(next)
        }
        return if (c.reverse) out.reversed() else out
    }

    // ------------------------------------------------------------------ images

    override suspend fun pages(chapterId: String): List<String> {
        val p = config.pages
        val images = LinkedHashSet<String>()
        var doc = page(p.request, chapterId, useCache = false)
        val visited = HashSet<String>()
        for (i in 0 until 300) {
            visited += doc.location()
            images += p.image.extractAll(doc, absolute = true)
            val next = p.nextPage?.asLink()?.extractOne(doc, absolute = true) ?: break
            if (next in visited) break
            doc = net.document(next)
        }
        if (images.isEmpty()) throw IOException("Aucune image trouvée : vérifie « pages.image » dans l'extension ${config.id}")
        return images.toList()
    }

    // ------------------------------------------------------------------ outils

    /** Page d'une série / d'un chapitre : URL du fichier si fournie, sinon l'adresse de l'élément. */
    private suspend fun page(request: RequestSpec?, itemId: String, useCache: Boolean = true): Document {
        val url = urlOf(itemId)
        val spec = request ?: RequestSpec(url = "{url}")
        val vars = mapOf("id" to itemId.trimStart('/'), "url" to url)
        val key = fillTemplate(spec.url, vars + ("baseUrl" to config.baseUrl)) + "|" + spec.body
        if (useCache) {
            synchronized(docCache) {
                docCache[key]?.let { (time, d) -> if (System.currentTimeMillis() - time < 60_000) return d }
            }
        }
        val doc = net.document(spec, vars)
        if (useCache) synchronized(docCache) { docCache[key] = System.currentTimeMillis() to doc }
        return doc
    }

    /** « a.next » sans attribut = son lien (href). */
    private fun Field.asLink(): Field = if (attr == "text" && regex == null) copy(attr = "href") else this

    private fun toId(absoluteUrl: String): String =
        if (absoluteUrl.startsWith(config.baseUrl)) absoluteUrl.removePrefix(config.baseUrl).ifEmpty { "/" } else absoluteUrl

    private fun urlOf(itemId: String): String = when {
        itemId.startsWith("http://") || itemId.startsWith("https://") -> itemId
        itemId.startsWith("/") -> config.baseUrl + itemId
        else -> "${config.baseUrl}/$itemId"
    }

    companion object {
        fun sourceIdOf(extensionId: String) = "ext-$extensionId"
    }
}
