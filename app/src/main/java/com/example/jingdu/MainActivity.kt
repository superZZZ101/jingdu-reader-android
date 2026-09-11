package com.example.jingdu

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class AppScreen { HOME, READER, BOOKSHELF }
private enum class ReaderTheme { IVORY, PAPER, NIGHT }
private enum class PageMode { VERTICAL, HORIZONTAL }
private enum class HorizontalTapMode { SIDE_PAGES, BOTH_NEXT }
private enum class ScreenOrientation { PORTRAIT, LANDSCAPE, SYSTEM }
private enum class ReaderPanel { NONE, SETTINGS, CATALOG }
private enum class ChapterOpenPosition { START, END }

private const val ReaderMenuTapStartFraction = 0.40f
private const val ReaderMenuTapEndFraction = 0.60f

private const val CATALOG_STATE_ROOT_KEY = "catalog_state_root"
private const val CATALOG_STATE_LOADED_KEY = "catalog_state_loaded"
private const val CATALOG_STATE_COUNT_KEY = "catalog_state_count"
private const val CATALOG_STATE_COMPLETE_KEY = "catalog_state_complete"
private const val CATALOG_STATE_LOAD_URL_KEY = "catalog_state_load_url"
private const val CATALOG_STATE_ERROR_KEY = "catalog_state_error"
private const val DIAGNOSTIC_LOG_KEY = "diagnostic_log"
private const val MAX_DIAGNOSTIC_LOGS = 150

// How many chapters past the current one stay warm: the reader can be reading one chapter while
// the next two are already cached, which keeps the boundary transition instant.
private const val MaxPrefetchChapterDepth = 2

private fun ReaderDocument.isUsableForReading(): Boolean =
    (isCatalog && catalogItems.isNotEmpty()) || (!isCatalog && paragraphs.isNotEmpty())

private data class ReaderNavigationLinks(
    val previous: ReaderLink? = null,
    val next: ReaderLink? = null,
    val catalog: ReaderLink? = null
)

// Catalog entries are stored as normalized URLs (".../35350916") while the live chapter URL
// carries a file extension and a text-continuation suffix (".../35350916_2.html"); match them all.
private fun catalogChapterKeys(raw: String): Set<String> {
    val value = raw.trim()
    if (value.isEmpty()) return emptySet()
    val keys = linkedSetOf<String>()
    fun add(candidate: String) {
        val key = cacheKey(candidate)
        if (key.isNotEmpty()) keys += key
    }
    add(value)
    val withoutExtension = value.replace(Regex("\\.html?$", RegexOption.IGNORE_CASE), "")
    add(withoutExtension)
    add(withoutExtension.replace(Regex("_\\d+$"), ""))
    return keys
}

private fun isCatalogPageEntry(link: ReaderLink, catalog: ReaderDocument?): Boolean {
    if (link.href.isBlank()) return false
    val keys = catalogChapterKeys(link.href)
    return catalog?.catalogPages.orEmpty().any { page -> catalogChapterKeys(page.href).any { it in keys } }
}

// Some sites hide the next chapter behind the book catalog (the chapter page itself only links
// to its own continuation pages), so neighbour links resolve from the loaded catalog first.
private fun adjacentChapterFromCatalog(
    document: ReaderDocument,
    catalog: ReaderDocument?,
    step: Int
): ReaderLink? {
    val items = catalog?.catalogItems.orEmpty()
    if (items.isEmpty() || document.isCatalog) return null
    val keys = catalogChapterKeys(document.sourceUrl).toMutableSet()
    document.navigation.catalog?.href?.let { keys += catalogChapterKeys(it) }
    val index = items.indexOfFirst { item -> catalogChapterKeys(item.href).any { it in keys } }
    if (index < 0) return null
    val candidate = items.getOrNull(index + step) ?: return null
    // The book page itself is often listed as the first directory entry; it is not a chapter.
    if (isCatalogPageEntry(candidate, catalog)) return null
    return candidate
}

private fun resolveReaderNavigationLinks(
    document: ReaderDocument,
    catalog: ReaderDocument?,
    nextChapter: ReaderDocument? = null
): ReaderNavigationLinks {
    if (document.isCatalog) {
        return ReaderNavigationLinks(previous = null, next = null, catalog = document.navigation.catalog)
    }
    val catalogNext = adjacentChapterFromCatalog(document, catalog, 1)
        ?.takeUnless { sameUrl(it.href, document.sourceUrl) }
    val continuation = document.navigation.nextPage?.href?.let(::normalizeUrl).orEmpty()
    val navigationNext = document.navigation.next?.takeUnless { sameUrl(it.href, document.sourceUrl) }
    val displayNext = nextChapter
        ?.takeUnless { sameUrl(it.sourceUrl, document.sourceUrl) }
        ?.let { ReaderLink(it.title, it.sourceUrl) }
    val next = catalogNext
        ?: displayNext
        ?: navigationNext?.takeIf { continuation.isEmpty() || !sameUrl(it.href, continuation) }
        ?: catalogNext
    val previous = adjacentChapterFromCatalog(document, catalog, -1)
        ?.takeUnless { sameUrl(it.href, document.sourceUrl) }
        ?: document.navigation.previous
        ?.takeIf { !isCatalogPageEntry(it, catalog) }
    return ReaderNavigationLinks(
        previous = previous,
        next = next,
        catalog = document.navigation.catalog
    )
}

private fun mergePagedDocuments(base: ReaderDocument, continuation: ReaderDocument): ReaderDocument {
    val baseNavigation = base.navigation
    val continuationNavigation = continuation.navigation
    val title = cleanChapterTitle(base.title)
    return base.copy(
        title = title,
        paragraphs = normalizeReaderParagraphs(base.paragraphs + continuation.paragraphs),
        navigation = ReaderNavigation(
            previous = baseNavigation.previous ?: continuationNavigation.previous,
            next = continuationNavigation.next ?: baseNavigation.next,
            catalog = baseNavigation.catalog ?: continuationNavigation.catalog,
            previousPage = null,
            nextPage = continuationNavigation.nextPage
        )
    )
}

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

private val PendingChapterNavigationSaver = Saver<PendingChapterNavigation?, Any>(
    save = { pending ->
        pending?.let {
            listOf(
                it.url,
                it.position?.name,
                it.catalogUrl,
                it.catalogIndex,
                it.verticalIndex,
                it.verticalOffset
            )
        }
    },
    restore = { saved ->
        val values = saved as? List<*> ?: return@Saver null
        val url = values.getOrNull(0) as? String ?: return@Saver null
        if (url.isBlank()) return@Saver null
        val position = (values.getOrNull(1) as? String)?.let { value ->
            runCatching { ChapterOpenPosition.valueOf(value) }.getOrNull()
        }
        PendingChapterNavigation(
            url = url,
            position = position,
            catalogUrl = values.getOrNull(2) as? String,
            catalogIndex = values.getOrNull(3) as? Int,
            verticalIndex = values.getOrNull(4) as? Int,
            verticalOffset = values.getOrNull(5) as? Int
        )
    }
)

private data class ChapterPage(
    val text: String,
    val startOffset: Int
)

private data class HorizontalHeaderTextStyles(
    val label: TextStyle,
    val title: TextStyle,
    val metadata: TextStyle
)

private data class ReadingPositionSnapshot(
    val sourceUrl: String,
    val chapterTitle: String,
    val textOffset: Int,
    val progressIndex: Int,
    val progressIndexKey: String,
    val viewportOffset: Int?
)

private class ReadingPositionHolder {
    var value: ReadingPositionSnapshot? = null
}

private data class VerticalViewport(
    val scrolling: Boolean,
    val firstVisible: Int,
    val firstOffset: Int,
    val lastVisible: Int,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean
)

private fun loadDiagnosticLogs(preferences: android.content.SharedPreferences): List<String> =
    preferences.getString(DIAGNOSTIC_LOG_KEY, "")
        .orEmpty()
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
        .takeLast(MAX_DIAGNOSTIC_LOGS)

private fun diagnosticValue(value: String): String = value
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifEmpty { "-" }
    .take(320)

private fun diagnosticTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

private fun buildDiagnosticReport(
    errorMessage: String?,
    logs: List<String>,
    currentUrl: String,
    activeLoadUrl: String,
    prefetchLoadUrl: String,
    previousLoadUrl: String,
    catalogLoadUrl: String,
    pendingUrl: String?,
    pageMode: PageMode
): String = buildString {
    appendLine("静读诊断日志")
    appendLine("generatedAt=${diagnosticTimestamp()}")
    appendLine("appVersion=${BuildConfig.VERSION_NAME}")
    appendLine("androidApi=${Build.VERSION.SDK_INT}")
    appendLine("device=${diagnosticValue("${Build.MANUFACTURER} ${Build.MODEL}")}")
    appendLine("pageMode=${pageMode.name}")
    appendLine("error=${diagnosticValue(errorMessage.orEmpty())}")
    appendLine("currentUrl=${diagnosticValue(currentUrl)}")
    appendLine("activeLoadUrl=${diagnosticValue(activeLoadUrl)}")
    appendLine("prefetchLoadUrl=${diagnosticValue(prefetchLoadUrl)}")
    appendLine("previousLoadUrl=${diagnosticValue(previousLoadUrl)}")
    appendLine("catalogLoadUrl=${diagnosticValue(catalogLoadUrl)}")
    appendLine("pendingUrl=${diagnosticValue(pendingUrl.orEmpty())}")
    appendLine("events:")
    if (logs.isEmpty()) {
        appendLine("(none)")
    } else {
        logs.forEach(::appendLine)
    }
}

private fun copyDiagnosticReport(context: android.content.Context, report: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
        ClipData.newPlainText("静读错误日志", report)
    )
    Toast.makeText(context, "错误日志已复制", Toast.LENGTH_SHORT).show()
}

