package com.example.ui.screens

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Hidden full in-app browser (Chromium via the system WebView).
 *
 * Not linked in the visible UI on purpose — open it by long-pressing the
 * "Manga Reader" title on the home screen (or the header of the Google Play
 * dialog). No system browser is ever invoked, so browserless devices behave
 * identically.
 *
 * Chrome-grade features: live tabs with a switcher (persisted), private
 * tabs, a new-tab page, bookmarks, history, downloads (with an in-browser
 * list), file uploads, camera/mic/location prompts asked per site and
 * remembered, target=_blank into new tabs, per-tab desktop mode,
 * find-in-page, URL autocomplete from history, pull-to-refresh, Safe
 * Browsing, real favicons, fullscreen HTML5 video, long-press link menus,
 * clear-browsing-data, share, and Chrome-style error pages with retry.
 *
 * Sessions survive restarts: cookies live in the global CookieManager and
 * open tabs are persisted (private tabs are not). The WebView User-Agent has
 * its `; wv` / `Version/4.0` markers stripped (the DJI GO 4 trick) so Google
 * sign-in works instead of "This browser or app may not be secure".
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
    var showDownloads by remember { mutableStateOf(false) }
    var showClearData by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    // ---- Bookmarks, history cache (for autocomplete) -----------------------------
    var bookmarks by remember { mutableStateOf(BrowserStore.loadBookmarks(context)) }
    var historyCache by remember { mutableStateOf(BrowserStore.loadHistory(context)) }
    LaunchedEffect(activeTab?.url) { historyCache = BrowserStore.loadHistory(context) }

    // ---- Fullscreen video ---------------------------------------------------------
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // ---- Long-press link menu ---------------------------------------------------------
    var linkMenu by remember { mutableStateOf<LinkMenuData?>(null) }
    var pendingImageSrc by remember { mutableStateOf<String?>(null) }

    // ---- Site permission prompts -----------------------------------------------------
    var pendingPagePermission by remember { mutableStateOf<PermissionRequest?>(null) }
    var pendingGeoPermission by remember { mutableStateOf<GeoPrompt?>(null) }
    var afterRuntimePerms by remember { mutableStateOf<(() -> Unit)?>(null) }
    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val proceed = afterRuntimePerms
        afterRuntimePerms = null
        if (result.values.isNotEmpty() && result.values.all { it }) proceed?.invoke()
        else pendingPagePermission?.deny().also { pendingPagePermission = null }
        if (result.values.any { !it }) pendingPagePermission?.let { it.deny(); pendingPagePermission = null }
    }
    val geoPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val geo = pendingGeoPermission
        pendingGeoPermission = null
        geo?.callback?.invoke(geo.origin, result.values.all { it }, false)
    }

    fun updateTab(id: Long, transform: (BrowserTab) -> BrowserTab) {
        tabs = tabs.map { if (it.id == id) transform(it) else it }
    }

    fun openInNewTab(url: String, private: Boolean = false) {
        val tab = BrowserTab(url = url, private = private)
        tabs = tabs + tab
        tabError = null
        progress = -1
        activeTabId = tab.id
        showTabSwitcher = false
    }

    // ---- WebView pool (one live instance per tab) -------------------------------
    val webViews = remember { mutableMapOf<Long, WebView>() }
    val containerRef = remember { mutableStateOf<FrameLayout?>(null) }
    val pullRef = remember { mutableStateOf<SwipeRefreshLayout?>(null) }

    // Delivers the href behind a long-pressed image link (requestFocusNodeHref).
    val focusedNodeHandler = remember {
        Handler(Looper.getMainLooper()) { msg ->
            val data = msg.data
            linkMenu = LinkMenuData(
                linkUrl = data?.getString("url")?.takeIf { it.isNotBlank() && it != "about:blank" },
                imageUrl = data?.getString("src")?.takeIf { it.isNotBlank() } ?: pendingImageSrc
            )
            pendingImageSrc = null
            true
        }
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
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val enqueued = dm.enqueue(request)
            BrowserStore.addDownload(context, BrowserStore.DownloadEntry(enqueued, fileName, System.currentTimeMillis()))
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

            // Long-press: Chrome-style link/image menu. For an image that links
            // somewhere, requestFocusNodeHref delivers the real link target.
            setOnLongClickListener {
                when (hitTestResult.type) {
                    WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                        linkMenu = LinkMenuData(linkUrl = hitTestResult.extra, imageUrl = null)
                        true
                    }
                    WebView.HitTestResult.IMAGE_TYPE -> {
                        linkMenu = LinkMenuData(linkUrl = null, imageUrl = hitTestResult.extra)
                        true
                    }
                    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                        pendingImageSrc = hitTestResult.extra
                        requestFocusNodeHref(Message.obtain(focusedNodeHandler))
                        true
                    }
                    else -> false
                }
            }

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
                    if (!isReload && !tab.private && url.startsWith("http")) {
                        BrowserStore.addHistory(context, url, tab.title.ifBlank { url })
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        tabError = tab.id to (request.url.toString() to (error.description?.toString() ?: "This site can't be reached"))
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
                    if (newProgress >= 100) pullRef.value?.isRefreshing = false
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    updateTab(tab.id) { it.copy(title = title.orEmpty()) }
                }

                // ---- Fullscreen HTML5 video ------------------------------------
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customViewCallback?.onCustomViewHidden()
                    (view.parent as? ViewGroup)?.removeView(view)
                    customView = view
                    customViewCallback = callback
                }

                override fun onHideCustomView() {
                    customView = null
                    customViewCallback = null
                }

                // ---- File uploads (input type=file, camera, etc.) ----
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

                // ---- Site permissions: ASK, remember per site (Chrome-style) ----
                override fun onPermissionRequest(request: PermissionRequest) {
                    val origin = request.origin?.toString() ?: return request.deny()
                    val remembered = request.resources.map { res ->
                        origin to when (res) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "video"
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "audio"
                            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "drm"
                            else -> res
                        }
                    }
                    val decisions = remembered.map { BrowserStore.sitePermission(context, it.first, it.second) }
                    when {
                        decisions.all { it == true } -> request.grant(request.resources)
                        decisions.any { it == false } -> request.deny()
                        else -> pendingPagePermission = request
                    }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    when (BrowserStore.sitePermission(context, origin, "geo")) {
                        true -> callback.invoke(origin, true, false)
                        false -> callback.invoke(origin, false, false)
                        else -> pendingGeoPermission = GeoPrompt(origin, callback)
                    }
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
                                openInNewTab(request.url.toString(), private = tab.private)
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
    val focusManager = LocalFocusManager.current
    var editingOmnibox by remember { mutableStateOf(false) }
    var omniboxText by remember(activeTabId, activeTab?.url) { mutableStateOf(activeTab?.url.orEmpty()) }

    fun submitOmnibox(raw: String) {
        val target = normalizeUrl(raw)
        focusManager.clearFocus()
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

    // ---- Back handling: video > overlays > find > menu > page > close ------------
    BackHandler {
        when {
            customView != null -> {
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
            }
            showTabSwitcher -> showTabSwitcher = false
            showBookmarks -> showBookmarks = false
            showHistory -> showHistory = false
            showDownloads -> showDownloads = false
            showClearData -> showClearData = false
            showFindBar -> showFindBar = false
            menuOpen -> menuOpen = false
            linkMenu != null -> linkMenu = null
            else -> {
                val wv = webViews[activeTabId]
                if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
            }
        }
    }

    // Persist tabs on every change; private tabs never touch disk.
    LaunchedEffect(tabs, activeTabId) {
        BrowserStore.saveTabs(context, tabs.filter { !it.private }, activeTabId.takeIf { tabs.firstOrNull { t -> t.id == it }?.private != true } ?: 0L)
    }

    // Destroy the pool when the browser closes.
    DisposableEffect(Unit) {
        onDispose {
            webViews.values.forEach { it.destroy() }
            webViews.clear()
        }
    }

    // ---- Fullscreen video overlay -------------------------------------------------
    customView?.let { videoView ->
        DisposableEffect(videoView) {
            onDispose {
                (videoView.parent as? ViewGroup)?.removeView(videoView)
            }
        }
        BackHandler {
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { videoView },
                modifier = Modifier.fillMaxSize()
            )
        }
        return
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
                        if (activeTab?.private == true) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = "Private tab",
                                tint = Color(0xFFB388FF),
                                modifier = Modifier.padding(start = 10.dp).size(16.dp)
                            )
                        } else {
                            Icon(
                                if (activeTab?.url?.startsWith("https") == true) Icons.Default.Lock else Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp).size(16.dp)
                            )
                        }
                        OutlinedTextField(
                            value = omniboxText,
                            onValueChange = { omniboxText = it },
                            placeholder = { Text("Search or enter address", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submitOmnibox(omniboxText) }),
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

                    // ---- Omnibox autocomplete (history + bookmarks) --------------
                    if (editingOmnibox && omniboxText.isNotBlank()) {
                        val q = omniboxText.trim().lowercase()
                        val fromHistory = historyCache
                            .filter { it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }
                            .take(4)
                        val fromBookmarks = bookmarks
                            .filter { it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }
                            .take(2)
                        Column(Modifier.fillMaxWidth()) {
                            SuggestionRow(
                                icon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
                                primary = omniboxText.trim(),
                                secondary = "Search Google"
                            ) { submitOmnibox(omniboxText) }
                            fromBookmarks.forEach { bm ->
                                SuggestionRow(
                                    icon = { Icon(Icons.Default.Star, null, tint = Color(0xFFFFD54F), modifier = Modifier.size(16.dp)) },
                                    primary = bm.title.ifBlank { bm.url },
                                    secondary = bm.url
                                ) { submitOmnibox(bm.url) }
                            }
                            fromHistory.forEach { h ->
                                SuggestionRow(
                                    icon = { Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp)) },
                                    primary = h.title.ifBlank { h.url },
                                    secondary = h.url
                                ) { submitOmnibox(h.url) }
                            }
                        }
                    }

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

            // ---- Middle: pull-to-refresh wrapping the live tabs -------------------
            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        SwipeRefreshLayout(ctx).apply {
                            addView(FrameLayout(ctx).also { containerRef.value = it })
                            setOnRefreshListener {
                                tabError = tabError?.takeIf { it.first != activeIdRef.longValue }
                                webViews[activeIdRef.longValue]?.reload()
                            }
                        }.also { pullRef.value = it }
                    },
                    update = { _ ->
                        // Attach the active tab's WebView to the tree (and only
                        // the active one). SwipeRefreshLayout auto-gates its
                        // gesture via canChildScrollUp on the attached WebView.
                        val tab = tabs.firstOrNull { it.id == activeTabId }
                        val wv = if (tab != null && tab.url.isNotBlank()) obtainWebView(tab) else null
                        val container = containerRef.value
                        if (container != null) {
                            val current = container.getChildAt(0)
                            if (current !== wv) {
                                (wv?.parent as? ViewGroup)?.removeView(wv) // e.g. reattach after fullscreen video
                                container.removeAllViews()
                                (current as? WebView)?.onPause()
                                if (wv != null) {
                                    container.addView(wv)
                                    wv.onResume()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // New-tab start page (tiles + bookmarks) overlays an empty tab.
                if (activeTab != null && activeTab.url.isBlank()) {
                    StartPage(
                        bookmarks = bookmarks,
                        private = activeTab.private,
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
                    text = { Text("New private tab") },
                    leadingIcon = { Icon(Icons.Default.Shield, null, tint = Color(0xFFB388FF)) },
                    onClick = {
                        menuOpen = false
                        openInNewTab("", private = true)
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
                        historyCache = BrowserStore.loadHistory(context)
                        showHistory = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Downloads") },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = {
                        menuOpen = false
                        showDownloads = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Clear browsing data") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = {
                        menuOpen = false
                        showClearData = true
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
                onNewPrivateTab = { openInNewTab("", private = true) },
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
                        historyCache = emptyList()
                        showHistory = false
                    }) { Text("Clear all") }
                }
                val history = historyCache
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

        // ---- Downloads sheet ---------------------------------------------------------
        if (showDownloads) {
            ModalBottomSheet(onDismissRequest = { showDownloads = false }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Downloads", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        showDownloads = false
                    }) { Text("Open Downloads") }
                }
                val downloads = BrowserStore.loadDownloads(context)
                if (downloads.isEmpty()) {
                    Text(
                        "Files you download from sites show up here (and in your system Downloads).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                LazyColumn {
                    items(downloads, key = { it.id }) { dl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    val uri = runCatching { dm.getUriForDownloadedFile(dl.id) }.getOrNull()
                                    if (uri != null) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, if (dl.mime?.startsWith("image") == true) "image/*" else dl.mime ?: "*/*")
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }
                                    } else {
                                        runCatching {
                                            context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(dl.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    android.text.format.DateUtils.getRelativeTimeSpanString(dl.at).toString(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Clear browsing data sheet ----------------------------------------------------
        if (showClearData) {
            var clearHistory by remember { mutableStateOf(true) }
            var clearCookies by remember { mutableStateOf(true) }
            var clearCache by remember { mutableStateOf(true) }
            var clearPerms by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { showClearData = false },
                title = { Text("Clear browsing data") },
                text = {
                    Column {
                        listOf(
                            "Browsing history" to clearHistory,
                            "Cookies and site data" to clearCookies,
                            "Cached images and files" to clearCache,
                            "Site settings (permission decisions)" to clearPerms
                        ).forEachIndexed { i, (label, _) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    when (i) {
                                        0 -> clearHistory = !clearHistory
                                        1 -> clearCookies = !clearCookies
                                        2 -> clearCache = !clearCache
                                        else -> clearPerms = !clearPerms
                                    }
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = when (i) {
                                        0 -> clearHistory
                                        1 -> clearCookies
                                        2 -> clearCache
                                        else -> clearPerms
                                    },
                                    onCheckedChange = {
                                        when (i) {
                                            0 -> clearHistory = it
                                            1 -> clearCookies = it
                                            2 -> clearCache = it
                                            else -> clearPerms = it
                                        }
                                    }
                                )
                                Text(label, fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (clearHistory) {
                            BrowserStore.clearHistory(context)
                            historyCache = emptyList()
                        }
                        if (clearCookies) {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                        }
                        if (clearCache) {
                            webViews.values.forEach { it.clearCache(true) }
                            WebView(context).clearCache(true)
                            WebStorage.getInstance().deleteAllData()
                        }
                        if (clearPerms) BrowserStore.clearSitePermissions(context)
                        showClearData = false
                        Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                    }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearData = false }) { Text("Cancel") }
                }
            )
        }

        // ---- Site permission prompt ------------------------------------------------------
        pendingPagePermission?.let { request ->
            val origin = request.origin?.host ?: "This site"
            val wants = request.resources.mapNotNull { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "protected content"
                    else -> null
                }
            }.filter { it != "protected content" || true }.distinct()
            AlertDialog(
                onDismissRequest = { request.deny(); pendingPagePermission = null },
                title = { Text("$origin wants to use") },
                text = { Text(if (wants.isEmpty()) "your device" else wants.joinToString(" and your ") { "your $it" }) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingPagePermission = null
                        val appPerms = request.resources.mapNotNull { res ->
                            when (res) {
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> android.Manifest.permission.CAMERA
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> android.Manifest.permission.RECORD_AUDIO
                                else -> null
                            }
                        }.distinct()
                        if (appPerms.isEmpty()) {
                            request.resources.forEach { res ->
                                val key = when (res) {
                                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "video"
                                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "audio"
                                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "drm"
                                    else -> res
                                }
                                BrowserStore.setSitePermission(context, request.origin.toString(), key, true)
                            }
                            request.grant(request.resources)
                        } else {
                            afterRuntimePerms = {
                                request.resources.forEach { res ->
                                    val key = when (res) {
                                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "video"
                                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "audio"
                                        else -> res
                                    }
                                    BrowserStore.setSitePermission(context, request.origin.toString(), key, true)
                                }
                                request.grant(request.resources)
                            }
                            runtimePermLauncher.launch(appPerms.toTypedArray())
                        }
                    }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        request.origin?.toString()?.let { o ->
                            request.resources.forEach { res ->
                                val key = when (res) {
                                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "video"
                                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "audio"
                                    else -> res
                                }
                                BrowserStore.setSitePermission(context, o, key, false)
                            }
                        }
                        request.deny()
                        pendingPagePermission = null
                    }) { Text("Don't allow") }
                }
            )
        }

        // ---- Geolocation prompt -------------------------------------------------------------
        pendingGeoPermission?.let { geo ->
            AlertDialog(
                onDismissRequest = {
                    geo.callback.invoke(geo.origin, false, false)
                    pendingGeoPermission = null
                },
                title = { Text("${Uri.parse(geo.origin).host ?: geo.origin} wants your location") },
                text = { Text("This site wants to use your device's location.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingGeoPermission = null
                        BrowserStore.setSitePermission(context, geo.origin, "geo", true)
                        geoPermLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                        afterRuntimePerms = null
                        // Grant is finished inside the launcher callback.
                    }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        BrowserStore.setSitePermission(context, geo.origin, "geo", false)
                        geo.callback.invoke(geo.origin, false, false)
                        pendingGeoPermission = null
                    }) { Text("Don't allow") }
                }
            )
        }

        // ---- Long-press link/image menu --------------------------------------------------
        linkMenu?.let { menu ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { linkMenu = null }
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.8f)
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        menu.linkUrl?.let { link ->
                            MenuRow(Icons.Default.Tab, "Open link in new tab") {
                                openInNewTab(link, private = activeTab?.private == true)
                                linkMenu = null
                            }
                            MenuRow(Icons.Default.ContentCopy, "Copy link address") {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("link", link))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                linkMenu = null
                            }
                            MenuRow(Icons.Default.Share, "Share link") {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, link)
                                        },
                                        "Share link"
                                    )
                                )
                                linkMenu = null
                            }
                        }
                        menu.imageUrl?.let { image ->
                            MenuRow(Icons.Default.Image, "Open image in new tab") {
                                openInNewTab(image, private = activeTab?.private == true)
                                linkMenu = null
                            }
                            MenuRow(Icons.Default.Download, "Download image") {
                                downloadListener(image, "", "", "image/*", 0)
                                linkMenu = null
                            }
                            MenuRow(Icons.Default.Share, "Share image") {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, image)
                                        },
                                        "Share image"
                                    )
                                )
                                linkMenu = null
                            }
                        }
                        if (menu.linkUrl == null && menu.imageUrl == null) {
                            MenuRow(Icons.Default.Close, "Nothing here") { linkMenu = null }
                        }
                    }
                }
            }
        }
    }
}

