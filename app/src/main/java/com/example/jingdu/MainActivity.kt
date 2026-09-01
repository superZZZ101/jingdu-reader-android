package com.example.jingdu

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

private enum class AppScreen { HOME, READER, BOOKSHELF }
private enum class ReaderTheme { IVORY, PAPER, NIGHT }
private enum class PageMode { VERTICAL, HORIZONTAL }
private enum class HorizontalTapMode { SIDE_PAGES, BOTH_NEXT }
private enum class ScreenOrientation { PORTRAIT, LANDSCAPE, SYSTEM }
private enum class ReaderPanel { NONE, SETTINGS, CATALOG }
private enum class ChapterOpenPosition { START, END }

private fun ScreenOrientation.toRequestedOrientation(): Int = when (this) {
    ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    ScreenOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

private fun loadScreenOrientation(preferences: android.content.SharedPreferences): ScreenOrientation = runCatching {
    ScreenOrientation.valueOf(
        preferences.getString("screen_orientation", ScreenOrientation.PORTRAIT.name)
            ?: ScreenOrientation.PORTRAIT.name
    )
}.getOrDefault(ScreenOrientation.PORTRAIT)

private data class PendingChapterNavigation(
    val url: String,
    val position: ChapterOpenPosition?,
    val catalogUrl: String?,
    val catalogIndex: Int?,
    val verticalIndex: Int?,
    val verticalOffset: Int?
)

private data class ChapterPage(
    val text: String,
    val startOffset: Int
)

private data class VerticalViewport(
    val scrolling: Boolean,
    val firstVisible: Int,
    val firstOffset: Int,
    val lastVisible: Int,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean
)

private data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.IVORY,
    val fontSize: Int = 19,
    val lineHeight: Float = 1.95f,
    val pageMode: PageMode = PageMode.VERTICAL,
    val horizontalTapMode: HorizontalTapMode = HorizontalTapMode.SIDE_PAGES,
    val screenOrientation: ScreenOrientation = ScreenOrientation.PORTRAIT
)

private data class ReaderPalette(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val border: Color
)

private val IvoryPalette = ReaderPalette(
    background = Color(0xFFF8F4E9),
    surface = Color(0xEEF8F4E9),
    ink = Color(0xFF302D27),
    muted = Color(0xFF827B6D),
    accent = Color(0xFFA45F38),
    border = Color(0x2B5B5343)
)

private val PaperPalette = ReaderPalette(
    background = Color(0xFFE9EEEB),
    surface = Color(0xEEF0F4F1),
    ink = Color(0xFF263530),
    muted = Color(0xFF6F8179),
    accent = Color(0xFF286C59),
    border = Color(0x2B334F43)
)

private val NightPalette = ReaderPalette(
    background = Color(0xFF1F2523),
    surface = Color(0xF21F2523),
    ink = Color(0xFFE2E5DE),
    muted = Color(0xFF9BA69E),
    accent = Color(0xFFDC9B63),
    border = Color(0x2BDCE5DC)
)

