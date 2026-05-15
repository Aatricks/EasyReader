package io.aatricks.easyreader.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Trims a URL down to a form safe to write into logs without leaking the user's
 * reading position: keeps scheme + host + first path segment, drops query and
 * fragment. Falls back to "<unparseable url>" rather than echoing the raw input
 * so a malformed scheme cannot smuggle the rest of the URL into logcat.
 *
 * For non-http(s) URIs (e.g., `content://`, `file://`), only the scheme is kept.
 */
object UrlSanitizer {

    fun sanitize(url: String?): String {
        if (url.isNullOrBlank()) return "<no url>"

        val parsed = url.toHttpUrlOrNull()
        if (parsed != null) {
            val firstSegment = parsed.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            return buildString {
                append(parsed.scheme)
                append("://")
                append(parsed.host)
                if (firstSegment != null) {
                    append('/')
                    append(firstSegment)
                    if (parsed.pathSegments.size > 1) append("/…")
                }
            }
        }

        val schemeEnd = url.indexOf(':')
        if (schemeEnd in 1..16) {
            val scheme = url.substring(0, schemeEnd)
            if (scheme.all { it.isLetterOrDigit() || it == '-' || it == '+' || it == '.' }) {
                return "$scheme://…"
            }
        }
        return "<unparseable url>"
    }
}
