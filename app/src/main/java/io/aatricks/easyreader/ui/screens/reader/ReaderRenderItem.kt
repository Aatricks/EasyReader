package io.aatricks.easyreader.ui.screens.reader

import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.ui.components.readerImageSliceCount
import io.aatricks.easyreader.ui.viewmodel.stableContentElementKey

internal sealed interface RenderPayload {
    data class Element(val element: ContentElement) : RenderPayload
    data class Tile(
        val imageUrl: String,
        val pageUrl: String,
        val sliceAspect: Float,
        val tileIndex: Int,
        val tileCount: Int
    ) : RenderPayload
}

internal data class ReaderRenderItem(
    val key: String,
    val sourceElementIndex: Int,
    val payload: RenderPayload,
    val tileIndex: Int? = null,
    val tileCount: Int? = null
)

internal fun buildReaderRenderItems(
    content: ChapterContent,
    isManhwa: Boolean,
    screenWidthPx: Int,
    dimensionResolver: (String) -> Pair<Int, Int>? = { null }
): List<ReaderRenderItem> {
    if (content.paragraphs.isEmpty()) return emptyList()
    val result = ArrayList<ReaderRenderItem>()
    content.paragraphs.forEachIndexed { sourceIndex, element ->
        val elementKey = stableContentElementKey(content.url, sourceIndex, element)
        if (element is ContentElement.Image && isManhwa && element.side == ContentElement.Image.Side.FULL) {
            val resolved = dimensionResolver(element.url)
            val imgW = resolved?.first ?: element.width
            val imgH = resolved?.second ?: element.height
            val sliceCount = readerImageSliceCount(screenWidthPx, imgW, imgH)
            if (sliceCount > 1 && imgW > 0 && imgH > 0) {
                val sliceAspect = imgW.toFloat() / (imgH.toFloat() / sliceCount)
                for (t in 0 until sliceCount) {
                    val tileKey = "$elementKey#$t/$sliceCount"
                    result.add(
                        ReaderRenderItem(
                            key = tileKey,
                            sourceElementIndex = sourceIndex,
                            payload = RenderPayload.Tile(
                                imageUrl = element.url,
                                pageUrl = content.url,
                                sliceAspect = sliceAspect,
                                tileIndex = t,
                                tileCount = sliceCount
                            ),
                            tileIndex = t,
                            tileCount = sliceCount
                        )
                    )
                }
                return@forEachIndexed
            }
        }
        result.add(
            ReaderRenderItem(
                key = elementKey,
                sourceElementIndex = sourceIndex,
                payload = RenderPayload.Element(element),
                tileIndex = null,
                tileCount = null
            )
        )
    }
    return result
}

internal fun findRenderIndexForSource(
    renderItems: List<ReaderRenderItem>,
    sourceIndex: Int,
    sourceOffsetFraction: Float
): Pair<Int, Float> {
    val matchingItems = renderItems.filter { it.sourceElementIndex == sourceIndex }
    return when {
        renderItems.isEmpty() -> 0 to 0f
        matchingItems.isEmpty() -> fallbackRenderPosition(renderItems, sourceIndex)
        matchingItems.first().tileCount?.let { it > 1 } == true -> {
            val firstRenderIndex = renderItems.indexOf(matchingItems.first())
            tiledRenderPosition(firstRenderIndex, matchingItems.first().tileCount!!, sourceOffsetFraction)
        }
        else -> renderItems.indexOf(matchingItems.first()) to sourceOffsetFraction.coerceIn(0f, 1f)
    }
}

private fun fallbackRenderPosition(renderItems: List<ReaderRenderItem>, sourceIndex: Int): Pair<Int, Float> {
    val maxSourceIndex = renderItems.last().sourceElementIndex
    val clampedSource = sourceIndex.coerceIn(0, maxSourceIndex)
    val fallback = renderItems.indexOfFirst { it.sourceElementIndex >= clampedSource }
    return (if (fallback >= 0) fallback else renderItems.lastIndex) to 0f
}

private fun tiledRenderPosition(firstRenderIndex: Int, tileCount: Int, sourceOffsetFraction: Float): Pair<Int, Float> {
    val clampedFraction = sourceOffsetFraction.coerceIn(0f, 1f)
    val targetTileIndex = (clampedFraction * tileCount).toInt().coerceIn(0, tileCount - 1)
    return (firstRenderIndex + targetTileIndex) to (clampedFraction * tileCount - targetTileIndex)
}

internal fun findSourcePositionForRender(
    renderItems: List<ReaderRenderItem>,
    renderIndex: Int,
    localOffsetFraction: Float
): Pair<Int, Float> {
    return if (renderItems.isEmpty()) {
        0 to 0f
    } else {
        val item = renderItems[renderIndex.coerceIn(0, renderItems.lastIndex)]
        val localFraction = localOffsetFraction.coerceIn(0f, 1f)
        val sourceFraction = if (item.tileIndex != null && item.tileCount != null && item.tileCount > 1) {
            (item.tileIndex + localFraction) / item.tileCount.toFloat()
        } else {
            localFraction
        }
        item.sourceElementIndex to sourceFraction
    }
}
