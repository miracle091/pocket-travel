package com.pockettravel.core.sync.di

import com.pockettravel.core.data.AppInitializer
import com.pockettravel.core.data.UpdateCheck
import com.pockettravel.core.sync.AlertNotificationSink
import com.pockettravel.core.sync.AlertNotificationsScheduler
import com.pockettravel.core.sync.AlertNotifier
import com.pockettravel.core.sync.AppUpdateCheckScheduler
import com.pockettravel.core.sync.WorkManagerAlertNotificationsScheduler
import com.pockettravel.core.sync.PublishedAtStore
import com.pockettravel.core.sync.DefaultPublishedAtStore
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
    fun providePublishedAtStore(store: DefaultPublishedAtStore): PublishedAtStore = store

    @Provides
    @IntoSet
    fun provideSyncInitializer(initializer: SyncInitializer): AppInitializer = initializer

    @Provides
    @IntoSet
    fun provideAppUpdateCheck(scheduler: AppUpdateCheckScheduler): UpdateCheck = scheduler

    @Provides
    fun provideAlertNotificationSink(notifier: AlertNotifier): AlertNotificationSink = notifier

    @Provides
    fun provideAlertNotificationsScheduler(scheduler: WorkManagerAlertNotificationsScheduler): AlertNotificationsScheduler = scheduler
}
