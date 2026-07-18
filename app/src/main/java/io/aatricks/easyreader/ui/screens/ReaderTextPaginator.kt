package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.ContentElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.math.floor

private const val PAGINATION_FRAME_BUDGET_NANOS = 8_000_000L
private const val LINKED_HASH_MAP_LOAD_FACTOR = 0.75f

internal data class TextMeasureKey(
    val content: String,
    val availableWidthPx: Int,
    val fontSizeSp: Float,
    val lineHeightPx: Float,
    val fontFamily: String
)

internal class ReaderTextLineCache(private val maxSize: Int) {
    private val entries = object : LinkedHashMap<TextMeasureKey, List<Int>>(
        maxSize,
        LINKED_HASH_MAP_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextMeasureKey, List<Int>>?): Boolean =
            size > maxSize
    }

    fun getOrMeasure(key: TextMeasureKey, measure: () -> List<Int>): List<Int> =
        entries[key] ?: measure().also { entries[key] = it }
}

internal fun paginationElementsWithoutImageDimensions(elements: List<ContentElement>): List<ContentElement> =
    elements.map(ContentElement::withoutImageDimensions)

internal fun paginationElementsForMode(
    isPagedMode: Boolean,
    elements: List<ContentElement>
): List<ContentElement> = if (isPagedMode) {
    paginationElementsWithoutImageDimensions(elements)
} else {
    emptyList()
}

private fun ContentElement.withoutImageDimensions(): ContentElement = when (this) {
    is ContentElement.Image -> if (width == 0 && height == 0) this else copy(width = 0, height = 0)
    is ContentElement.ImageGroup -> copy(images = images.map { it.copy(width = 0, height = 0) })
    is ContentElement.PageContent -> copy(elements = elements.map(ContentElement::withoutImageDimensions))
    else -> this
}

internal data class ReaderPagePosition(
    val sourceIndex: Int,
    val sourceOffsetFraction: Float
)

internal data class ReaderTextFragment(
    val text: String,
    val sourceIndex: Int,
    val sourceOffsetFraction: Float
)

internal sealed interface ReaderPage {
    val position: ReaderPagePosition

    data class Text(
        val fragments: List<ReaderTextFragment>,
        override val position: ReaderPagePosition
    ) : ReaderPage

    data class Element(
        val element: ContentElement,
        override val position: ReaderPagePosition
    ) : ReaderPage
}

internal fun paginateReaderContent(
    elements: List<ContentElement>,
    pageHeightPx: Float,
    lineHeightPx: Float,
    paragraphSpacingPx: Float,
    lineEndsFor: (String) -> List<Int>
): List<ReaderPage> {
    if (pageHeightPx <= 0f || lineHeightPx <= 0f) return elements.asDedicatedReaderPages()

    val pages = mutableListOf<ReaderPage>()
    val accumulator = TextPageAccumulator(
        pageHeightPx = pageHeightPx,
        lineHeightPx = lineHeightPx,
        paragraphSpacingPx = paragraphSpacingPx,
        pages = pages
    )

    elements.forEachIndexed { sourceIndex, element ->
        if (element is ContentElement.Text) {
            accumulator.addParagraph(
                text = element.content,
                sourceIndex = sourceIndex,
                lineEnds = lineEndsFor(element.content)
            )
        } else {
            accumulator.flush()
            pages += ReaderPage.Element(
                element = element,
                position = ReaderPagePosition(sourceIndex, 0f)
            )
        }
    }
    accumulator.flush()
    return pages
}

internal suspend fun paginateReaderContentForMode(
    isPagedMode: Boolean,
    request: ReaderPaginationRequest,
    runtime: PaginationRuntime = PaginationRuntime()
): List<ReaderPage> {
    if (!isPagedMode) return emptyList()
    return paginateReaderContentCancellable(request, runtime)
}

internal fun fallbackReaderPagesForMode(
    isPagedMode: Boolean,
    elements: List<ContentElement>
): List<ReaderPage> = if (isPagedMode) {
    paginateReaderContent(
        elements = elements,
        pageHeightPx = 0f,
        lineHeightPx = 0f,
        paragraphSpacingPx = 0f,
        lineEndsFor = { emptyList() }
    )
} else {
    emptyList()
}

