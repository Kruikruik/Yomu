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
    fun putIfAbsent(key: String, chapters: List<Chapter>) { map.putIfAbsent(key, chapters) }
    fun get(key: String): List<Chapter>? = map[key]
}

/** Proportions des pages déjà vues : évite que le lecteur « saute » quand on remonte. */
object PageSizes {
    private val cache = android.util.LruCache<String, Float>(3000)
    fun get(url: String): Float? = cache.get(url)
    fun put(url: String, ratio: Float) { if (ratio > 0f) cache.put(url, ratio) }
}
