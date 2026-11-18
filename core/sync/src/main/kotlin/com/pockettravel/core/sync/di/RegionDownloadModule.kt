package com.pockettravel.core.sync.di

import com.pockettravel.core.sync.RegionDownload
import com.pockettravel.core.sync.RegionPackageDownload
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Il download delle regioni per il worker della coda. Fuori da [SyncBindingsModule]: i test di :app sostituiscono quel modulo
 * e da un altro modulo Gradle non possono legare un tipo internal.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RegionDownloadModule {
    @Binds
    abstract fun bindRegionDownload(impl: RegionPackageDownload): RegionDownload
}
