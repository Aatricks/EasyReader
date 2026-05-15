package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.ExploreItem

/**
 * A per-source failure that the UI should surface so the user can distinguish
 * "no results found" from "this source broke". Built up by ExploreRepository as
 * an alternative to silently swallowing exceptions per source.
 */
data class SourceFailure(
    val sourceName: String,
    val reason: String?,
    val cause: Throwable? = null
)

/**
 * Detailed search result: items merged across sources plus the list of sources
 * that failed during this fetch. Callers can present an inline retry chip for
 * each failed source without losing the rows that did come back.
 */
data class SearchOutcome(
    val items: List<ExploreItem>,
    val failures: List<SourceFailure>
)