class MainActivity : ComponentActivity() {
    private val sharedUrlState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = loadScreenOrientation(getSharedPreferences("jingdu", 0)).toRequestedOrientation()
        sharedUrlState.value = extractSharedUrl(intent)
        setContent { JingduApp(initialUrl = sharedUrlState.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUrlState.value = extractSharedUrl(intent)
    }
}

@Composable
private fun JingduApp(initialUrl: String = "") {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? Activity
    val preferences = remember { context.getSharedPreferences("jingdu", 0) }
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME.name) }
    var address by rememberSaveable { mutableStateOf(initialUrl) }
    var currentUrl by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) address = initialUrl
    }
    var document by remember { mutableStateOf<ReaderDocument?>(null) }
    var previousDocument by remember { mutableStateOf<ReaderDocument?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(loadSettings(preferences)) }
    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        availableUpdate = checkForAppUpdate(BuildConfig.VERSION_NAME)
    }
    LaunchedEffect(settings.screenOrientation) {
        activity?.requestedOrientation = settings.screenOrientation.toRequestedOrientation()
    }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var prefetchWebView by remember { mutableStateOf<WebView?>(null) }
    var previousWebView by remember { mutableStateOf<WebView?>(null) }
    var catalogWebView by remember { mutableStateOf<WebView?>(null) }
    var activeLoadUrl by rememberSaveable { mutableStateOf("") }
    var prefetchLoadUrl by remember { mutableStateOf("") }
    var previousLoadUrl by remember { mutableStateOf("") }
    var catalogLoadUrl by remember { mutableStateOf("") }
    var activeCatalogUrl by remember { mutableStateOf("") }
    var activeCatalogIndex by remember { mutableStateOf<Int?>(null) }
    var chapterOpenPosition by remember { mutableStateOf<ChapterOpenPosition?>(null) }
    var verticalOpenIndex by remember { mutableStateOf<Int?>(null) }
    var verticalOpenOffset by remember { mutableStateOf<Int?>(null) }
    var readingOffset by remember { mutableStateOf<Int?>(null) }
    var pendingChapterNavigation by remember { mutableStateOf<PendingChapterNavigation?>(null) }
    var cachedDocuments by remember { mutableStateOf<Map<String, ReaderDocument>>(emptyMap()) }
    var shelfBooks by remember { mutableStateOf(loadShelfBooks(preferences)) }

    fun cacheDocument(result: ReaderDocument, requestedUrl: String = "") {
        val aliases = listOf(cacheKey(result.sourceUrl), cacheKey(requestedUrl)).filter { it.isNotEmpty() }.distinct()
        if (aliases.isEmpty()) return
        val updated = cachedDocuments.toMutableMap()
        aliases.forEach { updated[it] = result }
        cachedDocuments = updated
    }

    fun findCached(url: String): ReaderDocument? {
        val key = cacheKey(url)
        if (key.isEmpty()) return null
        return cachedDocuments[key] ?: cachedDocuments.values.firstOrNull { cacheKey(it.sourceUrl) == key }
    }

    fun pruneCache(current: ReaderDocument?, previous: ReaderDocument?) {
        val keep = mutableSetOf<String>()
        listOfNotNull(current, previous).forEach { keep += cacheKey(it.sourceUrl) }
        current?.navigation?.previous?.href?.let { keep += cacheKey(it) }
        current?.navigation?.next?.href?.let { keep += cacheKey(it) }
        val keptSources = cachedDocuments.values
            .distinctBy { cacheKey(it.sourceUrl) }
            .filter { cacheKey(it.sourceUrl) in keep }
            .map { cacheKey(it.sourceUrl) }
            .toSet()
        cachedDocuments = cachedDocuments.filterValues {
            it.isCatalog || cacheKey(it.sourceUrl) in keptSources
        }
    }

    fun prepareNext(result: ReaderDocument) {
        if (result.isCatalog) {
            prefetchLoadUrl = ""
            return
        }
        val next = result.navigation.next?.href?.let(::normalizeUrl).orEmpty()
        val cachedNext = next.takeIf { it.isNotEmpty() }?.let(::findCached)
        val nextReady = cachedNext != null && !cachedNext.isCatalog && cachedNext.paragraphs.isNotEmpty()
        prefetchLoadUrl = if (next.isNotEmpty() && !nextReady && !sameUrl(next, result.sourceUrl)) next else ""
    }

    fun preparePrevious(result: ReaderDocument) {
        if (result.isCatalog) {
            previousLoadUrl = ""
            return
        }
        val previous = result.navigation.previous?.href?.let(::normalizeUrl).orEmpty()
        val cachedPrevious = previous.takeIf { it.isNotEmpty() }?.let(::findCached)
        val previousReady = cachedPrevious != null && !cachedPrevious.isCatalog && cachedPrevious.paragraphs.isNotEmpty()
        previousLoadUrl = if (previous.isNotEmpty() && !previousReady && !sameUrl(previous, result.sourceUrl)) previous else ""
    }

    fun prepareCatalog(result: ReaderDocument) {
        if (result.isCatalog) {
            catalogLoadUrl = ""
            return
        }
        val catalog = result.navigation.catalog?.href?.let(::normalizeUrl).orEmpty()
        catalogLoadUrl = if (catalog.isNotEmpty() && findCached(catalog) == null && !sameUrl(catalog, result.sourceUrl)) catalog else ""
    }

    fun updateShelfForDocument(result: ReaderDocument) {
        if (result.isCatalog || result.paragraphs.isEmpty()) return
        val key = shelfKeyForDocument(result)
        val current = shelfBooks.firstOrNull { it.key == key } ?: return
        val updated = current.copy(
            catalogUrl = current.catalogUrl.ifBlank {
                result.navigation.catalog?.href?.let(::normalizeUrl).orEmpty()
            },
            lastReadUrl = result.sourceUrl,
            lastChapterTitle = result.title,
            updatedAt = System.currentTimeMillis()
        )
        shelfBooks = listOf(updated) + shelfBooks.filterNot { it.key == key }
        saveShelfBooks(preferences, shelfBooks)
    }

    fun addCurrentBookToShelf() {
        val result = document?.takeUnless { it.isCatalog } ?: return
        val key = shelfKeyForDocument(result)
        val existing = shelfBooks.firstOrNull { it.key == key }
        val catalogUrl = existing?.catalogUrl.orEmpty().ifBlank {
            result.navigation.catalog?.href?.let(::normalizeUrl).orEmpty()
        }
        val catalog = catalogUrl.takeIf { it.isNotBlank() }?.let(::findCached)
        val updated = ShelfBook(
            key = key,
            title = existing?.title?.takeIf { it.isNotBlank() } ?: bookTitleForDocument(result, catalog),
            catalogUrl = catalogUrl,
            lastReadUrl = result.sourceUrl,
            lastChapterTitle = result.title,
            updatedAt = System.currentTimeMillis()
        )
        shelfBooks = listOf(updated) + shelfBooks.filterNot { it.key == key }
        saveShelfBooks(preferences, shelfBooks)
    }

    fun showDocument(
        result: ReaderDocument,
        requestedUrl: String,
        openPosition: ChapterOpenPosition? = null,
        catalogUrlOverride: String? = null,
        catalogIndexOverride: Int? = null,
        verticalIndexOverride: Int? = null,
        verticalOffsetOverride: Int? = null,
        loadActiveWebView: Boolean = true
    ) {
        val old = document
        if (old != null && !old.isCatalog && !result.isCatalog && !sameUrl(old.sourceUrl, result.sourceUrl)) {
            previousDocument = old
        }
        if (old == null || old.isCatalog || result.isCatalog || !sameUrl(old.sourceUrl, result.sourceUrl)) {
            readingOffset = null
        }
        cacheDocument(result, requestedUrl)
        document = result
        updateShelfForDocument(result)
        currentUrl = result.sourceUrl
        address = result.sourceUrl
        loading = false
        errorMessage = null
        chapterOpenPosition = openPosition
        verticalOpenIndex = verticalIndexOverride
        verticalOpenOffset = verticalOffsetOverride
        val resolvedUrl = normalizeUrl(result.sourceUrl).ifEmpty { normalizeUrl(requestedUrl) }
        activeLoadUrl = if (loadActiveWebView) resolvedUrl else ""
        if (!result.isCatalog) {
            activeCatalogUrl = catalogUrlOverride?.let(::normalizeUrl)?.takeIf { it.isNotEmpty() }
                ?: result.navigation.catalog?.href?.let(::normalizeUrl).orEmpty()
            if (catalogIndexOverride != null) activeCatalogIndex = catalogIndexOverride
        }
        pruneCache(result, previousDocument)
        preparePrevious(result)
        prepareNext(result)
        prepareCatalog(result)
    }

    fun openUrl(
        raw: String,
        openPosition: ChapterOpenPosition? = null,
        catalogUrlOverride: String? = null,
        catalogIndexOverride: Int? = null,
        verticalIndexOverride: Int? = null,
        verticalOffsetOverride: Int? = null
    ) {
        val normalized = normalizeUrl(raw)
        val catalogContext = catalogUrlOverride?.let(::normalizeUrl)?.takeIf { it.isNotEmpty() }
        if (normalized.isEmpty()) {
            errorMessage = "请输入完整的网址，例如 https://example.com"
            return
        }
        preferences.edit().putString("last_url", normalized).apply()
        screen = AppScreen.READER.name
        if (catalogContext != null) activeCatalogUrl = catalogContext
        activeCatalogIndex = catalogIndexOverride
        val pendingNavigation = if (
            openPosition != null ||
            catalogContext != null ||
            catalogIndexOverride != null ||
            verticalIndexOverride != null
        ) {
            PendingChapterNavigation(
                normalized,
                openPosition,
                catalogContext,
                catalogIndexOverride,
                verticalIndexOverride,
                verticalOffsetOverride
            )
        } else {
            null
        }
        val cached = findCached(normalized)
        if (cached != null && document != null && !document!!.isCatalog && !cached.isCatalog &&
            sameUrl(cached.sourceUrl, document!!.sourceUrl)
        ) {
            pendingChapterNavigation = null
            preparePrevious(document!!)
            prepareNext(document!!)
            prepareCatalog(document!!)
            return
        }
        if (cached != null) {
            pendingChapterNavigation = null
            showDocument(
                cached,
                normalized,
                openPosition,
                catalogContext,
                catalogIndexOverride,
                verticalIndexOverride,
                verticalOffsetOverride,
                loadActiveWebView = false
            )
        } else {
            val preloading = (openPosition != null || verticalIndexOverride != null) && (
                sameUrl(normalized, prefetchLoadUrl) || sameUrl(normalized, previousLoadUrl)
            )
            if (preloading) {
                pendingChapterNavigation = pendingNavigation
                errorMessage = null
                loading = false
                activeLoadUrl = normalized
                return
            }
            pendingChapterNavigation = pendingNavigation
            document = null
            errorMessage = null
            loading = true
            currentUrl = normalized
            activeLoadUrl = normalized
            prefetchLoadUrl = ""
            previousLoadUrl = ""
        }
    }

    fun handlePrefetchedChapter(result: ReaderDocument, expected: String) {
        if (sameUrl(prefetchLoadUrl, expected)) prefetchLoadUrl = ""
        if (sameUrl(previousLoadUrl, expected)) previousLoadUrl = ""
        cacheDocument(result, expected)
        val pending = pendingChapterNavigation
        if (pending != null && sameUrl(pending.url, expected)) {
            pendingChapterNavigation = null
            showDocument(
                result,
                expected,
                pending.position,
                pending.catalogUrl,
                pending.catalogIndex,
                pending.verticalIndex,
                pending.verticalOffset,
                loadActiveWebView = false
            )
        }
    }

    fun fallbackPendingChapterToActive(expected: String) {
        val pending = pendingChapterNavigation
        if (pending != null && sameUrl(pending.url, expected)) {
            errorMessage = null
            loading = false
            activeLoadUrl = expected
        }
    }

    val activePayloadState = rememberUpdatedState<(Long, String, String) -> Unit> { requestToken, pageUrl, rawPayload ->
        val expected = activeLoadUrl
        val result = parseReaderPayload(rawPayload)
        val tokenMatches = requestToken != 0L && requestToken == webViewLoadToken(activeWebView)
        val matches = expected.isNotEmpty() && tokenMatches &&
            (sameUrl(pageUrl, expected) || (result != null && result.sourceUrl.isNotBlank()))
        if (matches) {
            val pending = pendingChapterNavigation
            if (pending == null || sameUrl(pending.url, expected)) {
                if (result == null) {
                    pendingChapterNavigation = null
                    loading = false
                    errorMessage = "网页正文暂时无法读取，请检查地址或网络连接。"
                } else {
                    pendingChapterNavigation = null
                    showDocument(
                        result,
                        expected,
                        pending?.position,
                        pending?.catalogUrl,
                        pending?.catalogIndex,
                        pending?.verticalIndex,
                        pending?.verticalOffset
                    )
                }
            }
        }
    }
    val activeErrorState = rememberUpdatedState<(String) -> Unit> { pageUrl ->
        if (activeLoadUrl.isNotEmpty() && sameUrl(pageUrl, activeLoadUrl)) {
            pendingChapterNavigation = null
            loading = false
            errorMessage = "网页加载失败，请检查地址或网络连接。"
        }
    }
    val prefetchPayloadState = rememberUpdatedState<(Long, String, String) -> Unit> { requestToken, pageUrl, rawPayload ->
        val expected = prefetchLoadUrl
        val result = parseReaderPayload(rawPayload)
        val tokenMatches = requestToken != 0L && requestToken == webViewLoadToken(prefetchWebView)
        val matches = expected.isNotEmpty() && tokenMatches &&
            (sameUrl(pageUrl, expected) || (result != null && result.sourceUrl.isNotBlank()))
        if (matches && result != null && !result.isCatalog && result.paragraphs.isNotEmpty()) {
            handlePrefetchedChapter(result, expected)
        }
    }
    val prefetchErrorState = rememberUpdatedState<(String) -> Unit> { pageUrl ->
        if (prefetchLoadUrl.isNotEmpty() && sameUrl(pageUrl, prefetchLoadUrl)) {
            val failedUrl = prefetchLoadUrl
            prefetchLoadUrl = ""
            fallbackPendingChapterToActive(failedUrl)
        }
    }
    val previousPayloadState = rememberUpdatedState<(Long, String, String) -> Unit> { requestToken, pageUrl, rawPayload ->
        val expected = previousLoadUrl
        val result = parseReaderPayload(rawPayload)
        val tokenMatches = requestToken != 0L && requestToken == webViewLoadToken(previousWebView)
        val matches = expected.isNotEmpty() && tokenMatches &&
            (sameUrl(pageUrl, expected) || (result != null && result.sourceUrl.isNotBlank()))
        if (matches && result != null && !result.isCatalog && result.paragraphs.isNotEmpty()) {
            handlePrefetchedChapter(result, expected)
        }
    }
    val previousErrorState = rememberUpdatedState<(String) -> Unit> { pageUrl ->
        if (previousLoadUrl.isNotEmpty() && sameUrl(pageUrl, previousLoadUrl)) {
            val failedUrl = previousLoadUrl
            previousLoadUrl = ""
            fallbackPendingChapterToActive(failedUrl)
        }
    }
    val catalogPayloadState = rememberUpdatedState<(Long, String, String) -> Unit> { requestToken, pageUrl, rawPayload ->
        val expected = catalogLoadUrl
        val result = parseReaderPayload(rawPayload)
        val tokenMatches = requestToken != 0L && requestToken == webViewLoadToken(catalogWebView)
        val matches = expected.isNotEmpty() && tokenMatches &&
            (sameUrl(pageUrl, expected) || (result != null && result.sourceUrl.isNotBlank()))
        if (matches && result != null) cacheDocument(result, expected)
    }
    val catalogErrorState = rememberUpdatedState<(String) -> Unit> { pageUrl ->
        if (catalogLoadUrl.isNotEmpty() && sameUrl(pageUrl, catalogLoadUrl)) catalogLoadUrl = ""
    }

    LaunchedEffect(activeWebView, activeLoadUrl) {
        val target = activeLoadUrl
        val view = activeWebView
        if (view != null && target.isNotEmpty() && !sameUrl(view.url.orEmpty(), target)) {
            view.stopLoading()
            startWebViewLoad(view, target)
        }
    }
    LaunchedEffect(prefetchWebView, prefetchLoadUrl) {
        val target = prefetchLoadUrl
        val view = prefetchWebView
        if (view != null && target.isNotEmpty()) {
            view.stopLoading()
            if (!sameUrl(view.url.orEmpty(), target)) startWebViewLoad(view, target)
        }
    }
    LaunchedEffect(previousWebView, previousLoadUrl) {
        val target = previousLoadUrl
        val view = previousWebView
        if (view != null && target.isNotEmpty()) {
            view.stopLoading()
            if (!sameUrl(view.url.orEmpty(), target)) startWebViewLoad(view, target)
        }
    }
    LaunchedEffect(catalogWebView, catalogLoadUrl) {
        val target = catalogLoadUrl
        val view = catalogWebView
        if (view != null && target.isNotEmpty()) {
            view.stopLoading()
            if (!sameUrl(view.url.orEmpty(), target)) startWebViewLoad(view, target)
        }
    }

    JingduTheme(settings.theme) {
        Box(modifier = Modifier.fillMaxSize().background(IvoryPalette.background)) {
            AndroidView(
                modifier = Modifier.size(1.dp).alpha(0f),
                factory = { viewContext ->
                    createReaderWebView(
                        context = viewContext,
                        onPayload = { requestToken, pageUrl, rawPayload -> activePayloadState.value(requestToken, pageUrl, rawPayload) },
                        onError = { pageUrl -> activeErrorState.value(pageUrl) }
                    ).also { activeWebView = it }
                },
                update = { activeWebView = it }
            )
            AndroidView(
                modifier = Modifier.size(1.dp).alpha(0f),
                factory = { viewContext ->
                    createReaderWebView(
                        context = viewContext,
                        onPayload = { requestToken, pageUrl, rawPayload -> prefetchPayloadState.value(requestToken, pageUrl, rawPayload) },
                        onError = { pageUrl -> prefetchErrorState.value(pageUrl) }
                    ).also { prefetchWebView = it }
                },
                update = { prefetchWebView = it }
            )
            AndroidView(
                modifier = Modifier.size(1.dp).alpha(0f),
                factory = { viewContext ->
                    createReaderWebView(
                        context = viewContext,
                        onPayload = { requestToken, pageUrl, rawPayload -> previousPayloadState.value(requestToken, pageUrl, rawPayload) },
                        onError = { pageUrl -> previousErrorState.value(pageUrl) }
                    ).also { previousWebView = it }
                },
                update = { previousWebView = it }
            )
            AndroidView(
                modifier = Modifier.size(1.dp).alpha(0f),
                factory = { viewContext ->
                    createReaderWebView(
                        context = viewContext,
                        onPayload = { requestToken, pageUrl, rawPayload -> catalogPayloadState.value(requestToken, pageUrl, rawPayload) },
                        onError = { pageUrl -> catalogErrorState.value(pageUrl) }
                    ).also { catalogWebView = it }
                },
                update = { catalogWebView = it }
            )

            if (screen == AppScreen.HOME.name) {
                HomeScreen(
                    address = address,
                    onAddressChange = { address = it },
                    onOpen = { openUrl(address) },
                    recentUrl = preferences.getString("last_url", null),
                    shelfCount = shelfBooks.size,
                    onOpenBookshelf = { screen = AppScreen.BOOKSHELF.name },
                    onOpenRecent = { openUrl(it) }
                )
            } else if (screen == AppScreen.BOOKSHELF.name) {
                BackHandler { screen = AppScreen.HOME.name }
                BookshelfScreen(
                    books = shelfBooks,
                    onOpenBook = { book ->
                        openUrl(
                            book.lastReadUrl,
                            catalogUrlOverride = book.catalogUrl.takeIf { it.isNotBlank() }
                        )
                    },
                    onRemoveBook = { book ->
                        shelfBooks = shelfBooks.filterNot { it.key == book.key }
                        saveShelfBooks(preferences, shelfBooks)
                    },
                    onBack = { screen = AppScreen.HOME.name }
                )
            } else {
                val baseCatalogUrl = document?.navigation?.catalog?.href?.let(::normalizeUrl).orEmpty()
                val cachedCatalog = activeCatalogUrl.takeIf { it.isNotEmpty() }?.let(::findCached)
                val currentSourceUrl = document?.sourceUrl.orEmpty()
                val previousChapter = document?.navigation?.previous?.href
                    ?.let(::normalizeUrl)
                    ?.let(::findCached)
                    ?.takeIf { cached -> !cached.isCatalog && cached.paragraphs.isNotEmpty() }
                    ?.takeUnless { cached -> sameUrl(cached.sourceUrl, currentSourceUrl) }
                val nextChapter = document?.navigation?.next?.href
                    ?.let(::normalizeUrl)
                    ?.let(::findCached)
                    ?.takeIf { cached -> !cached.isCatalog && cached.paragraphs.isNotEmpty() }
                    ?.takeUnless { cached ->
                        sameUrl(cached.sourceUrl, currentSourceUrl) ||
                            (previousChapter != null && sameUrl(cached.sourceUrl, previousChapter.sourceUrl))
                    }
                val nextChapterReady = nextChapter != null
                val currentBookInShelf = document?.takeUnless { it.isCatalog }?.let { current ->
                    shelfBooks.any { book -> book.key == shelfKeyForDocument(current) }
                } == true
                BackHandler {
                    screen = if (currentBookInShelf) AppScreen.BOOKSHELF.name else AppScreen.HOME.name
                    loading = false
                }
                ReaderScreen(
                    document = document,
                    loading = loading,
                    errorMessage = errorMessage,
                    settings = settings,
                    readingOffset = readingOffset,
                    chapterOpenPosition = chapterOpenPosition,
                    verticalOpenIndex = verticalOpenIndex,
                    verticalOpenOffset = verticalOpenOffset,
                    previousChapter = previousChapter,
                    nextChapter = nextChapter,
                    nextChapterReady = nextChapterReady,
                    catalogDocument = cachedCatalog,
                    catalogIndex = activeCatalogIndex,
                    catalogLoading = catalogLoadUrl.isNotEmpty(),
                    isInBookshelf = currentBookInShelf,
                    onAddToBookshelf = { addCurrentBookToShelf() },
                    onSettingsChange = {
                        settings = it
                        saveSettings(preferences, it)
                        activity?.requestedOrientation = it.screenOrientation.toRequestedOrientation()
                    },
                    onPositionChange = { readingOffset = it },
                    onNavigate = { openUrl(it) },
                    onNavigateChapter = { href, position -> openUrl(href, position) },
                    onContinueToChapter = { href, index, offset ->
                        openUrl(href, verticalIndexOverride = index, verticalOffsetOverride = offset)
                    },
                    onNavigateFromCatalog = { href, catalogUrl, index ->
                        openUrl(href, catalogUrlOverride = catalogUrl, catalogIndexOverride = index)
                    },
                    onOpenCatalog = {
                        val catalogUrl = activeCatalogUrl.takeIf { it.isNotEmpty() } ?: baseCatalogUrl
                        if (catalogUrl.isNotEmpty() && findCached(catalogUrl) == null) {
                            catalogLoadUrl = catalogUrl
                            if (sameUrl(catalogWebView?.url.orEmpty(), catalogUrl)) catalogWebView?.reload()
                        }
                    },
                    onCatalogNavigate = { href ->
                        val catalogUrl = normalizeUrl(href)
                        if (catalogUrl.isNotEmpty()) {
                            activeCatalogIndex = null
                            activeCatalogUrl = catalogUrl
                            val cached = findCached(catalogUrl)
                            catalogLoadUrl = if (cached == null) catalogUrl else ""
                            if (cached == null && sameUrl(catalogWebView?.url.orEmpty(), catalogUrl)) catalogWebView?.reload()
                        }
                    },
                    onClose = {
                        screen = if (currentBookInShelf) AppScreen.BOOKSHELF.name else AppScreen.HOME.name
                        loading = false
                    },
                    onReload = { openUrl(currentUrl) }
                )
            }
            availableUpdate?.let { update ->
                AlertDialog(
                    onDismissRequest = { availableUpdate = null },
                    title = { Text("发现新版本") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("静读 ${update.versionName} 已发布。")
                            if (update.notes.isNotBlank()) {
                                Text(
                                    update.notes,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val url = update.downloadUrl ?: update.releaseUrl
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                                availableUpdate = null
                            }
                        ) {
                            Text(if (update.downloadUrl != null) "下载更新" else "查看详情")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { availableUpdate = null }) {
                            Text("稍后")
                        }
                    }
                )
            }
        }
    }
}