private data class LinkMenuData(val linkUrl: String?, val imageUrl: String?)
private data class GeoPrompt(val origin: String, val callback: GeolocationPermissions.Callback)

@Composable
private fun SuggestionRow(
    icon: @Composable () -> Unit,
    primary: String,
    secondary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(primary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                secondary,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 14.dp), fontSize = 15.sp)
    }
}

// ----------------------------------------------------------------------------------
// Start page (new tab)
// ----------------------------------------------------------------------------------

@Composable
private fun StartPage(
    bookmarks: List<BrowserStore.Bookmark>,
    private: Boolean,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (private) "Private tab" else "Chromium",
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier.weight(1f)
            )
            if (private) {
                Text(
                    "Nothing from this tab is saved",
                    color = Color(0xFFB388FF),
                    fontSize = 12.sp
                )
            }
        }
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
    onNewPrivateTab: () -> Unit,
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
                IconButton(onClick = onNewPrivateTab) {
                    Icon(
                        Icons.Default.Shield,
                        "New private tab",
                        tint = Color(0xFFB388FF)
                    )
                }
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
                            if (tab.private) {
                                Icon(
                                    Icons.Default.Shield,
                                    null,
                                    tint = Color(0xFFB388FF),
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Favicon(tab.url)
                            }
                            Text(
                                tab.title.ifBlank { tab.url.ifBlank { if (tab.private) "Private tab" else "New tab" } },
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
                            if (tab.private) {
                                Icon(Icons.Default.Shield, null, tint = Color(0xFFB388FF).copy(alpha = 0.5f))
                            } else {
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
}

/** Real favicon from Google's favicon service; letter avatar behind it as fallback. */
@Composable
private fun Favicon(url: String) {
    val domain = url.toDomain()
    Box(
        Modifier
            .size(24.dp)
            .background(
                letterColor(domain),
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
        if (domain.isNotBlank()) {
            AsyncImage(
                model = "https://www.google.com/s2/favicons?domain=$domain&sz=64",
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun letterColor(domain: String): Color {
    if (domain.isEmpty()) return Color(0xFF546E7A)
    val h = (MessageDigest.getInstance("MD5").digest(domain.toByteArray()).first().toInt() and 0xFF) / 255f * 360f
    return Color.hsv(h, 0.55f, 0.85f).copy(alpha = 0.9f)
}

private fun String.toDomain(): String =
    runCatching { Uri.parse(this).host?.removePrefix("www.").orEmpty() }.getOrDefault("")

// ----------------------------------------------------------------------------------
// Tab model + persistence (tabs, bookmarks, history, downloads, site permissions)
// ----------------------------------------------------------------------------------

data class BrowserTab(
    val id: Long = System.nanoTime(),
    val url: String,          // blank = new-tab start page
    val title: String = "",
    val desktop: Boolean = false,
    val private: Boolean = false
)

object BrowserStore {
    data class Bookmark(val url: String, val title: String)
    data class HistoryEntry(val url: String, val title: String, val at: Long)
    data class DownloadEntry(val id: Long, val name: String, val at: Long, val mime: String? = null)

    private const val PREFS = "browser_prefs"
    private const val TABS = "tabs"
    private const val ACTIVE = "active"
    private const val BOOKMARKS = "bookmarks"
    private const val HISTORY = "history"
    private const val DOWNLOADS = "downloads"
    private const val SITE_PERMS = "site_perms"
    private const val HISTORY_MAX = 300

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Tabs (private tabs are never persisted) ----

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
        for (t in tabs.filter { !it.private }) {
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

    // ---- Bookmarks ----

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

    // ---- History ----

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

    // ---- Downloads ----

    fun loadDownloads(context: Context): List<DownloadEntry> = runCatching {
        val arr = JSONArray(prefs(context).getString(DOWNLOADS, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DownloadEntry(
                id = o.getLong("id"),
                name = o.getString("name"),
                at = o.optLong("at", 0L),
                mime = o.optString("mime", null)
            )
        }
    }.getOrDefault(emptyList())

    fun addDownload(context: Context, entry: DownloadEntry) {
        val updated = (listOf(entry) + loadDownloads(context)).take(100)
        val arr = JSONArray()
        for (d in updated) {
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("at", d.at)
                    .put("mime", d.mime ?: "")
            )
        }
        prefs(context).edit().putString(DOWNLOADS, arr.toString()).apply()
    }

    // ---- Per-site permission decisions (origin -> {video/audio/geo/drm: allowed}) ----

    fun sitePermission(context: Context, origin: String, key: String): Boolean? = runCatching {
        JSONObject(prefs(context).getString(SITE_PERMS, "{}"))
            .optJSONObject(origin)?.optBoolean(key) // null when unset
    }.getOrNull()

    fun setSitePermission(context: Context, origin: String, key: String, allowed: Boolean) {
        val root = runCatching { JSONObject(prefs(context).getString(SITE_PERMS, "{}")) }.getOrDefault(JSONObject())
        val site = root.optJSONObject(origin) ?: JSONObject().also { root.put(origin, it) }
        site.put(key, allowed)
        prefs(context).edit().putString(SITE_PERMS, root.toString()).apply()
    }

    fun clearSitePermissions(context: Context) {
        prefs(context).edit().remove(SITE_PERMS).apply()
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
        safeBrowsingEnabled = true // malware/phishing interstitials
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
