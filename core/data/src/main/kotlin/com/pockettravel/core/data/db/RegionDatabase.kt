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
        EmergencyNumbersEntity::class,
        InstalledGuidesEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RegionDatabase : RoomDatabase() {
    abstract fun guideDao(): GuideDao
    abstract fun poiDao(): PoiDao
    abstract fun regionPackageDao(): RegionPackageDao
    abstract fun passportDao(): PassportDao
    abstract fun emergencyNumbersDao(): EmergencyNumbersDao
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

// Numero di telefono del POI (ambasciate/consolati in primo luogo, ma vale per qualunque POI
// che lo abbia su OSM): nullable, i pacchetti regionali gia' pubblicati non lo portano finche'
// non vengono ripubblicati da tools/data-pipeline — la colonna resta NULL per quelli fino ad
// allora, non un errore di migrazione.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `phone` TEXT")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `emergency_numbers` (
                `regionId` TEXT NOT NULL PRIMARY KEY,
                `general` TEXT,
                `police` TEXT NOT NULL,
                `ambulance` TEXT NOT NULL,
                `fire` TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}

// Pacchetti separati (guide, mappa, routing, POI): installed_regions passa da una sola version a
// una per pacchetto, nullable (ogni combinazione e' possibile). Le regioni gia' installate hanno
// tutti e tre, con la stessa versione; poiSizeBytes resta NULL (il vecchio sizeBytes non separava
// i POI dal resto). installed_guides resta vuota: le guide finora importate venivano dai
// content.db per regione, il pacchetto guide unico va ancora scaricato.
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `installed_regions_new` (
                `regionId` TEXT NOT NULL PRIMARY KEY,
                `displayName` TEXT NOT NULL,
                `mapVersion` TEXT,
                `routingVersion` TEXT,
                `poiVersion` TEXT,
                `poiSizeBytes` INTEGER,
                `sizeBytes` INTEGER NOT NULL,
                `installedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `installed_regions_new`
            SELECT `regionId`, `displayName`, `version`, `version`, `version`, NULL, `sizeBytes`, `installedAt`
            FROM `installed_regions`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `installed_regions`")
        db.execSQL("ALTER TABLE `installed_regions_new` RENAME TO `installed_regions`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `installed_guides` (
                `id` INTEGER NOT NULL PRIMARY KEY,
                `version` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
