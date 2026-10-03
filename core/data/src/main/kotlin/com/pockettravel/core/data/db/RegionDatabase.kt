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
/** Tutte le migrazioni, per Room.databaseBuilder e per i test. */
val ALL_MIGRATIONS: Array<Migration>
    get() = arrayOf(MIGRATION_3_5, MIGRATION_5_6, MIGRATION_6_9, MIGRATION_9_11, MIGRATION_11_15, MIGRATION_15_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)

/** Versioni del database mai uscite in una versione pubblicata dell'app (solo sviluppo). */
val UNRELEASED_VERSIONS = intArrayOf(1, 2, 4, 7, 8, 10, 12, 13, 14, 16)

// Una migrazione per ogni versione dell'app pubblicata, dal suo database al successivo: chi
// aggiorna salta le versioni intermedie usate solo durante lo sviluppo. Le versioni 1 e 2 non sono
// mai uscite (v0.2.0 partiva gia' dalla 3). Dentro, i passi originali nello stesso ordine.

// Da 3 (v0.2.0 e v0.3.0) a 5 (v0.4.0).
val MIGRATION_3_5 = object : Migration(3, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 3 -> 4:
        //   Numero di telefono del POI (ambasciate/consolati in primo luogo, ma vale per qualunque POI
        //   che lo abbia su OSM): nullable, i pacchetti regionali gia' pubblicati non lo portano finche'
        //   non vengono ripubblicati da tools/data-pipeline — la colonna resta NULL per quelli fino ad
        //   allora, non un errore di migrazione.
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `phone` TEXT")

        // 4 -> 5.
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

// Da 5 (v0.4.0) a 6 (v0.5.0).
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

// Da 6 (v0.5.0) a 9 (v0.6.0).
val MIGRATION_6_9 = object : Migration(6, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 6 -> 7:
        //   Regioni senza numero di emergenza centralizzato, dal guides.db: vuota finche' non si importa un
        //   pacchetto guide che la contiene.
        db.execSQL("CREATE TABLE IF NOT EXISTS `emergency_numbers_none` (`regionId` TEXT NOT NULL PRIMARY KEY)")

        // 7 -> 8:
        //   Codice paese per la bandiera in Spazio: le regioni gia' installate restano a NULL finche' l'elenco
        //   regioni non lo riempie dal manifest (RegionListViewModel.refresh).
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `countryCode` TEXT")

        // 8 -> 9:
        //   Pacchetto civici (addresses.pmtiles): nessuna regione gia' installata lo ha.
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `addressesVersion` TEXT")
    }
}

// Da 9 (v0.6.0) a 11 (v0.7.0).
val MIGRATION_9_11 = object : Migration(9, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 9 -> 10:
        //   Pacchetto POI extra (poi-extra.db): i POI gia' importati sono tutti del pacchetto base.
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `poiExtraVersion` TEXT")
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `poiExtraSizeBytes` INTEGER")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `extra` INTEGER NOT NULL DEFAULT 0")

        // 10 -> 11:
        //   Accessibilita' in sedia a rotelle dei POI (tag OSM "wheelchair"): i POI gia' importati non la hanno.
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `wheelchair` TEXT")
    }
}

// Da 11 (v0.7.0) a 15 (v0.8.0).
val MIGRATION_11_15 = object : Migration(11, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 11 -> 12:
        //   Indice su poi.regionId: senza, caricare o eliminare i POI di una regione scorre l'intera tabella.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_poi_regionId` ON `poi` (`regionId`)")

        // 12 -> 13:
        //   Anteprima offline della regione (preview.pmtiles, pochi zoom): si installa e aggiorna da sola con
        //   ogni download della regione (RegionPackageInstaller), non e' un PackageKind ne' un pacchetto che
        //   l'utente puo' togliere a parte - le regioni gia' installate la prendono al prossimo aggiornamento.
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `previewVersion` TEXT")

        // 13 -> 14:
        //   Indice su guide_sections.regionId: senza, sectionsForRegion scorre l'intera tabella mondiale delle
        //   guide. Il tokenizer FTS delle guide passa da "simple" a unicode61, che casefolda anche le maiuscole
        //   accentate: la tabella va ricreata (FTS4 non supporta ALTER del tokenizer), con le stesse trigger di
        //   sincronizzazione col content table e un rebuild dell'indice sui dati gia' importati.
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

        // 14 -> 15:
        //   Guide delle citta' per regione e note personali.
        //   city_sections e' nella stessa forma di guide_sections, con l'indice composto (regionId, city)
        //   richiesto da CityRepository; city_sections_fts ha lo stesso tokenizer unicode61 e le stesse
        //   trigger di sincronizzazione col content table di guide_sections_fts (vedi MIGRATION_11_15, passo 13 -> 14).
        //   citiesVersion/citiesSizeBytes su installed_regions restano NULL finche' una regione non scarica
        //   il pacchetto citta'; notes resta vuota finche' l'utente non scrive la prima nota.
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

// Da 15 (v0.8.0) a 17 (prossima versione; la 16 e' stata solo di sviluppo, senza transitVersion).
// Orari e indirizzo dei POI di cibo, alloggi e ambasciate, sito ed email di alloggi e ambasciate, paese rappresentato dalle ambasciate, nomi in inglese e italiano: vuoti finche' la regione non riscarica
// i punti di interesse pubblicati con questi dati; versione degli orari dei mezzi pubblici installati.
val MIGRATION_15_17 = object : Migration(15, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `openingHours` TEXT")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `address` TEXT")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `website` TEXT")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `email` TEXT")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `country` TEXT")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `nameEn` TEXT")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `nameIt` TEXT")
        // Orari dei mezzi pubblici (pacchetto TRANSIT): nessuna regione gia' installata li ha.
        db.execSQL("ALTER TABLE `installed_regions` ADD COLUMN `transitVersion` TEXT")
    }
}

