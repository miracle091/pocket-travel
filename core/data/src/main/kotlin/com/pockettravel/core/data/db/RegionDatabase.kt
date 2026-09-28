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
        NoCentralEmergencyNumberEntity::class,
        CitySectionEntity::class,
        CitySectionFts::class,
        NoteEntity::class,
    ],
    version = 16,
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

// Regioni senza numero di emergenza centralizzato, dal guides.db: vuota finche' non si importa un
// pacchetto guide che la contiene.
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `emergency_numbers_none` (`regionId` TEXT NOT NULL PRIMARY KEY)")
    }
}

// Codice paese per la bandiera in Spazio: le regioni gia' installate restano a NULL finche' l'elenco
// regioni non lo riempie dal manifest (RegionListViewModel.refresh).
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `countryCode` TEXT")
    }
}

// Pacchetto civici (addresses.pmtiles): nessuna regione gia' installata lo ha.
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `addressesVersion` TEXT")
    }
}

// Pacchetto POI extra (poi-extra.db): i POI gia' importati sono tutti del pacchetto base.
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `poiExtraVersion` TEXT")
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `poiExtraSizeBytes` INTEGER")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `extra` INTEGER NOT NULL DEFAULT 0")
    }
}

// Accessibilita' in sedia a rotelle dei POI (tag OSM "wheelchair"): i POI gia' importati non la hanno.
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `wheelchair` TEXT")
    }
}

// Indice su poi.regionId: senza, caricare o eliminare i POI di una regione scorre l'intera tabella.
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_poi_regionId` ON `poi` (`regionId`)")
    }
}

// Anteprima offline della regione (preview.pmtiles, pochi zoom): si installa e aggiorna da sola con
// ogni download della regione (RegionPackageInstaller), non e' un PackageKind ne' un pacchetto che
// l'utente puo' togliere a parte - le regioni gia' installate la prendono al prossimo aggiornamento.
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `previewVersion` TEXT")
    }
}

// Indice su guide_sections.regionId: senza, sectionsForRegion scorre l'intera tabella mondiale delle
// guide. Il tokenizer FTS delle guide passa da "simple" a unicode61, che casefolda anche le maiuscole
// accentate: la tabella va ricreata (FTS4 non supporta ALTER del tokenizer), con le stesse trigger di
// sincronizzazione col content table e un rebuild dell'indice sui dati gia' importati.
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_guide_sections_regionId` ON `guide_sections` (`regionId`)")

        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_guide_sections_fts_BEFORE_UPDATE")
        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_guide_sections_fts_BEFORE_DELETE")
        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_guide_sections_fts_AFTER_UPDATE")
        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_guide_sections_fts_AFTER_INSERT")
        db.execSQL("DROP TABLE IF EXISTS `guide_sections_fts`")
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `guide_sections_fts` USING FTS4(`title` TEXT NOT NULL, `body` TEXT NOT NULL, content=`guide_sections`, tokenize=unicode61)"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_guide_sections_fts_BEFORE_UPDATE BEFORE UPDATE ON `guide_sections` BEGIN DELETE FROM `guide_sections_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_guide_sections_fts_BEFORE_DELETE BEFORE DELETE ON `guide_sections` BEGIN DELETE FROM `guide_sections_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_guide_sections_fts_AFTER_UPDATE AFTER UPDATE ON `guide_sections` BEGIN INSERT INTO `guide_sections_fts`(`docid`, `title`, `body`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`body`); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_guide_sections_fts_AFTER_INSERT AFTER INSERT ON `guide_sections` BEGIN INSERT INTO `guide_sections_fts`(`docid`, `title`, `body`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`body`); END"
        )
        db.execSQL("INSERT INTO guide_sections_fts(guide_sections_fts) VALUES('rebuild')")
    }
}

// Guide delle citta' per regione (rag-knowledge-plan.md, fase 1) e note personali (fase 4).
// city_sections e' nella stessa forma di guide_sections, con l'indice composto (regionId, city)
// richiesto da CityRepository; city_sections_fts ha lo stesso tokenizer unicode61 e le stesse
// trigger di sincronizzazione col content table di guide_sections_fts (vedi MIGRATION_13_14).
// citiesVersion/citiesSizeBytes su installed_regions restano NULL finche' una regione non scarica
// il pacchetto citta'; notes resta vuota finche' l'utente non scrive la prima nota.
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `citiesVersion` TEXT")
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `citiesSizeBytes` INTEGER")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `city_sections` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `regionId` TEXT NOT NULL,
                `city` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `body` TEXT NOT NULL,
                `sourceUrl` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_city_sections_regionId_city` ON `city_sections` (`regionId`, `city`)")
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `city_sections_fts` USING FTS4(`title` TEXT NOT NULL, `body` TEXT NOT NULL, content=`city_sections`, tokenize=unicode61)"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_city_sections_fts_BEFORE_UPDATE BEFORE UPDATE ON `city_sections` BEGIN DELETE FROM `city_sections_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_city_sections_fts_BEFORE_DELETE BEFORE DELETE ON `city_sections` BEGIN DELETE FROM `city_sections_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_city_sections_fts_AFTER_UPDATE AFTER UPDATE ON `city_sections` BEGIN INSERT INTO `city_sections_fts`(`docid`, `title`, `body`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`body`); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_city_sections_fts_AFTER_INSERT AFTER INSERT ON `city_sections` BEGIN INSERT INTO `city_sections_fts`(`docid`, `title`, `body`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`body`); END"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notes` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `body` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

// Orari e indirizzo dei POI di cibo e bevande: vuoti finche' la regione non riscarica
// i punti di interesse pubblicati con questi dati.
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `openingHours` TEXT")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `address` TEXT")
    }
}
