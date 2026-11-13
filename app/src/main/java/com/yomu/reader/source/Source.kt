package com.yomu.reader.source

import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.MangasPage

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
}

object SourceManager {
    val all: List<Source> = listOf(
        MangaDexSource("en"),
    )

    fun get(id: String): Source? = all.firstOrNull { it.id == id }
}

fun langName(code: String): String = when (code) {
    "fr" -> "Français"
    "en" -> "Anglais"
    else -> code.uppercase()
}
