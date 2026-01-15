package io.aatricks.novelscraper.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.repository.source.MangaBatSource
import io.aatricks.novelscraper.data.repository.source.NovelFireSource
import io.aatricks.novelscraper.data.repository.source.NovelSource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SourceModule {
    @Provides
    @Singleton
    @IntoSet
    fun provideNovelFireSource(preferencesManager: PreferencesManager): NovelSource = NovelFireSource(preferencesManager)

    @Provides
    @Singleton
    @IntoSet
    fun provideMangaBatSource(preferencesManager: PreferencesManager): NovelSource = MangaBatSource(preferencesManager)
}
