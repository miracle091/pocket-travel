package com.pockettravel.core.data.di

import com.pockettravel.core.data.AddressSearchRepository
import com.pockettravel.core.data.AndroidLastKnownPosition
import com.pockettravel.core.data.AppInitializer
import com.pockettravel.core.data.ConversationRetention
import com.pockettravel.core.data.LastKnownPosition
import com.pockettravel.core.data.OpenMeteoWeatherRepository
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RoomRegionRepository
import com.pockettravel.core.data.SqliteAddressSearchRepository
import com.pockettravel.core.data.SqliteTransitRepository
import com.pockettravel.core.data.TransitRepository
import com.pockettravel.core.data.WeatherRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Le implementazioni di repository e posizione legate alle interfacce che il resto dell'app inietta. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindRegionRepository(impl: RoomRegionRepository): RegionRepository

    @Binds
    abstract fun bindTransitRepository(impl: SqliteTransitRepository): TransitRepository

    @Binds
    abstract fun bindAddressSearchRepository(impl: SqliteAddressSearchRepository): AddressSearchRepository

    @Binds
    abstract fun bindLastKnownPosition(impl: AndroidLastKnownPosition): LastKnownPosition

    @Binds
    abstract fun bindWeatherRepository(impl: OpenMeteoWeatherRepository): WeatherRepository

    @Binds
    @IntoSet
    abstract fun bindConversationRetention(retention: ConversationRetention): AppInitializer
}
