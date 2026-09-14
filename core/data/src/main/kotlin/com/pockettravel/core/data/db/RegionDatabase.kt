package com.pockettravel.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GuideSectionEntity::class,
        GuideSectionFts::class,
        PoiEntity::class,
        InstalledRegionEntity::class,
        PassportEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RegionDatabase : RoomDatabase() {
    abstract fun guideDao(): GuideDao
    abstract fun poiDao(): PoiDao
    abstract fun regionPackageDao(): RegionPackageDao
    abstract fun passportDao(): PassportDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `installed_regions` (
                `regionId` TEXT NOT NULL PRIMARY KEY,
                `displayName` TEXT NOT NULL,
                `version` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `installedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `passport_vault` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `encryptedPayload` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
