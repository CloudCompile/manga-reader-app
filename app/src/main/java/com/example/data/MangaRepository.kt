package com.example.data

import com.example.models.ChapterData
import com.example.models.ChapterItem
import com.example.models.HistoryEntry
import com.example.models.TitleItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MangaRepository(
    private val baseUrl: String = "https://ais-dev-zcqbz5c27kmw3ydslovghv-484637397614.us-east1.run.app"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private val history = mutableListOf<HistoryEntry>()
    private val bookmarks = mutableListOf<TitleItem>()

    // Preloaded curated titles in case network is offline
    private val fallbackTitles = listOf(
        TitleItem("Berserk", "https://specterscans.com/series/berserk", "specterscans", "Spectre Scans", "Chapter 376", 376, "https://cdn.specterscans.com/covers/berserk.jpg"),
        TitleItem("Chainsaw Man", "https://chainsaw-man-manga.online/", "chainsawman", "Chainsaw Man", "Chapter 180", 180, "https://cdn.specterscans.com/covers/chainsaw-man.jpg"),
        TitleItem("Dandadan", "https://dandadan-manga.com/", "dandadana", "Dandadan", "Chapter 170", 170, "https://cdn.specterscans.com/covers/dandadan.jpg"),
        TitleItem("Oshi no Ko", "https://oshinoko-manga.online/", "oshino", "Oshi no Ko", "Chapter 166", 166, "https://cdn.specterscans.com/covers/oshi-no-ko.jpg"),
        TitleItem("Blue Box", "https://bluebox-manga.com/", "bluebox", "Blue Box", "Chapter 175", 175, "https://cdn.specterscans.com/covers/blue-box.jpg"),
        TitleItem("Solo Leveling", "https://specterscans.com/series/solo-leveling", "specterscans", "Spectre Scans", "Chapter 200", 200, "https://cdn.specterscans.com/covers/solo-leveling.jpg"),
        TitleItem("Jujutsu Kaisen", "https://specterscans.com/series/jujutsu-kaisen", "specterscans", "Spectre Scans", "Chapter 271", 271, "https://cdn.specterscans.com/covers/jujutsu-kaisen.jpg"),
        TitleItem("Vinland Saga", "https://specterscans.com/series/vinland-saga", "specterscans", "Spectre Scans", "Chapter 215", 215, "https://cdn.specterscans.com/covers/vinland-saga.jpg")
    )

    suspend fun fetchTitles(): List<TitleItem> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/titles")
                .header("User-Agent", "MangaReader-Android/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val map: Map<String, Any> = gson.fromJson(body, type)
                    val titlesJson = gson.toJson(map["titles"])
                    val listType = object : TypeToken<List<TitleItem>>() {}.type
                    val parsed: List<TitleItem> = gson.fromJson(titlesJson, listType)
                    if (parsed.isNotEmpty()) return@withContext parsed
                }
            }
        } catch (_: Exception) {
            // fallback gracefully
        }
        fallbackTitles
    }

    suspend fun fetchChapters(seriesUrl: String): List<ChapterItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(seriesUrl, "UTF-8")
            val req = Request.Builder()
                .url("$baseUrl/api/chapters?url=$encoded")
                .header("User-Agent", "MangaReader-Android/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val map: Map<String, Any> = gson.fromJson(body, type)
                    val chJson = gson.toJson(map["chapters"])
                    val listType = object : TypeToken<List<ChapterItem>>() {}.type
                    return@withContext gson.fromJson(chJson, listType)
                }
            }
        } catch (_: Exception) {
            // fallback
        }
        emptyList()
    }

    suspend fun fetchChapterData(chapterUrl: String): ChapterData? = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(chapterUrl, "UTF-8")
            val req = Request.Builder()
                .url("$baseUrl/api/scrape?url=$encoded")
                .header("User-Agent", "MangaReader-Android/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    return@withContext gson.fromJson(body, ChapterData::class.java)
                }
            }
        } catch (_: Exception) {
            // fallback
        }
        null
    }

    fun getHistory(): List<HistoryEntry> = history.toList()

    fun recordHistory(entry: HistoryEntry) {
        history.removeAll { it.url == entry.url }
        history.add(0, entry)
        if (history.size > 50) history.removeAt(history.lastIndex)
    }

    fun getBookmarks(): List<TitleItem> = bookmarks.toList()

    fun toggleBookmark(title: TitleItem): Boolean {
        val exists = bookmarks.any { it.url == title.url }
        if (exists) {
            bookmarks.removeAll { it.url == title.url }
            return false
        } else {
            bookmarks.add(0, title)
            return true
        }
    }
}
