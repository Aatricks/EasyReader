package io.aatricks.easyreader.data.repository.source

/**
 * Minimum acceptable parser version per source name. Sources whose declared
 * [NovelSource.version] sorts below the value here are filtered out before any
 * scrape runs, giving an offline kill-switch hook that ships with the binary.
 *
 * To remotely disable a source after release:
 * 1. Patch the entry below with a version newer than what the installed binary
 *    declares (e.g., bump NovelFire's floor to "9.9.9").
 * 2. Cut a hotfix release. Existing installs that update will drop the source
 *    until they ship a parser bump.
 *
 * Future work: hydrate this from a remote signed JSON so disabling does not
 * require a binary release. The hook stays minimal until that delivery channel
 * is decided.
 */
internal val SOURCE_MIN_VERSIONS: Map<String, String> = emptyMap()

internal fun isSourceEnabled(source: NovelSource): Boolean {
    val floor = SOURCE_MIN_VERSIONS[source.name] ?: return true
    return compareVersions(source.version, floor) >= 0
}

private fun compareVersions(actual: String, floor: String): Int {
    val a = actual.splitToVersionParts()
    val b = floor.splitToVersionParts()
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}

private fun String.splitToVersionParts(): List<Int> = split('.', '-')
    .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
