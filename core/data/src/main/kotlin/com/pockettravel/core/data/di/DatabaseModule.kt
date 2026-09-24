package com.pockettravel.core.data.di

import android.content.Context
import androidx.room.Room
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.MIGRATION_1_2
import com.pockettravel.core.data.db.MIGRATION_2_3
import com.pockettravel.core.data.db.MIGRATION_3_4
import com.pockettravel.core.data.db.MIGRATION_4_5
import com.pockettravel.core.data.db.MIGRATION_5_6
import com.pockettravel.core.data.db.MIGRATION_6_7
import com.pockettravel.core.data.db.MIGRATION_7_8
import com.pockettravel.core.data.db.MIGRATION_8_9
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()

    @Provides
    fun provideGuideDao(database: RegionDatabase): GuideDao = database.guideDao()

    @Provides
    fun provideEmergencyNumbersDao(database: RegionDatabase): EmergencyNumbersDao = database.emergencyNumbersDao()

    @Provides
    fun providePoiDao(database: RegionDatabase): PoiDao = database.poiDao()

    @Provides
    fun provideRegionPackageDao(database: RegionDatabase): RegionPackageDao = database.regionPackageDao()

    @Provides
    fun providePassportDao(database: RegionDatabase): PassportDao = database.passportDao()
}
