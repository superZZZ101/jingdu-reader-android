package com.example.jingdu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the directory drawer behaviour the reader asked for: opening it loads the first page,
 * reaching the bottom asks for a small batch more, and the freshly appended pages must never yank
 * the list back to the chapter currently being read.
 */
@RunWith(AndroidJUnit4::class)
class CatalogDrawerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun catalogDocument(count: Int): ReaderDocument = ReaderDocument(
        sourceUrl = "https://example.com/book/",
        title = "测试目录",
        paragraphs = emptyList(),
        catalogItems = (1..count).map { number ->
            ReaderLink(label = "第${number}章 标题", href = "https://example.com/book/$number.html")
        },
        catalogPages = emptyList(),
        navigation = ReaderNavigation(previous = null, next = null, catalog = null)
    )

    private fun currentDocument(): ReaderDocument = ReaderDocument(
        sourceUrl = "https://example.com/book/5.html",
        title = "第5章 标题",
        paragraphs = listOf("正文"),
        catalogItems = emptyList(),
        catalogPages = emptyList(),
        navigation = ReaderNavigation(
            previous = ReaderLink("上一章", "https://example.com/book/4.html"),
            next = ReaderLink("下一章", "https://example.com/book/6.html"),
            catalog = ReaderLink("目录", "https://example.com/book/")
        )
    )

    private fun scrollCatalogTo(itemIndex: Int) {
        // The drawer's own LazyColumn is the scrollable that carries a scroll-to-index action.
        composeRule.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(itemIndex)
        composeRule.waitForIdle()
    }

    @Test
    fun loadingNextPagesKeepsTheListWhereItWas() {
        var itemCount by mutableStateOf(100)
        var requested by mutableStateOf(0)
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CatalogDrawer(
                    currentDocument = currentDocument(),
                    catalogDocument = catalogDocument(itemCount),
                    catalogIndex = 4,
                    loadedPageCount = 1,
                    complete = false,
                    errorMessage = null,
                    loading = false,
                    palette = paletteFor(ReaderTheme.IVORY),
                    settings = ReaderSettings(),
                    onNavigate = { _, _ -> },
                    onRetryCatalog = {},
                    onCopyDiagnosticLog = {},
                    onShareDiagnosticLog = {},
                    onClose = {},
                    openGeneration = 1,
                    onReachCatalogEnd = {
                        // One batch is enough: the list grows while the drawer stays open.
                        if (requested == 0) {
                            requested = 1
                            itemCount = 200
                        }
                    }
                )
            }
        }

        // Opening the drawer lands on the chapter being read and fetches nothing yet.
        composeRule.onNodeWithText("第5章 标题").assertIsDisplayed()
        composeRule.onNodeWithText("第100章 标题").assertIsNotDisplayed()
        assertEquals(0, requested)

        scrollCatalogTo(100)
        composeRule.waitUntil(timeoutMillis = 5_000) { requested == 1 }
        composeRule.waitForIdle()

        // The appended batch landed in the open drawer, and the list kept the reader's place: the
        // entries around the end of the old batch stay on screen instead of the camera snapping
        // back to chapter 5 at the top of the book.
        val visibleAfterAppend = visibleChapterLabels()
        assertTrue(
            "expected chapter 100 to stay visible, saw $visibleAfterAppend",
            visibleAfterAppend.any { it == "第100章" }
        )
        composeRule.onNodeWithText("第5章 标题").assertIsNotDisplayed()
    }

    // Each row renders its chapter number twice (the number column plus the title), so a row is
    // collected as "第100章第100章". The prefix is what identifies the chapter.
    private fun visibleChapterLabels(): List<String> {
        val labels = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }
                ?.takeIf { it.startsWith("第") && it.endsWith("章 标题") }
                ?.let { labels += it.substringBefore("章") + "章" }
            node.children.forEach(::walk)
        }
        walk(composeRule.onRoot().fetchSemanticsNode())
        return labels
    }

    @Test
    fun reopeningTheDrawerReturnsToTheCurrentChapter() {
        var generation by mutableStateOf(1)
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CatalogDrawer(
                    currentDocument = currentDocument(),
                    catalogDocument = catalogDocument(100),
                    catalogIndex = 4,
                    loadedPageCount = 1,
                    complete = true,
                    errorMessage = null,
                    loading = false,
                    palette = paletteFor(ReaderTheme.IVORY),
                    settings = ReaderSettings(),
                    onNavigate = { _, _ -> },
                    onRetryCatalog = {},
                    onCopyDiagnosticLog = {},
                    onShareDiagnosticLog = {},
                    onClose = {},
                    openGeneration = generation,
                    onReachCatalogEnd = {}
                )
            }
        }

        composeRule.onNodeWithText("第5章 标题").assertIsDisplayed()
        scrollCatalogTo(100)
        composeRule.onNodeWithText("第100章 标题").assertIsDisplayed()
        composeRule.onNodeWithText("第5章 标题").assertIsNotDisplayed()

        // Reopening the drawer (new open generation) is the one case that re-centers.
        generation = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第5章 标题").assertIsDisplayed()
    }
}
