package io.aatricks.easyreader.util

import java.net.URI

private val CHAPTER_SEGMENT_PATTERN = Regex(
    "(?:/chapter[-_/]|/ch[-_/]|/c[-_/]?\\d).*$",
    RegexOption.IGNORE_CASE
)

fun inferSourceNameFromUrl(url: String): String {
    val host = hostOf(url) ?: ""
    return when {
        host.contains("novelight") -> "Novelight"
        host.contains("novelfire") -> "NovelFire"
        host.contains("asura") -> "Asura Scans"
        host.contains("mangabat") || host.contains("manganato") -> "MangaBat"
        url.startsWith("http://") || url.startsWith("https://") -> "Smart Scrape"
        else -> ""
    }
}

fun inferBaseNovelUrlFromUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank() || !trimmed.startsWith("http")) return ""
    val host = hostOf(trimmed) ?: ""

    val knownSlugUrl = resolveKnownHostNovelUrl(host, trimmed)
    val stripped = trimmed.replace(CHAPTER_SEGMENT_PATTERN, "").trimEnd('/')
    return knownSlugUrl ?: if (stripped.isNotBlank() && stripped != trimmed) stripped else trimmed
}

private fun hostOf(url: String): String? =
    runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()

private fun resolveKnownHostNovelUrl(host: String, url: String): String? {
    val slug = when {
        host.contains("novelfire") -> url.split("/book/").getOrNull(1)
        host.contains("asura") -> {
            url.split("/comics/").getOrNull(1)
                ?: url.split("/series/").getOrNull(1)
        }
        host.contains("mangabat") || host.contains("manganato") -> url.split("/manga/").getOrNull(1)
        else -> null
    }?.split("/")?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null

    return when {
        host.contains("novelfire") -> "https://novelfire.net/book/$slug"
        host.contains("asura") -> "https://asurascans.com/comics/$slug"
        else -> "https://www.mangabats.com/manga/$slug"
    }
}
