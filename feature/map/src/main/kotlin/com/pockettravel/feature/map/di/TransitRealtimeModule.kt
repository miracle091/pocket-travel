package com.pockettravel.feature.map.di

import com.pockettravel.feature.map.HttpTransitRealtimeClient
import com.pockettravel.feature.map.TransitRealtimeClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Il lettore dei feed GTFS-RT del tabellone (TransitRealtimeLoader). */
@Module
@InstallIn(SingletonComponent::class)
abstract class TransitRealtimeModule {
    @Binds
    abstract fun bindTransitRealtimeClient(client: HttpTransitRealtimeClient): TransitRealtimeClient
}
