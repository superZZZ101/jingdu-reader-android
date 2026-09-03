package com.example.jingdu

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ReaderLink(
    val label: String,
    val href: String
)

data class ReaderNavigation(
    val previous: ReaderLink?,
    val next: ReaderLink?,
    val catalog: ReaderLink?,
    val previousPage: ReaderLink? = null,
    val nextPage: ReaderLink? = null
)

data class ReaderDocument(
    val sourceUrl: String,
    val title: String,
    val paragraphs: List<String>,
    val catalogItems: List<ReaderLink>,
    val catalogPages: List<ReaderLink>,
    val navigation: ReaderNavigation
) {
    val isCatalog: Boolean
        get() = catalogItems.isNotEmpty()

    val wordCount: Int
        get() = paragraphs.sumOf { text ->
            text.count { it in '\u3400'..'\u9fff' } +
                Regex("[A-Za-z0-9]").findAll(text).count()
        }
}

fun mergeCatalogDocuments(base: ReaderDocument, page: ReaderDocument): ReaderDocument {
    val items = LinkedHashMap<String, ReaderLink>()
    val pages = LinkedHashMap<String, ReaderLink>()
    fun addLinks(target: LinkedHashMap<String, ReaderLink>, links: List<ReaderLink>) {
        links.forEach { link ->
            val key = link.href.trim().substringBefore('#').trimEnd('/').ifEmpty { link.href.trim() }
            if (!target.containsKey(key)) target[key] = link
        }
    }
    addLinks(items, base.catalogItems)
    addLinks(items, page.catalogItems)
    addLinks(pages, base.catalogPages)
    addLinks(pages, page.catalogPages)
    return base.copy(
        catalogItems = items.values.toList(),
        catalogPages = pages.values.toList(),
        navigation = base.navigation.copy(
            catalog = base.navigation.catalog ?: page.navigation.catalog
        )
    )
}

fun parseReaderPayload(raw: String): ReaderDocument? {
    val payload = runCatching {
        val value = JSONTokener(raw).nextValue()
        if (value is String) value else return null
    }.getOrNull() ?: return null
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val navigation = json.optJSONObject("navigation")
    val paragraphs = json.optStringList("paragraphs").filterNot(::isReaderNoiseParagraph)
    val catalogItems = json.optLinkList("catalogItems")
    val catalogPages = json.optLinkList("catalogPages")
    val rawTitle = json.optString("title", "未识别标题")
    return ReaderDocument(
        sourceUrl = json.optString("sourceUrl"),
        title = if (catalogItems.isEmpty()) cleanChapterTitle(rawTitle) else rawTitle,
        paragraphs = paragraphs,
        catalogItems = catalogItems,
        catalogPages = catalogPages,
        navigation = ReaderNavigation(
            previous = navigation?.optLink("previous"),
            next = navigation?.optLink("next"),
            catalog = navigation?.optLink("catalog"),
            previousPage = navigation?.optLink("previousPage"),
            nextPage = navigation?.optLink("nextPage")
        )
    )
}

private fun JSONObject.optStringList(name: String): List<String> {
    val values = optJSONArray(name) ?: return emptyList()
    return buildList(values.length()) {
        for (index in 0 until values.length()) {
            val value = values.optString(index).trim()
            if (value.isNotEmpty()) add(value)
        }
    }
}

private fun JSONObject.optLinkList(name: String): List<ReaderLink> {
    val values = optJSONArray(name) ?: return emptyList()
    return buildList(values.length()) {
        for (index in 0 until values.length()) {
            values.optJSONObject(index)?.toReaderLink()?.let { link ->
                add(if (name == "catalogItems") link.copy(label = cleanChapterTitle(link.label)) else link)
            }
        }
    }
}

private fun JSONObject.optLink(name: String): ReaderLink? = optJSONObject(name)?.toReaderLink()

private fun JSONObject.toReaderLink(): ReaderLink? {
    val label = optString("label").trim()
    val href = optString("href").trim()
    return if (label.isNotEmpty() && href.isNotEmpty()) ReaderLink(label, href) else null
}

fun cleanChapterTitle(raw: String): String {
    var title = raw.trim()
        .replace(Regex("\\s*[（(]\\s*\\d+\\s*/\\s*\\d+\\s*[）)]\\s*$"), "")
        .trim()
    val underscore = title.indexOf('_')
    if (underscore > 0) {
        val suffix = title.substring(underscore + 1)
        if (suffix.contains(Regex("小说|阅读|免费|网站|网"))) title = title.substring(0, underscore).trim()
    }
    title = title.replace(Regex("\\s*[-|｜·•]\\s*[^-|｜·•]*(?:小说|阅读|免费|网站|网).*$", RegexOption.IGNORE_CASE), "")
    return title.trim().ifEmpty { raw.trim().ifEmpty { "未识别标题" } }
}

