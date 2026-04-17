package io.aatricks.novelscraper.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.aatricks.novelscraper.data.repository.custom.AiTextGenerator
import io.aatricks.novelscraper.data.repository.custom.LlmEdgeTextGenerator
import io.aatricks.novelscraper.data.repository.custom.OkHttpRecipePageFetcher
import io.aatricks.novelscraper.data.repository.custom.RecipePageFetcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CustomSourceModule {

    @Binds
    @Singleton
    abstract fun bindRecipePageFetcher(impl: OkHttpRecipePageFetcher): RecipePageFetcher

    @Binds
    @Singleton
    abstract fun bindAiTextGenerator(impl: LlmEdgeTextGenerator): AiTextGenerator
}
