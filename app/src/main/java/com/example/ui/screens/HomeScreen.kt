package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.models.TitleItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    titles: List<TitleItem>,
    isLoading: Boolean,
    onTitleClick: (TitleItem) -> Unit,
    onSearchClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onPlayPublishClick: () -> Unit,
    onToggleBookmark: (TitleItem) -> Unit,
    bookmarkedUrls: Set<String>,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }

    val filteredTitles = remember(titles, selectedTab, searchQuery) {
        val base = if (selectedTab == 0) titles.take(15) else titles
        if (searchQuery.isBlank()) {
            base
        } else {
            val q = searchQuery.trim().lowercase()
            titles.filter {
                it.title.lowercase().contains(q) ||
                it.aliases.any { a -> a.lowercase().contains(q) } ||
                it.sourceName.lowercase().contains(q)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchExpanded) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search manga...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_input"),
                            trailingIcon = {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    isSearchExpanded = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Book,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Manga Reader",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                },
                actions = {
                    if (!isSearchExpanded) {
                        IconButton(
                            onClick = { isSearchExpanded = true },
                            modifier = Modifier.testTag("search_button")
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                    IconButton(
                        onClick = onBookmarksClick,
                        modifier = Modifier.testTag("bookmarks_button")
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Reading List")
                    }
                    IconButton(
                        onClick = onHistoryClick,
                        modifier = Modifier.testTag("history_button")
                    ) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(
                        onClick = onPlayPublishClick,
                        modifier = Modifier.testTag("play_publish_button")
                    ) {
                        Icon(Icons.Default.Shop, contentDescription = "Google Play Package")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Curated Shelf") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Full Catalogue") }
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (filteredTitles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No manga found for '$searchQuery'" else "No manga available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredTitles, key = { "${it.sourceId}_${it.url}" }) { titleItem ->
                        MangaCard(
                            item = titleItem,
                            isBookmarked = bookmarkedUrls.contains(titleItem.url),
                            onClick = { onTitleClick(titleItem) },
                            onToggleBookmark = { onToggleBookmark(titleItem) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MangaCard(
    item: TitleItem,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("manga_card_${item.title}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(Color.DarkGray)
            ) {
                if (!item.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                // Bookmark icon badge
                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Source badge
                Text(
                    text = item.sourceName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(topEnd = 6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.latestChapter.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.latestChapter,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
