package com.pockettravel.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        GuideSectionEntity::class,
        GuideSectionFts::class,
        PoiEntity::class,
        InstalledRegionEntity::class,
        PassportEntity::class,
        EmergencyNumbersEntity::class,
        InstalledGuidesEntity::class,
        NoCentralEmergencyNumberEntity::class,
        CitySectionEntity::class,
        CitySectionFts::class,
        NoteEntity::class,
        DiplomaticMissionEntity::class,
        VaccYfRiskEntity::class,
        VaccYfEntryEntity::class,
        VaccPolioStatusEntity::class,
        VaccPolioEntryEntity::class,
        VaccSpecialEntity::class,
        VaccRecommendedEntity::class,
        VaccMetaEntity::class,
    ],
    version = 20,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RegionDatabase : RoomDatabase() {
    abstract fun guideDao(): GuideDao
    abstract fun poiDao(): PoiDao
    abstract fun regionPackageDao(): RegionPackageDao
    abstract fun passportDao(): PassportDao
    abstract fun emergencyNumbersDao(): EmergencyNumbersDao
    abstract fun cityDao(): CityDao
    abstract fun noteDao(): NoteDao
    abstract fun diplomaticMissionDao(): DiplomaticMissionDao
    abstract fun vaccinationDao(): VaccinationDao
}