private fun isReaderNoiseParagraph(raw: String): Boolean {
    val text = raw.trim()
    val normalized = text.replace("/", "").replace("\\", "")
    return normalized.matches(
        Regex("^(上一章|下一章|上一页|下一页|上页|下页|前页|后页|书页/?目录|目录|章节目录|加入书签|收藏本书|投推荐票|章节报错).{0,280}$")
    ) || (normalized.startsWith("上一章") || normalized.startsWith("下一章")) && normalized.length < 280 ||
        normalized.contains("阅读体验极差") ||
        normalized.startsWith("如果被") && normalized.contains("阅读模式") ||
        normalized.matches(Regex("^(网页无法打开|无法加载此网页|无法连接到该网站|This site can.?t be reached|ERR_[A-Z_]+).*$", RegexOption.IGNORE_CASE))
}

data class ShelfBook(
    val key: String,
    val title: String,
    val catalogUrl: String,
    val lastReadUrl: String,
    val lastChapterTitle: String,
    val updatedAt: Long
)

private const val SHELF_BOOKS_KEY = "shelf_books"
private const val READER_DOCUMENT_CACHE_KEY = "reader_current_document_cache"

private fun ReaderLink.toJson(): JSONObject = JSONObject().apply {
    put("label", label)
    put("href", href)
}

private fun JSONObject.putLink(name: String, link: ReaderLink?) {
    put(name, link?.toJson() ?: JSONObject.NULL)
}

private fun ReaderDocument.toCacheJson(): JSONObject = JSONObject().apply {
    put("sourceUrl", sourceUrl)
    put("title", title)
    put("paragraphs", JSONArray().also { values -> paragraphs.forEach(values::put) })
    put("catalogItems", JSONArray().also { values -> catalogItems.forEach { values.put(it.toJson()) } })
    put("catalogPages", JSONArray().also { values -> catalogPages.forEach { values.put(it.toJson()) } })
    put("navigation", JSONObject().apply {
        putLink("previous", navigation.previous)
        putLink("next", navigation.next)
        putLink("catalog", navigation.catalog)
        putLink("previousPage", navigation.previousPage)
        putLink("nextPage", navigation.nextPage)
    })
}

private fun JSONObject.toCachedReaderDocument(): ReaderDocument? {
    val sourceUrl = optString("sourceUrl").trim()
    if (sourceUrl.isEmpty()) return null
    val navigation = optJSONObject("navigation")
    val paragraphs = optStringList("paragraphs").filterNot(::isReaderNoiseParagraph)
    val catalogItems = optLinkList("catalogItems")
    val catalogPages = optLinkList("catalogPages")
    val rawTitle = optString("title", "未识别标题")
    return ReaderDocument(
        sourceUrl = sourceUrl,
        title = if (catalogItems.isEmpty()) cleanChapterTitle(rawTitle) else rawTitle,
        paragraphs = paragraphs,
        catalogItems = catalogItems,
        catalogPages = catalogPages,
        navigation = ReaderNavigation(
            previous = navigation?.optLink("previous"),
            next = navigation?.optLink("next"),
            catalog = navigation?.optLink("catalog"),
            previousPage = navigation?.optLink("previousPage"),
            nextPage = navigation?.optLink("nextPage")
        )
    )
}

fun loadCachedReaderDocument(preferences: SharedPreferences): ReaderDocument? {
    val raw = preferences.getString(READER_DOCUMENT_CACHE_KEY, null).orEmpty()
    if (raw.isBlank()) return null
    return runCatching { JSONObject(raw).toCachedReaderDocument() }.getOrNull()
}

fun saveCachedReaderDocument(
    preferences: SharedPreferences,
    document: ReaderDocument,
    commit: Boolean = false
) {
    if (document.sourceUrl.isBlank() || document.isCatalog || document.paragraphs.isEmpty()) return
    val editor = preferences.edit().putString(READER_DOCUMENT_CACHE_KEY, document.toCacheJson().toString())
    if (commit) editor.commit() else editor.apply()
}

fun loadShelfBooks(preferences: SharedPreferences): List<ShelfBook> {
    val raw = preferences.getString(SHELF_BOOKS_KEY, null).orEmpty()
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val json = JSONArray(raw)
        buildList(json.length()) {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val key = item.optString("key").trim()
                val lastReadUrl = item.optString("lastReadUrl").trim()
                if (key.isEmpty() || lastReadUrl.isEmpty()) continue
                add(
                    ShelfBook(
                        key = key,
                        title = cleanChapterTitle(item.optString("title", "未命名书籍")).ifEmpty { "未命名书籍" },
                        catalogUrl = item.optString("catalogUrl").trim(),
                        lastReadUrl = lastReadUrl,
                        lastChapterTitle = cleanChapterTitle(item.optString("lastChapterTitle")),
                        updatedAt = item.optLong("updatedAt", 0L)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

fun saveShelfBooks(preferences: SharedPreferences, books: List<ShelfBook>) {
    val json = JSONArray()
    books.forEach { book ->
        json.put(
            JSONObject().apply {
                put("key", book.key)
                put("title", book.title)
                put("catalogUrl", book.catalogUrl)
                put("lastReadUrl", book.lastReadUrl)
                put("lastChapterTitle", book.lastChapterTitle)
                put("updatedAt", book.updatedAt)
            }
        )
    }
    preferences.edit().putString(SHELF_BOOKS_KEY, json.toString()).apply()
}