private fun webViewLoadToken(view: WebView?): Long = (view?.tag as? Long) ?: 0L

private fun startWebViewLoad(view: WebView, target: String) {
    view.tag = webViewLoadToken(view) + 1L
    view.loadUrl(target)
}

@SuppressLint("SetJavaScriptEnabled")
private fun createReaderWebView(
    context: android.content.Context,
    onPayload: (Long, String, String) -> Unit,
    onError: (String) -> Unit
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(1, 1)
        setBackgroundColor(AndroidColor.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val requestToken = webViewLoadToken(view)
                fun extractPayload() {
                    if (requestToken != webViewLoadToken(view)) return
                    view.evaluateJavascript(ReaderScript.extract) { rawPayload ->
                        onPayload(requestToken, url, rawPayload)
                    }
                }
                extractPayload()
                view.postDelayed({ extractPayload() }, 700L)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onError(request.url.toString())
            }
        }
    }
}

@Composable
private fun HomeScreen(
    address: String,
    onAddressChange: (String) -> Unit,
    onOpen: () -> Unit,
    recentUrl: String?,
    shelfCount: Int,
    onOpenBookshelf: () -> Unit,
    onOpenRecent: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IvoryPalette.background)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(11.dp),
                color = Color(0xFFE3EFE7),
                border = BorderStroke(1.dp, Color(0xFFB7D0C3))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MenuBook, contentDescription = null, tint = Color(0xFF226B59))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("静读", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = IvoryPalette.ink)
                Text("手机端阅读模式", fontSize = 12.sp, color = IvoryPalette.muted)
            }
        }
        Spacer(Modifier.height(28.dp))
        OutlinedButton(
            onClick = onOpenBookshelf,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(9.dp)
        ) {
            Icon(Icons.Default.Bookmark, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("书架${if (shelfCount > 0) " · $shelfCount 本" else ""}", fontSize = 14.sp)
        }
        Spacer(Modifier.height(28.dp))
        Text("打开小说网页", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IvoryPalette.muted)
        Spacer(Modifier.height(9.dp))
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://example.com/novel") },
            shape = RoundedCornerShape(9.dp)
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(9.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF226B59))
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("打开并整理", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!recentUrl.isNullOrBlank()) {
            Spacer(Modifier.height(28.dp))
            Text("上次打开", fontSize = 11.sp, color = IvoryPalette.muted)
            TextButton(onClick = { onOpenRecent(recentUrl) }, contentPadding = PaddingValues(0.dp)) {
                Text(recentUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF226B59))
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "正文只在本机整理 · 不上传页面内容",
            fontSize = 11.sp,
            color = IvoryPalette.muted
        )
    }
}

