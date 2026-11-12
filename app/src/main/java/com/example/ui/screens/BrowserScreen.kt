package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.app.DownloadManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import java.net.URI

/**
 * Hidden full in-app browser (Chromium via WebView).
 *
 * Not linked anywhere in the visible UI on purpose — open it by long-pressing
 * the "Manga Reader" title on the home screen (or the header of the Google
 * Play dialog). It behaves like a real browser: omnibox with search fallback,
 * history navigation, page progress, downloads (system DownloadManager),
 * file uploads (camera or files), site permissions (camera/mic/geo),
 * per-site cookies, desktop-mode toggle, find-in-page, quick links, share,
 * and "open in Chrome".
 */
@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var omnibox by remember { mutableStateOf(START_URL) }
    var currentUrl by remember { mutableStateOf(START_URL) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(-1) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isDesktopMode by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showQuickLinks by remember { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        pendingUpload?.onReceiveValue(parsed)
        pendingUpload = null
    }

    fun submitOmnibox(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val target = normalizeUrl(trimmed)
        omnibox = target
        webView?.loadUrl(target)
    }

    fun toggleDesktopMode(desktop: Boolean) {
        isDesktopMode = desktop
        webView?.settings?.userAgentString = if (desktop) DESKTOP_UA else DEFAULT_UA
        webView?.settings?.useWideViewPort = desktop
        webView?.reload()
    }

    val downloadListener = { url: String, userAgent: String, contentDisposition: String, mimeType: String, _: Long ->
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Manga Reader download")
                setMimeType(mimeType)
                addRequestHeader("cookie", cookies)
                addRequestHeader("User-Agent", userAgent)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- Omnibox -------------------------------------------------
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                        }
                        OutlinedTextField(
                            value = omnibox,
                            onValueChange = { omnibox = it },
                            placeholder = { Text("Search or enter address", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 4.dp)
                        )
                        if (progress in 0..99) {
                            IconButton(onClick = { webView?.stopLoading() }) {
                                Icon(Icons.Default.Close, contentDescription = "Stop")
                            }
                        } else {
                            IconButton(onClick = { webView?.reload() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload")
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 0.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = pageTitle.ifBlank { currentUrl },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row {
                            IconButton(
                                onClick = { toggleDesktopMode(!isDesktopMode) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.DesktopWindows,
                                    contentDescription = "Desktop mode",
                                    tint = if (isDesktopMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = { showFindBar = !showFindBar },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Find in page", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    showQuickLinks = !showQuickLinks
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Bookmarks, contentDescription = "Quick links", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (showFindBar) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = findQuery,
                                onValueChange = {
                                    findQuery = it
                                    if (it.isBlank()) webView?.clearMatches() else webView?.findAllAsync(it)
                                },
                                placeholder = { Text("Find in page") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                webView?.findNext(false)
                            }) { Text("Prev") }
                            TextButton(onClick = {
                                webView?.findNext(true)
                            }) { Text("Next") }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { (progress.coerceIn(0, 100)) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .padding(horizontal = 0.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                    if (showQuickLinks) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            items(QUICK_LINKS) { (label, link) ->
                                AssistChip(
                                    onClick = { submitOmnibox(link) },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ---- WebView ---------------------------------------------------
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        configureBrowserSettings(this)
                        setDownloadListener(downloadListener)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                return when (url.scheme) {
                                    "http", "https" -> {
                                        // Keep everything in-app; let the user break out manually.
                                        false
                                    }
                                    else -> {
                                        // mailto:, intent:, market:, tel:, etc.
                                        runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, url))
                                        }
                                        true
                                    }
                                }
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                currentUrl = url
                                omnibox = url
                                CookieManager.getInstance().flush()
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                currentUrl = url
                                omnibox = url
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = if (newProgress >= 100) -1 else newProgress
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title.orEmpty()
                            }

                            // ---- File uploads (input type=file, camera, etc.) ----
                            override fun onShowFileChooser(
                                view: WebView,
                                callback: android.webkit.ValueCallback<Array<Uri>>,
                                params: FileChooserParams
                            ): Boolean {
                                pendingUpload?.onReceiveValue(null)
                                pendingUpload = callback
                                runCatching {
                                    uploadLauncher.launch(params.createIntent())
                                }.onFailure {
                                    pendingUpload = null
                                    callback.onReceiveValue(null)
                                    return false
                                }
                                return true
                            }

                            // ---- Site permissions (camera / mic) ----
                            override fun onPermissionRequest(request: PermissionRequest) {
                                request.grant(request.resources)
                            }

                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String,
                                callback: GeolocationPermissions.Callback
                            ) {
                                callback.invoke(origin, true, false)
                            }
                        }
                    }.also { webView = it }
                },
                update = { /* navigation is driven by the omnibox/buttons */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // ---- Bottom toolbar ---------------------------------------------
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(onClick = { showQuickLinks = !showQuickLinks }) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, currentUrl)
                            putExtra(Intent.EXTRA_TITLE, pageTitle)
                        }
                        context.startActivity(Intent.createChooser(share, "Share page"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                        }.onFailure {
                            Toast.makeText(context, "No external browser found", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in Chrome")
                    }
                    IconButton(onClick = { onClose() }) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Close browser")
                    }
                }
            }
        }
    }
}

/** Load a URL or fall back to a search engine. */
private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    val looksLikeUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
        (!trimmed.contains(' ') && trimmed.contains('.') && !trimmed.endsWith('.'))
    return if (looksLikeUrl) {
        if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
    } else {
        "https://duckduckgo.com/?q=" + Uri.encode(trimmed)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureBrowserSettings(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        setSupportZoom(true)
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    DEFAULT_UA = webView.settings.userAgentString
}

@kotlin.jvm.Volatile
private var DEFAULT_UA: String =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private const val START_URL = "https://mangareader.cjhauser.me"

private val QUICK_LINKS = listOf(
    "Manga Reader" to "https://mangareader.cjhauser.me",
    "DuckDuckGo" to "https://duckduckgo.com",
    "MangaDex" to "https://mangadex.org",
    "MyAnimeList" to "https://myanimelist.net",
    "AniList" to "https://anilist.co",
    "Wikipedia" to "https://wikipedia.org"
)
