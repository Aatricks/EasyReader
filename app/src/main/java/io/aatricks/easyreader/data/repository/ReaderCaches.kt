package io.aatricks.easyreader.data.repository

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaderCaches @Inject constructor(
    val chapterListCache: ChapterListCache,
    val imageDimensionCache: ImageDimensionCacheRepository
)