@Composable
private fun BookshelfScreen(
    books: List<ShelfBook>,
    onOpenBook: (ShelfBook) -> Unit,
    onRemoveBook: (ShelfBook) -> Unit,
    onBack: () -> Unit
) {
    val palette = IvoryPalette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = palette.ink)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("书架", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${books.size} 本书", color = palette.muted, fontSize = 12.sp)
            }
        }
        if (books.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Bookmark, contentDescription = null, tint = palette.muted, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("书架为空", color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("打开一本书后，可从阅读菜单加入书架", color = palette.muted, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(books, key = { it.key }) { book ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBook(book) },
                        color = palette.surface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, palette.border)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    book.title,
                                    color = palette.ink,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "读到：${book.lastChapterTitle.ifBlank { "尚未记录章节" }}",
                                    color = palette.muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onRemoveBook(book) }) {
                                Icon(Icons.Default.Delete, contentDescription = "移出书架", tint = palette.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    document: ReaderDocument?,
    loading: Boolean,
    errorMessage: String?,
    settings: ReaderSettings,
    readingOffset: Int?,
    chapterOpenPosition: ChapterOpenPosition?,
    verticalOpenIndex: Int?,
    verticalOpenOffset: Int?,
    previousChapter: ReaderDocument?,
    nextChapter: ReaderDocument?,
    nextChapterReady: Boolean,
    catalogDocument: ReaderDocument?,
    catalogIndex: Int?,
    catalogLoading: Boolean,
    isInBookshelf: Boolean,
    onAddToBookshelf: () -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onPositionChange: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onNavigateChapter: (String, ChapterOpenPosition) -> Unit,
    onContinueToChapter: (String, Int, Int) -> Unit,
    onNavigateFromCatalog: (String, String, Int) -> Unit,
    onOpenCatalog: () -> Unit,
    onCatalogNavigate: (String) -> Unit,
    onClose: () -> Unit,
    onReload: () -> Unit
) {
    val palette = paletteFor(settings.theme)
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf(ReaderPanel.NONE.name) }
    val toggleMenu = {
        menuVisible = !menuVisible
        if (!menuVisible) panel = ReaderPanel.NONE.name
    }
    BackHandler(enabled = menuVisible || panel != ReaderPanel.NONE.name) {
        menuVisible = false
        panel = ReaderPanel.NONE.name
    }
    Surface(modifier = Modifier.fillMaxSize(), color = palette.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .centerTapDetector(toggleMenu)
        ) {
            when {
                loading -> LoadingView(palette)
                errorMessage != null -> ErrorView(errorMessage, palette, onReload)
                document == null -> LoadingView(palette)
                document.isCatalog -> CatalogView(document, palette, settings, onNavigate)
                else -> ChapterView(
                    document = document,
                    palette = palette,
                    settings = settings,
                    readingOffset = readingOffset,
                    chapterOpenPosition = chapterOpenPosition,
                    previousChapter = previousChapter,
                    nextChapter = nextChapter,
                    nextChapterReady = nextChapterReady,
                    verticalOpenIndex = verticalOpenIndex,
                    verticalOpenOffset = verticalOpenOffset,
                    onPositionChange = onPositionChange,
                    onContinueToChapter = onContinueToChapter,
                    onNavigateChapter = onNavigateChapter,
                    onAutoNext = {
                        document.navigation.next?.let { next ->
                            if (!sameUrl(next.href, document.sourceUrl)) {
                                onNavigateChapter(next.href, ChapterOpenPosition.START)
                            }
                        }
                    }
                )
            }
            if (menuVisible) {
                ReaderMenu(
                    document = document,
                    palette = palette,
                    onPreviousChapter = {
                        menuVisible = false
                        document?.navigation?.previous?.let { onNavigateChapter(it.href, ChapterOpenPosition.END) }
                    },
                    onNextChapter = {
                        menuVisible = false
                        document?.navigation?.next?.let { onNavigateChapter(it.href, ChapterOpenPosition.START) }
                    },
                    onOpenCatalog = {
                        onOpenCatalog()
                        menuVisible = false
                        panel = ReaderPanel.CATALOG.name
                    },
                    isInBookshelf = isInBookshelf,
                    onAddToBookshelf = {
                        onAddToBookshelf()
                        menuVisible = false
                    },
                    onOpenSettings = { panel = ReaderPanel.SETTINGS.name },
                    onExitReading = onClose,
                    onClose = toggleMenu,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
            if (menuVisible && panel == ReaderPanel.SETTINGS.name) {
                ReaderSettingsPanel(
                    palette = palette,
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onClose = { panel = ReaderPanel.NONE.name },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 78.dp)
                )
            }
            if (panel == ReaderPanel.CATALOG.name) {
                CatalogDrawer(
                    currentDocument = document,
                    catalogDocument = catalogDocument,
                    catalogIndex = catalogIndex,
                    loading = catalogLoading,
                    palette = palette,
                    settings = settings,
                    onNavigate = { href, index ->
                        panel = ReaderPanel.NONE.name
                        onNavigateFromCatalog(href, catalogDocument?.sourceUrl.orEmpty(), index)
                    },
                    onCatalogNavigate = onCatalogNavigate,
                    onClose = { panel = ReaderPanel.NONE.name },
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
        }
    }
}

@Composable
private fun ReaderMenu(
    document: ReaderDocument?,
    palette: ReaderPalette,
    isInBookshelf: Boolean,
    onPreviousChapter: () -> Unit,
    onOpenCatalog: () -> Unit,
    onNextChapter: () -> Unit,
    onAddToBookshelf: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitReading: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuAction(
                icon = Icons.Default.ArrowBack,
                label = "上一章",
                enabled = document?.navigation?.previous != null,
                palette = palette,
                onClick = onPreviousChapter
            )
            MenuAction(
                icon = Icons.Default.MenuBook,
                label = "目录",
                enabled = document?.navigation?.catalog != null,
                palette = palette,
                onClick = onOpenCatalog
            )
            MenuAction(
                icon = Icons.Default.ArrowForward,
                label = "下一章",
                enabled = document?.navigation?.next != null,
                palette = palette,
                onClick = onNextChapter
            )
            if (!isInBookshelf) {
                MenuAction(
                    icon = Icons.Default.Bookmark,
                    label = "加入书架",
                    enabled = document != null && !document.isCatalog,
                    palette = palette,
                    onClick = onAddToBookshelf
                )
            }
            MenuAction(
                icon = Icons.Default.ArrowBack,
                label = if (isInBookshelf) "返回书架" else "返回首页",
                enabled = true,
                palette = palette,
                onClick = onExitReading
            )
            MenuAction(
                icon = Icons.Default.Settings,
                label = "设置",
                enabled = true,
                palette = palette,
                onClick = onOpenSettings
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "关闭菜单", tint = palette.muted)
            }
        }
    }
}

@Composable
private fun PageModeChip(
    label: String,
    mode: PageMode,
    selectedMode: PageMode,
    palette: ReaderPalette,
    onSelect: (PageMode) -> Unit
) {
    val selected = mode == selectedMode
    Box(
        modifier = Modifier
            .background(if (selected) palette.accent else Color.Transparent, RoundedCornerShape(5.dp))
            .border(BorderStroke(1.dp, if (selected) palette.accent else palette.border), RoundedCornerShape(5.dp))
            .clickable { onSelect(mode) }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = if (selected) Color.White else palette.muted, fontSize = 11.sp)
    }
}

