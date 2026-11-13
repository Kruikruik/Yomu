package com.yomu.reader.source

import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.MangasPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Une source de lecture. Pour en ajouter une : créer une classe qui implémente
 * cette interface, puis l'ajouter dans [SourceManager.all].
 */
interface Source {
    /** Identifiant unique et stable (sert de clé dans la bibliothèque). */
    val id: String
    val name: String
    val lang: String

    /** En-têtes HTTP à envoyer avec chaque image (ex. Referer). */
    val imageHeaders: Map<String, String> get() = emptyMap()

    suspend fun popular(page: Int): MangasPage
    suspend fun latest(page: Int): MangasPage
    suspend fun search(query: String, page: Int): MangasPage
    suspend fun details(mangaId: String): Manga

    /** Chapitres triés du plus récent au plus ancien. */
    suspend fun chapters(mangaId: String): List<Chapter>

    /** URLs des images d'un chapitre, dans l'ordre de lecture. */
    suspend fun pages(chapterId: String): List<String>

    /** Retrouve une série par son titre exact (pour les imports). null = pas trouvée / pas géré. */
    suspend fun findByTitle(title: String): Manga? = null

    /** Filtres proposés par la source dans le catalogue. */
    suspend fun filters(): List<FilterGroup> = emptyList()

    /** Catalogue filtré (et recherche éventuelle). Par défaut : populaires ou recherche simple. */
    suspend fun browse(page: Int, query: String, filters: FilterState): MangasPage =
        if (query.isBlank()) popular(page) else search(query, page)
}

data class FilterOption(val value: String, val label: String)

/**
 * Groupe de filtres.
 * multi = plusieurs choix possibles ; triState = chaque choix peut être inclus (1) ou exclu (-1).
 */
data class FilterGroup(
    val key: String,
    val label: String,
    val options: List<FilterOption>,
    val multi: Boolean = false,
    val triState: Boolean = false,
)

/** Sélection : groupe → (valeur → 1 inclus / -1 exclu). */
typealias FilterState = Map<String, Map<String, Int>>

fun FilterState.included(group: String): List<String> = this[group].orEmpty().filterValues { it > 0 }.keys.toList()
fun FilterState.excluded(group: String): List<String> = this[group].orEmpty().filterValues { it < 0 }.keys.toList()
fun FilterState.single(group: String): String? = included(group).firstOrNull()
fun FilterState.activeCount(): Int = values.sumOf { m -> m.count { it.value != 0 } }

/** Compare deux titres en ignorant casse, ponctuation et espaces. */
fun normalizeTitle(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

object SourceManager {
    val all: List<Source> = listOf(
        MangaDexSource("en"),
        WebtoonSource(),
        LocalSource(),
    ).map { BackgroundSource(it) }

    fun get(id: String): Source? = all.firstOrNull { it.id == id }
}

/** Exécute tout le travail d'une source hors du thread de l'interface (pas de saccades). */
private class BackgroundSource(private val inner: Source) : Source by inner {
    override suspend fun popular(page: Int): MangasPage = withContext(Dispatchers.Default) { inner.popular(page) }
    override suspend fun latest(page: Int): MangasPage = withContext(Dispatchers.Default) { inner.latest(page) }
    override suspend fun search(query: String, page: Int): MangasPage =
        withContext(Dispatchers.Default) { inner.search(query, page) }
    override suspend fun details(mangaId: String): Manga = withContext(Dispatchers.Default) { inner.details(mangaId) }
    override suspend fun chapters(mangaId: String): List<Chapter> =
        withContext(Dispatchers.Default) { inner.chapters(mangaId) }
    override suspend fun pages(chapterId: String): List<String> = withContext(Dispatchers.Default) { inner.pages(chapterId) }
    override suspend fun findByTitle(title: String): Manga? = withContext(Dispatchers.Default) { inner.findByTitle(title) }
    override suspend fun filters(): List<FilterGroup> = withContext(Dispatchers.Default) { inner.filters() }
    override suspend fun browse(page: Int, query: String, filters: FilterState): MangasPage =
        withContext(Dispatchers.Default) { inner.browse(page, query, filters) }
}

fun langName(code: String): String = when (code) {
    "fr" -> "Français"
    "en" -> "Anglais"
    "local" -> "Fichiers du téléphone"
    else -> code.uppercase()
}
