package io.aatricks.easyreader.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.repository.source.AsuraScansSource
import io.aatricks.easyreader.data.repository.source.MangaBatSource
import io.aatricks.easyreader.data.repository.source.NovelFireSource
import io.aatricks.easyreader.data.repository.source.NovelightSource
import io.aatricks.easyreader.data.repository.source.NovelSource
import io.aatricks.easyreader.data.repository.source.SmartSource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SourceModule {
    @Provides
    @Singleton
    @IntoSet
    fun provideNovelFireSource(
        preferencesManager: PreferencesManager,
        okHttpClient: okhttp3.OkHttpClient
    ): NovelSource = NovelFireSource(preferencesManager, okHttpClient)

    @Provides
    @Singleton
    @IntoSet
    fun provideMangaBatSource(
        preferencesManager: PreferencesManager,
        okHttpClient: okhttp3.OkHttpClient
    ): NovelSource = MangaBatSource(preferencesManager, okHttpClient)

    @Provides
    @Singleton
    @IntoSet
    fun provideAsuraScansSource(
        preferencesManager: PreferencesManager,
        okHttpClient: okhttp3.OkHttpClient
    ): NovelSource = AsuraScansSource(preferencesManager, okHttpClient)

    @Provides
    @Singleton
    @IntoSet
    fun provideNovelightSource(
        preferencesManager: PreferencesManager,
        okHttpClient: okhttp3.OkHttpClient
    ): NovelSource = NovelightSource(preferencesManager, okHttpClient)

    @Provides
    @Singleton
    @IntoSet
    fun provideSmartSource(
        preferencesManager: PreferencesManager,
        okHttpClient: okhttp3.OkHttpClient
    ): NovelSource = SmartSource(preferencesManager, okHttpClient)
}
