package io.aatricks.novelscraper.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.aatricks.novelscraper.data.repository.summary.DisabledSummaryEngine
import io.aatricks.novelscraper.data.repository.summary.SummaryEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SummaryEngineModule {
    @Binds
    @Singleton
    abstract fun bindSummaryEngine(engine: DisabledSummaryEngine): SummaryEngine
}
