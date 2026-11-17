package com.pockettravel.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        GuideSectionEntity::class,
        GuideSectionFts::class,
        GuideTermEntity::class,
        PoiRow::class,
        PoiRegionEntity::class,
        PoiCodeEntity::class,
        InstalledRegionEntity::class,
        PassportEntity::class,
        EmergencyNumbersEntity::class,
        InstalledGuidesEntity::class,
        NoCentralEmergencyNumberEntity::class,
        CitySectionEntity::class,
        CitySectionFts::class,
        CityClimateEntity::class,
        NoteEntity::class,
        DiplomaticMissionEntity::class,
        VaccYfRiskEntity::class,
        VaccYfEntryEntity::class,
        VaccPolioStatusEntity::class,
        VaccPolioEntryEntity::class,
        VaccSpecialEntity::class,
        VaccRecommendedEntity::class,
        VaccMetaEntity::class,
        ConversationEntity::class,
        ConversationTurnEntity::class,
    ],
    version = 28,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@Suppress("TooManyFunctions") // un accessor per DAO, come vuole Room
abstract class RegionDatabase : RoomDatabase() {
    abstract fun guideDao(): GuideDao
    abstract fun guideTermDao(): GuideTermDao
    abstract fun poiDao(): PoiDao
    abstract fun poiCodeDao(): PoiCodeDao
    abstract fun installedRegionDao(): InstalledRegionDao
    abstract fun passportDao(): PassportDao
    abstract fun emergencyNumbersDao(): EmergencyNumbersDao
    abstract fun cityDao(): CityDao
    abstract fun noteDao(): NoteDao
    abstract fun diplomaticMissionDao(): DiplomaticMissionDao
    abstract fun vaccinationDao(): VaccinationDao
    abstract fun conversationDao(): ConversationDao
}
