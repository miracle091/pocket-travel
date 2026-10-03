package com.pockettravel.core.sync.di

import com.pockettravel.core.data.AppInitializer
import com.pockettravel.core.data.UpdateCheck
import com.pockettravel.core.sync.AppUpdateCheckScheduler
import com.pockettravel.core.sync.SyncInitializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @IntoSet
    fun provideSyncInitializer(initializer: SyncInitializer): AppInitializer = initializer

    @Provides
    @IntoSet
    fun provideAppUpdateCheck(scheduler: AppUpdateCheckScheduler): UpdateCheck = scheduler
}
