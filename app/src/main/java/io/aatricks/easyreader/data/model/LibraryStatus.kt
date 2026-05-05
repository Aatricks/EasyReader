package io.aatricks.easyreader.data.model

internal const val LIBRARY_FINISHED_PROGRESS_THRESHOLD = 90

internal fun LibraryItem.hasFinishedProgress(): Boolean =
    progress >= LIBRARY_FINISHED_PROGRESS_THRESHOLD

internal fun LibraryItem.hasActionableUpdate(): Boolean =
    hasUpdates && hasFinishedProgress()