internal data class ReaderPaginationRequest(
    val elements: List<ContentElement>,
    val pageHeightPx: Float,
    val lineHeightPx: Float,
    val paragraphSpacingPx: Float,
    val lineEndsFor: (String) -> List<Int>
)

internal data class PaginationRuntime(
    val timeProvider: () -> Long = System::nanoTime,
    val yieldForFrame: suspend () -> Unit = { yield() }
)

internal suspend fun paginateReaderContentCancellable(
    request: ReaderPaginationRequest,
    runtime: PaginationRuntime = PaginationRuntime()
): List<ReaderPage> {
    if (request.pageHeightPx <= 0f || request.lineHeightPx <= 0f) {
        return request.elements.asDedicatedReaderPages()
    }

    val pages = mutableListOf<ReaderPage>()
    val accumulator = TextPageAccumulator(
        pageHeightPx = request.pageHeightPx,
        lineHeightPx = request.lineHeightPx,
        paragraphSpacingPx = request.paragraphSpacingPx,
        pages = pages
    )
    var chunkStartedAt = runtime.timeProvider()

    request.elements.forEachIndexed { sourceIndex, element ->
        currentCoroutineContext().ensureActive()
        if (element is ContentElement.Text) {
            accumulator.addParagraph(element.content, sourceIndex, request.lineEndsFor(element.content))
        } else {
            accumulator.flush()
            pages += ReaderPage.Element(element, ReaderPagePosition(sourceIndex, 0f))
        }
        if (runtime.timeProvider() - chunkStartedAt >= PAGINATION_FRAME_BUDGET_NANOS) {
            runtime.yieldForFrame()
            currentCoroutineContext().ensureActive()
            chunkStartedAt = runtime.timeProvider()
        }
    }
    accumulator.flush()
    return pages
}

internal fun readerPageIndexForPosition(
    pages: List<ReaderPage>,
    sourceIndex: Int,
    sourceOffsetFraction: Float
): Int {
    if (pages.isEmpty()) return 0
    val targetFraction = sourceOffsetFraction.coerceIn(0f, 1f)
    val matchingIndex = pages.indexOfLast { page ->
        page.position.sourceIndex < sourceIndex ||
            (page.position.sourceIndex == sourceIndex &&
                page.position.sourceOffsetFraction <= targetFraction)
    }
    return matchingIndex.coerceAtLeast(0)
}

private fun List<ContentElement>.asDedicatedReaderPages(): List<ReaderPage> =
    mapIndexed { index, element ->
        ReaderPage.Element(
            element = element,
            position = ReaderPagePosition(index, 0f)
        )
    }

private class TextPageAccumulator(
    private val pageHeightPx: Float,
    private val lineHeightPx: Float,
    private val paragraphSpacingPx: Float,
    private val pages: MutableList<ReaderPage>
) {
    private val fragments = mutableListOf<ReaderTextFragment>()
    private var usedHeightPx = 0f

    fun addParagraph(text: String, sourceIndex: Int, lineEnds: List<Int>) {
        var lineIndex = 0
        var characterOffset = 0
        while (lineIndex < lineEnds.size) {
            val spacingPx = if (fragments.isEmpty()) 0f else paragraphSpacingPx
            val availableHeightPx = pageHeightPx - usedHeightPx - spacingPx
            var fittingLines = floor(availableHeightPx / lineHeightPx).toInt()
            if (fittingLines <= 0) {
                if (fragments.isNotEmpty()) {
                    flush()
                    continue
                }
                fittingLines = 1
            }

            val linesToTake = fittingLines.coerceAtMost(lineEnds.size - lineIndex)
            val endOffset = lineEnds[lineIndex + linesToTake - 1]
            fragments += ReaderTextFragment(
                text = text.substring(characterOffset, endOffset),
                sourceIndex = sourceIndex,
                sourceOffsetFraction = characterOffset.toFloat() / text.length
            )
            usedHeightPx += spacingPx + linesToTake * lineHeightPx
            characterOffset = endOffset
            lineIndex += linesToTake

            if (lineIndex < lineEnds.size) flush()
        }
    }

    fun flush() {
        if (fragments.isEmpty()) return
        val first = fragments.first()
        pages += ReaderPage.Text(
            fragments = fragments.toList(),
            position = ReaderPagePosition(first.sourceIndex, first.sourceOffsetFraction)
        )
        fragments.clear()
        usedHeightPx = 0f
    }
}
