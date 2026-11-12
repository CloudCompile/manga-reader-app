package com.example.data

import android.content.Context
import com.example.models.ChapterData
import com.example.models.ChapterItem
import com.example.models.HistoryEntry
import com.example.models.TitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talks to the deployed Manga Reader web API (the same service the web app uses).
 *
 * Endpoint contract (matches the Next.js routes):
 * - GET  /api/titles                -> { titles: [...] }
 * - POST /api/chapters {url}        -> { chapters: [...], volumes: [...] }
 * - POST /api/scrape    {url}       -> ChapterData
 * - GET  /api/search?q=             -> [ { title, latestChapter, url, source } ]
 * - GET  /api/proxy?url=            -> proxied page image (source sites block hotlinking)
 */
class MangaRepository(
    private val baseUrl: String = "https://mangareader.cjhauser.me",
    context: Context? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val prefs = context?.getSharedPreferences("manga_reader_prefs", Context.MODE_PRIVATE)

    @Serializable
    private data class TitlesResponse(val titles: List<TitleItem> = emptyList())

    @Serializable
    private data class ChapterListResponse(
        val chapters: List<ChapterItem> = emptyList(),
        val volumes: List<String> = emptyList()
    )

    // Preloaded curated titles for offline / first-launch use
    private val fallbackTitles = listOf(
        TitleItem("Berserk", "https://specterscans.com/series/berserk", "specterscans", "Spectre Scans"),
        TitleItem("Koe No Katachi", "https://specterscans.com/series/koe-no-katachi", "specterscans", "Spectre Scans"),
        TitleItem("Chainsaw Man", "https://dandadana.com/", "dandadana", "Dandadan"),
        TitleItem("Dandadan", "https://dandadana.com/", "dandadana", "Dandadan"),
        TitleItem("Oshi No Ko", "https://readoshino.com/", "oshino", "Oshino"),
        TitleItem("Blue Box", "https://blue-box-manga.online/", "bluebox", "Blue Box"),
        TitleItem("Kagurabachi", "https://komicantcommunicatechapters.website/", "komic", "Komic"),
        TitleItem("The Fragrant Flower Blooms with Dignity", "https://thefragrantflowerbloomswithdignity.com/", "fragrant", "Fragrant")
    )

    suspend fun fetchTitles(): List<TitleItem> = withContext(Dispatchers.IO) {
        val fetched = runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/titles")
                .header("User-Agent", UA)
                .build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body?.string().orEmpty()
                json.decodeFromString(TitlesResponse.serializer(), body).titles
            }
        }.getOrNull().orEmpty()
        if (fetched.isNotEmpty()) fetched else fallbackTitles
    }

    suspend fun fetchChapters(seriesUrl: String): List<ChapterItem> = withContext(Dispatchers.IO) {
        runCatching {
            postJson(
                "/api/chapters",
                """{"url":${json.encodeToString(serializer<String>(), seriesUrl)}}""",
                ChapterListResponse.serializer()
            ).chapters
        }.getOrElse { emptyList() }
    }

    suspend fun fetchChapterData(chapterUrl: String): ChapterData? = withContext(Dispatchers.IO) {
        runCatching {
            val data = postJson(
                "/api/scrape",
                """{"url":${json.encodeToString(serializer<String>(), chapterUrl)}}""",
                ChapterData.serializer()
            )
            // The web reader loads every page through the server's image proxy
            // because source sites block direct hotlinking; mirror that here.
            data.copy(images = data.images.map { proxied(it) })
        }.getOrNull()
    }

    /** Full-text search across every source. Returns titles ready to open. */
    suspend fun search(query: String): List<TitleItem> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body?.string().orEmpty()
                json.decodeFromString(ListSerializer(SearchHit.serializer()), body)
            }.map { hit ->
                TitleItem(
                    title = hit.title.ifBlank { hit.url },
                    url = hit.url,
                    sourceId = hit.source,
                    sourceName = hit.source,
                    latestChapter = hit.latestChapter
                )
            }
        }.getOrElse { emptyList() }
    }

    @Serializable
    private data class SearchHit(
        val title: String = "",
        val latestChapter: String = "",
        val url: String = "",
        val source: String = ""
    )

    // ---- Persistence: history & bookmarks survive app restarts ----------

    fun getHistory(): List<HistoryEntry> = prefs?.getString(KEY_HISTORY, null)
        ?.let { decodeList(it, HistoryEntry.serializer()) }
        ?: emptyList()

    fun recordHistory(entry: HistoryEntry) {
        val current = getHistory().toMutableList()
        current.removeAll { it.url == entry.url }
        current.add(0, entry)
        while (current.size > 50) current.removeAt(current.lastIndex)
        prefs?.edit()?.putString(KEY_HISTORY, encodeList(current, HistoryEntry.serializer()))?.apply()
    }

    fun getBookmarks(): List<TitleItem> = prefs?.getString(KEY_BOOKMARKS, null)
        ?.let { decodeList(it, TitleItem.serializer()) }
        ?: emptyList()

    fun clearHistory() {
        prefs?.edit()?.remove(KEY_HISTORY)?.apply()
    }

    fun toggleBookmark(title: TitleItem): Boolean {
        val current = getBookmarks().toMutableList()
        val exists = current.any { it.url == title.url }
        if (exists) {
            current.removeAll { it.url == title.url }
        } else {
            current.add(0, title)
        }
        prefs?.edit()?.putString(KEY_BOOKMARKS, encodeList(current, TitleItem.serializer()))?.apply()
        return !exists
    }

    // ---- Helpers ---------------------------------------------------------

    private val UA = "MangaReader-Android/1.1"

    private fun proxied(src: String): String =
        if (src.startsWith("http")) {
            "$baseUrl/api/proxy?url=" + URLEncoder.encode(src, "UTF-8")
        } else {
            src
        }

    private fun <T> postJson(path: String, body: String, serializer: KSerializer<T>): T {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", UA)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val text = resp.body?.string().orEmpty()
            return json.decodeFromString(serializer, text)
        }
    }

    private fun <T> encodeList(values: List<T>, serializer: KSerializer<T>): String =
        json.encodeToString(ListSerializer(serializer), values)

    private fun <T> decodeList(raw: String, serializer: KSerializer<T>): List<T>? =
        runCatching { json.decodeFromString(ListSerializer(serializer), raw) }.getOrNull()

    private companion object {
        const val KEY_HISTORY = "history"
        const val KEY_BOOKMARKS = "bookmarks"
    }
}
