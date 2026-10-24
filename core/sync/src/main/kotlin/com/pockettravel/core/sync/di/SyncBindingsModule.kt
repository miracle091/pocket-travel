package com.pockettravel.core.sync.di

import com.pockettravel.core.sync.AddressGridClient
import com.pockettravel.core.sync.AppStatusClient
import com.pockettravel.core.sync.CatalogPreferences
import com.pockettravel.core.sync.DefaultCatalogPreferences
import com.pockettravel.core.sync.HttpAppStatusClient
import com.pockettravel.core.sync.HttpAddressGridClient
import com.pockettravel.core.sync.HttpManifestClient
import com.pockettravel.core.sync.HttpTransitClient
import com.pockettravel.core.sync.HttpOnlineModelsClient
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.OnlineModelsClient
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.WorkManagerRegionSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Le implementazioni di scheduler, client e preferenze del catalogo legate alle interfacce che il resto dell'app inietta. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindingsModule {

    @Binds
    abstract fun bindRegionSyncScheduler(impl: WorkManagerRegionSyncScheduler): RegionSyncScheduler

    @Binds
    abstract fun bindManifestClient(impl: HttpManifestClient): ManifestClient

    @Binds
    abstract fun bindTransitClient(impl: HttpTransitClient): TransitClient

    @Binds
    abstract fun bindAddressGridClient(impl: HttpAddressGridClient): AddressGridClient

    @Binds
    abstract fun bindAppStatusClient(impl: HttpAppStatusClient): AppStatusClient

    @Binds
    abstract fun bindOnlineModelsClient(impl: HttpOnlineModelsClient): OnlineModelsClient

    @Binds
    abstract fun bindCatalogPreferences(impl: DefaultCatalogPreferences): CatalogPreferences
}
