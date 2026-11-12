package com.example.models

import kotlinx.serialization.Serializable

@Serializable
data class TitleItem(
    val title: String,
    val url: String,
    val sourceId: String,
    val sourceName: String,
    val latestChapter: String = "",
    val totalChapters: Int = 0,
    val thumbnail: String? = null,
    val aliases: List<String> = emptyList()
)

@Serializable
data class ChapterItem(
    val num: Double,
    val url: String,
    val title: String,
    val volume: String? = null
)

@Serializable
data class ChapterData(
    val title: String,
    val images: List<String> = emptyList(),
    val prevUrl: String? = null,
    val nextUrl: String? = null,
    val mangaName: String = "",
    val chapterNum: String = "",
    val seriesSlug: String = "",
    val sourceId: String? = null,
    val sourceName: String? = null
)

@Serializable
data class MangaDetails(
    val title: String,
    val description: String? = null,
    val cover: String? = null,
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val status: String? = null
)

@Serializable
data class HistoryEntry(
    val url: String,
    val mangaName: String,
    val chapterNum: String,
    val timestamp: Long = System.currentTimeMillis(),
    val page: Int = 0
)

enum class FitMode {
    WIDTH, HEIGHT, ORIGINAL
}
