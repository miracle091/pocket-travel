package com.pockettravel.core.data.di

import android.content.Context
import androidx.room.Room
import com.pockettravel.core.data.db.ALL_MIGRATIONS
import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.DiplomaticMissionDao
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.NoteDao
import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.db.RegionPackageDao
import com.pockettravel.core.data.db.UNRELEASED_VERSIONS
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
            .addMigrations(*ALL_MIGRATIONS)
            // Versioni mai pubblicate, rimaste solo su qualche telefono di sviluppo: senza una
            // migrazione l'app non partirebbe, cosi' il database si ricrea vuoto.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, *UNRELEASED_VERSIONS)
            .build()

    @Provides
    fun provideGuideDao(database: RegionDatabase): GuideDao = database.guideDao()

    @Provides
    fun provideEmergencyNumbersDao(database: RegionDatabase): EmergencyNumbersDao = database.emergencyNumbersDao()

    @Provides
    fun provideDiplomaticMissionDao(database: RegionDatabase): DiplomaticMissionDao = database.diplomaticMissionDao()

    @Provides
    fun providePoiDao(database: RegionDatabase): PoiDao = database.poiDao()

    @Provides
    fun provideRegionPackageDao(database: RegionDatabase): RegionPackageDao = database.regionPackageDao()

    @Provides
    fun providePassportDao(database: RegionDatabase): PassportDao = database.passportDao()

    @Provides
    fun provideCityDao(database: RegionDatabase): CityDao = database.cityDao()

    @Provides
    fun provideNoteDao(database: RegionDatabase): NoteDao = database.noteDao()
}
