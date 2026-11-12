package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.models.ChapterData
import com.example.models.FitMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    chapterData: ChapterData?,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    var showControls by remember { mutableStateOf(true) }
    var fitMode by remember { mutableStateOf(FitMode.WIDTH) }
    var isPaged by remember { mutableStateOf(false) }
    var isRtl by remember { mutableStateOf(false) }

    val images = chapterData?.images ?: emptyList()
    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { images.size })

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Loading chapter...", color = Color.White)
                }
            }
        } else if (images.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No pages found in this chapter", color = Color.White)
            }
        } else if (!isPaged) {
            // Continuous vertical scroll mode
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(images) { index, imageUrl ->
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Page ${index + 1}",
                        contentScale = when (fitMode) {
                            FitMode.WIDTH -> ContentScale.FillWidth
                            FitMode.HEIGHT -> ContentScale.FillHeight
                            FitMode.ORIGINAL -> ContentScale.None
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    )
                }
            }
        } else {
            // Paged mode
            HorizontalPager(
                state = pagerState,
                reverseLayout = isRtl,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = images[pageIndex],
                        contentDescription = "Page ${pageIndex + 1}",
                        contentScale = when (fitMode) {
                            FitMode.WIDTH -> ContentScale.FillWidth
                            FitMode.HEIGHT -> ContentScale.FillHeight
                            FitMode.ORIGINAL -> ContentScale.Fit
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Top Chrome Bar
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                contentColor = Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("reader_back_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = chapterData?.mangaName ?: "Manga",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                color = Color.White
                            )
                            Text(
                                text = chapterData?.title ?: "",
                                fontSize = 12.sp,
                                maxLines = 1,
                                color = Color.LightGray
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = {
                            fitMode = when (fitMode) {
                                FitMode.WIDTH -> FitMode.HEIGHT
                                FitMode.HEIGHT -> FitMode.ORIGINAL
                                FitMode.ORIGINAL -> FitMode.WIDTH
                            }
                        }) {
                            Icon(
                                imageVector = when (fitMode) {
                                    FitMode.WIDTH -> Icons.Default.WidthWide
                                    FitMode.HEIGHT -> Icons.Default.Height
                                    FitMode.ORIGINAL -> Icons.Default.AspectRatio
                                },
                                contentDescription = "Fit Mode",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { isPaged = !isPaged }) {
                            Icon(
                                imageVector = if (isPaged) Icons.Default.ViewCarousel else Icons.Default.ViewStream,
                                contentDescription = "Toggle Paged",
                                tint = if (isPaged) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                        if (isPaged) {
                            IconButton(onClick = { isRtl = !isRtl }) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = "RTL Direction",
                                    tint = if (isRtl) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Chrome Bar
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                contentColor = Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp)
                ) {
                    val currentPage = if (isPaged) pagerState.currentPage + 1 else listState.firstVisibleItemIndex + 1
                    Text(
                        text = "Page $currentPage / ${images.size}",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onPrevChapter,
                            enabled = chapterData?.prevUrl != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.DarkGray,
                                disabledContainerColor = Color(0xFF222222)
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Prev Chapter", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onNextChapter,
                            enabled = chapterData?.nextUrl != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = Color(0xFF222222)
                            )
                        ) {
                            Text("Next Chapter", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
