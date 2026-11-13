package com.yomu.reader.data

import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import java.util.concurrent.ConcurrentHashMap

/** Cache mémoire des séries vues pendant la session. */
object MangaCache {
    private val map = ConcurrentHashMap<String, Manga>()
    fun put(manga: Manga) { map[manga.key] = manga }
    fun get(key: String): Manga? = map[key]
}

/** Cache mémoire des listes de chapitres (sert au lecteur pour chapitre suivant/précédent). */
object ChapterCache {
    private val map = ConcurrentHashMap<String, List<Chapter>>()
    fun put(key: String, chapters: List<Chapter>) { map[key] = chapters }
    fun get(key: String): List<Chapter>? = map[key]
}
