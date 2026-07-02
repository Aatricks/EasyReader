package io.aatricks.easyreader.util

import kotlinx.coroutines.CancellationException

/**
 * Rethrows kotlinx.coroutines.CancellationException if present in the Result.
 */
fun <T> Result<T>.rethrowCancellation(): Result<T> {
    val e = exceptionOrNull()
    if (e is CancellationException) throw e
    return this
}
