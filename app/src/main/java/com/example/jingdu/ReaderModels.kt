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
    val catalog: ReaderLink?
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

fun parseReaderPayload(raw: String): ReaderDocument? {
    val payload = runCatching {
        val value = JSONTokener(raw).nextValue()
        if (value is String) value else return null
    }.getOrNull() ?: return null
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val navigation = json.optJSONObject("navigation")
    return ReaderDocument(
        sourceUrl = json.optString("sourceUrl"),
        title = json.optString("title", "未识别标题"),
        paragraphs = json.optStringList("paragraphs"),
        catalogItems = json.optLinkList("catalogItems"),
        catalogPages = json.optLinkList("catalogPages"),
        navigation = ReaderNavigation(
            previous = navigation?.optLink("previous"),
            next = navigation?.optLink("next"),
            catalog = navigation?.optLink("catalog")
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
            values.optJSONObject(index)?.toReaderLink()?.let(::add)
        }
    }
}

private fun JSONObject.optLink(name: String): ReaderLink? = optJSONObject(name)?.toReaderLink()

private fun JSONObject.toReaderLink(): ReaderLink? {
    val label = optString("label").trim()
    val href = optString("href").trim()
    return if (label.isNotEmpty() && href.isNotEmpty()) ReaderLink(label, href) else null
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
                        title = item.optString("title", "未命名书籍").trim().ifEmpty { "未命名书籍" },
                        catalogUrl = item.optString("catalogUrl").trim(),
                        lastReadUrl = lastReadUrl,
                        lastChapterTitle = item.optString("lastChapterTitle").trim(),
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
