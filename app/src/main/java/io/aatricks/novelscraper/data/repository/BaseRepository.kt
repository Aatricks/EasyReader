package io.aatricks.novelscraper.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

abstract class BaseRepository(protected val tag: String) {
    protected suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        block()
    }

    protected suspend fun <T> runCatching(
        errorMessage: String,
        fallback: T? = null,
        block: suspend () -> T
    ): T? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(tag, errorMessage, e)
            fallback
        }
    }
}
