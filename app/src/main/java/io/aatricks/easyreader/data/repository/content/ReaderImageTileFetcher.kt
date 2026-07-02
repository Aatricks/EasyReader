package io.aatricks.easyreader.data.repository.content

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.util.UrlSanitizer
import java.io.File

/**
 * Model for one vertical slice of a tall web image. Rendering a single bitmap taller than the GPU
 * max texture size forces a slow software-draw path on the RenderThread (measured ~5–11ms/frame
 * during fast up/down scroll). Splitting the strip into texture-sized slices — each a normal
 * hardware bitmap, region-decoded straight from the cached file so the giant bitmap is never
 * materialised — keeps every on-screen piece cheap to draw while preserving native width (no blur).
 */
data class ReaderImageTile(
    val imageUrl: String,
    val pageUrl: String,
    val sliceIndex: Int,
    val sliceCount: Int
)

class ReaderImageTileFetcher(
    private val tile: ReaderImageTile,
    private val contentRepository: ContentRepository,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val slice = resolveFile()?.let { decodeSlice(it) } ?: return null
        return ImageFetchResult(
            image = slice.bitmap.asImage(),
            isSampled = slice.sampled,
            dataSource = DataSource.DISK
        )
    }

    private class SliceBitmap(val bitmap: android.graphics.Bitmap, val sampled: Boolean)

    private fun decodeSlice(file: File): SliceBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        val count = tile.sliceCount.coerceAtLeast(1)
        val index = tile.sliceIndex.coerceIn(0, count - 1)
        val top = (srcH.toLong() * index / count).toInt()
        val bottom = (srcH.toLong() * (index + 1) / count).toInt().coerceAtMost(srcH)
        val targetW = (options.size.width as? Dimension.Pixels)?.px ?: srcW
        val sampleSize = calculateInSampleSize(srcW, targetW)
        val valid = srcW > 0 && srcH > 0 && bottom > top
        val decoded = if (!valid) null else newRegionDecoder(file)?.let { decoder ->
            try {
                decoder.decodeRegion(
                    Rect(0, top, srcW, bottom),
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }
                )
            } finally {
                decoder.recycle()
            }
        }
        if (decoded == null) {
            Log.w(TAG, "decode failed img=${UrlSanitizer.sanitize(tile.imageUrl)} w=$srcW h=$srcH")
            return null
        }
        // BitmapRegionDecoder always returns a software (ARGB_8888) bitmap, which HWUI would then
        // upload to the GPU on the RenderThread the first frame it is drawn (~5-12ms stall =
        // scroll micro-stutter). Copy it to a HARDWARE bitmap here, on the decode thread, so the
        // upload happens off the draw path and drawing it is free.
        val hardware = runCatching {
            decoded.copy(android.graphics.Bitmap.Config.HARDWARE, false)
        }.getOrNull()
        if (hardware != null) decoded.recycle()
        return SliceBitmap(hardware ?: decoded, sampleSize > 1)
    }

    /**
     * Resolves the image file for this tile.
     * Offline chapters serve file:// URIs from the offline manifest store.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun resolveFile(): File? {
        return localFile(tile.imageUrl)
            ?: contentRepository.findUsableCachedMediaFile(tile.imageUrl)
            ?: run {
                val referer = tile.pageUrl.takeIf { it.isNotBlank() } ?: tile.imageUrl
                contentRepository.downloadAndCacheImage(tile.imageUrl, referer)
                    ?.takeIf { it.exists() && it.length() > 0L }
            }
    }

    private fun localFile(url: String): File? {
        if (!url.startsWith("file:")) return null
        return runCatching {
            File(java.net.URI(url))
        }.getOrNull()?.takeIf { it.exists() && it.length() > 0L }
    }

    @Suppress("DEPRECATION")
    private fun newRegionDecoder(file: File): BitmapRegionDecoder? = runCatching {
        file.inputStream().use { stream ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                BitmapRegionDecoder.newInstance(stream, false)
            }
        }
    }.getOrNull()

    private fun calculateInSampleSize(srcWidth: Int, targetWidth: Int): Int {
        if (targetWidth <= 0 || srcWidth <= targetWidth) return 1
        var sample = 1
        while (srcWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    class Factory(
        private val contentRepository: ContentRepository
    ) : Fetcher.Factory<ReaderImageTile> {
        override fun create(data: ReaderImageTile, options: Options, imageLoader: ImageLoader): Fetcher {
            return ReaderImageTileFetcher(data, contentRepository, options)
        }
    }

    companion object {
        private const val TAG = "ReaderImageTileFetcher"
    }
}