private fun shareDiagnosticReport(context: android.content.Context, report: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "静读错误日志")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    runCatching {
        context.startActivity(Intent.createChooser(sendIntent, "发送错误日志"))
    }.onFailure {
        Toast.makeText(context, "没有可用的分享应用，已尝试复制日志", Toast.LENGTH_SHORT).show()
        copyDiagnosticReport(context, report)
    }
}

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
    val activity = context as? ComponentActivity
    val preferences = remember { context.getSharedPreferences("jingdu", 0) }
    val restoredDocument = remember { loadCachedReaderDocument(preferences) }
    val restoredCatalogDocument = remember { loadCachedCatalogDocument(preferences) }
    var screen by rememberSaveable {
        mutableStateOf(
            if (initialUrl.isBlank() && restoredDocument != null) AppScreen.READER.name else AppScreen.HOME.name
        )
    }
    var address by rememberSaveable {
        mutableStateOf(initialUrl.ifBlank { restoredDocument?.sourceUrl.orEmpty() })
    }
    var currentUrl by rememberSaveable {
        mutableStateOf(restoredDocument?.sourceUrl.orEmpty())
    }
    val latestReadingPosition = remember { ReadingPositionHolder() }

    fun saveLatestReadingPosition(commit: Boolean = false) {
        latestReadingPosition.value?.let { position ->
            val editor = preferences.edit()
                .putInt(position.progressIndexKey, position.progressIndex)
                .putInt(progressOffsetKey(position.sourceUrl), position.textOffset)
            if (position.viewportOffset != null) {
                editor.putInt(progressViewportKey(position.sourceUrl), position.viewportOffset)
            } else {
                editor.remove(progressViewportKey(position.sourceUrl))
            }
            if (commit) editor.commit() else editor.apply()
        }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) address = initialUrl
    }
    var document by remember { mutableStateOf(restoredDocument) }
    var visibleDocumentForPersistence by remember { mutableStateOf(restoredDocument) }
    DisposableEffect(activity, document, visibleDocumentForPersistence) {
        val lifecycle = activity?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                (visibleDocumentForPersistence ?: document)?.let {
                    saveCachedReaderDocument(preferences, it, commit = true)
                }
                saveLatestReadingPosition(commit = true)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var previousDocument by remember { mutableStateOf<ReaderDocument?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticLogs by remember { mutableStateOf(loadDiagnosticLogs(preferences)) }
    fun recordDiagnostic(stage: String, url: String = "", details: String = "") {
        val line = buildString {
            append(diagnosticTimestamp())
            append(" [")
            append(diagnosticValue(stage))
            append("] url=")
            append(diagnosticValue(url))
            if (details.isNotBlank()) {
                append(" ")
                append(diagnosticValue(details))
            }
        }
        diagnosticLogs = (diagnosticLogs + line).takeLast(MAX_DIAGNOSTIC_LOGS)
        preferences.edit()
            .putString(DIAGNOSTIC_LOG_KEY, diagnosticLogs.joinToString("\n"))
            .apply()
        android.util.Log.e("Jingdu", line)
    }
    var settings by remember { mutableStateOf(loadSettings(preferences)) }
    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    val updateScope = androidx.compose.runtime.rememberCoroutineScope()

    fun requestUpdateCheck() {
        if (updateChecking) return
        updateChecking = true
        updateStatusMessage = null
        availableUpdate = null
        updateScope.launch {
            val result = checkForAppUpdate(BuildConfig.VERSION_NAME)
            availableUpdate = result.update
            updateStatusMessage = when {
                result.update != null -> null
                result.succeeded -> "当前已是最新版本"
                else -> "检查更新失败，请稍后重试"
            }
            updateChecking = false
        }
    }

    LaunchedEffect(Unit) {
        updateChecking = true
        val result = checkForAppUpdate(BuildConfig.VERSION_NAME)
        availableUpdate = result.update
        updateChecking = false
    }
    LaunchedEffect(settings.screenOrientation) {
        activity?.requestedOrientation = settings.screenOrientation.toRequestedOrientation()
    }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var prefetchWebView by remember { mutableStateOf<WebView?>(null) }
    var previousWebView by remember { mutableStateOf<WebView?>(null) }
    var catalogWebView by remember { mutableStateOf<WebView?>(null) }
    var cloudflareChallengeWebView by remember { mutableStateOf<WebView?>(null) }
    var cloudflareChallengeUrl by remember { mutableStateOf("") }
    var activeWebViewGeneration by remember { mutableStateOf(0) }
    var prefetchWebViewGeneration by remember { mutableStateOf(0) }
    var previousWebViewGeneration by remember { mutableStateOf(0) }
    var catalogWebViewGeneration by remember { mutableStateOf(0) }
    var activeLoadUrl by rememberSaveable { mutableStateOf("") }
    var activeRequestId by remember { mutableStateOf(0L) }
    var prefetchLoadUrl by remember { mutableStateOf("") }
    var prefetchRequestId by remember { mutableStateOf(0L) }
    var previousLoadUrl by remember { mutableStateOf("") }
    var previousRequestId by remember { mutableStateOf(0L) }
    val restoredCatalogRoot = restoredCatalogDocument?.sourceUrl
        ?: restoredDocument?.takeIf { it.isCatalog }?.sourceUrl.orEmpty()
    val restoredCatalogLoadedUrls = remember {
        preferences.getStringSet(CATALOG_STATE_LOADED_KEY, emptySet()).orEmpty()
            .map { cacheKey(it) }
            .filter { it.isNotEmpty() }
            .toSet()
    }
    var catalogLoadUrl by remember {
        mutableStateOf(preferences.getString(CATALOG_STATE_LOAD_URL_KEY, "").orEmpty())
    }
    var catalogAggregateUrl by remember {
        mutableStateOf(
            preferences.getString(CATALOG_STATE_ROOT_KEY, "").orEmpty()
                .ifBlank { restoredCatalogRoot }
        )
    }
    var catalogLoadedUrls by remember { mutableStateOf(restoredCatalogLoadedUrls) }
    var catalogLoadedPageCount by remember {
        mutableStateOf(
            maxOf(
                preferences.getInt(CATALOG_STATE_COUNT_KEY, 0),
                restoredCatalogLoadedUrls.size
            )
        )
    }
    var catalogComplete by remember {
        mutableStateOf(preferences.getBoolean(CATALOG_STATE_COMPLETE_KEY, false))
    }
    var catalogErrorMessage by remember {
        mutableStateOf(preferences.getString(CATALOG_STATE_ERROR_KEY, null))
    }
    var activeCatalogUrl by remember {
        mutableStateOf(
            restoredCatalogDocument?.sourceUrl
                ?: restoredDocument?.let {
                    if (it.isCatalog) it.sourceUrl else it.navigation.catalog?.href.orEmpty()
                }
                .orEmpty()
                .let(::normalizeUrl)
        )
    }
    var activeCatalogIndex by remember { mutableStateOf<Int?>(null) }
    var catalogNavigationRevision by remember { mutableStateOf(0) }
    var persistedCatalogDocument by remember { mutableStateOf(restoredCatalogDocument) }
    var chapterOpenPosition by remember { mutableStateOf<ChapterOpenPosition?>(null) }
    var verticalOpenIndex by remember { mutableStateOf<Int?>(null) }
    var verticalOpenOffset by remember { mutableStateOf<Int?>(null) }
    var readingOffset by remember { mutableStateOf<Int?>(null) }
    var pendingChapterNavigation by rememberSaveable(
        stateSaver = PendingChapterNavigationSaver
    ) { mutableStateOf<PendingChapterNavigation?>(null) }
    var pendingAutoNext by remember { mutableStateOf(false) }
    var prefetchPageBaseUrl by remember { mutableStateOf("") }
    var prefetchChapterDepth by remember { mutableStateOf(0) }
    var prefetchCompletedKeys by remember { mutableStateOf(emptySet<String>()) }
    var previousPageBaseUrl by remember { mutableStateOf("") }
    var chapterNavigationTarget by remember { mutableStateOf("") }
    var activeRetryUrl by remember { mutableStateOf("") }
    var activeRetryCount by remember { mutableStateOf(0) }
    var activeRetryScheduled by remember { mutableStateOf(false) }
    var prefetchRetryUrl by remember { mutableStateOf("") }
    var prefetchRetryCount by remember { mutableStateOf(0) }
    var prefetchRetryScheduled by remember { mutableStateOf(false) }
    var cachedDocuments by remember {
        mutableStateOf(
            listOfNotNull(restoredDocument, restoredCatalogDocument)
                .associateBy { cacheKey(it.sourceUrl) }
        )
    }
    var shelfBooks by remember { mutableStateOf(loadShelfBooks(preferences)) }

    SideEffect {
        val window = activity?.window
        if (window != null) {
            val palette = if (screen == AppScreen.READER.name) paletteFor(settings.theme) else IvoryPalette
            val horizontalReader = screen == AppScreen.READER.name && settings.pageMode == PageMode.HORIZONTAL
            window.statusBarColor = palette.background.toArgb()
            window.navigationBarColor = if (horizontalReader) AndroidColor.TRANSPARENT else palette.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = !horizontalReader
            }
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = screen != AppScreen.READER.name || settings.theme != ReaderTheme.NIGHT
                isAppearanceLightNavigationBars = screen != AppScreen.READER.name || settings.theme != ReaderTheme.NIGHT
            }
        }
    }

    fun cacheDocument(result: ReaderDocument, requestedUrl: String = "") {
        val aliases = listOf(cacheKey(result.sourceUrl), cacheKey(requestedUrl)).filter { it.isNotEmpty() }.distinct()
        if (aliases.isEmpty()) return
        val updated = cachedDocuments.toMutableMap()
        aliases.forEach { updated[it] = result }
        cachedDocuments = updated
    }

    fun clearCloudflareChallenge(view: WebView? = null) {
        if (view == null || cloudflareChallengeWebView === view) {
            cloudflareChallengeWebView?.settings?.loadsImagesAutomatically = false
            cloudflareChallengeWebView = null
            cloudflareChallengeUrl = ""
        }
    }

    fun claimCloudflareChallenge(view: WebView, url: String, phase: String, token: Long, preferActive: Boolean = false) {
        val heldByOtherView = cloudflareChallengeWebView != null && cloudflareChallengeWebView !== view
        if (heldByOtherView && !preferActive) {
            recordDiagnostic("cloudflare_challenge_deferred", url, "phase=$phase token=$token")
            return
        }
        cloudflareChallengeWebView?.takeUnless { it === view }?.settings?.loadsImagesAutomatically = false
        cloudflareChallengeWebView = view
        cloudflareChallengeUrl = url
        loading = false
        errorMessage = null
        recordDiagnostic("cloudflare_challenge", url, "phase=$phase token=$token")
    }

    fun restartActiveWebView() {
        clearCloudflareChallenge(activeWebView)
        activeWebView?.settings?.loadsImagesAutomatically = false
        activeWebView?.stopLoading()
        activeWebView = null
        activeWebViewGeneration += 1
        activeRequestId += 1
    }

    fun restartPrefetchWebView() {
        clearCloudflareChallenge(prefetchWebView)
        prefetchWebView?.settings?.loadsImagesAutomatically = false
        prefetchWebView?.stopLoading()
        prefetchWebView = null
        prefetchWebViewGeneration += 1
        prefetchRequestId += 1
    }

    fun restartPreviousWebView() {
        clearCloudflareChallenge(previousWebView)
        previousWebView?.settings?.loadsImagesAutomatically = false
        previousWebView?.stopLoading()
        previousWebView = null
        previousWebViewGeneration += 1
        previousRequestId += 1
    }

    fun restartCatalogWebView() {
        clearCloudflareChallenge(catalogWebView)
        catalogWebView?.settings?.loadsImagesAutomatically = false
        catalogWebView?.stopLoading()
        catalogWebView = null
        catalogWebViewGeneration += 1
    }

    fun cancelReaderChapterLoads() {
        pendingChapterNavigation = null
        pendingAutoNext = false
        chapterNavigationTarget = ""
        activeLoadUrl = ""
        activeRetryUrl = ""
        activeRetryCount = 0
        activeRetryScheduled = false
        prefetchLoadUrl = ""
        prefetchPageBaseUrl = ""
        prefetchChapterDepth = 0
        prefetchRetryUrl = ""
        prefetchRetryCount = 0
        prefetchRetryScheduled = false
        previousLoadUrl = ""
        previousPageBaseUrl = ""
        loading = false
        errorMessage = null
        clearCloudflareChallenge()
        restartActiveWebView()
        restartPrefetchWebView()
        restartPreviousWebView()
        restartCatalogWebView()
    }

    fun findCached(url: String): ReaderDocument? {
        val key = cacheKey(url)
        if (key.isEmpty()) return null
        return cachedDocuments[key] ?: cachedDocuments.values.firstOrNull { cacheKey(it.sourceUrl) == key }
    }

    fun uncachedPageContinuationUrl(start: ReaderDocument): String? {
        var current = start
        val visited = mutableSetOf<String>()
        while (true) {
            val currentKey = cacheKey(current.sourceUrl)
            if (currentKey.isEmpty() || !visited.add(currentKey)) return null
            val nextUrl = current.navigation.nextPage?.href?.let(::normalizeUrl).orEmpty()
            if (nextUrl.isEmpty()) return null
            val nextKey = cacheKey(nextUrl)
            if (nextKey.isEmpty() || nextKey in visited) return null
            val continuation = findCached(nextUrl)
            if (continuation == null || continuation.isCatalog || continuation.paragraphs.isEmpty()) return nextUrl
            current = continuation
        }
    }

    fun hasUncachedPageContinuation(start: ReaderDocument): Boolean =
        uncachedPageContinuationUrl(start) != null

    fun setPrefetchTarget(baseUrl: String, targetUrl: String) {
        if (prefetchPageBaseUrl != baseUrl || prefetchLoadUrl != targetUrl) {
            if (prefetchPageBaseUrl.isNotEmpty() || prefetchLoadUrl.isNotEmpty() || prefetchWebView != null) {
                restartPrefetchWebView()
            }
            prefetchRetryUrl = ""
            prefetchRetryCount = 0
            prefetchRetryScheduled = false
        }
        prefetchPageBaseUrl = baseUrl
        prefetchLoadUrl = targetUrl
    }

    fun invalidateCatalogCache(rawRootUrl: String) {
        val rootUrl = normalizeUrl(rawRootUrl)
        val rootKey = cacheKey(rootUrl)
        if (rootKey.isEmpty()) return
        val related = linkedSetOf(rootKey)
        var changed = true
        while (changed) {
            changed = false
            cachedDocuments.values
                .filter { it.isCatalog }
                .forEach { cached ->
                    val belongsToRoot = cacheKey(cached.sourceUrl) in related ||
                        sameUrl(cached.navigation.catalog?.href.orEmpty(), rootUrl) ||
                        cached.catalogPages.any { cacheKey(it.href) in related }
                    if (!belongsToRoot) return@forEach
                    if (cacheKey(cached.sourceUrl).isNotEmpty() && related.add(cacheKey(cached.sourceUrl))) {
                        changed = true
                    }
                    cached.catalogPages.forEach { page ->
                        if (related.add(cacheKey(page.href))) changed = true
                    }
                }
        }
        cachedDocuments = cachedDocuments.filterValues {
            !it.isCatalog || cacheKey(it.sourceUrl) !in related
        }
        clearCachedCatalogDocument(preferences, rootUrl)
    }

    fun mergeCachedContinuation(base: ReaderDocument): ReaderDocument {
        var merged = base
        val seen = mutableSetOf(cacheKey(base.sourceUrl))
        while (true) {
            val continuationUrl = merged.navigation.nextPage?.href?.let(::normalizeUrl).orEmpty()
            val continuationKey = cacheKey(continuationUrl)
            if (continuationKey.isEmpty() || !seen.add(continuationKey)) break
            val continuation = findCached(continuationUrl) ?: break
            if (continuation.isCatalog || continuation.paragraphs.isEmpty()) break
            merged = mergePagedDocuments(merged, continuation)
        }
        return merged
    }

    fun pruneCache(current: ReaderDocument?, previous: ReaderDocument?) {
        val keep = mutableSetOf<String>()
        listOfNotNull(current, previous).forEach { keep += cacheKey(it.sourceUrl) }
        current?.navigation?.previous?.href?.let { keep += cacheKey(it) }
        current?.navigation?.next?.href?.let { keep += cacheKey(it) }
        current?.navigation?.previousPage?.href?.let { keep += cacheKey(it) }
        current?.navigation?.nextPage?.href?.let { keep += cacheKey(it) }
        listOf(prefetchPageBaseUrl, prefetchLoadUrl).forEach { url ->
            cacheKey(url).takeIf { it.isNotEmpty() }?.let { keep += it }
        }
        fun keepPageChain(startUrl: String) {
            var cached = findCached(startUrl)
            val visited = mutableSetOf<String>()
            while (cached != null && visited.add(cacheKey(cached.sourceUrl))) {
                keep += cacheKey(cached.sourceUrl)
                val nextPage = cached.navigation.nextPage?.href?.let(::normalizeUrl).orEmpty()
                if (nextPage.isEmpty()) break
                keep += cacheKey(nextPage)
                cached = findCached(nextPage)
            }
        }
        keepPageChain(current?.sourceUrl.orEmpty())
        keepPageChain(previous?.sourceUrl.orEmpty())
        keepPageChain(current?.navigation?.next?.href.orEmpty())
        val keptSources = cachedDocuments.values
            .distinctBy { cacheKey(it.sourceUrl) }
            .filter { cacheKey(it.sourceUrl) in keep }
            .map { cacheKey(it.sourceUrl) }
            .toMutableSet()

        val catalogRoot = when {
            current?.isCatalog == true -> current.sourceUrl
            activeCatalogUrl.isNotEmpty() -> activeCatalogUrl
            else -> current?.navigation?.catalog?.href.orEmpty()
        }
        val catalogEntries = cachedDocuments.values
            .filter { it.isCatalog }
            .distinctBy { cacheKey(it.sourceUrl) }
        val activeCatalogEntries = catalogEntries.filter { cached ->
            cacheKey(cached.sourceUrl) == cacheKey(catalogRoot) ||
                sameUrl(cached.navigation.catalog?.href.orEmpty(), catalogRoot)
        }
        val catalogSourcesToKeep = linkedSetOf<String>()
        catalogSourcesToKeep += keptSources.filter { source ->
            catalogEntries.any { cacheKey(it.sourceUrl) == source }
        }
        activeCatalogEntries
            .filter { cacheKey(it.sourceUrl) == cacheKey(catalogRoot) }
            .forEach { catalogSourcesToKeep += cacheKey(it.sourceUrl) }
        activeCatalogEntries
            .asReversed()
            .take(16)
            .forEach { catalogSourcesToKeep += cacheKey(it.sourceUrl) }
        catalogEntries
            .asReversed()
            .take(16)
            .forEach { catalogSourcesToKeep += cacheKey(it.sourceUrl) }

        val retainedNonCatalogSources = cachedDocuments.values
            .filterNot { it.isCatalog }
            .distinctBy { cacheKey(it.sourceUrl) }
            .filter { cacheKey(it.sourceUrl) in keep }
            .map { cacheKey(it.sourceUrl) }
            .toSet()
        cachedDocuments = cachedDocuments.filterValues { cached ->
            if (cached.isCatalog) {
                cacheKey(cached.sourceUrl) in catalogSourcesToKeep
            } else {
                cacheKey(cached.sourceUrl) in retainedNonCatalogSources
            }
        }
    }

    fun navigationCatalogFor(result: ReaderDocument): ReaderDocument? {
        val catalogUrl = activeCatalogUrl.takeIf { it.isNotEmpty() }
            ?: result.navigation.catalog?.href?.let(::normalizeUrl).orEmpty()
        if (catalogUrl.isEmpty()) return null
        val cached = findCached(catalogUrl)?.takeIf { it.isCatalog }
        // The pruned in-memory cache can drop catalog pages that were loaded earlier; the saved
        // aggregate catalog still holds the full chapter list, so merge it back for navigation.
        val persisted = persistedCatalogDocument
            ?.takeIf { it.isCatalog && sameUrl(it.sourceUrl, catalogUrl) }
        return when {
            cached != null && persisted != null -> mergeCatalogDocuments(persisted, cached)
            cached != null -> cached
            else -> persisted
        }
    }

    // Chapter pages often expose only a "next page" link, with the real next chapter living on
    // the continuation page or in the catalog; fall back in that order so the reader can advance.
    fun nextChapterLink(result: ReaderDocument): ReaderLink? =
        resolveReaderNavigationLinks(result, navigationCatalogFor(result)).next

    fun previousChapterLink(result: ReaderDocument): ReaderLink? =
        resolveReaderNavigationLinks(result, navigationCatalogFor(result)).previous

    // Whether a real previous chapter exists, independent of whether it is already cached: the
    // menu button must stay usable right after the catalog resolves instead of waiting for a load.
    fun hasPreviousChapter(result: ReaderDocument): Boolean {
        val catalog = navigationCatalogFor(result)
        val link = resolveReaderNavigationLinks(result, catalog).previous ?: return false
        return link.href.isNotBlank() && !sameUrl(link.href, result.sourceUrl)
    }

    // True while the fetched chapter is still ahead of the chapter being read, so the warm-ahead
    // chain may continue; chapters behind the reader are cached without extending the chain.
    fun lineOfChapter(url: String): Int {
        val items = navigationCatalogFor(document ?: return -1)?.catalogItems.orEmpty()
        val keys = catalogChapterKeys(url)
        return items.indexOfFirst { item -> catalogChapterKeys(item.href).any { it in keys } }
    }

    fun isAheadOfCurrent(baseUrl: String): Boolean {
        if (prefetchChapterDepth >= MaxPrefetchChapterDepth) return false
        val current = document?.takeUnless { it.isCatalog } ?: return false
        if (sameUrl(baseUrl, current.sourceUrl)) return false
        val baseLine = lineOfChapter(baseUrl)
        val currentLine = lineOfChapter(current.sourceUrl)
        if (baseLine < 0 || currentLine < 0) return false
        return baseLine > currentLine
    }

    // The next thing that still needs downloading after this chapter: its own remaining text
    // pages first, then the following chapter. Returns an empty base url when nothing is needed.
    fun nextPrefetchTarget(result: ReaderDocument): Pair<String, String> {
        if (result.isCatalog) return "" to ""
        val currentContinuation = uncachedPageContinuationUrl(result)
        if (currentContinuation != null && !sameUrl(currentContinuation, result.sourceUrl)) {
            return cacheKey(result.sourceUrl) to currentContinuation
        }
        val next = nextChapterLink(result)?.href?.let(::normalizeUrl).orEmpty()
        if (next.isEmpty() || sameUrl(next, result.sourceUrl)) return "" to ""
        val cachedNext = findCached(next)
        val nextContinuation = cachedNext?.let(::uncachedPageContinuationUrl)
        if (nextContinuation != null && !sameUrl(nextContinuation, cachedNext.sourceUrl)) {
            return cacheKey(cachedNext.sourceUrl) to nextContinuation
        }
        val nextReady = cachedNext != null && cachedNext.isUsableForReading()
        // Guard against downloading the same chapter twice in one session even if the in-memory
        // cache entry was pruned between the download and this check.
        if (nextReady || cacheKey(next) in prefetchCompletedKeys) return "" to ""
        return "" to next
    }

    fun prepareNext(result: ReaderDocument): Boolean {
        val (baseUrl, targetUrl) = nextPrefetchTarget(result)
        val alreadyDownloading = targetUrl.isNotEmpty() &&
            (sameUrl(prefetchLoadUrl, targetUrl) || sameUrl(prefetchPageBaseUrl, targetUrl))
        setPrefetchTarget(baseUrl, targetUrl)
        // A repeat request for the download already in flight must not look like progress.
        return targetUrl.isNotEmpty() && !alreadyDownloading
    }

    fun preparePrevious(result: ReaderDocument) {
        previousPageBaseUrl = ""
        if (result.isCatalog) {
            previousLoadUrl = ""
            return
        }
        val previous = previousChapterLink(result)?.href?.let(::normalizeUrl).orEmpty()
        val cachedPrevious = previous.takeIf { it.isNotEmpty() }?.let(::findCached)
        val previousContinuation = cachedPrevious?.let(::uncachedPageContinuationUrl)
        if (cachedPrevious != null && previousContinuation != null && !sameUrl(previousContinuation, cachedPrevious.sourceUrl)) {
            previousPageBaseUrl = cacheKey(cachedPrevious.sourceUrl)
            previousLoadUrl = previousContinuation
            return
        }
        val previousReady = cachedPrevious != null && cachedPrevious.isUsableForReading()
        previousLoadUrl = if (previous.isNotEmpty() && !previousReady && !sameUrl(previous, result.sourceUrl)) previous else ""
    }

    LaunchedEffect(document?.sourceUrl, document?.isCatalog, catalogNavigationRevision) {
        document?.takeUnless { it.isCatalog }?.let { restored ->
            val resolved = resolveReaderNavigationLinks(restored, navigationCatalogFor(restored))
            if (catalogNavigationRevision > 0) {
                recordDiagnostic(
                    "neighbors",
                    restored.sourceUrl,
                    "previous=${resolved.previous?.href.orEmpty()} next=${resolved.next?.href.orEmpty()} catalog=${resolved.catalog?.href.orEmpty()}"
                )
            }
            preparePrevious(restored)
            prepareNext(restored)
        }
    }

    fun catalogPageKey(url: String): String = cacheKey(normalizeUrl(url))

    fun nextCatalogPage(catalog: ReaderDocument, loaded: Set<String>): String? =
        catalog.catalogPages
            .asSequence()
            .map { normalizeUrl(it.href) }
            .filter { it.isNotEmpty() }
            .firstOrNull { catalogPageKey(it) !in loaded && !sameUrl(it, catalog.sourceUrl) }

    fun startCatalogCrawl(
        rawRootUrl: String,
        seed: ReaderDocument? = null,
        forceReload: Boolean = false
    ) {
        val rootUrl = normalizeUrl(rawRootUrl)
        if (rootUrl.isEmpty()) return
        val sameRoot = catalogAggregateUrl.isNotEmpty() && sameUrl(catalogAggregateUrl, rootUrl)
        if (forceReload) {
            invalidateCatalogCache(rootUrl)
            restartCatalogWebView()
        }
        if (forceReload || !sameRoot) {
            catalogAggregateUrl = rootUrl
            catalogLoadedUrls = emptySet()
            catalogLoadedPageCount = 0
            catalogComplete = false
            catalogErrorMessage = null
            catalogLoadUrl = ""
        }

        val rootDocument = if (forceReload) {
            null
        } else {
            seed?.takeIf { it.isCatalog } ?: findCached(rootUrl)
        }
        if (rootDocument != null && rootDocument.isCatalog) {
            val cachedPageKeys = rootDocument.catalogPages
                .map { catalogPageKey(it.href) }
                .filter { it.isNotEmpty() }
                .filter { findCached(it)?.isCatalog == true }
                .toSet()
            val rootKey = catalogPageKey(rootDocument.sourceUrl).ifEmpty { catalogPageKey(rootUrl) }
            val loaded = catalogLoadedUrls + rootKey + cachedPageKeys
            catalogLoadedUrls = loaded
            catalogLoadedPageCount = loaded.size
            cacheDocument(rootDocument, rootUrl)
            val next = nextCatalogPage(rootDocument, loaded)
            catalogErrorMessage = null
            catalogComplete = next == null
            catalogLoadUrl = next.orEmpty()
            if (next != null && sameUrl(catalogWebView?.url.orEmpty(), next)) {
                restartCatalogWebView()
            }
        } else {
            catalogLoadedUrls = emptySet()
            catalogLoadedPageCount = 0
            catalogComplete = false
            catalogErrorMessage = null
            catalogLoadUrl = rootUrl
            if (sameUrl(catalogWebView?.url.orEmpty(), rootUrl)) {
                restartCatalogWebView()
            }
        }
    }

    // Chapter pages can land here without a catalog, and some sites only reveal the next
    // chapter through the catalog; warm it in the background so navigation stays available.
    LaunchedEffect(document?.sourceUrl, document?.isCatalog, catalogAggregateUrl) {
        val current = document?.takeUnless { it.isCatalog } ?: return@LaunchedEffect
        val navigationCatalog = current.navigation.catalog ?: return@LaunchedEffect
        val root = catalogAggregateUrl.takeIf { it.isNotEmpty() }
            ?: navigationCatalog.href.let(::normalizeUrl)
        if (root.isEmpty()) return@LaunchedEffect
        startCatalogCrawl(root)
    }

    fun updateShelfTitleFromCatalog(catalogUrl: String, catalog: ReaderDocument) {
        if (!catalog.isCatalog) return
        val current = document?.takeUnless { it.isCatalog } ?: return
        val key = shelfKeyForDocument(current)
        val existing = shelfBooks.firstOrNull {
            it.key == key || (it.catalogUrl.isNotBlank() && sameUrl(it.catalogUrl, catalogUrl))
        } ?: return
        val updated = existing.copy(
            title = bookTitleForDocument(current, catalog),
            updatedAt = System.currentTimeMillis()
        )
        shelfBooks = listOf(updated) + shelfBooks.filterNot { it.key == existing.key }
        saveShelfBooks(preferences, shelfBooks)
    }

    fun acceptCatalogPage(result: ReaderDocument, expected: String) {
        val rootUrl = catalogAggregateUrl.takeIf { it.isNotEmpty() }
            ?: activeCatalogUrl.takeIf { it.isNotEmpty() }
            ?: expected
        val existing = findCached(rootUrl)?.takeIf { it.isCatalog }
        cacheDocument(result, expected)
        val merged = if (existing != null) mergeCatalogDocuments(existing, result) else result
        cacheDocument(merged, rootUrl)
        saveCachedCatalogDocument(preferences, merged)
        persistedCatalogDocument = merged
        pruneCache(merged, previousDocument)

        val loadedBefore = catalogLoadedUrls
        val loadedAfter = loadedBefore + catalogPageKey(expected) + catalogPageKey(result.sourceUrl)
        catalogLoadedUrls = loadedAfter
        catalogLoadedPageCount += loadedAfter.size - loadedBefore.size
        catalogErrorMessage = null
        catalogNavigationRevision += 1
        updateShelfTitleFromCatalog(rootUrl, merged)

        if (document?.isCatalog == true && (
                sameUrl(document?.sourceUrl.orEmpty(), rootUrl) || sameUrl(document?.sourceUrl.orEmpty(), expected)
            )) {
            document = merged
            currentUrl = merged.sourceUrl
            address = merged.sourceUrl
        }

        val next = nextCatalogPage(merged, loadedAfter)
        catalogComplete = next == null
        catalogLoadUrl = next.orEmpty()
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

    fun saveCurrentDocumentCache(commit: Boolean = false) {
        (visibleDocumentForPersistence ?: document)?.let {
            saveCachedReaderDocument(preferences, it, commit = commit)
        }
    }

    fun savedReadingOffset(url: String): Int? = preferences
        .getInt(progressOffsetKey(url), -1)
        .takeIf { it >= 0 }

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
        if (catalog == null && catalogUrl.isNotBlank()) {
            startCatalogCrawl(catalogUrl)
        }
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
        val displayResult = mergeCachedContinuation(result)
        val old = document
        if (old != null && !old.isCatalog && !displayResult.isCatalog && !sameUrl(old.sourceUrl, displayResult.sourceUrl)) {
            previousDocument = old
        }
        if (old == null || old.isCatalog || displayResult.isCatalog || !sameUrl(old.sourceUrl, displayResult.sourceUrl)) {
            readingOffset = null
        }
        cacheDocument(displayResult, requestedUrl)
        document = displayResult
        visibleDocumentForPersistence = displayResult
        saveCachedReaderDocument(preferences, displayResult)
        if (displayResult.isCatalog) saveCachedCatalogDocument(preferences, displayResult)
        updateShelfForDocument(displayResult)
        currentUrl = displayResult.sourceUrl
        address = displayResult.sourceUrl
        loading = false
        errorMessage = null
        if (chapterNavigationTarget.isNotEmpty() && (
                sameUrl(chapterNavigationTarget, displayResult.sourceUrl) || sameUrl(chapterNavigationTarget, requestedUrl)
            )) {
            chapterNavigationTarget = ""
        }
        chapterOpenPosition = openPosition
        verticalOpenIndex = verticalIndexOverride
        verticalOpenOffset = verticalOffsetOverride
        if (!loadActiveWebView) restartActiveWebView()
        activeLoadUrl = ""
        if (displayResult.isCatalog) {
            activeCatalogUrl = normalizeUrl(displayResult.sourceUrl)
            if (catalogIndexOverride != null) activeCatalogIndex = catalogIndexOverride
        } else {
            activeCatalogUrl = catalogUrlOverride?.let(::normalizeUrl)?.takeIf { it.isNotEmpty() }
                ?: displayResult.navigation.catalog?.href?.let(::normalizeUrl).orEmpty()
            if (catalogIndexOverride != null) activeCatalogIndex = catalogIndexOverride
        }
        pruneCache(displayResult, previousDocument)
        restartPrefetchWebView()
        prefetchChapterDepth = 0
        preparePrevious(displayResult)
        prepareNext(displayResult)
        if (displayResult.isCatalog) startCatalogCrawl(displayResult.sourceUrl, displayResult)
    }

    fun openUrl(
        raw: String,
        openPosition: ChapterOpenPosition? = null,
        catalogUrlOverride: String? = null,
        catalogIndexOverride: Int? = null,
        verticalIndexOverride: Int? = null,
        verticalOffsetOverride: Int? = null,
        forceReload: Boolean = false,
        keepCurrentDocumentWhileLoading: Boolean = false
    ) {
        val normalized = normalizeUrl(raw)
        val catalogContext = catalogUrlOverride?.let(::normalizeUrl)?.takeIf { it.isNotEmpty() }
        if (normalized.isEmpty()) {
            errorMessage = "请输入完整的网址，例如 https://example.com"
            recordDiagnostic("invalid_url", raw, "normalize_failed")
            return
        }
        pendingAutoNext = false
        preferences.edit().putString("last_url", normalized).apply()
        screen = AppScreen.READER.name
        if (catalogContext != null) activeCatalogUrl = catalogContext
        activeCatalogIndex = catalogIndexOverride
        if (forceReload) {
            val catalogRootToRefresh = normalized.takeIf {
                (document?.isCatalog == true && sameUrl(document?.sourceUrl.orEmpty(), normalized)) ||
                    (activeCatalogUrl.isNotEmpty() && sameUrl(activeCatalogUrl, normalized))
            }
            if (catalogRootToRefresh != null) {
                invalidateCatalogCache(catalogRootToRefresh)
                catalogAggregateUrl = catalogRootToRefresh
                catalogLoadedUrls = emptySet()
                catalogLoadedPageCount = 0
                catalogComplete = false
                catalogErrorMessage = null
                catalogLoadUrl = ""
                restartCatalogWebView()
            }
            restartActiveWebView()
        }
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
        val cached = if (forceReload) null else findCached(normalized)
        val cachedContinuation = cached?.takeIf { !it.isCatalog }?.let(::uncachedPageContinuationUrl)
        val needsPreviousPageChain = cached != null && !cached.isCatalog &&
            (openPosition != null || verticalIndexOverride != null) &&
            cachedContinuation != null
        if (needsPreviousPageChain) {
            val continuationBaseUrl = cacheKey(cached!!.sourceUrl)
            val continuationTarget = cachedContinuation ?: normalized
            val samePendingLoad = previousPageBaseUrl == continuationBaseUrl &&
                sameUrl(previousLoadUrl, continuationTarget)
            restartPrefetchWebView()
            pendingChapterNavigation = pendingNavigation
            previousPageBaseUrl = continuationBaseUrl
            previousLoadUrl = continuationTarget
            errorMessage = null
            loading = false
            if (!samePendingLoad) restartPreviousWebView()
            return
        }
        if (cached != null && document != null && !document!!.isCatalog && !cached.isCatalog &&
            openPosition == null && catalogContext == null && catalogIndexOverride == null &&
            verticalIndexOverride == null && !forceReload && sameUrl(cached.sourceUrl, document!!.sourceUrl)
        ) {
            pendingChapterNavigation = null
            chapterOpenPosition = null
            verticalOpenIndex = null
            verticalOpenOffset = null
            readingOffset = savedReadingOffset(cached.sourceUrl) ?: readingOffset
            currentUrl = cached.sourceUrl
            address = cached.sourceUrl
            loading = false
            errorMessage = null
            saveCachedReaderDocument(preferences, cached)
            preparePrevious(cached)
            prepareNext(cached)
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
                return
            }
            pendingChapterNavigation = pendingNavigation
            if (!keepCurrentDocumentWhileLoading) document = null
            errorMessage = null
            loading = true
            currentUrl = normalized
            activeLoadUrl = normalized
            activeRetryUrl = normalized
            activeRetryCount = 0
            activeRetryScheduled = false
            restartPrefetchWebView()
            prefetchLoadUrl = ""
            prefetchPageBaseUrl = ""
            prefetchChapterDepth = 0
            previousLoadUrl = ""
        }
    }

    fun openChapter(
        raw: String,
        position: ChapterOpenPosition? = null,
        verticalIndex: Int? = null,
        verticalOffset: Int? = null
    ) {
        val normalized = normalizeUrl(raw)
        if (normalized.isEmpty()) return
        if (chapterNavigationTarget.isNotEmpty() && sameUrl(chapterNavigationTarget, normalized)) return
        pendingAutoNext = false
        chapterNavigationTarget = normalized
        openUrl(
            normalized,
            openPosition = position,
            verticalIndexOverride = verticalIndex,
            verticalOffsetOverride = verticalOffset,
            keepCurrentDocumentWhileLoading = true
        )
    }

    fun requestAutoNext() {
        val current = document ?: return
        if (settings.pageMode == PageMode.VERTICAL) {
            // Keep the current list alive while the next chapter is fetched and appended.
            prepareNext(current)
            return
        }
        val nextUrl = current.let(::nextChapterLink)?.href?.let(::normalizeUrl).orEmpty()
        if (nextUrl.isEmpty() || sameUrl(nextUrl, current.sourceUrl)) {
            pendingAutoNext = hasUncachedPageContinuation(current)
            return
        }
        if (hasUncachedPageContinuation(current)) {
            pendingAutoNext = true
            return
        }
        val cachedNext = findCached(nextUrl)
        val nextPagePending = cachedNext != null && !cachedNext.isCatalog &&
            uncachedPageContinuationUrl(cachedNext) != null
        if (nextPagePending) {
            prepareNext(current)
            pendingAutoNext = true
            return
        }
        if (cachedNext == null && !sameUrl(prefetchLoadUrl, nextUrl)) {
            prepareNext(current)
        }
        if (cachedNext == null && sameUrl(prefetchLoadUrl, nextUrl)) {
            pendingAutoNext = true
            return
        }
        pendingAutoNext = false
        openChapter(nextUrl, ChapterOpenPosition.START)
    }

    fun failPendingAutoNext(target: String, reason: String) {
        if (!pendingAutoNext) return
        if (settings.pageMode == PageMode.VERTICAL) {
            pendingAutoNext = false
            recordDiagnostic("auto_next_prefetch_failed_vertical", target, reason)
            return
        }
        val current = document
        val nextUrl = current?.let(::nextChapterLink)?.href?.let(::normalizeUrl).orEmpty()
        pendingAutoNext = false
        if (current != null && nextUrl.isNotEmpty() && sameUrl(nextUrl, target)) {
            recordDiagnostic("auto_next_prefetch_fallback", target, reason)
            // The prefetched page may only be the first text page of the next chapter; prefer the
            // cached continuation so the user never lands in the middle of a chapter.
            val cachedNext = findCached(nextUrl)
                ?.takeUnless { it.isCatalog || it.paragraphs.isEmpty() }
                ?.let(::mergeCachedContinuation)
            if (cachedNext != null) {
                showDocument(cachedNext, nextUrl, ChapterOpenPosition.START)
            } else {
                openChapter(target, ChapterOpenPosition.START)
            }
        } else {
            loading = false
            errorMessage = "下一章暂时无法预读取，请点击重试"
            recordDiagnostic("auto_next_prefetch_failed", target, reason)
        }
    }

    fun handlePrefetchedChapter(result: ReaderDocument, expected: String) {
        restartPrefetchWebView()
        if (sameUrl(prefetchLoadUrl, expected)) prefetchLoadUrl = ""
        if (sameUrl(previousLoadUrl, expected)) previousLoadUrl = ""
        cacheKey(expected).takeIf { it.isNotEmpty() }?.let { key -> prefetchCompletedKeys += key }
        cacheKey(result.sourceUrl).takeIf { it.isNotEmpty() }?.let { key -> prefetchCompletedKeys += key }

        val pageBase = prefetchPageBaseUrl
        if (pageBase.isNotEmpty()) {
            val base = findCached(pageBase) ?: document?.takeIf { sameUrl(it.sourceUrl, pageBase) }
            if (base != null && !base.isCatalog && result.sourceUrl.isNotBlank()) {
                val baseIsCurrent = document?.sourceUrl?.let { sameUrl(it, base.sourceUrl) } == true
                val merged = mergeCachedContinuation(mergePagedDocuments(base, result))
                prefetchPageBaseUrl = ""
                cacheDocument(merged, base.sourceUrl)
                cacheDocument(merged, expected)
                val pending = pendingChapterNavigation
                if (pending != null && sameUrl(pending.url, base.sourceUrl)) {
                    pendingChapterNavigation = null
                    showDocument(
                        merged,
                        pending.url,
                        pending.position,
                        pending.catalogUrl,
                        pending.catalogIndex,
                        pending.verticalIndex,
                        pending.verticalOffset,
                        loadActiveWebView = false
                    )
                } else if (document?.sourceUrl?.let { sameUrl(it, base.sourceUrl) } == true) {
                    document = merged
                    visibleDocumentForPersistence = merged
                    currentUrl = merged.sourceUrl
                    address = merged.sourceUrl
                    val catalogUrl = merged.navigation.catalog?.href?.let(::normalizeUrl).orEmpty()
                    if (catalogUrl.isNotEmpty() && !sameUrl(catalogUrl, activeCatalogUrl)) {
                        activeCatalogUrl = catalogUrl
                    }
                    saveCachedReaderDocument(preferences, merged)
                    updateShelfForDocument(merged)
                    pruneCache(merged, previousDocument)
                    // Attached chapter content grew: restart the warm-ahead budget for this chapter.
                    prefetchChapterDepth = 0
                    preparePrevious(merged)
                    prepareNext(merged)
                } else if (uncachedPageContinuationUrl(merged) != null) {
                    prepareNext(merged)
                } else if (isAheadOfCurrent(base.sourceUrl)) {
                    // Keep the following chapters warm while the reader is still behind them.
                    if (prepareNext(merged)) {
                        prefetchChapterDepth += 1
                        recordDiagnostic("warm_ahead", merged.sourceUrl, "depth=$prefetchChapterDepth")
                    }
                }
                return
            }
            prefetchPageBaseUrl = ""
        }

        cacheDocument(result, expected)
        if (uncachedPageContinuationUrl(result) != null) {
            prepareNext(result)
            return
        }
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
        } else if (isAheadOfCurrent(result.sourceUrl)) {
            // The fetched chapter sits ahead of the chapter being read, so keep warming forward:
            // without this the reader has to wait for the chapter after the attached one.
            if (prepareNext(result)) {
                prefetchChapterDepth += 1
                recordDiagnostic("warm_ahead", result.sourceUrl, "depth=$prefetchChapterDepth")
            }
        }
    }

    fun fallbackPendingChapterToActive(expected: String) {
        val pending = pendingChapterNavigation
        if (pending != null && sameUrl(pending.url, expected)) {
            recordDiagnostic("fallback_to_active", expected, "pending_navigation=true")
            errorMessage = null
            loading = false
            activeLoadUrl = expected
            activeRetryUrl = expected
            activeRetryCount = 0
            activeRetryScheduled = false
        }
    }

    fun handleActiveError(view: WebView, requestToken: Long, pageUrl: String, reason: String) {
        if (screen == AppScreen.READER.name &&
            view === activeWebView && requestToken != 0L && requestToken == webViewLoadToken(view) &&
            activeLoadUrl.isNotEmpty() && sameReaderLoadUrl(pageUrl, activeLoadUrl)) {
            if (isCloudflareChallengeReason(reason)) {
                claimCloudflareChallenge(view, pageUrl, "active", requestToken, preferActive = true)
                return
            }
            val failedUrl = activeLoadUrl
            val failedView = view
            val failedToken = requestToken
            val failedRequestId = activeRequestId
            recordDiagnostic(
                "active_load_error",
                failedUrl,
                "token=$failedToken pageUrl=$pageUrl retry=$activeRetryCount reason=$reason"
            )
            if (activeRetryUrl != failedUrl) {
                activeRetryUrl = failedUrl
                activeRetryCount = 0
                activeRetryScheduled = false
            }
            if (activeRetryCount < 2 && !activeRetryScheduled) {
                activeRetryCount += 1
                activeRetryScheduled = true
                loading = true
                errorMessage = null
                val retryDelay = 700L * activeRetryCount
                failedView.postDelayed({
                    if (activeRequestId != failedRequestId) return@postDelayed
                    activeRetryScheduled = false
                    if (activeWebView === failedView &&
                        webViewLoadToken(failedView) == failedToken &&
                        activeLoadUrl.isNotEmpty() && sameUrl(activeLoadUrl, failedUrl)
                    ) {
                        restartActiveWebView()
                    }
                }, retryDelay)
            } else if (!activeRetryScheduled) {
                pendingChapterNavigation = null
                loading = false
                errorMessage = "网页暂时无法打开，已重试 2 次，请检查网络后重新读取。"
                chapterNavigationTarget = ""
            }
        }
    }

    val activePayloadState = rememberUpdatedState<(WebView, Long, String, String) -> Boolean> { view, requestToken, pageUrl, rawPayload ->
        var accepted = false
        val expected = activeLoadUrl
        val result = parseReaderPayload(rawPayload)
        val tokenMatches = screen == AppScreen.READER.name &&
            view === activeWebView && requestToken != 0L && requestToken == webViewLoadToken(view)
        val matches = expected.isNotEmpty() && tokenMatches &&
            (sameReaderLoadUrl(pageUrl, expected) || (result != null && sameReaderLoadUrl(result.sourceUrl, expected)))
        if (matches) {
            val pending = pendingChapterNavigation
            if ((pending == null || sameUrl(pending.url, expected)) && result?.isUsableForReading() == true) {
                clearCloudflareChallenge(view)
                activeRetryUrl = ""
                activeRetryCount = 0
                activeRetryScheduled = false
                if (document == null || document != result) {
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
                pendingChapterNavigation = null
                accepted = true
            }
        }
        accepted
    }
    val activeErrorState = rememberUpdatedState<(WebView, Long, String, String) -> Unit> { view, requestToken, pageUrl, reason ->
        handleActiveError(view, requestToken, pageUrl, reason)
    }
    val prefetchPayloadState = rememberUpdatedState<(WebView, Long, String, String) -> Boolean> { view, requestToken, pageUrl, rawPayload ->
        var accepted = false
        val expected = prefetchLoadUrl
        val result = parseReaderPayload(rawPayload)
        val tokenMatches = screen == AppScreen.READER.name &&
            view === prefetchWebView && requestToken != 0L && requestToken == webViewLoadToken(view)
        val matches = expected.isNotEmpty() && tokenMatches &&
            (sameReaderLoadUrl(pageUrl, expected) || (result != null && sameReaderLoadUrl(result.sourceUrl, expected)))
        if (matches && result != null && !result.isCatalog && result.paragraphs.isNotEmpty()) {
            clearCloudflareChallenge(view)
            prefetchRetryUrl = ""
            prefetchRetryCount = 0
            prefetchRetryScheduled = false
            handlePrefetchedChapter(result, expected)
            accepted = true
        }
        accepted
    }
    val prefetchErrorState = rememberUpdatedState<(WebView, Long, String, String) -> Unit> { view, requestToken, pageUrl, reason ->
        if (screen == AppScreen.READER.name &&
            view === prefetchWebView && requestToken != 0L && requestToken == webViewLoadToken(view) &&
            prefetchLoadUrl.isNotEmpty() && sameReaderLoadUrl(pageUrl, prefetchLoadUrl)) {
            val failedUrl = prefetchLoadUrl
            val failedView = view
            val failedToken = requestToken
            val failedRequestId = prefetchRequestId
            recordDiagnostic(
                "prefetch_load_error",
                failedUrl,
                "token=$failedToken pageUrl=$pageUrl retry=$prefetchRetryCount reason=$reason"
            )
            if (isCloudflareChallengeReason(reason)) {
                claimCloudflareChallenge(failedView, failedUrl, "prefetch", failedToken)
            } else {
                if (prefetchRetryUrl != failedUrl) {
                    prefetchRetryUrl = failedUrl
                    prefetchRetryCount = 0
                    prefetchRetryScheduled = false
                }
                if (prefetchRetryCount < 2 && !prefetchRetryScheduled) {
                    prefetchRetryCount += 1
                    prefetchRetryScheduled = true
                    val retryDelay = 900L * prefetchRetryCount
                    failedView.postDelayed({
                        if (prefetchRequestId != failedRequestId) return@postDelayed
                        prefetchRetryScheduled = false
                        if (prefetchWebView === failedView &&
                            webViewLoadToken(failedView) == failedToken &&
                            prefetchLoadUrl.isNotEmpty() && sameUrl(prefetchLoadUrl, failedUrl)
                        ) {
                            restartPrefetchWebView()
                        }
                    }, retryDelay)
                } else if (!prefetchRetryScheduled) {
                    prefetchLoadUrl = ""
                    prefetchPageBaseUrl = ""
                    prefetchChapterDepth = 0
                    prefetchRetryUrl = ""
                    prefetchRetryCount = 0
                    fallbackPendingChapterToActive(failedUrl)
                    failPendingAutoNext(failedUrl, "retry_exhausted")
                }
            }
        }
    }
    val previousPayloadState = rememberUpdatedState<(WebView, Long, String, String) -> Boolean> { view, requestToken, pageUrl, rawPayload ->
        var accepted = false
        val expected = previousLoadUrl
        val result = parseReaderPayload(rawPayload)
        val tokenMatches = screen == AppScreen.READER.name &&
            view === previousWebView && requestToken != 0L && requestToken == webViewLoadToken(view)
        val matches = expected.isNotEmpty() && tokenMatches &&
            (sameReaderLoadUrl(pageUrl, expected) || (result != null && sameReaderLoadUrl(result.sourceUrl, expected)))
        if (matches && result != null && !result.isCatalog && result.paragraphs.isNotEmpty()) {
            clearCloudflareChallenge(view)
            val baseUrl = previousPageBaseUrl
            val base = baseUrl.takeIf { it.isNotEmpty() }?.let(::findCached)
            val merged = mergeCachedContinuation(
                if (base != null && !base.isCatalog) mergePagedDocuments(base, result) else result
            )
            val continuationUrl = merged.navigation.nextPage?.href?.let(::normalizeUrl).orEmpty()
            cacheDocument(merged, if (baseUrl.isNotEmpty()) baseUrl else expected)
            if (continuationUrl.isNotEmpty()) {
                previousPageBaseUrl = cacheKey(merged.sourceUrl)
                previousLoadUrl = continuationUrl
            } else {
                previousPageBaseUrl = ""
                previousLoadUrl = ""
                val pending = pendingChapterNavigation
                if (pending != null && (
                        sameUrl(pending.url, merged.sourceUrl) ||
                            (baseUrl.isNotEmpty() && sameUrl(pending.url, baseUrl))
                    )) {
                    pendingChapterNavigation = null
                    showDocument(
                        merged,
                        pending?.url ?: merged.sourceUrl,
                        pending?.position,
                        pending?.catalogUrl,
                        pending?.catalogIndex,
                        pending?.verticalIndex,
                        pending?.verticalOffset,
                        loadActiveWebView = false
                    )
                }
            }
            accepted = true
        }
        accepted
    }
    val previousErrorState = rememberUpdatedState<(WebView, Long, String, String) -> Unit> { view, requestToken, pageUrl, reason ->
        if (screen == AppScreen.READER.name &&
            view === previousWebView && requestToken != 0L && requestToken == webViewLoadToken(view) &&
            previousLoadUrl.isNotEmpty() && sameReaderLoadUrl(pageUrl, previousLoadUrl)) {
            val failedUrl = previousPageBaseUrl.takeIf { it.isNotEmpty() } ?: previousLoadUrl
            recordDiagnostic(
                "previous_load_error",
                failedUrl,
                "token=$requestToken pageUrl=$pageUrl reason=$reason"
            )
            if (isCloudflareChallengeReason(reason)) {
                claimCloudflareChallenge(view, pageUrl, "previous", requestToken)
            } else {
                previousLoadUrl = ""
                previousPageBaseUrl = ""
                fallbackPendingChapterToActive(failedUrl)
            }
        }
    }
    val catalogPayloadState = rememberUpdatedState<(WebView, Long, String, String) -> Boolean> { view, requestToken, pageUrl, rawPayload ->
        val expected = catalogLoadUrl
        val result = parseReaderPayload(rawPayload)
        val tokenMatches = screen == AppScreen.READER.name &&
            view === catalogWebView && requestToken != 0L && requestToken == webViewLoadToken(view)
        val matches = expected.isNotEmpty() && tokenMatches &&
            (sameReaderLoadUrl(pageUrl, expected) || (result != null && sameReaderLoadUrl(result.sourceUrl, expected)))
        if (matches && result?.isCatalog == true) {
            clearCloudflareChallenge(view)
            acceptCatalogPage(result, expected)
            true
        } else {
            false
        }
    }
    val catalogErrorState = rememberUpdatedState<(WebView, Long, String, String) -> Unit> { view, requestToken, pageUrl, reason ->
        if (screen == AppScreen.READER.name &&
            view === catalogWebView && requestToken != 0L && requestToken == webViewLoadToken(view) &&
            catalogLoadUrl.isNotEmpty() && sameReaderLoadUrl(pageUrl, catalogLoadUrl)) {
            recordDiagnostic(
                "catalog_load_error",
                catalogLoadUrl,
                "token=$requestToken pageUrl=$pageUrl reason=$reason"
            )
            if (isCloudflareChallengeReason(reason)) {
                claimCloudflareChallenge(view, pageUrl, "catalog", requestToken)
            } else {
                catalogLoadUrl = ""
                catalogComplete = false
                catalogErrorMessage = "目录暂时无法读取，请点击重试"
            }
        }
    }

    LaunchedEffect(
        catalogAggregateUrl,
        catalogLoadedUrls,
        catalogLoadedPageCount,
        catalogComplete,
        catalogLoadUrl,
        catalogErrorMessage
    ) {
        preferences.edit()
            .putString(CATALOG_STATE_ROOT_KEY, catalogAggregateUrl)
            .putStringSet(CATALOG_STATE_LOADED_KEY, catalogLoadedUrls)
            .putInt(CATALOG_STATE_COUNT_KEY, catalogLoadedPageCount)
            .putBoolean(CATALOG_STATE_COMPLETE_KEY, catalogComplete)
            .putString(CATALOG_STATE_LOAD_URL_KEY, catalogLoadUrl)
            .putString(CATALOG_STATE_ERROR_KEY, catalogErrorMessage.orEmpty())
            .apply()
    }
    LaunchedEffect(screen, document, activeLoadUrl) {
        if (screen != AppScreen.READER.name) {
            cancelReaderChapterLoads()
            return@LaunchedEffect
        }
        if (document == null && activeLoadUrl.isBlank()) {
            preferences.getString("last_url", null)?.let { lastUrl ->
                if (lastUrl.isNotBlank()) openUrl(lastUrl)
            }
        }
    }
    LaunchedEffect(
        pendingAutoNext,
        settings.pageMode,
        document?.sourceUrl,
        document?.paragraphs?.size,
        document?.navigation?.next?.href,
        document?.navigation?.nextPage?.href,
        catalogNavigationRevision,
        prefetchLoadUrl,
        cachedDocuments
    ) {
        if (!pendingAutoNext) return@LaunchedEffect
        val current = document ?: return@LaunchedEffect
        if (settings.pageMode == PageMode.VERTICAL) {
            // Vertical mode consumes the prefetched chapter as list content; do not reset to START.
            prepareNext(current)
            pendingAutoNext = false
            return@LaunchedEffect
        }
        if (hasUncachedPageContinuation(current)) return@LaunchedEffect
        val nextUrl = nextChapterLink(current)?.href?.let(::normalizeUrl).orEmpty()
        if (nextUrl.isEmpty() || sameUrl(nextUrl, current.sourceUrl)) {
            pendingAutoNext = false
            return@LaunchedEffect
        }
        val cachedNext = findCached(nextUrl)
        val nextPagePending = cachedNext != null && !cachedNext.isCatalog &&
            uncachedPageContinuationUrl(cachedNext) != null
        if (nextPagePending) {
            prepareNext(current)
            return@LaunchedEffect
        }
        if (cachedNext == null && !sameUrl(prefetchLoadUrl, nextUrl)) {
            prepareNext(current)
        }
        if (cachedNext == null && sameUrl(prefetchLoadUrl, nextUrl)) return@LaunchedEffect
        pendingAutoNext = false
        openChapter(nextUrl, ChapterOpenPosition.START)
    }

    LaunchedEffect(activeWebView, activeLoadUrl) {
        val target = activeLoadUrl
        val view = activeWebView
        if (view != null && target.isNotEmpty()) {
            when {
                webViewLoadTarget(view).isNotEmpty() && !sameUrl(webViewLoadTarget(view), target) -> {
                    restartActiveWebView()
                }
                webViewLoadTarget(view).isEmpty() -> {
                    view.stopLoading()
                    val referer = document?.sourceUrl.orEmpty().takeIf { it.isNotBlank() && !sameUrl(it, target) }
                        ?: currentUrl.takeIf { it.isNotBlank() && !sameUrl(it, target) }.orEmpty()
                    startWebViewLoad(view, target, referer)
                }
            }
        }
    }
    LaunchedEffect(prefetchWebView, prefetchLoadUrl) {
        val target = prefetchLoadUrl
        val view = prefetchWebView
        if (view != null && target.isNotEmpty()) {
            when {
                webViewLoadTarget(view).isNotEmpty() && !sameUrl(webViewLoadTarget(view), target) -> {
                    restartPrefetchWebView()
                }
                webViewLoadTarget(view).isEmpty() -> {
                    view.stopLoading()
                    val referer = document?.sourceUrl.orEmpty().takeIf { it.isNotBlank() && !sameUrl(it, target) }
                        ?: currentUrl.takeIf { it.isNotBlank() && !sameUrl(it, target) }.orEmpty()
                    startWebViewLoad(view, target, referer)
                }
            }
        }
    }
    LaunchedEffect(
        prefetchLoadUrl,
        prefetchRequestId,
        prefetchPageBaseUrl,
        pendingChapterNavigation?.url,
        pendingAutoNext,
        cloudflareChallengeWebView
    ) {
        val target = prefetchLoadUrl
        val requestId = prefetchRequestId
        val baseUrl = prefetchPageBaseUrl
        val pendingUrl = pendingChapterNavigation?.url
        val waitingForAutoNext = pendingAutoNext
        if (target.isEmpty() || cloudflareChallengeWebView != null) return@LaunchedEffect
        delay(20_000)
        if (prefetchRequestId != requestId ||
            !sameUrl(prefetchLoadUrl, target) ||
            prefetchPageBaseUrl != baseUrl ||
            pendingChapterNavigation?.url != pendingUrl ||
            pendingAutoNext != waitingForAutoNext
        ) return@LaunchedEffect
        val waitingForTarget = pendingUrl != null && sameUrl(pendingUrl, target)
        recordDiagnostic(
            "prefetch_timeout",
            target,
            "pending=$waitingForTarget auto=$waitingForAutoNext base=$baseUrl"
        )
        prefetchLoadUrl = ""
        prefetchPageBaseUrl = ""
        prefetchChapterDepth = 0
        prefetchRetryUrl = ""
        prefetchRetryCount = 0
        prefetchRetryScheduled = false
        restartPrefetchWebView()
        if (waitingForTarget) fallbackPendingChapterToActive(target)
        if (waitingForAutoNext) failPendingAutoNext(target, "timeout")
    }
    LaunchedEffect(previousWebView, previousLoadUrl) {
        val target = previousLoadUrl
        val view = previousWebView
        if (view != null && target.isNotEmpty()) {
            when {
                webViewLoadTarget(view).isNotEmpty() && !sameUrl(webViewLoadTarget(view), target) -> {
                    restartPreviousWebView()
                }
                webViewLoadTarget(view).isEmpty() -> {
                    view.stopLoading()
                    val referer = document?.sourceUrl.orEmpty().takeIf { it.isNotBlank() && !sameUrl(it, target) }
                        ?: currentUrl.takeIf { it.isNotBlank() && !sameUrl(it, target) }.orEmpty()
                    startWebViewLoad(view, target, referer)
                }
            }
        }
    }
    LaunchedEffect(
        previousLoadUrl,
        previousRequestId,
        previousPageBaseUrl,
        pendingChapterNavigation?.url,
        cloudflareChallengeWebView
    ) {
        val target = previousLoadUrl
        val requestId = previousRequestId
        val baseUrl = previousPageBaseUrl
        val pendingUrl = pendingChapterNavigation?.url
        if (target.isEmpty() || cloudflareChallengeWebView != null) return@LaunchedEffect
        delay(20_000)
        if (previousRequestId != requestId ||
            !sameUrl(previousLoadUrl, target) ||
            previousPageBaseUrl != baseUrl ||
            pendingChapterNavigation?.url != pendingUrl
        ) return@LaunchedEffect
        val pending = pendingChapterNavigation
        val fallbackTarget = pendingUrl?.takeIf {
            sameUrl(it, target) || (baseUrl.isNotEmpty() && sameUrl(it, baseUrl))
        }
        recordDiagnostic(
            "previous_timeout",
            target,
            "pending=${pending != null} fallback=${fallbackTarget != null} base=$previousPageBaseUrl"
        )
        previousLoadUrl = ""
        previousPageBaseUrl = ""
        restartPreviousWebView()
        fallbackTarget?.let(::fallbackPendingChapterToActive)
    }
    LaunchedEffect(catalogWebView, catalogLoadUrl) {
        val target = catalogLoadUrl
        val view = catalogWebView
        if (view != null && target.isNotEmpty()) {
            when {
                webViewLoadTarget(view).isNotEmpty() && !sameUrl(webViewLoadTarget(view), target) -> {
                    restartCatalogWebView()
                }
                webViewLoadTarget(view).isEmpty() -> {
                    // Gentle throttling: a full catalog can span dozens of pages and pacing the
                    // requests keeps anti-bot protection from challenging a long crawl.
                    delay(300L)
                    if (catalogLoadUrl != target || catalogWebView !== view) return@LaunchedEffect
                    view.stopLoading()
                    val referer = catalogWebView?.url.orEmpty().takeIf { it.isNotBlank() && !sameUrl(it, target) }
                        ?: currentUrl.takeIf { it.isNotBlank() && !sameUrl(it, target) }.orEmpty()
                    startWebViewLoad(view, target, referer)
                }
            }
        }
    }

    val diagnosticReport = buildDiagnosticReport(
        errorMessage = errorMessage,
        logs = diagnosticLogs,
        currentUrl = currentUrl,
        activeLoadUrl = activeLoadUrl,
        prefetchLoadUrl = prefetchLoadUrl,
        previousLoadUrl = previousLoadUrl,
        catalogLoadUrl = catalogLoadUrl,
        pendingUrl = pendingChapterNavigation?.url,
        pageMode = settings.pageMode
    )

    JingduTheme(settings.theme) {
        val windowBackground = if (screen == AppScreen.READER.name) {
            paletteFor(settings.theme).background
        } else {
            IvoryPalette.background
        }
        Box(modifier = Modifier.fillMaxSize().background(windowBackground)) {
            key(activeWebViewGeneration) {
                AndroidView(
                    modifier = if (screen == AppScreen.READER.name && cloudflareChallengeWebView === activeWebView) {
                        Modifier.fillMaxSize().zIndex(10f)
                    } else {
                        Modifier.size(1.dp).alpha(0f)
                    },
                    factory = { viewContext ->
                        createReaderWebView(
                            context = viewContext,
                            label = "active",
                            onTrace = { name, details -> recordDiagnostic("web_$name", "", details) },
                            onPayload = { view, requestToken, pageUrl, rawPayload -> activePayloadState.value(view, requestToken, pageUrl, rawPayload) },
                            onError = { view, requestToken, pageUrl, reason -> activeErrorState.value(view, requestToken, pageUrl, reason) }
                        ).also { activeWebView = it }
                    },
                    update = { activeWebView = it }
                )
            }
            key(prefetchWebViewGeneration) {
                AndroidView(
                    modifier = if (screen == AppScreen.READER.name &&
                        cloudflareChallengeWebView === prefetchWebView &&
                        (pendingAutoNext || pendingChapterNavigation != null)
                    ) {
                        Modifier.fillMaxSize().zIndex(10f)
                    } else {
                        Modifier.size(1.dp).alpha(0f)
                    },
                    factory = { viewContext ->
                        createReaderWebView(
                            context = viewContext,
                            label = "prefetch",
                            onTrace = { name, details -> recordDiagnostic("web_$name", "", details) },
                            onPayload = { view, requestToken, pageUrl, rawPayload -> prefetchPayloadState.value(view, requestToken, pageUrl, rawPayload) },
                            onError = { view, requestToken, pageUrl, reason -> prefetchErrorState.value(view, requestToken, pageUrl, reason) }
                        ).also { prefetchWebView = it }
                    },
                    update = { prefetchWebView = it }
                )
            }
            key(previousWebViewGeneration) {
                AndroidView(
                    modifier = if (screen == AppScreen.READER.name &&
                        cloudflareChallengeWebView === previousWebView &&
                        pendingChapterNavigation != null
                    ) {
                        Modifier.fillMaxSize().zIndex(10f)
                    } else {
                        Modifier.size(1.dp).alpha(0f)
                    },
                    factory = { viewContext ->
                        createReaderWebView(
                            context = viewContext,
                            label = "previous",
                            onTrace = { name, details -> recordDiagnostic("web_$name", "", details) },
                            onPayload = { view, requestToken, pageUrl, rawPayload -> previousPayloadState.value(view, requestToken, pageUrl, rawPayload) },
                            onError = { view, requestToken, pageUrl, reason -> previousErrorState.value(view, requestToken, pageUrl, reason) }
                        ).also { previousWebView = it }
                    },
                    update = { previousWebView = it }
                )
            }
            key(catalogWebViewGeneration) {
                AndroidView(
                    modifier = if (screen == AppScreen.READER.name &&
                        cloudflareChallengeWebView === catalogWebView &&
                        catalogLoadUrl.isNotEmpty()
                    ) {
                        Modifier.fillMaxSize().zIndex(10f)
                    } else {
                        Modifier.size(1.dp).alpha(0f)
                    },
                    factory = { viewContext ->
                        createReaderWebView(
                            context = viewContext,
                            label = "catalog",
                            onTrace = { name, details -> recordDiagnostic("web_$name", "", details) },
                            onPayload = { view, requestToken, pageUrl, rawPayload -> catalogPayloadState.value(view, requestToken, pageUrl, rawPayload) },
                            onError = { view, requestToken, pageUrl, reason -> catalogErrorState.value(view, requestToken, pageUrl, reason) }
                        ).also { catalogWebView = it }
                    },
                    update = { catalogWebView = it }
                )
            }

            if (screen == AppScreen.HOME.name) {
                HomeScreen(
                    address = address,
                    onAddressChange = { address = it },
                    onOpen = { openUrl(address) },
                    recentUrl = preferences.getString("last_url", null),
                    shelfCount = shelfBooks.size,
                    onOpenBookshelf = { screen = AppScreen.BOOKSHELF.name },
                    onOpenRecent = { openUrl(it) },
                    onCheckForUpdate = { requestUpdateCheck() },
                    updateChecking = updateChecking,
                    updateStatusMessage = updateStatusMessage
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
                val cachedCatalog = document?.let { current -> navigationCatalogFor(current) }
                val navigationLinks = document?.let { current ->
                    resolveReaderNavigationLinks(current, cachedCatalog)
                } ?: ReaderNavigationLinks(catalog = document?.navigation?.catalog)
                val previousChapterAvailable = document?.let { current -> hasPreviousChapter(current) } == true
                val currentSourceUrl = document?.sourceUrl.orEmpty()
                val previousChapter = navigationLinks.previous?.href
                    ?.let(::normalizeUrl)
                    ?.let(::findCached)
                    ?.let(::mergeCachedContinuation)
                    ?.takeIf { cached ->
                        cached.isUsableForReading() &&
                            !cached.isCatalog &&
                            !hasUncachedPageContinuation(cached)
                    }
                    ?.takeUnless { cached -> sameUrl(cached.sourceUrl, currentSourceUrl) }
                val nextChapter = navigationLinks.next?.href
                    ?.let(::normalizeUrl)
                    ?.let(::findCached)
                    ?.let(::mergeCachedContinuation)
                    ?.takeIf { cached ->
                        cached.isUsableForReading() &&
                            !cached.isCatalog &&
                            (settings.pageMode == PageMode.VERTICAL || !hasUncachedPageContinuation(cached))
                    }
                    ?.takeUnless { cached ->
                        sameUrl(cached.sourceUrl, currentSourceUrl) ||
                            (previousChapter != null && sameUrl(cached.sourceUrl, previousChapter.sourceUrl))
                    }
                val nextChapterReady = nextChapter != null && !hasUncachedPageContinuation(nextChapter)
                val currentBookInShelf = document?.takeUnless { it.isCatalog }?.let { current ->
                    shelfBooks.any { book -> book.key == shelfKeyForDocument(current) }
                } == true
                BackHandler {
                    saveCurrentDocumentCache(commit = true)
                    saveLatestReadingPosition(commit = true)
                    cancelReaderChapterLoads()
                    screen = if (currentBookInShelf) AppScreen.BOOKSHELF.name else AppScreen.HOME.name
                }
                ReaderScreen(
                    document = document,
                    diagnosticLog = diagnosticReport,
                     onCopyDiagnosticLog = { copyDiagnosticReport(context, diagnosticReport) },
                     onShareDiagnosticLog = { shareDiagnosticReport(context, diagnosticReport) },
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
                    navigationLinks = navigationLinks,
                    previousChapterAvailable = previousChapterAvailable,
                    catalogDocument = cachedCatalog,
                    catalogIndex = activeCatalogIndex,
                    catalogLoading = catalogLoadUrl.isNotEmpty(),
                    catalogLoadedPageCount = catalogLoadedPageCount,
                    catalogComplete = catalogComplete,
                    catalogError = catalogErrorMessage,
                    isInBookshelf = currentBookInShelf,
                    onAddToBookshelf = { addCurrentBookToShelf() },
                    onSettingsChange = { updatedSettings ->
                        if (updatedSettings.pageMode != settings.pageMode) {
                             document?.sourceUrl?.let { sourceUrl ->
                                 val latestOffset = savedReadingOffset(sourceUrl) ?: readingOffset
                                 if (latestOffset != null) {
                                     preferences.edit()
                                         .putInt(progressOffsetKey(sourceUrl), latestOffset)
                                          .remove(progressViewportKey(sourceUrl))
                                         .apply()
                                 }
                             }
                         readingOffset = null
                         chapterOpenPosition = null
                             verticalOpenIndex = null
                             verticalOpenOffset = null
                         }
                         settings = updatedSettings
                        saveSettings(preferences, updatedSettings)
                        activity?.requestedOrientation = updatedSettings.screenOrientation.toRequestedOrientation()
                    },
                    onPositionChange = { position, persist ->
                         val catalogPositionChanged = latestReadingPosition.value?.let { previous ->
                              !sameUrl(previous.sourceUrl, position.sourceUrl)
                          } ?: true
                          latestReadingPosition.value = position
                         if (persist && document?.sourceUrl?.let { current -> sameUrl(current, position.sourceUrl) } == true) {
                             readingOffset = position.textOffset
                         }
                         if (persist) {
                             val persistedDocument = findCached(position.sourceUrl)
                                 ?.takeUnless { it.isCatalog }
                                 ?: document?.takeIf { sameUrl(it.sourceUrl, position.sourceUrl) }
                             if (persistedDocument != null) {
                                 visibleDocumentForPersistence = persistedDocument
                                 preferences.edit().putString("last_url", persistedDocument.sourceUrl).apply()
                                 saveCachedReaderDocument(preferences, persistedDocument)
                             }
                         }
                         if (persist && catalogPositionChanged && activeCatalogUrl.isNotEmpty()) {
                              findCached(activeCatalogUrl)?.catalogItems?.indexOfFirst { item ->
                                  catalogItemMatches(item, position.sourceUrl, position.chapterTitle)
                              }?.takeIf { it >= 0 }?.let { index ->
                                  if (activeCatalogIndex != index) activeCatalogIndex = index
                              }
                          }
                          if (persist) saveLatestReadingPosition()
                     },
                    onNavigate = { openUrl(it) },
                     onNavigateChapter = { href, position ->
                         openChapter(href, position)
                     },
                     onContinueToChapter = { href, index, offset ->
                        openChapter(href, verticalIndex = index, verticalOffset = offset)
                    },
                    onNavigateFromCatalog = { href, catalogUrl, index ->
                        openUrl(href, catalogUrlOverride = catalogUrl, catalogIndexOverride = index)
                    },
                    onOpenCatalog = {
                        val catalogUrl = activeCatalogUrl.takeIf { it.isNotEmpty() } ?: baseCatalogUrl
                        if (catalogUrl.isNotEmpty()) startCatalogCrawl(catalogUrl)

                    },
                    onRetryCatalog = {
                        val catalogUrl = activeCatalogUrl.takeIf { it.isNotEmpty() } ?: baseCatalogUrl
                        if (catalogUrl.isNotEmpty()) startCatalogCrawl(catalogUrl, forceReload = true)
                    },
                    onClose = {
                        (visibleDocumentForPersistence ?: document)?.let { current ->
                            saveCachedReaderDocument(preferences, current, commit = true)
                        }
                        saveLatestReadingPosition(commit = true)
                        cancelReaderChapterLoads()
                        screen = if (currentBookInShelf) AppScreen.BOOKSHELF.name else AppScreen.HOME.name
                        loading = false
                    },
                    onReload = { openUrl(currentUrl, forceReload = true) },
                     onAutoNext = { requestAutoNext() },
                     onOpenNextChapter = {
                         document?.let { current ->
                             val next = nextChapterLink(current)
                             if (next != null && !sameUrl(next.href, current.sourceUrl)) {
                                 openChapter(next.href, ChapterOpenPosition.START)
                             } else {
                                 requestAutoNext()
                             }
                         }
                     }
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

private const val CLOUDFLARE_CHALLENGE_REASON = "cloudflare_challenge"

private fun isCloudflareChallengeReason(reason: String): Boolean =
    reason.startsWith(CLOUDFLARE_CHALLENGE_REASON)

private fun isCloudflareChallengeResponse(response: android.webkit.WebResourceResponse): Boolean {
    return response.responseHeaders.orEmpty().entries.any { (name, value) ->
        name.equals("Cf-Mitigated", ignoreCase = true) &&
            value?.contains("challenge", ignoreCase = true) == true
    }
}

private fun rawPayloadLooksLikeCloudflareChallenge(rawPayload: String): Boolean {
    val value = rawPayload.lowercase(Locale.ROOT)
    return value.contains("just a moment") ||
        value.contains("verify you are human") ||
        value.contains("checking your browser") ||
        value.contains("cf-turnstile") ||
        value.contains("security verification") ||
        rawPayload.contains("安全验证")
}

private fun fetchReaderHtml(url: String, referer: String = "", cookie: String = ""): String? {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    val connection = runCatching { java.net.URL(url).openConnection() as java.net.HttpURLConnection }.getOrNull()
        ?: return null
    return try {
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        if (referer.isNotBlank() && !sameUrl(referer, url)) {
            connection.setRequestProperty("Referer", referer)
        }
        if (cookie.isNotBlank()) {
            connection.setRequestProperty("Cookie", cookie)
        }
        if (connection.responseCode !in 200..399) return null
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText().takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private data class ReaderWebViewLoad(
    val token: Long,
    val target: String,
    val referer: String = ""
)

private var readerWebViewTokenCounter = 0L

private fun nextReaderWebViewToken(): Long {
    readerWebViewTokenCounter += 1L
    return readerWebViewTokenCounter
}

private fun webViewLoadToken(view: WebView?): Long = when (val tag = view?.tag) {
    is ReaderWebViewLoad -> tag.token
    is Long -> tag
    else -> 0L
}

private fun webViewLoadTarget(view: WebView?): String =
    (view?.tag as? ReaderWebViewLoad)?.target.orEmpty()

private fun webViewLoadReferer(view: WebView?): String =
    (view?.tag as? ReaderWebViewLoad)?.referer.orEmpty()

private fun startWebViewLoad(view: WebView, target: String, referer: String = "") {
    view.tag = ReaderWebViewLoad(nextReaderWebViewToken(), target, referer)
    val headers = linkedMapOf<String, String>()
    if (referer.isNotBlank() && !sameUrl(referer, target)) {
        headers["Referer"] = referer
    }
    headers["Accept"] = "text/html,application/xhtml+xml"
    headers["Accept-Language"] = "zh-CN,zh;q=0.9,en;q=0.6"
    view.loadUrl(target, headers)
}

@SuppressLint("SetJavaScriptEnabled")
private fun createReaderWebView(
    context: android.content.Context,
    label: String,
    onTrace: (String, String) -> Unit,
    onPayload: (WebView, Long, String, String) -> Boolean,
    onError: (WebView, Long, String, String) -> Unit
): WebView {
    return WebView(context).apply {
        var nativeFallbackToken = 0L
        var nativeFallbackInFlightToken = 0L
        var nativeFallbackUrl = ""
        var nativeFallbackCount = 0
        var cloudflareChallengeToken = 0L
        var acceptedPayloadToken = 0L
        var reportedErrorToken = 0L
        var blockedRedirects = 0

        fun requestHost(raw: String): String =
            runCatching { Uri.parse(raw).host?.lowercase().orEmpty() }.getOrDefault("")

        fun registrableTail(host: String): String = host.trimEnd('.').split('.').takeLast(2).joinToString(".")

        fun sameSite(left: String, right: String): Boolean {
            val leftHost = requestHost(left)
            val rightHost = requestHost(right)
            if (leftHost.isEmpty() || rightHost.isEmpty()) return false
            if (leftHost == rightHost) return true
            return registrableTail(leftHost) == registrableTail(rightHost)
        }

        // Chapter sites routinely attach pop-under scripts that redirect the hidden reader
        // WebView to ad pages; keep every reader WebView on the site it was asked to read.
        fun offTarget(rawUrl: String): Boolean {
            val target = webViewLoadTarget(this)
            if (target.isEmpty() || rawUrl.isEmpty()) return false
            val targetScheme = runCatching { Uri.parse(target).scheme?.lowercase().orEmpty() }.getOrDefault("")
            val scheme = runCatching { Uri.parse(rawUrl).scheme?.lowercase().orEmpty() }.getOrDefault("")
            if (scheme != "http" && scheme != "https") return true
            if (targetScheme != "http" && targetScheme != "https") return false
            return !sameSite(target, rawUrl)
        }

        fun guardNavigation(view: WebView, rawUrl: String): Boolean {
            if (!offTarget(rawUrl)) return false
            blockedRedirects += 1
            view.stopLoading()
            // Throttle: ad scripts can retry hundreds of times per page.
            if (blockedRedirects == 1 || blockedRedirects % 20 == 0) {
                onTrace(
                    label,
                    "blocked_offsite token=${webViewLoadToken(view)} count=$blockedRedirects target=${webViewLoadTarget(view)} attempted=$rawUrl"
                )
            }
            return true
        }

        fun reportError(view: WebView, requestToken: Long, url: String, reason: String) {
            if (requestToken == 0L || reportedErrorToken == requestToken) return
            reportedErrorToken = requestToken
            onError(view, requestToken, url, reason)
        }

        fun reportCloudflareChallenge(view: WebView, requestToken: Long, url: String, details: String) {
            if (requestToken == 0L) return
            cloudflareChallengeToken = requestToken
            view.settings.loadsImagesAutomatically = true
            view.requestFocus()
            reportError(view, requestToken, url, "$CLOUDFLARE_CHALLENGE_REASON;$details")
        }

        fun tryNativeFallback(view: WebView, url: String, requestToken: Long, reason: String) {
            if (requestToken == 0L || nativeFallbackToken == requestToken || cloudflareChallengeToken == requestToken) return
            nativeFallbackToken = requestToken
            if (url != nativeFallbackUrl) {
                nativeFallbackUrl = url
                nativeFallbackCount = 0
            }
            if (nativeFallbackCount >= 2) {
                reportError(view, requestToken, url, "$reason;native_fallback_attempts_exhausted")
                return
            }
            nativeFallbackCount += 1
            nativeFallbackInFlightToken = requestToken
            onTrace(label, "native_fallback token=$requestToken attempt=$nativeFallbackCount url=$url reason=$reason")
            val referer = webViewLoadReferer(view)
            val cookie = runCatching {
                android.webkit.CookieManager.getInstance().getCookie(url).orEmpty()
            }.getOrDefault("")
            CoroutineScope(Dispatchers.IO).launch {
                val html = fetchReaderHtml(url, referer, cookie)
                view.post {
                    if (nativeFallbackInFlightToken != requestToken || requestToken != webViewLoadToken(view)) return@post
                    nativeFallbackInFlightToken = 0L
                    if (html != null) {
                        view.stopLoading()
                        view.tag = ReaderWebViewLoad(nextReaderWebViewToken(), url, referer)
                        view.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
                    } else {
                        reportError(view, requestToken, url, "$reason;native_fallback_failed")
                    }
                }
            }
        }

        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(AndroidColor.TRANSPARENT)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        webViewClient = object : WebViewClient() {
            fun scheduleExtraction(
                view: WebView,
                url: String,
                requestToken: Long,
                delayMillis: Long,
                finalAttempt: Boolean = false
            ) {
                view.postDelayed({
                    if (requestToken != webViewLoadToken(view)) return@postDelayed
                    if (acceptedPayloadToken == requestToken) return@postDelayed
                    val currentUrl = view.url.orEmpty()
                    if (currentUrl.isNotEmpty() && !sameReaderLoadUrl(currentUrl, url)) {
                        onTrace(label, "extract_skip token=$requestToken loaded=$currentUrl")
                        return@postDelayed
                    }
                    view.evaluateJavascript(ReaderScript.extract) { rawPayload ->
                        if (onPayload(view, requestToken, url, rawPayload)) {
                            acceptedPayloadToken = requestToken
                            if (nativeFallbackInFlightToken == requestToken) nativeFallbackInFlightToken = 0L
                            onTrace(label, "accepted token=$requestToken delay=$delayMillis url=$url length=${rawPayload?.length ?: 0}")
                        } else if (finalAttempt && requestToken == webViewLoadToken(view)) {
                            onTrace(label, "rejected token=$requestToken delay=$delayMillis url=$url loaded=${view.url.orEmpty()} length=${rawPayload?.length ?: 0}")
                            view.evaluateJavascript(ReaderScript.challengeProbe) { challengePayload ->
                                if (requestToken == webViewLoadToken(view) &&
                                    rawPayloadLooksLikeCloudflareChallenge(challengePayload)
                                ) {
                                    reportCloudflareChallenge(view, requestToken, url, "page_probe=true")
                                } else if (requestToken == webViewLoadToken(view) &&
                                    nativeFallbackInFlightToken != requestToken
                                ) {
                                    tryNativeFallback(view, url, requestToken, "payload_unusable_or_not_found")
                                }
                            }
                        }
                    }
                }, delayMillis)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (guardNavigation(view, url)) return
                val requestToken = webViewLoadToken(view)
                onTrace(label, "page_started token=$requestToken url=$url")
                if (cloudflareChallengeToken == requestToken &&
                    !url.contains("__cf_chl_", ignoreCase = true)
                ) {
                    cloudflareChallengeToken = 0L
                }
                scheduleExtraction(view, url, requestToken, 1_200L)
                scheduleExtraction(view, url, requestToken, 3_500L)
                scheduleExtraction(view, url, requestToken, 8_000L, finalAttempt = true)
                view.postDelayed({
                    if (requestToken != webViewLoadToken(view)) return@postDelayed
                    if (acceptedPayloadToken == requestToken) return@postDelayed
                    val currentUrl = view.url.orEmpty()
                    if (currentUrl.isEmpty() || sameReaderLoadUrl(currentUrl, url)) {
                        view.evaluateJavascript(ReaderScript.challengeProbe) { challengePayload ->
                            if (requestToken != webViewLoadToken(view)) return@evaluateJavascript
                            if (rawPayloadLooksLikeCloudflareChallenge(challengePayload)) {
                                reportCloudflareChallenge(view, requestToken, url, "timeout_probe=true")
                            } else {
                                tryNativeFallback(view, url, requestToken, "webview_load_timeout")
                            }
                        }
                    }
                }, 7_000L)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (guardNavigation(view, url)) return
                val requestToken = webViewLoadToken(view)
                onTrace(label, "page_finished token=$requestToken url=$url")
                scheduleExtraction(view, url, requestToken, 0L)
                scheduleExtraction(view, url, requestToken, 700L)
                scheduleExtraction(view, url, requestToken, 2_000L)
                scheduleExtraction(view, url, requestToken, 5_000L, finalAttempt = true)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                return guardNavigation(view, request.url.toString())
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                guardNavigation(view, url)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    guardNavigation(view, request.url.toString())
                    onTrace(
                        label,
                        "main_frame_error token=${webViewLoadToken(view)} url=${request.url} code=${error.errorCode} description=${error.description}"
                    )
                    tryNativeFallback(
                        view,
                        request.url.toString(),
                        webViewLoadToken(view),
                        "webview_error code=${error.errorCode} description=${error.description}"
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    val requestUrl = request.url.toString()
                    val requestToken = webViewLoadToken(view)
                    guardNavigation(view, requestUrl)
                    onTrace(label, "main_frame_http_error token=$requestToken status=${errorResponse.statusCode} url=$requestUrl")
                    if (isCloudflareChallengeResponse(errorResponse)) {
                        reportCloudflareChallenge(
                            view,
                            requestToken,
                            requestUrl,
                            "status=${errorResponse.statusCode}"
                        )
                    } else {
                        tryNativeFallback(
                            view,
                            requestUrl,
                            requestToken,
                            "http_error status=${errorResponse.statusCode} reason=${errorResponse.reasonPhrase}"
                        )
                    }
                }
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
    onOpenRecent: (String) -> Unit,
    onCheckForUpdate: () -> Unit,
    updateChecking: Boolean,
    updateStatusMessage: String?
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
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onCheckForUpdate,
            enabled = !updateChecking,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(9.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (updateChecking) "检查中..." else "检查更新", fontSize = 14.sp)
        }
        updateStatusMessage?.let { message ->
            Spacer(Modifier.height(7.dp))
            Text(message, fontSize = 11.sp, color = IvoryPalette.muted)
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
    diagnosticLog: String,
    onCopyDiagnosticLog: () -> Unit,
    onShareDiagnosticLog: () -> Unit,
    settings: ReaderSettings,
    readingOffset: Int?,
    chapterOpenPosition: ChapterOpenPosition?,
    verticalOpenIndex: Int?,
    verticalOpenOffset: Int?,
    previousChapter: ReaderDocument?,
    nextChapter: ReaderDocument?,
    nextChapterReady: Boolean,
    navigationLinks: ReaderNavigationLinks,
    previousChapterAvailable: Boolean,
    catalogDocument: ReaderDocument?,
    catalogIndex: Int?,
    catalogLoading: Boolean,
    catalogLoadedPageCount: Int,
    catalogComplete: Boolean,
    catalogError: String?,
    isInBookshelf: Boolean,
    onAddToBookshelf: () -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onPositionChange: (ReadingPositionSnapshot, Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    onNavigateChapter: (String, ChapterOpenPosition) -> Unit,
    onContinueToChapter: (String, Int, Int) -> Unit,
    onNavigateFromCatalog: (String, String, Int) -> Unit,
    onOpenCatalog: () -> Unit,
    onRetryCatalog: () -> Unit,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onAutoNext: () -> Unit,
    onOpenNextChapter: () -> Unit
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
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (settings.pageMode == PageMode.HORIZONTAL) Color.Transparent else palette.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .centerTapDetector {
                    if (!menuVisible && panel == ReaderPanel.NONE.name && !loading && document?.isCatalog != true) {
                        toggleMenu()
                    }
                }
        ) {
            when {
                loading && document == null -> LoadingView(palette)
                errorMessage != null -> ErrorView(
                     message = errorMessage,
                     palette = palette,
                     diagnosticLog = diagnosticLog,
                     onCopyDiagnosticLog = onCopyDiagnosticLog,
                     onShareDiagnosticLog = onShareDiagnosticLog,
                     onReload = onReload
                 )
                document == null -> LoadingView(palette)
                document.isCatalog -> CatalogView(
                    document = document,
                    palette = palette,
                    settings = settings,
                    catalogLoading = catalogLoading,
                    catalogLoadedPageCount = catalogLoadedPageCount,
                    catalogComplete = catalogComplete,
                    catalogError = catalogError,
                    onRetryCatalog = onRetryCatalog,
                    onNavigate = onNavigate,
                     onCopyDiagnosticLog = onCopyDiagnosticLog,
                     onShareDiagnosticLog = onShareDiagnosticLog
                )
                else -> ChapterView(
                    document = document,
                    palette = palette,
                    settings = settings,
                    readingOffset = readingOffset,
                    chapterOpenPosition = chapterOpenPosition,
                    previousChapter = previousChapter,
                    nextChapter = nextChapter,
                    nextChapterReady = nextChapterReady,
                    navigationLinks = navigationLinks,
                    verticalOpenIndex = verticalOpenIndex,
                    verticalOpenOffset = verticalOpenOffset,
                    onPositionChange = onPositionChange,
                    onContinueToChapter = onContinueToChapter,
                    onNavigateChapter = onNavigateChapter,
                    onAutoNext = onAutoNext
                )
            }
            if (menuVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            menuVisible = false
                            panel = ReaderPanel.NONE.name
                        }
                )
                ReaderMenu(
                    document = document,
                    palette = palette,
                    onPreviousChapter = {
                        menuVisible = false
                        navigationLinks.previous?.let { onNavigateChapter(it.href, ChapterOpenPosition.START) }
                    },
                    onNextChapter = {
                        menuVisible = false
                        onOpenNextChapter()
                    },
                    nextChapterAvailable = navigationLinks.next != null,
                    previousChapterAvailable = previousChapterAvailable,
                    onCopyDiagnosticLog = onCopyDiagnosticLog,
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
                     loadedPageCount = catalogLoadedPageCount,
                     complete = catalogComplete,
                     errorMessage = catalogError,
                    loading = catalogLoading,
                    palette = palette,
                    settings = settings,
                    onCopyDiagnosticLog = onCopyDiagnosticLog,
                     onShareDiagnosticLog = onShareDiagnosticLog,
                     onNavigate = { href, index ->
                        panel = ReaderPanel.NONE.name
                        onNavigateFromCatalog(href, catalogDocument?.sourceUrl.orEmpty(), index)
                    },
                onRetryCatalog = onRetryCatalog,
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
    nextChapterAvailable: Boolean,
    previousChapterAvailable: Boolean,
    onCopyDiagnosticLog: () -> Unit,
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
                enabled = previousChapterAvailable,
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
                enabled = nextChapterAvailable,
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
            MenuAction(
                icon = Icons.Default.Refresh,
                label = "诊断",
                enabled = true,
                palette = palette,
                onClick = onCopyDiagnosticLog
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
    loadedPageCount: Int,
    complete: Boolean,
    errorMessage: String?,
    onNavigate: (String, Int) -> Unit,
    onRetryCatalog: () -> Unit,
    onCopyDiagnosticLog: () -> Unit,
    onShareDiagnosticLog: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentUrl = currentDocument?.sourceUrl.orEmpty()
    val currentTitle = currentDocument?.title.orEmpty()
    val catalogItemCount = catalogDocument?.catalogItems?.size ?: 0
    val catalogLastItemKey = catalogDocument?.catalogItems?.lastOrNull()?.let { readerUrlKey(it.href) }.orEmpty()
    val matchedIndex = remember(
        catalogDocument?.sourceUrl,
        catalogItemCount,
        catalogLastItemKey,
        currentUrl,
        currentTitle
    ) {
        catalogDocument?.catalogItems?.indexOfFirst { item ->
            catalogItemMatches(item, currentUrl, currentTitle)
        } ?: -1
    }
    val indexedPosition = catalogIndex?.takeIf { it in 0 until catalogItemCount }
    val currentIndex = indexedPosition ?: matchedIndex
    val listState = rememberLazyListState()
    var positionedKey by remember(catalogDocument?.sourceUrl) { mutableStateOf("") }
    LaunchedEffect(
        catalogDocument?.sourceUrl,
        catalogItemCount,
        catalogLastItemKey,
        currentUrl,
        currentTitle,
        currentIndex
    ) {
        if (currentIndex >= 0) {
            val targetKey = "${catalogDocument?.sourceUrl}:$currentIndex"
            if (positionedKey != targetKey) {
                val targetIndex = currentIndex + 1
                listState.scrollToItem(targetIndex)
                val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                if (targetItem != null) {
                    val viewportHeight = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset)
                        .coerceAtLeast(targetItem.size)
                    val centerOffset = -((viewportHeight - targetItem.size) / 2)
                    listState.scrollToItem(targetIndex, centerOffset)
                }
                positionedKey = targetKey
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
                                if (currentIndex >= 0) {
                                    val item = catalogDocument.catalogItems[currentIndex]
                                    val number = chapterNumber(item.label)
                                    if (number != null) "当前位置：第${number}章" else "当前位置：${item.label}"
                                } else "请选择章节",
                                color = palette.muted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                                when {
                                    loading -> Text("正在自动加载全部目录（已加载 ${loadedPageCount} 页）", color = palette.muted, fontSize = 10.sp)
                                    errorMessage != null -> Column {
                                        Text(errorMessage, color = palette.muted, fontSize = 10.sp)
                                        TextButton(
                                            onClick = onRetryCatalog,
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("重试自动加载", color = palette.accent, fontSize = 10.sp)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                onClick = onCopyDiagnosticLog,
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(3.dp))
                                                Text("复制日志", color = palette.accent, fontSize = 10.sp)
                                            }
                                            TextButton(
                                                onClick = onShareDiagnosticLog,
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(3.dp))
                                                Text("分享日志", color = palette.accent, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                    complete -> Text("目录已全部加载", color = palette.muted, fontSize = 10.sp)
                                }
                            }
                        }
                        itemsIndexed(
                            catalogDocument.catalogItems,
                            key = { _, item -> "drawer_${readerUrlKey(item.href).ifEmpty { item.href.trim() }}" }
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
                                    catalogNumberLabel(item.label),
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
                    }
                    loading -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("目录正在自动加载（已加载 ${loadedPageCount} 页）", color = palette.muted, fontSize = 13.sp)
                    }
                    else -> Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(errorMessage ?: "目录暂时无法读取", color = palette.muted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetryCatalog) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("重试自动加载")
                        }
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
        val inCenter = start.x in (size.width * ReaderMenuTapStartFraction)..(size.width * ReaderMenuTapEndFraction)
        if (!moved && !consumed && inCenter) onTap()
    }
}

private fun Modifier.verticalBoundaryGestureDetector(
    key: Any,
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,
    onUserScroll: () -> Unit,
    onSwipeBackward: () -> Unit,
    onSwipeForward: () -> Unit
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val start = down.position
        var last = start
        var moved = false
        var finished = false
        while (!finished) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            event.changes.forEach { change ->
                last = change.position
                if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
            }
            finished = event.changes.none { it.pressed }
        }
        val delta = last - start
        val verticalMovement = moved && kotlin.math.abs(delta.y) >= kotlin.math.abs(delta.x)
        if (verticalMovement) onUserScroll()
        if (verticalMovement && kotlin.math.abs(delta.y) >= size.height * 0.12f) {
            if (delta.y < 0f && !canScrollForward()) onSwipeForward()
            if (delta.y > 0f && !canScrollBackward()) onSwipeBackward()
        }
    }
}

private fun Modifier.horizontalPageGestureDetector(
    key: Any,
    currentPage: () -> Int,
    onUserGesture: () -> Unit,
    onTap: (tappedLeft: Boolean) -> Unit,
    onSwipe: (swipedRight: Boolean, pageAtDown: Int, passedSnapThreshold: Boolean) -> Unit
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
        val horizontalSwipe = moved && kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)
        if (horizontalSwipe) {
            onUserGesture()
            onSwipe(
                delta.x > 0f,
                pageAtDown,
                kotlin.math.abs(delta.x) >= size.width * HorizontalPageSnapPositionalThreshold
            )
        } else if (!moved && !consumed) {
            when {
                start.x < size.width * ReaderMenuTapStartFraction -> {
                    onUserGesture()
                    onTap(true)
                }
                start.x > size.width * ReaderMenuTapEndFraction -> {
                    onUserGesture()
                    onTap(false)
                }
            }
        }
    }
}

@Composable
private fun CatalogView(
    document: ReaderDocument,
    palette: ReaderPalette,
    settings: ReaderSettings,
    catalogLoading: Boolean,
    catalogLoadedPageCount: Int,
    catalogComplete: Boolean,
    catalogError: String?,
    onRetryCatalog: () -> Unit,
    onNavigate: (String) -> Unit,
    onCopyDiagnosticLog: () -> Unit,
    onShareDiagnosticLog: () -> Unit
) {
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("jingdu", 0) }
    var positionRestored by remember(document.sourceUrl) { mutableStateOf(false) }
    LaunchedEffect(document.sourceUrl) {
        positionRestored = false
        val saved = preferences.getInt(progressKey(document.sourceUrl), 0)
        listState.scrollToItem(min(saved, max(0, document.catalogItems.size)))
        positionRestored = true
    }
    LaunchedEffect(listState, document.sourceUrl) {
        snapshotFlow {
            positionRestored to listState.firstVisibleItemIndex
        }
            .distinctUntilChanged()
            .collectLatest { (restored, index) ->
                if (restored) preferences.edit().putInt(progressKey(document.sourceUrl), index).apply()
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Text("章节目录", color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(document.title, color = palette.ink, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("${document.catalogItems.size} 个章节", color = palette.muted, fontSize = 12.sp)
            when {
                catalogLoading -> Text("正在自动加载全部目录（已加载 ${catalogLoadedPageCount} 页）", color = palette.muted, fontSize = 11.sp)
                catalogError != null -> {
                    Text(catalogError, color = palette.muted, fontSize = 11.sp)
                    TextButton(
                        onClick = onRetryCatalog,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("重试自动加载", color = palette.accent, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = onCopyDiagnosticLog,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("复制日志", color = palette.accent, fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = onShareDiagnosticLog,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("分享日志", color = palette.accent, fontSize = 11.sp)
                        }
                    }
                }
                catalogComplete -> Text("目录已全部加载", color = palette.muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(22.dp))
        }
        itemsIndexed(document.catalogItems, key = { _, item -> "catalog_${readerUrlKey(item.href).ifEmpty { item.href.trim() }}" }) { index, item ->
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
    navigationLinks: ReaderNavigationLinks,
    onPositionChange: (ReadingPositionSnapshot, Boolean) -> Unit,
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
            nextChapterReady = nextChapterReady,
            navigationLinks = navigationLinks,
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
    nextChapterReady: Boolean,
    navigationLinks: ReaderNavigationLinks,
    onPositionChange: (ReadingPositionSnapshot, Boolean) -> Unit,
    onContinueToChapter: (String, Int, Int) -> Unit,
    onNavigateChapter: (String, ChapterOpenPosition) -> Unit,
    onAutoNext: () -> Unit
) {
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("jingdu", 0) }
    val previousParagraphOffsets = remember(previousChapter?.sourceUrl, previousChapter?.paragraphs) {
        previousChapter?.let { paragraphStartOffsets(it) }.orEmpty()
    }
    val currentParagraphOffsets = remember(document.sourceUrl, document.paragraphs) { paragraphStartOffsets(document) }
    val nextParagraphOffsets = remember(nextChapter?.sourceUrl, nextChapter?.paragraphs) {
        nextChapter?.let { paragraphStartOffsets(it) }.orEmpty()
    }
    var positionRestored by remember(document.sourceUrl) { mutableStateOf(false) }
    var skipInitialPositionSave by remember(document.sourceUrl) { mutableStateOf(false) }
    val previousItemCount = previousChapter?.let { it.paragraphs.size + 1 } ?: 0
    val currentStartIndex = previousItemCount
    val currentEndIndex = currentStartIndex + document.paragraphs.size
    val nextStartIndex = currentEndIndex + 1
    val totalItemCount = nextChapter?.let { nextStartIndex + it.paragraphs.size + 1 } ?: (currentEndIndex + 1)
    var suppressBoundaryNavigation by remember(document.sourceUrl) { mutableStateOf(false) }
    var userScrollGeneration by remember { mutableStateOf(0) }

    fun positionForViewport(viewport: VerticalViewport): ReadingPositionSnapshot? {
        val index = viewport.firstVisible
        if (index < 0) return null
        val target = when {
            nextChapter != null && index >= nextStartIndex -> Triple(
                nextChapter,
                (index - nextStartIndex).coerceIn(0, nextChapter.paragraphs.size),
                nextParagraphOffsets
            )
            index >= currentStartIndex -> Triple(
                document,
                (index - currentStartIndex).coerceIn(0, document.paragraphs.size),
                currentParagraphOffsets
            )
            previousChapter != null -> Triple(
                previousChapter,
                index.coerceIn(0, previousChapter.paragraphs.size),
                previousParagraphOffsets
            )
            else -> Triple(document, 0, currentParagraphOffsets)
        }
        val targetDocument = target.first
        val relativeIndex = target.second
        val offsets = target.third
        val paragraphIndex = (relativeIndex - 1).coerceIn(0, max(0, targetDocument.paragraphs.size - 1))
        return ReadingPositionSnapshot(
            sourceUrl = targetDocument.sourceUrl,
            chapterTitle = targetDocument.title,
            textOffset = offsets.getOrElse(paragraphIndex) { 0 },
            progressIndex = relativeIndex,
            progressIndexKey = progressKey(targetDocument.sourceUrl),
            viewportOffset = viewport.firstOffset
        )
    }

    LaunchedEffect(
        document.sourceUrl,
        chapterOpenPosition,
        verticalOpenIndex,
        verticalOpenOffset
    ) {
        positionRestored = false
        skipInitialPositionSave = true
        val saved = preferences.getInt(progressKey(document.sourceUrl), 0)
        val savedOffset = preferences.getInt(progressOffsetKey(document.sourceUrl), -1)
        val savedViewportOffset = preferences.getInt(progressViewportKey(document.sourceUrl), -1)
        val anchorOffset = savedOffset.takeIf { it >= 0 } ?: readingOffset
        val hasContinuationPosition = chapterOpenPosition == null && verticalOpenIndex != null
        val hasSavedViewportPosition = chapterOpenPosition == null && verticalOpenIndex == null && savedViewportOffset >= 0
        val relativeIndex = if (hasContinuationPosition) {
            verticalOpenIndex.coerceIn(0, document.paragraphs.size)
        } else {
            when (chapterOpenPosition) {
                ChapterOpenPosition.START -> 0
                ChapterOpenPosition.END -> document.paragraphs.size
                null -> if (hasSavedViewportPosition) {
                    saved.coerceIn(0, document.paragraphs.size)
                } else if (anchorOffset != null) {
                    (paragraphIndexForOffset(document, anchorOffset) + 1).coerceIn(0, document.paragraphs.size)
                } else {
                    saved.coerceIn(0, document.paragraphs.size)
                }
            }
        }
        val target = currentStartIndex + relativeIndex
        val offset = when {
            hasContinuationPosition -> verticalOpenOffset?.coerceAtLeast(0) ?: 0
            hasSavedViewportPosition -> savedViewportOffset.coerceAtLeast(0)
            else -> 0
        }
        val boundedTarget = target.coerceIn(0, max(0, totalItemCount - 1))
        suppressBoundaryNavigation = true
        var restored = false
        try {
            for (attempt in 0 until 8) {
                listState.scrollToItem(boundedTarget, offset)
                delay(40)
                val layout = listState.layoutInfo
                val targetVisible = layout.visibleItemsInfo.any { it.index == boundedTarget }
                val atEnd = !listState.canScrollForward
                if (listState.firstVisibleItemIndex == boundedTarget || (targetVisible && atEnd)) {
                    restored = true
                    break
                }
            }
        } finally {
            suppressBoundaryNavigation = false
            if (restored) positionRestored = true
        }
    }

    LaunchedEffect(
        listState,
        document.sourceUrl,
        document.paragraphs,
        previousChapter?.sourceUrl,
        nextChapter?.sourceUrl,
        currentStartIndex,
        nextStartIndex
    ) {
        snapshotFlow {
            Triple(
                positionRestored,
                suppressBoundaryNavigation,
                listState.isScrollInProgress
            ) to Pair(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
            .distinctUntilChanged()
            .collectLatest { (flags, _) ->
                val (restored, suppressed, scrolling) = flags
                if (!restored || suppressed || scrolling) return@collectLatest
                delay(120)
                if (!positionRestored || suppressBoundaryNavigation || listState.isScrollInProgress) {
                    return@collectLatest
                }
                if (skipInitialPositionSave) {
                    skipInitialPositionSave = false
                    return@collectLatest
                }
                val settledViewport = VerticalViewport(
                    scrolling = false,
                    firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1,
                    firstOffset = listState.firstVisibleItemScrollOffset,
                    lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                    canScrollBackward = listState.canScrollBackward,
                    canScrollForward = listState.canScrollForward
                )
                positionForViewport(settledViewport)?.let { onPositionChange(it, true) }
            }
    }
    LaunchedEffect(
        listState,
        document.sourceUrl,
        document.paragraphs,
        document.navigation.previous?.href,
        document.navigation.next?.href,
        previousChapter?.sourceUrl,
        previousChapter?.paragraphs,
        nextChapter?.sourceUrl,
        nextChapter?.paragraphs,
        currentStartIndex,
        currentEndIndex,
        nextStartIndex
    ) {
        var lastHandledUserScrollGeneration = userScrollGeneration
        snapshotFlow {
            Pair(
                Triple(userScrollGeneration, positionRestored, suppressBoundaryNavigation),
                VerticalViewport(
                    scrolling = listState.isScrollInProgress,
                    firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1,
                    firstOffset = listState.firstVisibleItemScrollOffset,
                    lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                    canScrollBackward = listState.canScrollBackward,
                    canScrollForward = listState.canScrollForward
                )
            )
        }
            .distinctUntilChanged()
            .collectLatest { (flags, viewport) ->
                val (scrollGeneration, restored, suppressed) = flags
                if (!restored || suppressed) {
                    return@collectLatest
                }
                val firstVisible = viewport.firstVisible
                if (firstVisible < 0 || viewport.scrolling) return@collectLatest
                if (scrollGeneration <= lastHandledUserScrollGeneration) return@collectLatest
                lastHandledUserScrollGeneration = scrollGeneration
                if (!viewport.canScrollForward) {
                    when {
                        nextChapter == null || !nextChapterReady -> {
                            if (viewport.lastVisible >= currentEndIndex) {
                                // The next chapter or its remaining page chain is still loading.
                                onAutoNext()
                            }
                        }
                        firstVisible >= nextStartIndex -> {
                            // Promote the attached chapter at its visible position so the list does not jump.
                            onContinueToChapter(
                                nextChapter.sourceUrl,
                                (firstVisible - nextStartIndex).coerceIn(0, nextChapter.paragraphs.size),
                                viewport.firstOffset
                            )
                        }
                    }
                    return@collectLatest
                }
                if (!viewport.canScrollBackward && previousChapter != null) {
                    navigationLinks.previous?.let {
                        onContinueToChapter(it.href, 0, 0)
                    }
                }
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalBoundaryGestureDetector(
                key = listOf(
                     document.sourceUrl,
                     document.navigation.previous?.href,
                     document.navigation.next?.href,
                     previousChapter?.sourceUrl,
                     nextChapter?.sourceUrl
                 ),
                canScrollBackward = { listState.canScrollBackward },
                canScrollForward = { listState.canScrollForward },
                onUserScroll = { userScrollGeneration += 1 },
                onSwipeBackward = {
                    if (previousChapter == null) navigationLinks.previous?.let { onNavigateChapter(it.href, ChapterOpenPosition.END) }
                },
                onSwipeForward = { if (nextChapter == null || !nextChapterReady) onAutoNext() }
            ),
        contentPadding = PaddingValues(start = 22.dp, top = 68.dp, end = 22.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        if (previousChapter != null) {
            item(key = "${previousChapter.sourceUrl}:header") { ChapterHeader(previousChapter, palette, titleMaxLines = Int.MAX_VALUE) }
            itemsIndexed(previousChapter.paragraphs, key = { index, _ -> "${previousChapter.sourceUrl}:paragraph:$index" }) { _, paragraph ->
                ChapterParagraph(paragraph, palette, settings)
            }
        }
        item(key = "${document.sourceUrl}:header") { ChapterHeader(document, palette, titleMaxLines = Int.MAX_VALUE) }
        itemsIndexed(document.paragraphs, key = { index, _ -> "${document.sourceUrl}:paragraph:$index" }) { _, paragraph ->
            ChapterParagraph(paragraph, palette, settings)
        }
        if (nextChapter != null) {
            item(key = "${nextChapter.sourceUrl}:header") { ChapterHeader(nextChapter, palette, titleMaxLines = Int.MAX_VALUE) }
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
    onPositionChange: (ReadingPositionSnapshot, Boolean) -> Unit,
    onNavigateChapter: (String, ChapterOpenPosition) -> Unit,
    onAutoNext: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(palette.background)
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val inheritedTextStyle = LocalTextStyle.current
        val bodyTextStyle = inheritedTextStyle.merge(horizontalPageTextStyle(settings))
        val headerTextStyles = HorizontalHeaderTextStyles(
            label = inheritedTextStyle.merge(TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)),
            title = inheritedTextStyle.merge(
                TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
            ),
            metadata = inheritedTextStyle.merge(TextStyle(fontSize = 12.sp))
        )
        val contentWidthPx = with(density) {
            maxWidth.toPx().roundToInt() - HorizontalPageHorizontalPadding.toPx().roundToInt() * 2
        }.coerceAtLeast(1)
        val pageIndicatorHeightPx = with(density) { HorizontalPageIndicatorHeight.toPx().roundToInt() }
        val contentHeightPx = with(density) {
            maxHeight.toPx().roundToInt() -
                pageIndicatorHeightPx -
                HorizontalPageTopPadding.toPx().roundToInt() -
                HorizontalPageBottomPadding.toPx().roundToInt() -
                HorizontalPagePaginationSafetyPx
        }.coerceAtLeast(1)
        val headerHeightPx = remember(document.sourceUrl, document.title, contentWidthPx, headerTextStyles) {
            horizontalHeaderHeightPx(document, textMeasurer, density, contentWidthPx, headerTextStyles)
        }
        val nextHeaderHeightPx = remember(nextChapter?.sourceUrl, nextChapter?.title, contentWidthPx, headerTextStyles) {
            nextChapter?.let {
                horizontalHeaderHeightPx(it, textMeasurer, density, contentWidthPx, headerTextStyles)
            } ?: 0
        }
        val pages = remember(
            document.sourceUrl,
            document.paragraphs,
            settings.fontSize,
            settings.lineHeight,
            contentWidthPx,
            contentHeightPx,
            headerHeightPx,
            bodyTextStyle
        ) {
            paginateChapterPages(document, textMeasurer, density, contentWidthPx, contentHeightPx, headerHeightPx, bodyTextStyle)
        }
        val hasNextChapter = document.navigation.next != null
        val nextPages = remember(
            nextChapter?.sourceUrl,
            nextChapter?.paragraphs,
            settings.fontSize,
            settings.lineHeight,
            contentWidthPx,
            contentHeightPx,
            nextHeaderHeightPx,
            bodyTextStyle
        ) {
            nextChapter
                ?.takeIf { nextChapterReady && !it.isCatalog && it.paragraphs.isNotEmpty() }
                ?.let {
                    paginateChapterPages(it, textMeasurer, density, contentWidthPx, contentHeightPx, nextHeaderHeightPx, bodyTextStyle)
                }
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
        var horizontalUserScrollGeneration by remember(document.sourceUrl) { mutableStateOf(0) }

        LaunchedEffect(document.sourceUrl, chapterOpenPosition, pages.size, settings.fontSize, settings.lineHeight, contentWidthPx, contentHeightPx) {
            positionRestored = false
            val savedPage = preferences.getInt(horizontalProgressKey, 0)
            val savedOffset = preferences.getInt(offsetKey, -1)
            val anchorOffset = savedOffset.takeIf { it >= 0 } ?: readingOffset
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
        LaunchedEffect(pagerState, horizontalProgressKey, offsetKey, pages, nextPages, showNextContent) {
            snapshotFlow { Triple(positionRestored, pagerState.currentPage, pages.size) }
                .distinctUntilChanged()
                .collectLatest { (restored, page, _) ->
                    if (!restored) return@collectLatest
                    val inNextContent = showNextContent && page >= pages.size
                    val progressDocument = if (inNextContent) nextChapter!! else document
                    val progressPages = if (inNextContent) nextPages else pages
                    val contentPage = (if (inNextContent) page - pages.size else page)
                        .coerceIn(0, progressPages.lastIndex)
                    val textOffset = progressPages[contentPage].startOffset
                    onPositionChange(
                        ReadingPositionSnapshot(
                            sourceUrl = progressDocument.sourceUrl,
                            chapterTitle = progressDocument.title,
                            textOffset = textOffset,
                            progressIndex = contentPage,
                            progressIndexKey = progressKey(progressDocument.sourceUrl) + "_horizontal",
                            viewportOffset = null
                        ),
                        true
                    )
                }
        }

        LaunchedEffect(
            pagerState,
            document.sourceUrl,
            document.navigation.previous?.href,
            document.navigation.next?.href,
            pages.size,
            nextPages.size,
            showNextContent
        ) {
            var lastHandledUserGesture = horizontalUserScrollGeneration
            snapshotFlow {
                Triple(
                    horizontalUserScrollGeneration,
                    pagerState.currentPage,
                    pagerState.isScrollInProgress
                ) to positionRestored
            }
                .distinctUntilChanged()
                .collectLatest { (gesture, restored) ->
                    val (generation, page, scrolling) = gesture
                    if (!restored || scrolling || generation <= lastHandledUserGesture) return@collectLatest
                    val enteredNextContent = showNextContent && page >= pages.size
                    val reachedCurrentEnd = !showNextContent && page >= pages.lastIndex
                    if (!enteredNextContent && !reachedCurrentEnd) {
                        return@collectLatest
                    }
                    lastHandledUserGesture = generation
                    onAutoNext()
                }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                HorizontalPager(
                state = pagerState,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapPositionalThreshold = HorizontalPageSnapPositionalThreshold
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalPageGestureDetector(
                        key = listOf(
                             document.sourceUrl,
                             totalPages,
                             settings.horizontalTapMode,
                             document.navigation.previous?.href,
                             document.navigation.next?.href
                         ),
                        currentPage = { pagerState.currentPage },
                        onUserGesture = { horizontalUserScrollGeneration += 1 },
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
                                    Unit
                                }
                            }
                        },
                        onSwipe = { swipedRight, pageAtDown, passedSnapThreshold ->
                            val target = if (swipedRight) pageAtDown - 1 else pageAtDown + 1
                            when {
                                target in 0 until totalPages &&
                                    passedSnapThreshold && pagerState.currentPage == pageAtDown -> {
                                    pagerScope.launch {
                                        if (pagerState.isScrollInProgress) {
                                            snapshotFlow { pagerState.isScrollInProgress }.first { scrolling -> !scrolling }
                                        }
                                        if (pagerState.currentPage == pageAtDown) {
                                            pagerState.animateScrollToPage(target)
                                        }
                                    }
                                }
                                swipedRight && pageAtDown == 0 -> {
                                    document.navigation.previous?.let { onNavigateChapter(it.href, ChapterOpenPosition.END) }
                                }
                                !swipedRight && pageAtDown == pages.lastIndex && !showNextContent -> {
                                    Unit
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
                        headerHeightPx = nextHeaderHeightPx,
                        headerTextStyles = headerTextStyles,
                        textStyle = bodyTextStyle,
                        palette = palette
                    )
                } else {
                    HorizontalChapterPage(
                        document = document,
                        text = pages[page].text,
                        page = page,
                        headerHeightPx = headerHeightPx,
                        headerTextStyles = headerTextStyles,
                        textStyle = bodyTextStyle,
                        palette = palette
                    )
                }
            }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HorizontalPageIndicatorHeight),
                contentAlignment = Alignment.Center
            ) {
                if (pagerState.currentPage < pages.size) {
                    Text(
                        "${pagerState.currentPage + 1} / ${pages.size}",
                        color = palette.muted,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

private val HorizontalPageHorizontalPadding = 22.dp
private val HorizontalPageTopPadding = 64.dp
private val HorizontalPageBottomPadding = 42.dp
private val HorizontalPageIndicatorHeight = 24.dp
private const val HorizontalPageSnapPositionalThreshold = 0.05f
private const val HorizontalPagePaginationSafetyPx = 2
private val HorizontalPageTextBottomSafety = 1.dp

private fun horizontalHeaderHeightPx(
    document: ReaderDocument,
    textMeasurer: TextMeasurer,
    density: Density,
    contentWidthPx: Int,
    textStyles: HorizontalHeaderTextStyles
): Int {
    fun measuredHeight(text: String, style: TextStyle): Int = textMeasurer.measure(
        text = AnnotatedString(text),
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = true,
        maxLines = Int.MAX_VALUE,
        constraints = Constraints(maxWidth = contentWidthPx.coerceAtLeast(1))
    ).size.height

    val labelHeight = measuredHeight("静读 · 当前章节", textStyles.label)
    val titleHeight = measuredHeight(document.title.ifEmpty { " " }, textStyles.title)
    val metadataHeight = measuredHeight(
        "${document.paragraphs.size} 段 · ${document.wordCount} 字",
        textStyles.metadata
    )
    val spacing = with(density) {
        listOf(12.dp, 10.dp, 19.dp, 2.dp, 13.dp)
            .sumOf { it.toPx().roundToInt() }
    }
    val safety = with(density) { 2.dp.toPx().roundToInt().coerceAtLeast(1) }
    return labelHeight + titleHeight + metadataHeight + spacing + safety
}

@Composable
private fun HorizontalChapterPage(
    document: ReaderDocument,
    text: String,
    page: Int,
    headerHeightPx: Int,
    headerTextStyles: HorizontalHeaderTextStyles,
    textStyle: TextStyle,
    palette: ReaderPalette
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
            val headerHeight = with(androidx.compose.ui.platform.LocalDensity.current) { headerHeightPx.toDp() }
            Box(modifier = Modifier.height(headerHeight)) {
                ChapterHeader(
                    document = document,
                    palette = palette,
                    titleMaxLines = Int.MAX_VALUE,
                    textStyles = headerTextStyles
                )
            }
        }
        Text(
            text = text.ifEmpty { " " },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = HorizontalPageTextBottomSafety),
            color = palette.ink,
            style = textStyle,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip
        )
    }
}

private fun horizontalPageTextStyle(settings: ReaderSettings): TextStyle = TextStyle(
    fontSize = settings.fontSize.sp,
    lineHeight = (settings.fontSize * settings.lineHeight).sp,
    fontFamily = FontFamily.Serif,
    lineBreak = LineBreak.Paragraph
)

private fun paginateChapterPages(
    document: ReaderDocument,
    textMeasurer: TextMeasurer,
    density: Density,
    contentWidthPx: Int,
    contentHeightPx: Int,
    headerHeightPx: Int,
    textStyle: TextStyle
): List<ChapterPage> {
    val style = textStyle
    val fullText = normalizeReaderParagraphs(document.paragraphs)
        .joinToString("\n\n")
        .trim()
    val textSafetyPx = with(density) {
        HorizontalPageTextBottomSafety.toPx().roundToInt().coerceAtLeast(1)
    }
    val firstHeight = (contentHeightPx - headerHeightPx - textSafetyPx).coerceAtLeast(1)
    val normalHeight = (contentHeightPx - textSafetyPx).coerceAtLeast(1)
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
    rebalanceTrailingSparsePages(
        pages = pages,
        textMeasurer = textMeasurer,
        style = style,
        contentWidthPx = contentWidthPx,
        firstPageHeightPx = firstHeight,
        normalPageHeightPx = normalHeight
    )

    return pages
}

private const val TrailingRebalanceMaxPages = 4
private const val TrailingRebalanceHeightFraction = 0.60f
private const val TrailingRebalanceBacktrackCharacters = 0

private fun rebalanceTrailingSparsePages(
    pages: MutableList<ChapterPage>,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    contentWidthPx: Int,
    firstPageHeightPx: Int,
    normalPageHeightPx: Int
) {
    if (pages.size < 2) return
    val lastIndex = pages.lastIndex
    fun pageHeight(index: Int): Int = textMeasurer.measure(
        text = AnnotatedString(pages[index].text.ifEmpty { " " }),
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = true,
        maxLines = Int.MAX_VALUE,
        constraints = Constraints(maxWidth = contentWidthPx.coerceAtLeast(1))
    ).size.height
    fun availableHeight(index: Int): Int =
        if (index == 0) firstPageHeightPx else normalPageHeightPx
    fun isSparse(index: Int): Boolean =
        pageHeight(index) < (availableHeight(index) * TrailingRebalanceHeightFraction).toInt()

    if (!isSparse(lastIndex)) return
    var firstTailIndex = (lastIndex - 1).coerceAtLeast(0)
    while (
        firstTailIndex > 0 &&
        lastIndex - firstTailIndex + 1 < TrailingRebalanceMaxPages &&
        isSparse(firstTailIndex - 1)
    ) {
        firstTailIndex -= 1
    }
    if (firstTailIndex == 0 && pages.size > TrailingRebalanceMaxPages) return

    val oldTailPageCount = lastIndex - firstTailIndex + 1
    val combinedText = pages.subList(firstTailIndex, pages.size)
        .joinToString(separator = "") { it.text }
        .trim()
    if (combinedText.isEmpty()) return

    fun tryReflow(targetPageCount: Int, minimumPageCharacters: Int): List<ChapterPage>? {
        val reflowed = mutableListOf<ChapterPage>()
        var remainder = combinedText
        var consumed = 0
        for (localPage in 0 until targetPageCount) {
            if (remainder.isEmpty()) return null
            val absolutePage = firstTailIndex + localPage
            val availableHeight = availableHeight(absolutePage)
            val fit = fitTextPrefix(remainder, textMeasurer, style, contentWidthPx, availableHeight)
            val remainingPageCount = targetPageCount - localPage - 1
            val maxLength = if (remainingPageCount == 0) {
                fit
            } else {
                minOf(fit, remainder.length - minimumPageCharacters * remainingPageCount)
            }
            if (maxLength < 1) return null
            val (pageText, rest) = splitTextAtBoundary(
                remainder,
                maxLength,
                maxBacktrackCharacters = TrailingRebalanceBacktrackCharacters
            )
            if (pageText.isBlank() || fitTextPrefix(pageText, textMeasurer, style, contentWidthPx, availableHeight) < pageText.length) {
                return null
            }
            reflowed += ChapterPage(
                text = pageText,
                startOffset = pages[firstTailIndex].startOffset + consumed
            )
            consumed += remainder.length - rest.length
            remainder = rest
        }
        return reflowed.takeIf { remainder.isEmpty() }
    }

    val minimumCharacterTargets = listOf(64, 48, 32, 20, 12, 1)
    var reflowed: List<ChapterPage>? = null
    for (targetPageCount in 1..oldTailPageCount) {
        val targets = if (targetPageCount == 1) listOf(0) else minimumCharacterTargets
        for (minimumPageCharacters in targets) {
            reflowed = tryReflow(targetPageCount, minimumPageCharacters)
            if (reflowed != null) break
        }
        if (reflowed != null) break
    }
    if (reflowed == null) return

    pages.subList(firstTailIndex, pages.size).clear()
    pages.addAll(reflowed)
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

    // Use the last complete visual line so a page never ends halfway through a line.
    val fullLayout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = true,
        maxLines = Int.MAX_VALUE,
        constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1))
    )
    var lastFittingLine = -1
    for (line in 0 until fullLayout.lineCount) {
        if (fullLayout.getLineBottom(line) <= heightPx) {
            lastFittingLine = line
        } else {
            break
        }
    }
    if (lastFittingLine >= 0) {
        var candidateLine = lastFittingLine
        while (candidateLine >= 0) {
            val candidate = fullLayout.getLineEnd(candidateLine, visibleEnd = true)
                .coerceIn(1, text.length)
            if (fits(candidate)) return candidate
            candidateLine -= 1
        }
    }

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

private fun splitTextAtBoundary(
    text: String,
    requestedLength: Int,
    maxBacktrackCharacters: Int = 0
): Pair<String, String> {
    if (text.isEmpty()) return "" to ""
    var cut = requestedLength.coerceIn(1, text.length)
    val searchStart = if (maxBacktrackCharacters <= 0) {
        cut
    } else {
        (cut - maxBacktrackCharacters).coerceAtLeast(1)
    }
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
private fun ChapterHeader(
    document: ReaderDocument,
    palette: ReaderPalette,
    titleMaxLines: Int = 2,
    textStyles: HorizontalHeaderTextStyles? = null
) {
    val inheritedTextStyle = LocalTextStyle.current
    val resolvedStyles = textStyles ?: HorizontalHeaderTextStyles(
        label = inheritedTextStyle.merge(TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)),
        title = inheritedTextStyle.merge(
            TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
        ),
        metadata = inheritedTextStyle.merge(TextStyle(fontSize = 12.sp))
    )
    Column {
        Text("静读 · 当前章节", color = palette.accent, style = resolvedStyles.label)
        Spacer(Modifier.height(12.dp))
        Text(
            document.title,
            color = palette.ink,
            style = resolvedStyles.title,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "${document.paragraphs.size} 段 · ${document.wordCount} 字",
            color = palette.muted,
            style = resolvedStyles.metadata
        )
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
private fun ErrorView(
    message: String,
    palette: ReaderPalette,
    diagnosticLog: String,
    onCopyDiagnosticLog: () -> Unit,
    onShareDiagnosticLog: () -> Unit,
    onReload: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                message,
                color = palette.ink,
                fontSize = 15.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "已记录 ${diagnosticLog.lineSequence().count { it.contains(" [") }} 条诊断事件，仅包含加载状态和网页地址，不包含正文内容",
                color = palette.muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyDiagnosticLog,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(7.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("复制日志")
                }
                Button(
                    onClick = onShareDiagnosticLog,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(7.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("分享日志")
                }
            }
            Spacer(Modifier.height(10.dp))
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
    val candidate = if (
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
    ) value else "https://$value"
    return runCatching {
        val uri = android.net.Uri.parse(candidate)
        if (uri.scheme?.lowercase() == "http" || uri.scheme?.lowercase() == "https") uri.toString() else ""
    }.getOrDefault("")
}

private fun cacheKey(raw: String): String = readerUrlKey(raw)

private fun sameUrl(first: String, second: String): Boolean {
    val left = cacheKey(first)
    val right = cacheKey(second)
    return left.isNotEmpty() && left == right
}

private fun sameReaderLoadUrl(first: String, second: String): Boolean {
    if (sameUrl(first, second)) return true
    val left = runCatching { Uri.parse(normalizeUrl(first)) }.getOrNull() ?: return false
    val right = runCatching { Uri.parse(normalizeUrl(second)) }.getOrNull() ?: return false
    if (!left.scheme.equals(right.scheme, ignoreCase = true) ||
        !left.host.equals(right.host, ignoreCase = true) ||
        left.encodedPath != right.encodedPath
    ) return false

    fun stableQuery(uri: Uri): List<String> = uri.queryParameterNames
        .filterNot { it.startsWith("__cf_chl_", ignoreCase = true) }
        .sorted()
        .map { name -> "$name=${uri.getQueryParameter(name).orEmpty()}" }
    return stableQuery(left) == stableQuery(right)
}

private fun catalogItemMatches(item: ReaderLink, sourceUrl: String, chapterTitle: String): Boolean {
    return sameReaderLoadUrl(item.href, sourceUrl) ||
        (chapterTitle.isNotBlank() && sameChapter(item.label, chapterTitle))
}

private fun sameChapter(first: String, second: String): Boolean {
    val firstNumber = chapterNumber(first)
    val secondNumber = chapterNumber(second)
    if (firstNumber != null && secondNumber != null && firstNumber != secondNumber) return false
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

private fun catalogNumberLabel(label: String): String = chapterNumber(label)?.let { "第${it}章" } ?: "章节"

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

private fun progressViewportKey(url: String): String = "progress_viewport_" + url.hashCode().toUInt().toString(16)

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
