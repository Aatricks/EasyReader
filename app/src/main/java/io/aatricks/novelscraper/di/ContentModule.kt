package io.aatricks.novelscraper.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.aatricks.novelscraper.data.repository.content.DefaultPdfDocumentOpener
import io.aatricks.novelscraper.data.repository.content.PdfDocumentOpener
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HtmlCacheDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaCacheDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpubCacheDir

@Module
@InstallIn(SingletonComponent::class)
object ContentModule {

    @Provides
    @Singleton
    @HtmlCacheDir
    fun provideCacheDir(@ApplicationContext context: Context): File {
        return File(context.cacheDir, "html_cache").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    @MediaCacheDir
    fun provideMediaCacheDir(@ApplicationContext context: Context): File {
        return File(context.cacheDir, "media_cache").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    @EpubCacheDir
    fun provideEpubCacheDir(@ApplicationContext context: Context): File {
        return File(context.cacheDir, "epub_cache").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    internal fun providePdfDocumentOpener(@ApplicationContext context: Context): PdfDocumentOpener {
        return DefaultPdfDocumentOpener(context)
    }
}
