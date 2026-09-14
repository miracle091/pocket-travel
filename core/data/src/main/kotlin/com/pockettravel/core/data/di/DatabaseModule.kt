package com.pockettravel.core.data.di

import android.content.Context
import androidx.room.Room
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.MIGRATION_1_2
import com.pockettravel.core.data.db.MIGRATION_2_3
import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.db.RegionPackageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideRegionDatabase(@ApplicationContext context: Context): RegionDatabase =
        Room.databaseBuilder(context, RegionDatabase::class.java, "region.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideGuideDao(database: RegionDatabase): GuideDao = database.guideDao()

    @Provides
    fun providePoiDao(database: RegionDatabase): PoiDao = database.poiDao()

    @Provides
    fun provideRegionPackageDao(database: RegionDatabase): RegionPackageDao = database.regionPackageDao()

    @Provides
    fun providePassportDao(database: RegionDatabase): PassportDao = database.passportDao()
}