@Composable
private fun SettingChip(
    label: String,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(if (selected) palette.accent else Color.Transparent, RoundedCornerShape(5.dp))
            .border(BorderStroke(1.dp, if (selected) palette.accent else palette.border), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(label, color = if (selected) Color.White else palette.muted, fontSize = 10.sp)
    }
}

@Composable
private fun MenuAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 7.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = if (enabled) palette.accent else palette.muted, modifier = Modifier.size(20.dp))
            Text(label, color = if (enabled) palette.ink else palette.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ReaderSettingsPanel(
    palette: ReaderPalette,
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        color = palette.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 10.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("阅读设置", color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭设置", tint = palette.muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("翻页方式", color = palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                PageModeChip("上下", PageMode.VERTICAL, settings.pageMode, palette) {
                    onSettingsChange(settings.copy(pageMode = PageMode.VERTICAL))
                }
                PageModeChip("左右", PageMode.HORIZONTAL, settings.pageMode, palette) {
                    onSettingsChange(settings.copy(pageMode = PageMode.HORIZONTAL))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("屏幕方向", color = palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                SettingChip("竖屏", settings.screenOrientation == ScreenOrientation.PORTRAIT, palette) {
                    onSettingsChange(settings.copy(screenOrientation = ScreenOrientation.PORTRAIT))
                }
                SettingChip("横屏", settings.screenOrientation == ScreenOrientation.LANDSCAPE, palette) {
                    onSettingsChange(settings.copy(screenOrientation = ScreenOrientation.LANDSCAPE))
                }
                SettingChip("跟随系统", settings.screenOrientation == ScreenOrientation.SYSTEM, palette) {
                    onSettingsChange(settings.copy(screenOrientation = ScreenOrientation.SYSTEM))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字号", color = palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(15))) }, modifier = Modifier.size(34.dp)) {
                    Text("A−", color = palette.accent, fontSize = 12.sp)
                }
                Text("${settings.fontSize}sp", color = palette.ink, fontSize = 12.sp, modifier = Modifier.width(42.dp))
                IconButton(onClick = { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(25))) }, modifier = Modifier.size(34.dp)) {
                    Text("A+", color = palette.accent, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("行距", color = palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                listOf(1.7f to "紧凑", 1.95f to "舒适", 2.25f to "宽松").forEach { (value, label) ->
                    SettingChip(label, settings.lineHeight == value, palette) {
                        onSettingsChange(settings.copy(lineHeight = value))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("左右点击", color = palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                SettingChip("左退右进", settings.horizontalTapMode == HorizontalTapMode.SIDE_PAGES, palette) {
                    onSettingsChange(settings.copy(horizontalTapMode = HorizontalTapMode.SIDE_PAGES))
                }
                SettingChip("两侧都下一页", settings.horizontalTapMode == HorizontalTapMode.BOTH_NEXT, palette) {
                    onSettingsChange(settings.copy(horizontalTapMode = HorizontalTapMode.BOTH_NEXT))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("底色", color = palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                ReaderTheme.values().forEach { theme ->
                    val selected = theme == settings.theme
                    Box(
                        modifier = Modifier
                            .size(29.dp)
                            .padding(3.dp)
                            .background(paletteFor(theme).background, RoundedCornerShape(50))
                            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) palette.accent else palette.border), RoundedCornerShape(50))
                            .clickable { onSettingsChange(settings.copy(theme = theme)) }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun CatalogDrawer(
    currentDocument: ReaderDocument?,
    catalogDocument: ReaderDocument?,
    loading: Boolean,
    palette: ReaderPalette,
    settings: ReaderSettings,
    catalogIndex: Int?,
    onNavigate: (String, Int) -> Unit,
    onCatalogNavigate: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentUrl = currentDocument?.sourceUrl.orEmpty()
    val currentTitle = currentDocument?.title.orEmpty()
    val matchedIndex = remember(catalogDocument?.sourceUrl, currentUrl, currentTitle) {
        catalogDocument?.catalogItems?.indexOfFirst { item ->
            sameUrl(item.href, currentUrl) || sameChapter(item.label, currentTitle)
        } ?: -1
    }
    val currentIndex = catalogIndex?.takeIf {
        catalogDocument != null && it in catalogDocument.catalogItems.indices
    } ?: matchedIndex
    val listState = rememberLazyListState()
    LaunchedEffect(catalogDocument?.sourceUrl, currentUrl, currentTitle, currentIndex) {
        if (currentIndex >= 0) {
            val targetIndex = currentIndex + 1
            listState.scrollToItem(targetIndex)
            val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            if (targetItem != null) {
                val viewportHeight = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset)
                    .coerceAtLeast(targetItem.size)
                val centerOffset = -((viewportHeight - targetItem.size) / 2)
                listState.scrollToItem(targetIndex, centerOffset)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.22f))
                .clickable(onClick = onClose)
        )
        Surface(
            modifier = modifier
                .fillMaxHeight()
                .fillMaxWidth(0.84f),
            color = palette.surface,
            shape = RoundedCornerShape(topEnd = 13.dp, bottomEnd = 13.dp),
            border = BorderStroke(1.dp, palette.border),
            shadowElevation = 12.dp
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 17.dp, top = 15.dp, end = 8.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("目录", color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        if (catalogDocument != null) {
                            Text(
                                if (currentIndex >= 0) "当前位置：第${currentIndex + 1}章" else "请选择章节",
                                color = palette.muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭目录", tint = palette.muted)
                    }
                }
                when {
                    catalogDocument != null -> LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 18.dp)
                    ) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 17.dp, vertical = 5.dp)) {
                                Text(catalogDocument.title, color = palette.accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${catalogDocument.catalogItems.size} 个章节", color = palette.muted, fontSize = 10.sp)
                            }
                        }
                        itemsIndexed(
                            catalogDocument.catalogItems,
                            key = { index, item -> "drawer_${index}_${item.href}" }
                        ) { index, item ->
                            val selected = index == currentIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (selected) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable { onNavigate(item.href, index) }
                                    .padding(horizontal = 17.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "第${index + 1}章",
                                    color = if (selected) palette.accent else palette.muted,
                                    fontSize = 10.sp,
                                    modifier = Modifier.width(54.dp)
                                )
                                Text(
                                    item.label,
                                    color = palette.ink,
                                    fontSize = settings.fontSize.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(palette.accent, RoundedCornerShape(50))
                                    )
                                }
                            }
                        }
                        if (catalogDocument.catalogPages.isNotEmpty()) {
                            item {
                                Column(modifier = Modifier.padding(horizontal = 17.dp, vertical = 16.dp)) {
                                    Text("目录分页", color = palette.muted, fontSize = 11.sp)
                                    catalogDocument.catalogPages.forEach { page ->
                                        TextButton(
                                            onClick = { onCatalogNavigate(page.href) },
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
                                            Text(page.label, color = palette.accent, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                            }
                        }
                    }
                    loading -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("目录正在预加载…", color = palette.muted, fontSize = 13.sp)
                    }
                    else -> Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("目录暂时无法读取", color = palette.muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun Modifier.centerTapDetector(onTap: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val start = down.position
        var moved = false
        var consumed = down.isConsumed
        var finished = false
        while (!finished) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            event.changes.forEach { change ->
                consumed = consumed || change.isConsumed
                if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
            }
            finished = event.changes.none { it.pressed }
        }
        val inCenter = start.x in (size.width * 0.2f)..(size.width * 0.8f) &&
            start.y in (size.height * 0.28f)..(size.height * 0.72f)
        if (!moved && !consumed && inCenter) onTap()
    }
}

private fun Modifier.horizontalPageGestureDetector(
    key: Any,
    currentPage: () -> Int,
    onTap: (tappedLeft: Boolean) -> Unit,
    onSwipe: (swipedRight: Boolean, pageAtDown: Int) -> Unit
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val pageAtDown = currentPage()
        val start = down.position
        var last = start
        var moved = false
        var consumed = down.isConsumed
        var finished = false
        while (!finished) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            event.changes.forEach { change ->
                consumed = consumed || change.isConsumed
                last = change.position
                if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
            }
            finished = event.changes.none { it.pressed }
        }
        val delta = last - start
        val horizontalSwipe = moved && kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y) &&
            kotlin.math.abs(delta.x) >= size.width * 0.16f
        if (horizontalSwipe) {
            onSwipe(delta.x > 0f, pageAtDown)
        } else if (!moved && !consumed) {
            when {
                start.x < size.width * 0.2f -> onTap(true)
                start.x > size.width * 0.8f -> onTap(false)
            }
        }
    }
}

@Composable
private fun CatalogView(
    document: ReaderDocument,
    palette: ReaderPalette,
    settings: ReaderSettings,
    onNavigate: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("jingdu", 0) }
    LaunchedEffect(document.sourceUrl) {
        val saved = preferences.getInt(progressKey(document.sourceUrl), 0)
        listState.scrollToItem(min(saved, max(0, document.catalogItems.size)))
    }
    LaunchedEffect(listState, document.sourceUrl) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collectLatest { index -> preferences.edit().putInt(progressKey(document.sourceUrl), index).apply() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Text("章节目录", color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(10.dp))
            Text(document.title, color = palette.ink, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("${document.catalogItems.size} 个章节", color = palette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(22.dp))
        }
        itemsIndexed(document.catalogItems, key = { index, item -> "${index}_${item.href}" }) { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(item.href) }
                    .border(BorderStroke(0.7.dp, palette.border))
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(String.format("%03d", index + 1), color = palette.muted, fontSize = 10.sp, modifier = Modifier.width(38.dp))
                Text(item.label, color = palette.ink, fontSize = settings.fontSize.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowForward, contentDescription = "打开章节", tint = palette.accent, modifier = Modifier.size(17.dp))
            }
        }
        if (document.catalogPages.isNotEmpty()) {
            item {
                Spacer(Modifier.height(25.dp))
                Text("目录分页", color = palette.muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(document.catalogPages, key = { it.href }) { page ->
                        OutlinedButton(
                            onClick = { onNavigate(page.href) },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, palette.border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.accent)
                        ) { Text(page.label, fontSize = 11.sp) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun ChapterView(
    document: ReaderDocument,
    palette: ReaderPalette,
    settings: ReaderSettings,
    readingOffset: Int?,
    chapterOpenPosition: ChapterOpenPosition?,
    verticalOpenIndex: Int?,
    verticalOpenOffset: Int?,
    previousChapter: ReaderDocument?,
    nextChapter: ReaderDocument?,
    nextChapterReady: Boolean,
    onPositionChange: (Int) -> Unit,
    onContinueToChapter: (String, Int, Int) -> Unit,
    onNavigateChapter: (String, ChapterOpenPosition) -> Unit,
    onAutoNext: () -> Unit
) {
    if (settings.pageMode == PageMode.HORIZONTAL) {
        HorizontalChapterView(
            document = document,
            palette = palette,
            settings = settings,
            readingOffset = readingOffset,
            chapterOpenPosition = chapterOpenPosition,
            nextChapter = nextChapter,
            nextChapterReady = nextChapterReady,
            onPositionChange = onPositionChange,
            onNavigateChapter = onNavigateChapter,
            onAutoNext = onAutoNext
        )
    } else {
        VerticalChapterView(
            document = document,
            palette = palette,
            settings = settings,
            readingOffset = readingOffset,
            chapterOpenPosition = chapterOpenPosition,
            verticalOpenIndex = verticalOpenIndex,
            verticalOpenOffset = verticalOpenOffset,
            previousChapter = previousChapter,
            nextChapter = nextChapter,
            onPositionChange = onPositionChange,
            onContinueToChapter = onContinueToChapter,
            onNavigateChapter = onNavigateChapter,
            onAutoNext = onAutoNext
        )
    }
}

@Composable
private fun VerticalChapterView(
    document: ReaderDocument,
    palette: ReaderPalette,
    settings: ReaderSettings,
    readingOffset: Int?,
    chapterOpenPosition: ChapterOpenPosition?,
    verticalOpenIndex: Int?,
    verticalOpenOffset: Int?,
    previousChapter: ReaderDocument?,
    nextChapter: ReaderDocument?,
    onPositionChange: (Int) -> Unit,
    onContinueToChapter: (String, Int, Int) -> Unit,
    onNavigateChapter: (String, ChapterOpenPosition) -> Unit,
    onAutoNext: () -> Unit
) {
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("jingdu", 0) }
    val currentParagraphOffsets = remember(document.sourceUrl) { paragraphStartOffsets(document) }
    var positionRestored by remember(document.sourceUrl) { mutableStateOf(false) }
    val previousItemCount = previousChapter?.let { it.paragraphs.size + 1 } ?: 0
    val currentStartIndex = previousItemCount
    val currentEndIndex = currentStartIndex + document.paragraphs.size
    val nextStartIndex = currentEndIndex + 1
    val totalItemCount = nextChapter?.let { nextStartIndex + it.paragraphs.size + 1 } ?: (currentEndIndex + 1)
    var knownPreviousItemCount by remember(document.sourceUrl) { mutableStateOf(previousItemCount) }
    var suppressBoundaryNavigation by remember(document.sourceUrl) { mutableStateOf(false) }

    LaunchedEffect(document.sourceUrl, previousChapter?.sourceUrl, previousItemCount) {
        val delta = previousItemCount - knownPreviousItemCount
        if (delta != 0) {
            suppressBoundaryNavigation = true
            try {
                val anchoredIndex = (listState.firstVisibleItemIndex + delta).coerceIn(0, max(0, totalItemCount - 1))
                listState.scrollToItem(anchoredIndex, listState.firstVisibleItemScrollOffset)
                delay(50)
            } finally {
                suppressBoundaryNavigation = false
            }
        }
        knownPreviousItemCount = previousItemCount
    }

    LaunchedEffect(document.sourceUrl, chapterOpenPosition, verticalOpenIndex, verticalOpenOffset) {
        positionRestored = false
        val saved = preferences.getInt(progressKey(document.sourceUrl), 0)
        val savedOffset = preferences.getInt(progressOffsetKey(document.sourceUrl), -1)
        val anchorOffset = readingOffset ?: savedOffset.takeIf { it >= 0 }
        val hasContinuationPosition = chapterOpenPosition == null && verticalOpenIndex != null
        val relativeIndex = if (hasContinuationPosition) {
            verticalOpenIndex.coerceIn(0, document.paragraphs.size)
        } else {
            when (chapterOpenPosition) {
                ChapterOpenPosition.START -> 0
                ChapterOpenPosition.END -> document.paragraphs.size
                null -> if (anchorOffset != null) {
                    (paragraphIndexForOffset(document, anchorOffset) + 1).coerceIn(0, document.paragraphs.size)
                } else {
                    saved.coerceIn(0, document.paragraphs.size)
                }
            }
        }
        val target = currentStartIndex + relativeIndex
        val offset = if (hasContinuationPosition) verticalOpenOffset?.coerceAtLeast(0) ?: 0 else 0
        suppressBoundaryNavigation = true
        try {
            listState.scrollToItem(target.coerceIn(0, max(0, totalItemCount - 1)), offset)
            delay(50)
        } finally {
            suppressBoundaryNavigation = false
            positionRestored = true
        }
    }
    LaunchedEffect(listState, document.sourceUrl, currentStartIndex) {
        snapshotFlow {
            Triple(positionRestored, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
            .distinctUntilChanged()
            .collectLatest { (restored, index, _) ->
                if (!restored) return@collectLatest
                val relativeIndex = (index - currentStartIndex).coerceIn(0, document.paragraphs.size)
                val paragraphIndex = (relativeIndex - 1).coerceIn(0, max(0, document.paragraphs.size - 1))
                val textOffset = currentParagraphOffsets.getOrElse(paragraphIndex) { 0 }
                preferences.edit()
                    .putInt(progressKey(document.sourceUrl), relativeIndex)
                    .putInt(progressOffsetKey(document.sourceUrl), textOffset)
                    .apply()
                onPositionChange(textOffset)
            }
    }
    LaunchedEffect(
        listState,
        document.sourceUrl,
        previousChapter?.sourceUrl,
        nextChapter?.sourceUrl,
        currentStartIndex,
        currentEndIndex,
        nextStartIndex
    ) {
        var wasScrolling = false
        var boundaryNavigationRequested: String? = null
        snapshotFlow {
            VerticalViewport(
                scrolling = listState.isScrollInProgress,
                firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1,
                firstOffset = listState.firstVisibleItemScrollOffset,
                lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                canScrollBackward = listState.canScrollBackward,
                canScrollForward = listState.canScrollForward
            )
        }
            .distinctUntilChanged()
            .collectLatest { viewport ->
                if (suppressBoundaryNavigation) {
                    wasScrolling = false
                    return@collectLatest
                }
                if (viewport.scrolling) {
                    wasScrolling = true
                    return@collectLatest
                }
                if (!wasScrolling) return@collectLatest
                wasScrolling = false

                val firstVisible = viewport.firstVisible
                val inPreviousChapter = previousChapter != null && firstVisible in 0 until currentStartIndex
                val inNextChapter = nextChapter != null && firstVisible >= nextStartIndex
                val atPreviousEdge = previousChapter == null && !viewport.canScrollBackward && document.navigation.previous != null
                val atNextEdge = nextChapter == null && !viewport.canScrollForward && document.navigation.next != null
                when {
                    inPreviousChapter && boundaryNavigationRequested != "previous" -> {
                        boundaryNavigationRequested = "previous"
                        document.navigation.previous?.let {
                            onContinueToChapter(
                                it.href,
                                firstVisible.coerceIn(0, previousChapter?.paragraphs?.size ?: 0),
                                viewport.firstOffset
                            )
                        }
                    }
                    inNextChapter && boundaryNavigationRequested != "next" -> {
                        boundaryNavigationRequested = "next"
                        document.navigation.next?.let {
                            onContinueToChapter(
                                it.href,
                                (firstVisible - nextStartIndex).coerceIn(0, nextChapter?.paragraphs?.size ?: 0),
                                viewport.firstOffset
                            )
                        }
                    }
                    atPreviousEdge && boundaryNavigationRequested != "previous" -> {
                        boundaryNavigationRequested = "previous"
                        document.navigation.previous?.let { onNavigateChapter(it.href, ChapterOpenPosition.END) }
                    }
                    atNextEdge && boundaryNavigationRequested != "next" -> {
                        boundaryNavigationRequested = "next"
                        onAutoNext()
                    }
                    !inPreviousChapter && !inNextChapter && !atPreviousEdge && !atNextEdge -> {
                        boundaryNavigationRequested = null
                    }
                }
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, top = 68.dp, end = 22.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        if (previousChapter != null) {
            item(key = "${previousChapter.sourceUrl}:header") { ChapterHeader(previousChapter, palette) }
            itemsIndexed(previousChapter.paragraphs, key = { index, _ -> "${previousChapter.sourceUrl}:paragraph:$index" }) { _, paragraph ->
                ChapterParagraph(paragraph, palette, settings)
            }
        }
        item(key = "${document.sourceUrl}:header") { ChapterHeader(document, palette) }
        itemsIndexed(document.paragraphs, key = { index, _ -> "${document.sourceUrl}:paragraph:$index" }) { _, paragraph ->
            ChapterParagraph(paragraph, palette, settings)
        }
        if (nextChapter != null) {
            item(key = "${nextChapter.sourceUrl}:header") { ChapterHeader(nextChapter, palette) }
            itemsIndexed(nextChapter.paragraphs, key = { index, _ -> "${nextChapter.sourceUrl}:paragraph:$index" }) { _, paragraph ->
                ChapterParagraph(paragraph, palette, settings)
            }
        }
    }
}

@Composable
private fun HorizontalChapterView(
    document: ReaderDocument,
    palette: ReaderPalette,
    settings: ReaderSettings,
    readingOffset: Int?,
    chapterOpenPosition: ChapterOpenPosition?,
    nextChapter: ReaderDocument?,
    nextChapterReady: Boolean,
    onPositionChange: (Int) -> Unit,
    onNavigateChapter: (String, ChapterOpenPosition) -> Unit,
    onAutoNext: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val contentWidthPx = with(density) { (maxWidth - HorizontalPageHorizontalPadding * 2).toPx().toInt() }
            .coerceAtLeast(1)
        val contentHeightPx = with(density) {
            (maxHeight - HorizontalPageTopPadding - HorizontalPageBottomPadding).toPx().toInt()
        }.coerceAtLeast(1)
        val pages = remember(
            document.sourceUrl,
            settings.fontSize,
            settings.lineHeight,
            contentWidthPx,
            contentHeightPx
        ) {
            paginateChapterPages(document, textMeasurer, density, contentWidthPx, contentHeightPx, settings)
        }
        val hasNextChapter = document.navigation.next != null
        val nextPages = remember(
            nextChapter?.sourceUrl,
            settings.fontSize,
            settings.lineHeight,
            contentWidthPx,
            contentHeightPx
        ) {
            nextChapter
                ?.takeIf { nextChapterReady && !it.isCatalog && it.paragraphs.isNotEmpty() }
                ?.let { paginateChapterPages(it, textMeasurer, density, contentWidthPx, contentHeightPx, settings) }
                .orEmpty()
        }
        val showNextContent = hasNextChapter && nextPages.isNotEmpty()
        val totalPages = pages.size + if (showNextContent) nextPages.size else 0
        val pagerState = key(document.sourceUrl) {
            rememberPagerState(pageCount = { totalPages })
        }
        val pagerScope = androidx.compose.runtime.rememberCoroutineScope()
        val context = androidx.compose.ui.platform.LocalContext.current
        val preferences = remember { context.getSharedPreferences("jingdu", 0) }
        val horizontalProgressKey = progressKey(document.sourceUrl) + "_horizontal"
        val offsetKey = progressOffsetKey(document.sourceUrl)
        var positionRestored by remember(document.sourceUrl) { mutableStateOf(false) }

        LaunchedEffect(document.sourceUrl, chapterOpenPosition, pages.size, settings.fontSize, settings.lineHeight, contentWidthPx, contentHeightPx) {
            positionRestored = false
            val savedPage = preferences.getInt(horizontalProgressKey, 0)
            val savedOffset = preferences.getInt(offsetKey, -1)
            val anchorOffset = readingOffset ?: savedOffset.takeIf { it >= 0 }
            val target = when (chapterOpenPosition) {
                ChapterOpenPosition.START -> 0
                ChapterOpenPosition.END -> pages.lastIndex
                null -> if (anchorOffset != null) {
                    horizontalPageForOffset(pages, anchorOffset)
                } else {
                    savedPage
                }
            }
            pagerState.scrollToPage(target.coerceIn(0, max(0, pages.lastIndex)))
            positionRestored = true
        }
        LaunchedEffect(pagerState, horizontalProgressKey, offsetKey, pages) {
            snapshotFlow { Triple(positionRestored, pagerState.currentPage, pages.size) }
                .distinctUntilChanged()
                .collectLatest { (restored, page, _) ->
                    if (!restored) return@collectLatest
                    val contentPage = page.coerceIn(0, pages.lastIndex)
                    val textOffset = pages[contentPage].startOffset
                    preferences.edit()
                        .putInt(horizontalProgressKey, page)
                        .putInt(offsetKey, textOffset)
                        .apply()
                    onPositionChange(textOffset)
                }
        }
        LaunchedEffect(document.sourceUrl, pagerState.currentPage, pages.size, nextPages.size, showNextContent) {
            if (showNextContent && pagerState.currentPage >= pages.size) onAutoNext()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalPageGestureDetector(
                        key = Triple(document.sourceUrl, totalPages, settings.horizontalTapMode),
                        currentPage = { pagerState.currentPage },
                        onTap = { tappedLeft ->
                            val current = pagerState.currentPage
                            val target = if (settings.horizontalTapMode == HorizontalTapMode.BOTH_NEXT || !tappedLeft) {
                                current + 1
                            } else {
                                current - 1
                            }
                            when {
                                target in 0 until totalPages -> {
                                    pagerScope.launch { pagerState.animateScrollToPage(target) }
                                }
                                tappedLeft && settings.horizontalTapMode == HorizontalTapMode.SIDE_PAGES && current == 0 -> {
                                    document.navigation.previous?.let { onNavigateChapter(it.href, ChapterOpenPosition.END) }
                                }
                                !tappedLeft && current == pages.lastIndex -> {
                                    document.navigation.next?.let { onNavigateChapter(it.href, ChapterOpenPosition.START) }
                                }
                            }
                        },
                        onSwipe = { swipedRight, pageAtDown ->
                            when {
                                swipedRight && pageAtDown == 0 -> {
                                    document.navigation.previous?.let { onNavigateChapter(it.href, ChapterOpenPosition.END) }
                                }
                                !swipedRight && pageAtDown == pages.lastIndex && !showNextContent -> {
                                    document.navigation.next?.let { onNavigateChapter(it.href, ChapterOpenPosition.START) }
                                }
                            }
                        }
                    )
            ) { page ->
                if (showNextContent && page >= pages.size) {
                    val nextPage = page - pages.size
                    HorizontalChapterPage(
                        document = nextChapter!!,
                        text = nextPages[nextPage].text,
                        page = nextPage,
                        palette = palette,
                        settings = settings
                    )
                } else {
                    HorizontalChapterPage(
                        document = document,
                        text = pages[page].text,
                        page = page,
                        palette = palette,
                        settings = settings
                    )
                }
            }
            if (pagerState.currentPage < pages.size) {
                Text(
                    "${pagerState.currentPage + 1} / ${pages.size}",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    color = palette.muted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

private val HorizontalPageHorizontalPadding = 22.dp
private val HorizontalPageTopPadding = 64.dp
private val HorizontalPageBottomPadding = 42.dp
private val HorizontalHeaderReservedHeight = 160.dp

@Composable
private fun HorizontalChapterPage(
    document: ReaderDocument,
    text: String,
    page: Int,
    palette: ReaderPalette,
    settings: ReaderSettings
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = HorizontalPageHorizontalPadding,
                top = HorizontalPageTopPadding,
                end = HorizontalPageHorizontalPadding,
                bottom = HorizontalPageBottomPadding
            )
    ) {
        if (page == 0) {
            Box(modifier = Modifier.height(HorizontalHeaderReservedHeight)) {
                ChapterHeader(document, palette)
            }
        }
        Text(
            text = text.ifEmpty { " " },
            modifier = Modifier.fillMaxWidth(),
            color = palette.ink,
            fontSize = settings.fontSize.sp,
            lineHeight = (settings.fontSize * settings.lineHeight).sp,
            fontFamily = FontFamily.Serif
        )
    }
}

private fun paginateChapterPages(
    document: ReaderDocument,
    textMeasurer: TextMeasurer,
    density: Density,
    contentWidthPx: Int,
    contentHeightPx: Int,
    settings: ReaderSettings
): List<ChapterPage> {
    val style = TextStyle(
        fontSize = settings.fontSize.sp,
        lineHeight = (settings.fontSize * settings.lineHeight).sp,
        fontFamily = FontFamily.Serif
    )
    val fullText = document.paragraphs.joinToString("\n\n").trim()
    val headerHeightPx = with(density) { HorizontalHeaderReservedHeight.toPx().toInt() }
    val firstHeight = (contentHeightPx - headerHeightPx).coerceAtLeast(1)
    val normalHeight = contentHeightPx.coerceAtLeast(1)
    val pages = mutableListOf<ChapterPage>()
    var remainder = fullText
    var firstPage = true
    var startOffset = 0

    while (remainder.isNotEmpty()) {
        val availableHeight = if (firstPage) firstHeight else normalHeight
        val fit = fitTextPrefix(remainder, textMeasurer, style, contentWidthPx, availableHeight)
        val (pageText, rest) = splitTextAtBoundary(remainder, fit)
        pages += ChapterPage(pageText, startOffset)
        startOffset += remainder.length - rest.length
        remainder = rest
        firstPage = false
    }
    if (pages.isEmpty()) pages += ChapterPage("", 0)

    return pages
}

private fun appendFullPages(
    pages: MutableList<String>,
    text: String,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    contentWidthPx: Int,
    contentHeightPx: Int
) {
    var remainder = text
    while (remainder.isNotEmpty()) {
        val fit = fitTextPrefix(remainder, textMeasurer, style, contentWidthPx, contentHeightPx)
        val (pageText, rest) = splitTextAtBoundary(remainder, fit)
        pages += pageText
        remainder = rest
    }
}

private fun fitTextPrefix(
    text: String,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int
): Int {
    if (text.isEmpty()) return 0
    val constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1), maxHeight = heightPx.coerceAtLeast(1))
    fun fits(length: Int): Boolean = !textMeasurer.measure(
        text = AnnotatedString(text.substring(0, length)),
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = true,
        constraints = constraints
    ).didOverflowHeight

    if (fits(text.length)) return text.length
    var low = 1
    var high = text.length
    var best = 1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (fits(middle)) {
            best = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return best.coerceAtMost(text.length)
}

private fun splitTextAtBoundary(text: String, requestedLength: Int): Pair<String, String> {
    if (text.isEmpty()) return "" to ""
    var cut = requestedLength.coerceIn(1, text.length)
    val searchStart = (cut - 180).coerceAtLeast(1)
    val newline = text.lastIndexOf('\n', cut - 1)
    if (newline >= searchStart) {
        cut = newline
    } else {
        val punctuation = listOf('。', '！', '？', '；', '，', '.', '!', '?', ';', ',')
            .mapNotNull { text.lastIndexOf(it, cut - 1).takeIf { index -> index >= searchStart } }
            .maxOrNull()
        if (punctuation != null) cut = punctuation + 1
    }
    if (cut <= 0) cut = requestedLength.coerceIn(1, text.length)
    return text.substring(0, cut).trimEnd() to text.substring(cut).trimStart()
}

@Composable
private fun AutoNextView(palette: ReaderPalette) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("正在打开下一章…", color = palette.muted, fontSize = 13.sp)
    }
}

@Composable
private fun ChapterHeader(document: ReaderDocument, palette: ReaderPalette) {
    Column {
        Text("静读 · 当前章节", color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            document.title,
            color = palette.ink,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 38.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Text("${document.paragraphs.size} 段 · ${document.wordCount} 字", color = palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(19.dp))
        Box(modifier = Modifier.width(44.dp).height(2.dp).background(palette.accent))
        Spacer(Modifier.height(13.dp))
    }
}

@Composable
private fun ChapterParagraph(paragraph: String, palette: ReaderPalette, settings: ReaderSettings) {
    Text(
        text = paragraph,
        modifier = Modifier.fillMaxWidth(),
        color = palette.ink,
        fontSize = settings.fontSize.sp,
        lineHeight = (settings.fontSize * settings.lineHeight).sp,
        fontFamily = FontFamily.Serif
    )
}

@Composable
private fun LoadingView(palette: ReaderPalette) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在整理网页", color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text("读取正文与目录链接…", color = palette.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ErrorView(message: String, palette: ReaderPalette, onReload: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = palette.ink, fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onReload, shape = RoundedCornerShape(7.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("重新读取")
            }
        }
    }
}

@Composable
private fun JingduTheme(theme: ReaderTheme, content: @Composable () -> Unit) {
    val palette = paletteFor(theme)
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            surface = palette.background,
            onSurface = palette.ink,
            outline = palette.border
        ),
        content = content
    )
}

private fun paletteFor(theme: ReaderTheme): ReaderPalette = when (theme) {
    ReaderTheme.IVORY -> IvoryPalette
    ReaderTheme.PAPER -> PaperPalette
    ReaderTheme.NIGHT -> NightPalette
}

private fun extractSharedUrl(intent: Intent?): String {
    val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    return Regex("https?://\\S+").find(sharedText)?.value?.trimEnd('.', ',', ')', ']', '，', '。') ?: ""
}

private fun normalizeUrl(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) return ""
    val candidate = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    return runCatching {
        val uri = android.net.Uri.parse(candidate)
        if (uri.scheme == "http" || uri.scheme == "https") uri.toString() else ""
    }.getOrDefault("")
}

private fun cacheKey(raw: String): String = normalizeUrl(raw).substringBefore('#').trimEnd('/')

private fun sameUrl(first: String, second: String): Boolean {
    val left = cacheKey(first)
    val right = cacheKey(second)
    return left.isNotEmpty() && left == right
}

private fun sameChapter(first: String, second: String): Boolean {
    val firstNumber = chapterNumber(first)
    val secondNumber = chapterNumber(second)
    if (firstNumber != null && firstNumber == secondNumber) return true
    val firstTitle = chapterTitleKey(first)
    val secondTitle = chapterTitleKey(second)
    return firstTitle.length >= 6 && secondTitle.length >= 6 &&
        (firstTitle == secondTitle || firstTitle.startsWith(secondTitle) || secondTitle.startsWith(firstTitle))
}

private fun chapterNumber(value: String): String? {
    val match = Regex("(?:第\\s*([0-9一二三四五六七八九十百千万]+)\\s*[章回节卷集篇]|chapter\\s*([0-9]+))", RegexOption.IGNORE_CASE)
        .find(value)
    return match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
}

private fun chapterTitleKey(value: String): String = value
    .substringBefore('_')
    .substringBefore(" - ")
    .substringBefore("｜")
    .substringBefore('|')
    .lowercase()
    .replace(Regex("\\s+"), "")

private fun shelfKeyForDocument(document: ReaderDocument): String {
    val catalogUrl = document.navigation.catalog?.href?.let(::normalizeUrl).orEmpty()
    if (catalogUrl.isNotEmpty()) return "catalog:${cacheKey(catalogUrl)}"
    val source = normalizeUrl(document.sourceUrl)
    val uri = runCatching { android.net.Uri.parse(source) }.getOrNull()
    val host = uri?.host.orEmpty()
    val parentPath = uri?.path.orEmpty().substringBeforeLast('/', "").trimEnd('/')
    return if (host.isNotEmpty()) {
        "site:${uri?.scheme ?: "https"}://$host$parentPath"
    } else {
        "url:${cacheKey(document.sourceUrl)}"
    }
}

private fun bookTitleForDocument(document: ReaderDocument, catalog: ReaderDocument? = null): String {
    val catalogTitle = catalog?.title?.trim().orEmpty()
    if (catalogTitle.isNotEmpty() && !catalogTitle.equals("章节目录", ignoreCase = true)) return catalogTitle
    val title = document.title.trim().ifEmpty { "未命名书籍" }
    val suffix = title.substringAfterLast('_', "").substringBefore(" - ").substringBefore('-').trim()
    if (suffix.length >= 2) return suffix
    return title.replace(
        Regex("^第\\s*[0-9一二三四五六七八九十百千万]+\\s*[章回节卷集篇]\\s*"), ""
    ).trim().ifEmpty { title }
}

private fun progressKey(url: String): String = "progress_" + url.hashCode().toUInt().toString(16)

private fun progressOffsetKey(url: String): String = "progress_offset_" + url.hashCode().toUInt().toString(16)

private fun paragraphStartOffsets(document: ReaderDocument): List<Int> {
    var offset = 0
    return buildList(document.paragraphs.size) {
        document.paragraphs.forEach { paragraph ->
            add(offset)
            offset += paragraph.length + 2
        }
    }
}

private fun paragraphIndexForOffset(document: ReaderDocument, offset: Int): Int {
    if (document.paragraphs.isEmpty()) return 0
    val target = offset.coerceAtLeast(0)
    return paragraphStartOffsets(document)
        .indexOfLast { it <= target }
        .coerceIn(0, document.paragraphs.lastIndex)
}

private fun horizontalPageForOffset(pages: List<ChapterPage>, offset: Int): Int {
    if (pages.isEmpty()) return 0
    return pages.indexOfLast { it.startOffset <= offset.coerceAtLeast(0) }
        .coerceIn(0, pages.lastIndex)
}

private fun loadSettings(preferences: android.content.SharedPreferences): ReaderSettings {
    val theme = runCatching { ReaderTheme.valueOf(preferences.getString("theme", ReaderTheme.IVORY.name) ?: ReaderTheme.IVORY.name) }.getOrDefault(ReaderTheme.IVORY)
    val pageMode = runCatching { PageMode.valueOf(preferences.getString("page_mode", PageMode.VERTICAL.name) ?: PageMode.VERTICAL.name) }.getOrDefault(PageMode.VERTICAL)
    val horizontalTapMode = runCatching {
        HorizontalTapMode.valueOf(preferences.getString("horizontal_tap_mode", HorizontalTapMode.SIDE_PAGES.name) ?: HorizontalTapMode.SIDE_PAGES.name)
    }.getOrDefault(HorizontalTapMode.SIDE_PAGES)
    val screenOrientation = loadScreenOrientation(preferences)
    return ReaderSettings(
        theme = theme,
        fontSize = preferences.getInt("font_size", 19).coerceIn(15, 25),
        lineHeight = preferences.getFloat("line_height", 1.95f).coerceIn(1.65f, 2.35f),
        pageMode = pageMode,
        horizontalTapMode = horizontalTapMode,
        screenOrientation = screenOrientation
    )
}

private fun saveSettings(preferences: android.content.SharedPreferences, settings: ReaderSettings) {
    preferences.edit()
        .putString("theme", settings.theme.name)
        .putInt("font_size", settings.fontSize.coerceIn(15, 25))
        .putFloat("line_height", settings.lineHeight.coerceIn(1.65f, 2.35f))
        .putString("page_mode", settings.pageMode.name)
        .putString("horizontal_tap_mode", settings.horizontalTapMode.name)
        .putString("screen_orientation", settings.screenOrientation.name)
        .apply()
}
