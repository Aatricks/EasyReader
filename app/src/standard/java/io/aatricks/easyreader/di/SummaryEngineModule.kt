package io.aatricks.easyreader.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.aatricks.easyreader.data.repository.summary.DisabledSummaryEngine
import io.aatricks.easyreader.data.repository.summary.SummaryEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SummaryEngineModule {
    @Binds
    @Singleton
    abstract fun bindSummaryEngine(engine: DisabledSummaryEngine): SummaryEngine
}
