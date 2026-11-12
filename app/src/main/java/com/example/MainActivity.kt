package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.example.data.MangaRepository
import com.example.models.ChapterData
import com.example.models.ChapterItem
import com.example.models.HistoryEntry
import com.example.models.TitleItem
import com.example.ui.screens.GooglePlayPublishDialog
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.theme.MangaReaderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val repository = MangaRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var titles by remember { mutableStateOf<List<TitleItem>>(emptyList()) }
            var isTitlesLoading by remember { mutableStateOf(true) }
            var bookmarkedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
            var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }

            // Current reader state
            var currentChapterData by remember { mutableStateOf<ChapterData?>(null) }
            var isReaderLoading by remember { mutableStateOf(false) }
            var isReading by remember { mutableStateOf(false) }

            // Dialogs
            var showPlayDialog by remember { mutableStateOf(false) }
            var showBookmarksDialog by remember { mutableStateOf(false) }
            var showHistoryDialog by remember { mutableStateOf(false) }

            // Chapter list picker dialog
            var selectedTitleForChapters by remember { mutableStateOf<TitleItem?>(null) }
            var chaptersList by remember { mutableStateOf<List<ChapterItem>>(emptyList()) }
            var isChaptersLoading by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                titles = repository.fetchTitles()
                isTitlesLoading = false
                bookmarkedUrls = repository.getBookmarks().map { it.url }.toSet()
                history = repository.getHistory()
            }

            fun openChapter(chapterUrl: String, mangaName: String) {
                isReading = true
                isReaderLoading = true
                lifecycleScope.launch {
                    val data = repository.fetchChapterData(chapterUrl)
                    currentChapterData = data
                    isReaderLoading = false
                    if (data != null) {
                        repository.recordHistory(
                            HistoryEntry(
                                url = chapterUrl,
                                mangaName = mangaName.ifEmpty { data.mangaName },
                                chapterNum = data.chapterNum
                            )
                        )
                        history = repository.getHistory()
                    }
                }
            }

            fun openTitle(titleItem: TitleItem) {
                selectedTitleForChapters = titleItem
                isChaptersLoading = true
                lifecycleScope.launch {
                    val list = repository.fetchChapters(titleItem.url)
                    chaptersList = list
                    isChaptersLoading = false
                }
            }

            MangaReaderTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isReading) {
                        ReaderScreen(
                            chapterData = currentChapterData,
                            isLoading = isReaderLoading,
                            onBack = { isReading = false },
                            onPrevChapter = {
                                currentChapterData?.prevUrl?.let {
                                    openChapter(it, currentChapterData?.mangaName ?: "")
                                }
                            },
                            onNextChapter = {
                                currentChapterData?.nextUrl?.let {
                                    openChapter(it, currentChapterData?.mangaName ?: "")
                                }
                            }
                        )
                    } else {
                        HomeScreen(
                            titles = titles,
                            isLoading = isTitlesLoading,
                            onTitleClick = { openTitle(it) },
                            onSearchClick = {},
                            onBookmarksClick = { showBookmarksDialog = true },
                            onHistoryClick = { showHistoryDialog = true },
                            onPlayPublishClick = { showPlayDialog = true },
                            onToggleBookmark = { title ->
                                repository.toggleBookmark(title)
                                bookmarkedUrls = repository.getBookmarks().map { it.url }.toSet()
                            },
                            bookmarkedUrls = bookmarkedUrls
                        )
                    }

                    // Google Play Publishing Package Modal
                    if (showPlayDialog) {
                        GooglePlayPublishDialog(onDismiss = { showPlayDialog = false })
                    }

                    // Chapter List Bottom Sheet / Dialog
                    if (selectedTitleForChapters != null) {
                        Dialog(onDismissRequest = { selectedTitleForChapters = null }) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.8f)
                                    .clip(RoundedCornerShape(16.dp)),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = selectedTitleForChapters?.title ?: "Chapters",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = { selectedTitleForChapters = null }) {
                                            Icon(Icons.Default.Close, contentDescription = "Close")
                                        }
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                    if (isChaptersLoading) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                        }
                                    } else if (chaptersList.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("Opening latest chapter directly...")
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Button(onClick = {
                                                    val url = selectedTitleForChapters?.url ?: ""
                                                    val name = selectedTitleForChapters?.title ?: ""
                                                    selectedTitleForChapters = null
                                                    openChapter(url, name)
                                                }) {
                                                    Text("Read First Available")
                                                }
                                            }
                                        }
                                    } else {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            items(chaptersList) { chapter ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            val name = selectedTitleForChapters?.title ?: ""
                                                            selectedTitleForChapters = null
                                                            openChapter(chapter.url, name)
                                                        }
                                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = chapter.title.ifEmpty { "Chapter ${chapter.num}" },
                                                        fontSize = 14.sp
                                                    )
                                                    if (!chapter.volume.isNullOrBlank()) {
                                                        Text(
                                                            text = "Vol ${chapter.volume}",
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Reading List Dialog
                    if (showBookmarksDialog) {
                        Dialog(onDismissRequest = { showBookmarksDialog = false }) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.7f)
                                    .clip(RoundedCornerShape(16.dp)),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "Reading List (Bookmarks)",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                    val bookmarks = repository.getBookmarks()
                                    if (bookmarks.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("No bookmarked manga yet.")
                                        }
                                    } else {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            items(bookmarks) { item ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            showBookmarksDialog = false
                                                            openTitle(item)
                                                        }
                                                        .padding(vertical = 10.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(item.title, fontWeight = FontWeight.SemiBold)
                                                    Text(item.sourceName, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                                }
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // History Dialog
                    if (showHistoryDialog) {
                        Dialog(onDismissRequest = { showHistoryDialog = false }) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.7f)
                                    .clip(RoundedCornerShape(16.dp)),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "Reading History",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                    if (history.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("No reading history yet.")
                                        }
                                    } else {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            items(history) { entry ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            showHistoryDialog = false
                                                            openChapter(entry.url, entry.mangaName)
                                                        }
                                                        .padding(vertical = 10.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column {
                                                        Text(entry.mangaName, fontWeight = FontWeight.SemiBold)
                                                        Text("Chapter ${entry.chapterNum}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
