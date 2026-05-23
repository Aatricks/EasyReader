package io.aatricks.easyreader.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.aatricks.easyreader.data.repository.content.PermanentFailureStore
import io.aatricks.easyreader.data.repository.content.RoomPermanentFailureStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PermanentFailureStoreModule {
    @Binds
    @Singleton
    abstract fun bindPermanentFailureStore(impl: RoomPermanentFailureStore): PermanentFailureStore
}
