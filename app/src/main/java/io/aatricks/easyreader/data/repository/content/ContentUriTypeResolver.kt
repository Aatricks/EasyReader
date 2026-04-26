package io.aatricks.easyreader.data.repository.content

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentUriTypeResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun resolveMimeType(url: String): String? {
        if (!url.startsWith("content://")) return null

        return runCatching {
            context.contentResolver.getType(Uri.parse(url))
        }.getOrNull()
    }
}
