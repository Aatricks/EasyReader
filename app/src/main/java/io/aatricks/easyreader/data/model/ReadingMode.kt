package io.aatricks.easyreader.data.model

import kotlinx.serialization.Serializable

/**
 * Reading mode for the novel reader
 */
@Serializable
enum class ReadingMode {
    VERTICAL,
    PAGED
}
