package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.ContentElement
import kotlin.math.floor

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
