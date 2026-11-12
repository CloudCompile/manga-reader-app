package com.example.ui.screens

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.view.ViewGroup
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Hidden full in-app browser (Chromium via the system WebView).
 *
 * Not linked in the visible UI on purpose — open it by long-pressing the
 * "Manga Reader" title on the home screen (or the header of the Google Play
 * dialog).
 *
 * A real browser chrome, not a single WebView: multiple live tabs with a
 * switcher, a new-tab page, bookmarks, history, target=_blank into new tabs,
 * per-tab desktop mode, find-in-page, omnibox with search fallback, secure
 * indicator, downloads (system DownloadManager), file uploads, site
 * permissions, share, open-in-Chrome and error pages with retry.
 *
 * Sessions survive restarts: cookies live in the global CookieManager and the
 * open tabs are persisted (and reloaded) from disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // ---- Tabs ----------------------------------------------------------------
    var tabs by remember { mutableStateOf(BrowserStore.loadTabs(context)) }
    var activeTabId by remember { mutableLongStateOf(BrowserStore.loadActiveTab(context)) }
    // Which tab the callbacks should report UI state for (read from callbacks).
    val activeIdRef = remember { mutableLongStateOf(0L) }
    activeIdRef.longValue = activeTabId

    val activeTab = tabs.firstOrNull { it.id == activeTabId }

    if (activeTab == null || tabs.isEmpty()) {
        // Nothing restorable — start fresh with one tab.
        val seed = BrowserTab(url = START_URL)
        tabs = listOf(seed)
        activeTabId = seed.id
    }

    var progress by remember { mutableIntStateOf(-1) }
    var tabError by remember { mutableStateOf<Pair<Long, Pair<String, String>>?>(null) } // tabId to (url, message)

    // ---- Sheets & overlays ----------------------------------------------------
    var showTabSwitcher by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    // ---- Bookmarks & history ---------------------------------------------------
    var bookmarks by remember { mutableStateOf(BrowserStore.loadBookmarks(context)) }

    // ---- WebView pool (one live instance per tab) -------------------------------
    val webViews = remember { mutableMapOf<Long, WebView>() }
    val containerRef = remember { mutableStateOf<FrameLayout?>(null) }

    fun updateTab(id: Long, transform: (BrowserTab) -> BrowserTab) {
        tabs = tabs.map { if (it.id == id) transform(it) else it }
    }

    fun openInNewTab(url: String) {
        val tab = BrowserTab(url = url)
        tabs = tabs + tab
        tabError = null
        progress = -1
        activeTabId = tab.id
        showTabSwitcher = false
    }

    fun closeTab(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        webViews.remove(id)?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        val remaining = tabs.filter { it.id != id }
        tabs = remaining
        if (remaining.isEmpty()) {
            val seed = BrowserTab(url = START_URL)
            tabs = listOf(seed)
            activeTabId = seed.id
        } else if (id == activeTabId) {
            activeTabId = remaining[(idx - 1).coerceIn(0, remaining.size - 1)].id
        }
        tabError = tabError?.takeIf { it.first != id }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        pendingUpload?.onReceiveValue(parsed)
        pendingUpload = null
    }

    val downloadListener = { url: String, userAgent: String, contentDisposition: String, mimeType: String, _: Long ->
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Manga Reader download")
                setMimeType(mimeType)
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url).orEmpty())
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

    fun obtainWebView(tab: BrowserTab): WebView {
        webViews[tab.id]?.let { return it }
        val created = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            configureBrowserSettings(this, desktop = tab.desktop)

            setDownloadListener(downloadListener)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return when (request.url.scheme) {
                        "http", "https" -> false // stay in the browser
                        else -> { // mailto:, intent:, market:, tel:, ...
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                            true
                        }
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    updateTab(tab.id) { it.copy(url = url) }
                    CookieManager.getInstance().flush()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    updateTab(tab.id) { it.copy(url = url) }
                    if (!isReload && url.startsWith("http")) {
                        BrowserStore.addHistory(context, url, tab.title.ifBlank { url })
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        tabError = tab.id to (urlOf(request) to (error.description?.toString() ?: "This site can't be reached"))
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // A crashed renderer shows up as a blank white page — rebuild
                    // the tab's WebView and reload instead.
                    webViews.remove(tab.id)
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    val current = tabs.firstOrNull { it.id == tab.id } ?: return true
                    containerRef.value?.addView(obtainWebView(current))
                    obtainWebView(current).loadUrl(current.url)
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (tab.id == activeIdRef.longValue) {
                        progress = if (newProgress >= 100) -1 else newProgress
                    }
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    updateTab(tab.id) { it.copy(title = title.orEmpty()) }
                }

                override fun onShowFileChooser(
                    view: WebView,
                    callback: android.webkit.ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    pendingUpload?.onReceiveValue(null)
                    pendingUpload = callback
                    runCatching { uploadLauncher.launch(params.createIntent()) }
                        .onFailure {
                            pendingUpload = null
                            callback.onReceiveValue(null)
                            return false
                        }
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    callback.invoke(origin, true, false)
                }

                // target="_blank" / window.open() opens a new tab, like Chrome.
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val harvester = WebView(view.context).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                openInNewTab(request.url.toString())
                                return true
                            }
                        }
                    }
                    (resultMsg?.obj as? WebView.WebViewTransport)?.let { transport ->
                        transport.webView = harvester
                        resultMsg.sendToTarget()
                    }
                    return true
                }
            }
        }
        webViews[tab.id] = created
        return created
    }

    // ---- Omnibox ----------------------------------------------------------------
    var editingOmnibox by remember { mutableStateOf(false) }
    var omniboxText by remember(activeTabId, activeTab?.url) { mutableStateOf(activeTab?.url.orEmpty()) }

    fun submitOmnibox(raw: String) {
        val target = normalizeUrl(raw)
        editingOmnibox = false
        tabError = tabError?.takeIf { it.first != activeTabId }
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        updateTab(tab.id) { it.copy(url = target, title = "") }
        progress = 0
        obtainWebView(tab.copy(url = target)).loadUrl(target)
    }

    fun toggleDesktopMode() {
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        val desktop = !tab.desktop
        updateTab(tab.id) { it.copy(desktop = desktop) }
        webViews[tab.id]?.let {
            configureBrowserSettings(it, desktop)
            it.reload()
        }
    }

    val isBookmarked = bookmarks.any { it.url == activeTab?.url }

    fun toggleBookmark() {
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        val url = tab.url
        if (url.isBlank() || !url.startsWith("http")) return
        bookmarks = if (isBookmarked) {
            BrowserStore.removeBookmark(context, url)
        } else {
            BrowserStore.addBookmark(context, url, tab.title.ifBlank { url })
        }
        Toast.makeText(
            context,
            if (isBookmarked) "Bookmark removed" else "Bookmarked",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---- Back handling ------------------------------------------------------------
    BackHandler {
        when {
            showTabSwitcher -> showTabSwitcher = false
            showBookmarks -> showBookmarks = false
            showHistory -> showHistory = false
            showFindBar -> showFindBar = false
            menuOpen -> menuOpen = false
            else -> {
                val wv = webViews[activeTabId]
                if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
            }
        }
    }

    // Persist tabs on every change so the browser reopens where it left off.
    LaunchedEffect(tabs, activeTabId) {
        BrowserStore.saveTabs(context, tabs, activeTabId)
    }

    // Destroy the pool when the browser closes.
    DisposableEffect(Unit) {
        onDispose {
            webViews.values.forEach { it.destroy() }
            webViews.clear()
        }
    }

    // ---- UI ------------------------------------------------------------------------
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- Top: omnibox ------------------------------------------------
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (activeTab?.url?.startsWith("https") == true) Icons.Default.Lock else Icons.Default.OpenInBrowser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp).size(16.dp)
                        )
                        OutlinedTextField(
                            value = omniboxText,
                            onValueChange = { omniboxText = it },
                            placeholder = { Text("Search or enter address", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .onFocusChanged { editingOmnibox = it.isFocused }
                        )
                        if (progress in 0..99) {
                            IconButton(onClick = {
                                webViews[activeTabId]?.stopLoading()
                                progress = -1
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Stop")
                            }
                        } else {
                            IconButton(onClick = {
                                tabError = tabError?.takeIf { it.first != activeTabId }
                                webViews[activeTabId]?.reload()
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload")
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                    if (showFindBar) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = findQuery,
                                onValueChange = {
                                    findQuery = it
                                    val wv = webViews[activeTabId]
                                    if (it.isBlank()) wv?.clearMatches() else wv?.findAllAsync(it)
                                },
                                placeholder = { Text("Find in page") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { webViews[activeTabId]?.findNext(false) }) { Text("Prev") }
                            TextButton(onClick = { webViews[activeTabId]?.findNext(true) }) { Text("Next") }
                        }
                    }
                }
            }

            // ---- Middle: the live tabs ------------------------------------------
            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).also { containerRef.value = it }
                    },
                    update = { container ->
                        val tab = tabs.firstOrNull { it.id == activeTabId }
                        val wv = if (tab != null && tab.url.isNotBlank()) obtainWebView(tab) else null
                        val current = container.getChildAt(0)
                        if (current !== wv) {
                            container.removeAllViews()
                            (current as? WebView)?.onPause()
                            if (wv != null) {
                                container.addView(wv)
                                wv.onResume()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // New-tab start page (tiles + bookmarks) overlays an empty tab.
                if (activeTab != null && activeTab.url.isBlank()) {
                    StartPage(
                        bookmarks = bookmarks,
                        onOpen = { submitOmnibox(it) },
                        onRemoveBookmark = {
                            bookmarks = BrowserStore.removeBookmark(context, it.url)
                        }
                    )
                }

                // Error page — never a silent blank.
                val err = tabError?.takeIf { it.first == activeTabId }
                if (err != null && activeTab?.url?.isNotBlank() == true) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("😕", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("This site can't be reached", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            err.second.second,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Text(
                            err.second.first,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            tabError = null
                            webViews[activeTabId]?.reload()
                        }) { Text("Retry") }
                    }
                }
            }

            // ---- Bottom toolbar ----------------------------------------------------
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webViews[activeTabId]?.goBack() },
                        enabled = webViews[activeTabId]?.canGoBack() == true
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(
                        onClick = { webViews[activeTabId]?.goForward() },
                        enabled = webViews[activeTabId]?.canGoForward() == true
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(onClick = {
                        // Home = the start page in the current tab.
                        updateTab(activeTabId) { it.copy(url = "") }
                        tabError = tabError?.takeIf { it.first != activeTabId }
                        progress = -1
                    }) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = { toggleBookmark() }) {
                        Icon(
                            if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark",
                            tint = if (isBookmarked) Color(0xFFFFD54F) else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showTabSwitcher = true }) {
                        BadgedBox(badge = {
                            Badge { Text(tabs.size.toString()) }
                        }) {
                            Icon(Icons.Default.Tab, contentDescription = "Tabs")
                        }
                    }
                }
            }
        }

        // ---- ⋮ menu ---------------------------------------------------------------
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("New tab") },
                    leadingIcon = { Icon(Icons.Default.Add, null) },
                    onClick = {
                        menuOpen = false
                        openInNewTab("")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Find in page") },
                    leadingIcon = { Icon(Icons.Default.FindInPage, null) },
                    onClick = {
                        menuOpen = false
                        showFindBar = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (activeTab?.desktop == true) "Mobile site" else "Desktop site") },
                    leadingIcon = { Icon(Icons.Default.DesktopWindows, null) },
                    onClick = {
                        menuOpen = false
                        toggleDesktopMode()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Bookmarks") },
                    leadingIcon = { Icon(Icons.Default.Star, null) },
                    onClick = {
                        menuOpen = false
                        showBookmarks = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("History") },
                    leadingIcon = { Icon(Icons.Default.History, null) },
                    onClick = {
                        menuOpen = false
                        showHistory = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share page") },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = {
                        menuOpen = false
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, activeTab?.url.orEmpty())
                            putExtra(Intent.EXTRA_TITLE, activeTab?.title)
                        }
                        context.startActivity(Intent.createChooser(share, "Share page"))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Exit browser") },
                    leadingIcon = { Icon(Icons.Default.Close, null) },
                    onClick = {
                        menuOpen = false
                        onClose()
                    }
                )
            }
        }

        // ---- Tab switcher -----------------------------------------------------------
        if (showTabSwitcher) {
            TabSwitcher(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelect = {
                    activeTabId = it.id
                    progress = -1
                    showTabSwitcher = false
                },
                onClose = { closeTab(it.id) },
                onNewTab = { openInNewTab("") },
                onCloseAll = {
                    webViews.values.forEach { wv ->
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                    }
                    webViews.clear()
                    val seed = BrowserTab(url = START_URL)
                    tabs = listOf(seed)
                    activeTabId = seed.id
                    showTabSwitcher = false
                },
                onDismiss = { showTabSwitcher = false }
            )
        }

        // ---- Bookmarks sheet ----------------------------------------------------------
        if (showBookmarks) {
            ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
                Text(
                    "Bookmarks",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (bookmarks.isEmpty()) {
                    Text(
                        "No bookmarks yet — tap the star to save the current page.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                LazyColumn {
                    items(bookmarks, key = { it.url }) { bm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showBookmarks = false
                                    submitOmnibox(bm.url)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Favicon(bm.url)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(bm.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    bm.url,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = {
                                bookmarks = BrowserStore.removeBookmark(context, bm.url)
                            }) {
                                Icon(Icons.Default.Delete, "Delete bookmark", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // ---- History sheet --------------------------------------------------------------
        if (showHistory) {
            ModalBottomSheet(onDismissRequest = { showHistory = false }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("History", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        BrowserStore.clearHistory(context)
                        showHistory = false
                    }) { Text("Clear all") }
                }
                val history = BrowserStore.loadHistory(context)
                if (history.isEmpty()) {
                    Text(
                        "Nothing here yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                LazyColumn {
                    items(history, key = { it.url + it.at }) { h ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showHistory = false
                                    submitOmnibox(h.url)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Favicon(h.url)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    h.url,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------
// Start page (new tab)
// ----------------------------------------------------------------------------------

@Composable
private fun StartPage(
    bookmarks: List<BrowserStore.Bookmark>,
    onOpen: (String) -> Unit,
    onRemoveBookmark: (BrowserStore.Bookmark) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text(
            "Chromium",
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        Text(
            "Quick links",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        LazyRow(Modifier.padding(top = 8.dp, bottom = 16.dp)) {
            items(QUICK_LINKS) { (label, link) ->
                AssistChip(
                    onClick = { onOpen(link) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        if (bookmarks.isNotEmpty()) {
            Text(
                "Saved",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                items(bookmarks) { bm ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpen(bm.url) }
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Favicon(bm.url)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                bm.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveBookmark(bm) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------
// Tab switcher
// ----------------------------------------------------------------------------------

@Composable
private fun TabSwitcher(
    tabs: List<BrowserTab>,
    activeTabId: Long,
    onSelect: (BrowserTab) -> Unit,
    onClose: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 32.dp)
                .clickable(enabled = false) {}
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${tabs.size} tab${if (tabs.size == 1) "" else "s"}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCloseAll) { Text("Close all") }
                FilledTonalButton(onClick = onNewTab) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("New tab")
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(tabs, key = { it.id }) { tab ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (tab.id == activeTabId) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onSelect(tab) }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Favicon(tab.url)
                            Text(
                                tab.title.ifBlank { tab.url.ifBlank { "New tab" } },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                            )
                            IconButton(onClick = { onClose(tab) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, "Close tab", modifier = Modifier.size(16.dp))
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.3f)
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tab.url.toDomain().ifBlank { "New tab" },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Letter-avatar favicon (WebViews don't expose favicons reliably). */
@Composable
private fun Favicon(url: String) {
    val domain = url.toDomain()
    val hue = remember(domain) {
        if (domain.isEmpty()) 0f
        else (MessageDigest.getInstance("MD5").digest(domain.toByteArray()).first().toInt() and 0xFF) / 255f * 360f
    }
    Box(
        Modifier
            .size(24.dp)
            .background(
                Color.hsv(hue, 0.55f, 0.85f).copy(alpha = 0.9f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            domain.removePrefix("www.").firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun urlOf(request: WebResourceRequest): String = request.url.toString()

private fun String.toDomain(): String =
    runCatching { Uri.parse(this).host?.removePrefix("www.").orEmpty() }.getOrDefault("")

// ----------------------------------------------------------------------------------
// Tab model + persistence (tabs, bookmarks, history)
// ----------------------------------------------------------------------------------

data class BrowserTab(
    val id: Long = System.nanoTime(),
    val url: String,          // blank = new-tab start page
    val title: String = "",
    val desktop: Boolean = false
)

object BrowserStore {
    data class Bookmark(val url: String, val title: String)
    data class HistoryEntry(val url: String, val title: String, val at: Long)

    private const val PREFS = "browser_prefs"
    private const val TABS = "tabs"
    private const val ACTIVE = "active"
    private const val BOOKMARKS = "bookmarks"
    private const val HISTORY = "history"
    private const val HISTORY_MAX = 300

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadTabs(context: Context): List<BrowserTab> = runCatching {
        val arr = JSONArray(prefs(context).getString(TABS, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            BrowserTab(
                id = o.optLong("id", System.nanoTime()),
                url = o.optString("url"),
                title = o.optString("title"),
                desktop = o.optBoolean("desktop", false)
            )
        }
    }.getOrDefault(emptyList())

    fun saveTabs(context: Context, tabs: List<BrowserTab>, activeId: Long) {
        val arr = JSONArray()
        for (t in tabs) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("url", t.url)
                    .put("title", t.title)
                    .put("desktop", t.desktop)
            )
        }
        prefs(context).edit()
            .putString(TABS, arr.toString())
            .putLong(ACTIVE, activeId)
            .apply()
    }

    fun loadActiveTab(context: Context): Long = prefs(context).getLong(ACTIVE, 0L)

    fun loadBookmarks(context: Context): List<Bookmark> = runCatching {
        val arr = JSONArray(prefs(context).getString(BOOKMARKS, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Bookmark(url = o.getString("url"), title = o.optString("title"))
        }
    }.getOrDefault(emptyList())

    fun addBookmark(context: Context, url: String, title: String): List<Bookmark> {
        val current = loadBookmarks(context).filterNot { it.url == url }
        val updated = listOf(Bookmark(url, title)) + current
        saveBookmarks(context, updated)
        return updated
    }

    fun removeBookmark(context: Context, url: String): List<Bookmark> {
        val updated = loadBookmarks(context).filterNot { it.url == url }
        saveBookmarks(context, updated)
        return updated
    }

    private fun saveBookmarks(context: Context, marks: List<Bookmark>) {
        val arr = JSONArray()
        for (b in marks) arr.put(JSONObject().put("url", b.url).put("title", b.title))
        prefs(context).edit().putString(BOOKMARKS, arr.toString()).apply()
    }

    fun loadHistory(context: Context): List<HistoryEntry> = runCatching {
        val arr = JSONArray(prefs(context).getString(HISTORY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HistoryEntry(url = o.getString("url"), title = o.optString("title"), at = o.optLong("at", 0L))
        }
    }.getOrDefault(emptyList())

    fun addHistory(context: Context, url: String, title: String) {
        val current = loadHistory(context).filterNot { it.url == url }
        val updated = (listOf(HistoryEntry(url, title, System.currentTimeMillis())) + current).take(HISTORY_MAX)
        val arr = JSONArray()
        for (h in updated) {
            arr.put(JSONObject().put("url", h.url).put("title", h.title).put("at", h.at))
        }
        prefs(context).edit().putString(HISTORY, arr.toString()).apply()
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(HISTORY).apply()
    }
}

// ----------------------------------------------------------------------------------
// Settings + URL helpers
// ----------------------------------------------------------------------------------

/** Load a URL or fall back to a search engine. */
private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    val looksLikeUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
        (!trimmed.contains(' ') && trimmed.contains('.') && !trimmed.endsWith('.'))
    return if (looksLikeUrl) {
        if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
    } else {
        "https://www.google.com/search?q=" + Uri.encode(trimmed)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureBrowserSettings(webView: WebView, desktop: Boolean) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true) // target=_blank opens a real new tab
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        setSupportZoom(true)
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        userAgentString = if (desktop) {
            DESKTOP_UA
        } else {
            // Same trick DJI GO 4's beloved hidden browser used: strip the two
            // tokens that mark this UA as an embedded WebView (`; wv` and
            // `Version/4.0`). Google's login shows "This browser or app may
            // not be secure" when it sees them; without them accounts.google.com
            // behaves as if the user were in Chrome.
            webView.settings.userAgentString
                .replace("; wv", "")
                .replace(" wv;", "")
                .replace("Version/4.0 ", "")
        }
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private const val START_URL = "https://mangareader.cjhauser.me"

private val QUICK_LINKS = listOf(
    "Manga Reader" to "https://mangareader.cjhauser.me",
    "Google" to "https://www.google.com",
    "DuckDuckGo" to "https://duckduckgo.com",
    "MangaDex" to "https://mangadex.org",
    "MyAnimeList" to "https://myanimelist.net",
    "AniList" to "https://anilist.co",
    "Wikipedia" to "https://wikipedia.org"
)

/** Pending file-upload callback shared across tabs (only one dialog at a time). */
private var pendingUpload: android.webkit.ValueCallback<Array<Uri>>? = null
