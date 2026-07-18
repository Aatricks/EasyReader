package io.aatricks.easyreader.data.repository.content

import android.content.Context
import android.net.Uri
import android.util.LruCache
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import io.aatricks.easyreader.util.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.aatricks.easyreader.data.repository.ImageDimensionCacheRepository
import io.aatricks.easyreader.di.EpubCacheDir
import io.aatricks.easyreader.di.EpubDownloadsDir
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpubContentLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    @EpubCacheDir private val epubCacheDir: File,
    @EpubDownloadsDir private val epubDownloadsDir: File,
    private val imageDimensionCache: ImageDimensionCacheRepository
) {
    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val WRAPPER_TOC_TITLES = setOf(
            "start",
            "cover",
            "contents",
            "table of contents",
            "toc",
            "sommaire"
        )
        private val WRAPPER_TOC_FILES = setOf(
            "cover.xhtml",
            "cover.html",
            "index.xhtml",
            "index.html",
            "toc.xhtml",
            "toc.html",
            "nav.xhtml",
            "nav.html"
        )
    }

    private val epubBookCache = object : LruCache<String, EpubBook>(5) {}
    private val extractedImageDir = File(epubCacheDir, "extracted_images")
    private val extractedImageCache = EpubImageDiskCache(extractedImageDir)

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: Set<String>
    )

    private data class SpineItems(
        val raw: List<String>,
        val reading: List<String>
    )

    private data class EpubImageRequest(
        val epubFile: File,
        val imageHref: String
    )

    suspend fun loadEpubContent(filePath: String, chapterHref: String? = null): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            val book = getEpubBook(filePath) ?: throw Exception("Failed to load EPUB")
            val href = chapterHref ?: book.getFirstReadableHref() ?: throw Exception("No chapters")
            val chapter = loadEpubChapter(filePath, book, href)
            ContentResult.Success(chapter.content, chapter.title ?: book.metadata.title, "$filePath#$href")
        }.getOrElse { e ->
            ContentResult.Error("EPUB Error: ${e.message}")
        }
    }

    suspend fun getEpubBook(path: String): EpubBook? = withContext(Dispatchers.IO) {
        runCatching {
            epubBookCache.get(path) ?: parseEpubFile(path).also { epubBookCache.put(path, it) }
        }.getOrNull()
    }

    suspend fun loadEpubChapterFull(path: String, href: String): EpubChapter? = withContext(Dispatchers.IO) {
        runCatching {
            val book = getEpubBook(path) ?: throw Exception("Failed to load EPUB")
            loadEpubChapter(path, book, href)
        }.getOrNull()
    }

    suspend fun prefetchEpub(path: String, tier: StorageTier = StorageTier.CACHE): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = resolveEpubFile(path, tier)
            if (tier == StorageTier.DOWNLOADS) {
                promoteEpubToDownloads(path)
            }
            file.exists() && getEpubBook(path) != null
        }.getOrDefault(false)
    }

    suspend fun getEpubImage(url: String): ByteArray? = getEpubImageFile(url)?.readBytes()

    suspend fun getEpubImageFile(url: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val request = resolveEpubImageRequest(url)
            request?.let { extractedImageCache.get(it.epubFile, it.imageHref) }
        }.getOrNull()
    }

    private fun resolveEpubImageRequest(url: String): EpubImageRequest? {
        val parts = url.split("#img:", limit = 2).takeIf { it.size == 2 }
        val epubFile = parts?.firstOrNull()?.let(::resolveEpubFile)
        return if (parts != null && epubFile?.exists() == true) {
            EpubImageRequest(
                epubFile = epubFile,
                imageHref = parts[1].replace("\\", "/").removePrefix("/")
            )
        } else {
            null
        }
    }

    fun clearCache(url: String) {
        epubBookCache.remove(url)
        extractedImageCache.clear()
        prefetchedImageDirVariants(url).forEach { it.deleteRecursively() }
        cachedEpubFileVariants(url).forEach { it.delete() }
    }

    fun clearDownload(url: String) {
        epubBookCache.remove(url)
        primaryEpubFile(url, StorageTier.DOWNLOADS).delete()
        File(epubDownloadsDir, CacheKeyUtils.keyFor(url)).deleteRecursively()
    }

    fun clearAllCache() {
        epubCacheDir.deleteRecursively()
        epubBookCache.evictAll()
        epubCacheDir.mkdirs()
        extractedImageCache.onExternalChange(isCleared = true)
    }

    fun clearAllDownloads() {
        epubDownloadsDir.deleteRecursively()
        epubBookCache.evictAll()
        epubDownloadsDir.mkdirs()
    }

    fun trimCache(maxBytes: Long) {
        FileSizeUtils.trimDirectoryToSize(epubCacheDir, maxBytes)
        extractedImageCache.onExternalChange(isCleared = false)
    }

    fun getCacheSize(): Long {
        return FileSizeUtils.calculateDirectorySize(epubCacheDir)
    }

    fun getDownloadsSize(): Long {
        return FileSizeUtils.calculateDirectorySize(epubDownloadsDir)
    }

    fun isCached(path: String): Boolean {
        return if (path.startsWith("content://")) {
            findExistingCachedEpubFile(path) != null
        } else {
            File(path).exists()
        }
    }

    fun isDownloaded(path: String): Boolean {
        return if (path.startsWith("content://")) {
            primaryEpubFile(path, StorageTier.DOWNLOADS).exists()
        } else {
            File(path).exists()
        }
    }

    private fun parseEpubFile(filePath: String): EpubBook {
        val file = resolveEpubFile(filePath)

        ZipFile(file).use { zip ->
            val opfPath = readOpfPath(zip)
            val opfDoc = readOpfDocument(zip, opfPath)
            val manifestItems = parseManifestItems(opfDoc, opfPath)
            val spineItems = parseSpineItems(opfDoc, manifestItems)
            val spine = spineItems.reading.ifEmpty { spineItems.raw }
            val toc = parseToc(zip, opfDoc, manifestItems)

            return EpubBook(
                metadata = parseMetadata(opfDoc),
                toc = toc,
                spine = spine,
                manifest = manifestItems.mapValues { it.value.href }
            )
        }
    }

    private fun readOpfPath(zip: ZipFile): String {
        val container = ZipUtils.readZipEntrySafely(zip, "META-INF/container.xml")
            ?: throw Exception("No container.xml")
        return Jsoup.parse(String(container), "", org.jsoup.parser.Parser.xmlParser())
            .select("rootfile")
            .attr("full-path")
    }

    private fun readOpfDocument(zip: ZipFile, opfPath: String): org.jsoup.nodes.Document {
        val opfContent = ZipUtils.readZipEntrySafely(zip, opfPath) ?: throw Exception("No OPF")
        return Jsoup.parse(String(opfContent), "", org.jsoup.parser.Parser.xmlParser())
    }

    private fun parseMetadata(opfDoc: org.jsoup.nodes.Document): EpubMetadata =
        EpubMetadata(
            title = opfDoc.select("metadata dc|title, title").first()?.text() ?: "Unknown",
            author = opfDoc.select("dc|creator").first()?.text()
        )

    private fun parseManifestItems(
        opfDoc: org.jsoup.nodes.Document,
        opfPath: String
    ): Map<String, ManifestItem> {
        val manifestItems = mutableMapOf<String, ManifestItem>()
        opfDoc.select("manifest item").forEach { item ->
            val id = item.attr("id").trim()
            val href = item.attr("href").trim()
            if (id.isNotBlank() && href.isNotBlank()) {
                manifestItems[id] = ManifestItem(
                    id = id,
                    href = resolveEpubPath(opfPath, href),
                    mediaType = item.attr("media-type").lowercase(),
                    properties = item.attr("properties").asPropertySet()
                )
            }
        }
        return manifestItems
    }

    private fun parseSpineItems(
        opfDoc: org.jsoup.nodes.Document,
        manifestItems: Map<String, ManifestItem>
    ): SpineItems {
        val rawSpine = mutableListOf<String>()
        val readingSpine = mutableListOf<String>()
        opfDoc.select("spine itemref").forEach { itemRef ->
            manifestItems[itemRef.attr("idref")]?.let { item ->
                rawSpine.add(item.href)
                if (isReadingSpineItem(itemRef, item)) {
                    readingSpine.add(item.href)
                }
            }
        }
        return SpineItems(raw = rawSpine, reading = readingSpine)
    }

    private fun parseToc(
        zip: ZipFile,
        opfDoc: org.jsoup.nodes.Document,
        manifestItems: Map<String, ManifestItem>
    ): List<EpubTocItem> {
        val ncxPath = resolveNcxPath(opfDoc, manifestItems)
        val ncxBytes = ncxPath?.let { ZipUtils.readZipEntrySafely(zip, it) }
        val navItem = resolveNavItem(manifestItems)
        val navBytes = navItem?.let { ZipUtils.readZipEntrySafely(zip, it.href) }

        return parseTocNcx(ncxBytes, ncxPath).takeUnless { it.isNullOrEmpty() }
            ?: parseTocNav(navBytes, navItem?.href)
            ?: emptyList()
    }

    private fun resolveNcxPath(
        opfDoc: org.jsoup.nodes.Document,
        manifestItems: Map<String, ManifestItem>
    ): String? {
        return opfDoc.select("spine").first()?.attr("toc")
            ?.takeIf { it.isNotBlank() }
            ?.let { manifestItems[it]?.href }
            ?: manifestItems.values.firstOrNull { item ->
                item.mediaType == "application/x-dtbncx+xml" ||
                    item.href.endsWith(".ncx", ignoreCase = true)
            }?.href
    }

    private fun resolveNavItem(manifestItems: Map<String, ManifestItem>): ManifestItem? {
        return manifestItems.values.firstOrNull { item ->
            item.properties.contains("nav") && item.mediaType.isHtmlMediaType()
        } ?: manifestItems.values.firstOrNull { item ->
            item.id.equals("nav", ignoreCase = true) && item.mediaType.isHtmlMediaType()
        }
    }

    private fun isReadingSpineItem(itemRef: org.jsoup.nodes.Element, item: ManifestItem): Boolean =
        !itemRef.attr("linear").equals("no", ignoreCase = true) &&
            !item.properties.contains("nav") &&
            item.mediaType != "application/x-dtbncx+xml"

    private fun parseTocNcx(ncxBytes: ByteArray?, ncxPath: String?): List<EpubTocItem>? {
        if (ncxBytes == null || ncxPath == null) return null
        val doc = Jsoup.parse(String(ncxBytes), "", org.jsoup.parser.Parser.xmlParser())

        fun parsePoint(e: org.jsoup.nodes.Element): EpubTocItem {
            val src = e.select("content").attr("src").let { if (it.startsWith("/")) it.drop(1) else it }
            val resolvedSrc = resolveEpubPath(ncxPath, src)
            return EpubTocItem(
                id = e.attr("id"),
                title = e.select("navLabel text").first()?.text() ?: "Chapter",
                href = resolvedSrc,
                children = e.select("> navPoint").map { parsePoint(it) }
            )
        }

        return normalizeTocRoots(doc.select("navMap > navPoint").map { parsePoint(it) })
    }

    private fun parseTocNav(navBytes: ByteArray?, navHref: String?): List<EpubTocItem>? {
        val tocItems = if (navBytes == null || navHref == null) {
            null
        } else {
            val doc = Jsoup.parse(String(navBytes), "", org.jsoup.parser.Parser.xmlParser())
            val tocNav = doc.select("nav").firstOrNull { nav ->
                nav.attr("epub:type").hasToken("toc") || nav.attr("type").hasToken("toc")
            } ?: doc.select("nav").firstOrNull()

            tocNav?.let {
                normalizeTocRoots(
                    it.select("> ol > li, > ul > li")
                        .mapNotNull { item -> parseNavListItem(item, navHref) }
                )
            }
        }

        return tocItems?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeTocRoots(items: List<EpubTocItem>): List<EpubTocItem> {
        val root = items.singleOrNull() ?: return items
        return if (root.children.isNotEmpty() && root.isWrapperTocRoot()) {
            root.children
        } else {
            items
        }
    }

    private fun EpubTocItem.isWrapperTocRoot(): Boolean {
        val normalizedTitle = title.trim().lowercase()
        val fileName = href.substringAfterLast('/').substringBefore('#').lowercase()
        return normalizedTitle in WRAPPER_TOC_TITLES ||
            fileName in WRAPPER_TOC_FILES ||
            fileName.startsWith("titlepage") ||
            fileName.startsWith("cover")
    }

    private fun parseNavListItem(item: org.jsoup.nodes.Element, navHref: String): EpubTocItem? {
        val children = item.select("> ol > li, > ul > li")
            .mapNotNull { parseNavListItem(it, navHref) }
        val link = item.select("> a[href]").first()
        val span = item.select("> span").first()
        val href = link?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveEpubPath(navHref, it) }
            ?: children.firstOrNull()?.href
            ?: return null
        val title = link?.text()?.trim()
            ?: span?.text()?.trim()
            ?: item.ownText().trim()

        return EpubTocItem(
            id = item.id().ifBlank { href },
            title = title.ifBlank { "Chapter" },
            href = href,
            children = children
        )
    }

    private suspend fun loadEpubChapter(filePath: String, book: EpubBook, href: String): EpubChapter {
        val file = resolveEpubFile(filePath)
        val chapterHref = normalizeEpubPath(href.substringBefore("#").replace("\\", "/").removePrefix("/"))
        val chapterHrefs = getChapterSpineHrefs(book, chapterHref)

        val els = mutableListOf<ContentElement>()
        var loadedAnyDocument = false
        try {
            ZipFile(file).use { zip ->
                chapterHrefs.forEach { segmentHref ->
                    val bytes = readChapterBytes(zip, segmentHref) ?: return@forEach
                    loadedAnyDocument = true
                    parseChapterElements(String(bytes), filePath, segmentHref, els)
                }
            }
        } catch (e: Exception) {
            if (filePath.startsWith("content://")) file.delete()
            throw e
        }

        if (!loadedAnyDocument) throw Exception("No chapter bytes")

        val enrichedEls = enrichEpubImageDimensionsFromCache(els)

        return EpubChapter(
            href = chapterHref,
            title = book.findTocItemByHref(chapterHref)?.title,
            content = enrichedEls,
            nextHref = book.getNextHref(chapterHref),
            previousHref = book.getPreviousHref(chapterHref)
        )
    }

    private suspend fun enrichEpubImageDimensionsFromCache(
        els: List<ContentElement>
    ): List<ContentElement> {
        val imageUrls = mutableListOf<String>()
        els.forEach { el ->
            when (el) {
                is ContentElement.Image ->
                    if (el.width <= 0 || el.height <= 0) imageUrls.add(el.url)
                is ContentElement.ImageGroup ->
                    el.images.forEach { img ->
                        if (img.width <= 0 || img.height <= 0) imageUrls.add(img.url)
                    }
                else -> Unit
            }
        }
        if (imageUrls.isEmpty()) return els
        val cached = imageDimensionCache.getMany(imageUrls)
        if (cached.isEmpty()) return els

        return els.map { el ->
            when (el) {
                is ContentElement.Image -> {
                    if (el.width > 0 && el.height > 0) el
                    else cached[el.url]?.let { el.copy(width = it.width, height = it.height) } ?: el
                }
                is ContentElement.ImageGroup -> el.copy(
                    images = el.images.map { img ->
                        if (img.width > 0 && img.height > 0) img
                        else cached[img.url]?.let { img.copy(width = it.width, height = it.height) } ?: img
                    }
                )
                else -> el
            }
        }
    }

    private fun getChapterSpineHrefs(book: EpubBook, chapterHref: String): List<String> {
        val startIndex = book.spine.indexOfFirst { epubPathsMatch(it, chapterHref) }
        val toc = book.getFlatToc()
        val tocIndex = toc.indexOfFirst { epubPathsMatch(it.href, chapterHref) }
        return when {
            startIndex < 0 -> listOf(chapterHref)
            tocIndex < 0 -> listOf(book.spine[startIndex])
            else -> {
                val endIndex = findNextTocSpineIndex(book, toc, tocIndex, startIndex)
                book.spine.subList(startIndex, endIndex).ifEmpty { listOf(book.spine[startIndex]) }
            }
        }
    }

    private fun findNextTocSpineIndex(
        book: EpubBook,
        toc: List<EpubTocItem>,
        tocIndex: Int,
        startIndex: Int
    ): Int =
        toc.asSequence()
            .drop(tocIndex + 1)
            .mapNotNull { nextItem ->
                book.spine.indexOfFirst { epubPathsMatch(it, nextItem.href) }
                    .takeIf { it > startIndex }
            }
            .firstOrNull() ?: book.spine.size

    private fun readChapterBytes(zip: ZipFile, href: String): ByteArray? {
        val normalizedHref = normalizeEpubPath(href.substringBefore("#").replace("\\", "/").removePrefix("/"))
        var entry = zip.getEntry(normalizedHref)
        if (entry == null) {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.name == normalizedHref || e.name.endsWith("/$normalizedHref")) {
                    entry = e
                    break
                }
            }
        }

        return entry?.let { ZipUtils.readZipEntrySafely(zip, it.name) }
    }

    private fun parseChapterElements(
        html: String,
        filePath: String,
        chapterHref: String,
        els: MutableList<ContentElement>
    ) {
        val doc = Jsoup.parse(html)

        fun traverse(element: org.jsoup.nodes.Element) {
            val tagName = element.tagName().lowercase()
            when {
                tagName.isImageTag() -> addImageElement(element, filePath, chapterHref, els)
                tagName.isTextBlockTag() -> addTextBlockElement(element, filePath, chapterHref, els)
                else -> {
                    element.children().forEach { traverse(it) }
                    val ownText = element.ownText().trim()
                    if (ownText.length > 1 && !element.hasBlockChildren()) {
                        els.add(ContentElement.Text(ownText))
                    }
                }
            }
        }

        traverse(doc.body())
    }

    private fun addTextBlockElement(
        element: org.jsoup.nodes.Element,
        filePath: String,
        chapterHref: String,
        els: MutableList<ContentElement>
    ) {
        val text = element.text().trim()
        if (text.length > 1) {
            els.add(ContentElement.Text(text))
        }
        element.select("img, image").forEach { img ->
            addImageElement(img, filePath, chapterHref, els)
        }
    }

    private fun addImageElement(
        element: org.jsoup.nodes.Element,
        filePath: String,
        chapterHref: String,
        els: MutableList<ContentElement>
    ) {
        val src = element.imageSource()
        if (src.isNotBlank()) {
            els.add(
                ContentElement.Image(
                    url = "$filePath#img:${resolveEpubPath(chapterHref, src)}",
                    altText = element.attr("alt")
                )
            )
        }
    }

    private fun org.jsoup.nodes.Element.imageSource(): String =
        if (tagName().equals("img", ignoreCase = true)) {
            attr("src")
        } else {
            attr("xlink:href").ifEmpty { attr("href") }
        }

    private fun org.jsoup.nodes.Element.hasBlockChildren(): Boolean =
        children().any { it.tagName().lowercase().isBlockChildTag() }

    private fun String.isImageTag(): Boolean = this == "img" || this == "image"

    private fun String.isTextBlockTag(): Boolean =
        this in setOf("p", "h1", "h2", "h3", "h4", "li")

    private fun String.isBlockChildTag(): Boolean =
        this in setOf("p", "div", "h1", "h2", "h3", "h4", "li")

    private fun resolveEpubPath(base: String, rel: String): String {
        val cleanRel = rel.replace("\\", "/").substringBefore("#")
        if (cleanRel.startsWith("/")) return normalizeEpubPath(cleanRel.drop(1))
        val parent = base.substringBeforeLast("/", "")
        val combined = if (parent.isNotBlank()) "$parent/$cleanRel" else cleanRel

        return normalizeEpubPath(combined)
    }

    private fun normalizeEpubPath(path: String): String {
        val parts = path.split("/")
        val result = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                "." -> {}
                ".." -> if (result.isNotEmpty()) result.removeAt(result.size - 1)
                else -> if (part.isNotBlank()) result.add(part)
            }
        }
        return result.joinToString("/")
    }

    private fun epubPathsMatch(first: String, second: String): Boolean =
        normalizeEpubPath(first.substringBefore("#").replace("\\", "/").removePrefix("/")) ==
            normalizeEpubPath(second.substringBefore("#").replace("\\", "/").removePrefix("/"))

    private fun String.asPropertySet(): Set<String> =
        split(WHITESPACE_REGEX)
            .filter { property -> property.isNotBlank() }
            .map { property -> property.lowercase() }
            .toSet()

    private fun String.hasToken(token: String): Boolean =
        split(WHITESPACE_REGEX).any { it.equals(token, ignoreCase = true) }

    private fun String.isHtmlMediaType(): Boolean =
        contains("html", ignoreCase = true) || isBlank()

    private fun resolveEpubFile(path: String, writeTier: StorageTier = StorageTier.CACHE): File {
        return if (path.startsWith("content://")) {
            findExistingCachedEpubFile(path)?.let { file ->
                file.setLastModified(System.currentTimeMillis())
                return file
            }

            val finalFile = primaryEpubFile(path, writeTier)
            if (!finalFile.exists()) {
                finalFile.parentFile?.mkdirs()
                val tmpFile = File(finalFile.parentFile, "${CacheKeyUtils.keyFor(path)}.${UUID.randomUUID()}.tmp")
                try {
                    context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("Failed to open content URI")

                    if (!tmpFile.renameTo(finalFile) && !finalFile.exists()) {
                         throw Exception("Failed to cache EPUB")
                    }
                } finally {
                    if (tmpFile.exists()) tmpFile.delete()
                }
            }
            finalFile
        } else {
            File(path).also { if (!it.exists()) throw Exception("File not found") }
        }
    }

    private fun promoteEpubToDownloads(path: String): File? {
        if (!path.startsWith("content://")) return null
        val target = primaryEpubFile(path, StorageTier.DOWNLOADS)
        if (target.exists()) return target
        val src = primaryEpubFile(path, StorageTier.CACHE).takeIf(File::exists)
            ?: legacyCachedEpubFile(path).takeIf(File::exists)
            ?: return null
        target.parentFile?.mkdirs()
        if (src.renameTo(target)) return target
        return runCatching {
            src.copyTo(target, overwrite = true)
            src.delete()
            target
        }.getOrNull()
    }

    private fun primaryEpubFile(path: String, tier: StorageTier): File {
        val dir = when (tier) {
            StorageTier.DOWNLOADS -> epubDownloadsDir
            StorageTier.CACHE -> epubCacheDir
        }
        return File(dir, "${CacheKeyUtils.keyFor(path)}.epub")
    }

    private fun legacyCachedEpubFile(path: String): File =
        File(epubCacheDir, "${path.hashCode()}.epub")

    private fun findExistingCachedEpubFile(path: String): File? =
        cachedEpubFileVariants(path).firstOrNull(File::exists)

    private fun cachedEpubFileVariants(path: String): List<File> = listOf(
        primaryEpubFile(path, StorageTier.DOWNLOADS),
        primaryEpubFile(path, StorageTier.CACHE),
        legacyCachedEpubFile(path)
    ).distinctBy(File::getAbsolutePath)

    private fun prefetchedImageDirVariants(path: String): List<File> {
        val key = CacheKeyUtils.keyFor(path)
        val legacyKey = path.hashCode().toString()
        return listOf(
            File(epubDownloadsDir, key),
            File(epubCacheDir, key),
            File(epubCacheDir, legacyKey)
        ).distinctBy(File::getAbsolutePath)
    }
}
