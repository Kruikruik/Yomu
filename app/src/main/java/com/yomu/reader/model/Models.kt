package com.yomu.reader.model

data class Manga(
    val sourceId: String,
    val id: String,
    val title: String,
    val cover: String? = null,
    val author: String? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
) {
    val key: String get() = keyOf(sourceId, id)
}

fun keyOf(sourceId: String, mangaId: String): String = "$sourceId::$mangaId"

data class Chapter(
    val id: String,
    val title: String,
    val number: Float? = null,
    val date: Long? = null,
    val scanlator: String? = null,
)

data class MangasPage(val mangas: List<Manga>, val hasNextPage: Boolean)