// Da 17 a 18: ambasciate e consolati da Wikidata (diplomatic_missions), vuota finche' non si importa un
// pacchetto guide che la contiene; bagni accessibili e posti auto per disabili dei POI (toiletsWheelchair,
// capacityDisabled), vuoti finche' la regione non riscarica i punti di interesse che li contengono.
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `toiletsWheelchair` TEXT")
        db.execSQL("ALTER TABLE `poi` ADD COLUMN `capacityDisabled` INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `diplomatic_missions` (
                `wikidata` TEXT NOT NULL,
                `sending` TEXT NOT NULL,
                `host` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `nameEn` TEXT,
                `city` TEXT,
                `address` TEXT,
                `phone` TEXT,
                `website` TEXT,
                `email` TEXT,
                `lat` REAL,
                `lon` REAL,
                PRIMARY KEY(`wikidata`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_diplomatic_missions_sending_host` ON `diplomatic_missions` (`sending`, `host`)")
    }
}

// Da 18 a 19: dati vaccinali per il viaggio (vacc_*, dal guides.db), vuoti finche' non si importa un pacchetto
// guide che li contiene. Una tabella per file curato della pipeline, con le stesse colonne.
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vacc_yf_risk` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `iso2` TEXT NOT NULL,
                `scope` TEXT NOT NULL,
                `areasIt` TEXT NOT NULL,
                `areasEn` TEXT NOT NULL,
                `sources` TEXT NOT NULL,
                `verified` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vacc_yf_entry` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `iso2` TEXT NOT NULL,
                `rule` TEXT NOT NULL,
                `minAgeMonths` INTEGER,
                `transit` TEXT NOT NULL,
                `fromList` TEXT NOT NULL,
                `exitRequired` INTEGER NOT NULL,
                `noteIt` TEXT NOT NULL,
                `noteEn` TEXT NOT NULL,
                `sources` TEXT NOT NULL,
                `verified` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vacc_polio_status` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `iso2` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `statement` TEXT NOT NULL,
                `sources` TEXT NOT NULL,
                `verified` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vacc_polio_entry` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `iso2` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `vaccine` TEXT NOT NULL,
                `timeWindow` TEXT NOT NULL,
                `applies` TEXT NOT NULL,
                `noteIt` TEXT NOT NULL,
                `noteEn` TEXT NOT NULL,
                `sources` TEXT NOT NULL,
                `verified` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vacc_special` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `iso2` TEXT NOT NULL,
                `purpose` TEXT NOT NULL,
                `vaccine` TEXT NOT NULL,
                `minAgeMonths` INTEGER,
                `minDaysBefore` INTEGER,
                `validityYears` INTEGER,
                `noteIt` TEXT NOT NULL,
                `noteEn` TEXT NOT NULL,
                `sources` TEXT NOT NULL,
                `verified` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vacc_recommended` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `iso2` TEXT NOT NULL,
                `vaccine` TEXT NOT NULL,
                `level` TEXT NOT NULL,
                `conditionIt` TEXT NOT NULL,
                `conditionEn` TEXT NOT NULL,
                `sources` TEXT NOT NULL,
                `verified` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `vacc_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
    }
}

/** Popolazione e capitale delle citta' (CitySectionEntity.population, capital): le citta' principali della scheda Citta'. */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `city_sections` ADD COLUMN `population` INTEGER")
        db.execSQL("ALTER TABLE `city_sections` ADD COLUMN `capital` INTEGER NOT NULL DEFAULT 0")
    }
}
