package com.pockettravel.core.data.di

import android.content.Context
import com.pockettravel.core.data.RegionsDir
import com.pockettravel.core.data.RegionsStagingDir
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    @RegionsDir
    fun provideRegionsDir(@ApplicationContext context: Context): File =
        File(context.filesDir, "regions").apply { mkdirs() }

    @Provides
    @Singleton
    @RegionsStagingDir
    fun provideRegionsStagingDir(@ApplicationContext context: Context): File =
        File(context.cacheDir, "regions_staging").apply { mkdirs() }
}
