package com.pockettravel.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ogni percorso di aggiornamento verso la versione corrente: da ciascuna versione pubblicata con tutte le migrazioni di
 * [ALL_MIGRATIONS], come le registra DatabaseModule, e da ciascuna versione di solo sviluppo con la ricostruzione del database.
 * Controlla che i dati dell'utente restino, che le tabelle FTS e le loro trigger siano quelle attese e che gli indici siano
 * quelli della versione corrente; poi apre il database migrato con Room e interroga i DAO.
 */
@RunWith(AndroidJUnit4::class)
class RegionDatabaseMigrationPathsTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegionDatabase::class.java)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun deleteDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun daOgniVersionePubblicataSiArrivaAllaCorrenteSenzaPerdereDati() {
        for (start in RELEASED_STARTS) {
            helper.createDatabase(DB_NAME, start).use { db -> seed(db, start) }

            helper.runMigrationsAndValidate(DB_NAME, CURRENT_VERSION, true, *ALL_MIGRATIONS).use { db ->
                val label = "partendo dalla versione $start"
                assertEquals(label, "cifrato", text(db, "SELECT encryptedPayload FROM passport_vault WHERE id = 'p1'"))
                assertEquals(label, "Italia", text(db, "SELECT displayName FROM installed_regions WHERE regionId = 'italia'"))
                assertEquals(label, "Da Mario", text(db, POI_NAME_SQL))
                assertEquals(label, "PERÙ in autobus", text(db, "SELECT title FROM guide_sections WHERE regionId = 'peru'"))
                if (start >= 15) {
                    assertEquals(label, "cifrato-titolo", text(db, "SELECT title FROM notes"))
                    assertEquals(label, "Cosa vedere a Lima", text(db, "SELECT title FROM city_sections WHERE city = 'Lima'"))
                }
            }
        }
    }

    @Test
    fun daOgniVersionePubblicataLeTabelleFtsHannoLeTriggerEIndiceUnicode61() {
        for (start in RELEASED_STARTS) {
            helper.createDatabase(DB_NAME, start).use { db -> seed(db, start) }

            helper.runMigrationsAndValidate(DB_NAME, CURRENT_VERSION, true, *ALL_MIGRATIONS).use { db ->
                val label = "partendo dalla versione $start"
                val triggers = names(db, "trigger")
                assertTrue("$label: trigger FTS mancanti ${FTS_TRIGGERS - triggers}", triggers.containsAll(FTS_TRIGGERS))
                // Le sezioni gia' importate sono reindicizzate: la maiuscola accentata si trova in minuscolo.
                assertEquals(label, 1, count(db, "SELECT COUNT(*) FROM guide_sections_fts WHERE guide_sections_fts MATCH 'perù'"))

                // Le trigger tengono l'indice allineato a inserimenti, modifiche ed eliminazioni.
                db.execSQL("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES ('peru', 'SALUTE', 'Vaccini', 'febbre gialla', 'u')")
                assertEquals(label, 1, count(db, "SELECT COUNT(*) FROM guide_sections_fts WHERE guide_sections_fts MATCH 'febbre'"))
                db.execSQL("UPDATE guide_sections SET body = 'malaria' WHERE title = 'Vaccini'")
                assertEquals(label, 0, count(db, "SELECT COUNT(*) FROM guide_sections_fts WHERE guide_sections_fts MATCH 'febbre'"))
                assertEquals(label, 1, count(db, "SELECT COUNT(*) FROM guide_sections_fts WHERE guide_sections_fts MATCH 'malaria'"))
                db.execSQL("DELETE FROM guide_sections WHERE title = 'Vaccini'")
                assertEquals(label, 0, count(db, "SELECT COUNT(*) FROM guide_sections_fts WHERE guide_sections_fts MATCH 'malaria'"))

                db.execSQL("INSERT INTO city_sections (regionId, city, category, title, body, sourceUrl) VALUES ('peru', 'Cusco', 'COSA_VEDERE', 'Machu Picchu', 'rovine', 'u')")
                assertEquals(label, 1, count(db, "SELECT COUNT(*) FROM city_sections_fts WHERE city_sections_fts MATCH 'rovine'"))
                db.execSQL("DELETE FROM city_sections WHERE city = 'Cusco'")
                assertEquals(label, 0, count(db, "SELECT COUNT(*) FROM city_sections_fts WHERE city_sections_fts MATCH 'rovine'"))
            }
        }
    }

    @Test
    fun daOgniVersionePubblicataGliIndiciSonoQuelliDellaVersioneCorrente() {
        for (start in RELEASED_STARTS) {
            helper.createDatabase(DB_NAME, start).use { db -> seed(db, start) }

            helper.runMigrationsAndValidate(DB_NAME, CURRENT_VERSION, true, *ALL_MIGRATIONS).use { db ->
                val label = "partendo dalla versione $start"
                val indexes = names(db, "index")
                assertTrue("$label: indici mancanti ${EXPECTED_INDEXES - indexes}", indexes.containsAll(EXPECTED_INDEXES))
                // Quelli della tabella poi vecchia spariscono con la DROP: la ricerca per nome usa index_poi_regionRef.
                for (old in listOf("index_poi_regionId", "index_poi_regionId_lat", "index_poi_regionId_category_osmTag")) {
                    assertFalse("$label: indice $old rimasto", indexes.contains(old))
                }
            }
        }
    }

    @Test
    fun daOgniVersionePubblicataRoomApreIlDatabaseMigratoEIDaoRispondono() {
        for (start in RELEASED_STARTS) {
            helper.createDatabase(DB_NAME, start).use { db -> seed(db, start) }

            val database = Room.databaseBuilder(context, RegionDatabase::class.java, DB_NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            try {
                runBlocking {
                    val label = "partendo dalla versione $start"
                    val sections = database.guideDao().sectionsForRegion("peru")
                    assertEquals(label, listOf("PERÙ in autobus"), sections.map { it.title })
                    assertFalse(label, sections.single().translated)
                    val found = database.guideDao().searchInRegionsRanked(listOf("peru"), "perù", 10)
                    assertEquals(label, listOf("PERÙ in autobus"), found.map { it.section.title })
                    assertEquals(label, "Italia", database.installedRegionDao().findById("italia")?.displayName)
                    assertEquals(label, listOf("Da Mario"), database.poiDao().poisForRegion("italia").map { it.name })
                    assertEquals(label, "cifrato", database.passportDao().findById("p1")?.encryptedPayload)
                }
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun daOgniVersioneDiSoloSviluppoIlDatabaseSiRicreaVuotoSenzaErrori() {
        // Le versioni di sviluppo con uno schema esportato: Room le ricostruisce, non c'e' migrazione che parta da li'.
        for (start in UNRELEASED_VERSIONS.filter { it in EXPORTED_UNRELEASED }) {
            helper.createDatabase(DB_NAME, start).use { db ->
                db.execSQL("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES ('peru', 'TRASPORTI', 't', 'b', 'u')")
            }

            val database = Room.databaseBuilder(context, RegionDatabase::class.java, DB_NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, *UNRELEASED_VERSIONS)
                .build()
            try {
                runBlocking {
                    assertEquals("partendo dalla versione $start", emptyList<Any>(), database.guideDao().sectionsForRegion("peru"))
                }
                assertEquals(CURRENT_VERSION, database.openHelper.readableDatabase.version)
            } finally {
                database.close()
                context.deleteDatabase(DB_NAME)
            }
        }
    }

    // Dati dell'utente e delle regioni che ogni versione di partenza sa scrivere: le colonne comuni a tutte le versioni
    // pubblicate, con i nomi che hanno in quella versione.
    private fun seed(db: SupportSQLiteDatabase, version: Int) {
        db.execSQL("INSERT INTO passport_vault VALUES ('p1', 'cifrato', 1, 2)")
        val mapColumn = if (version <= 5) "version" else "mapVersion"
        db.execSQL("INSERT INTO installed_regions (regionId, displayName, $mapColumn, sizeBytes, installedAt) VALUES ('italia', 'Italia', '1', 1000, 42)")
        db.execSQL("INSERT INTO poi (regionId, name, category, lat, lon, osmTag) VALUES ('italia', 'Da Mario', 'restaurant', 45.0, 9.0, 'amenity=restaurant')")
        db.execSQL("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES ('peru', 'TRASPORTI', 'PERÙ in autobus', 'corpo', 'u')")
        if (version >= 15) {
            db.execSQL("INSERT INTO notes (title, body, updatedAt) VALUES ('cifrato-titolo', 'cifrato-corpo', 100)")
            db.execSQL("INSERT INTO city_sections (regionId, city, category, title, body, sourceUrl) VALUES ('peru', 'Lima', 'COSA_VEDERE', 'Cosa vedere a Lima', 'corpo', 'u')")
        }
    }

    private fun text(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            assertTrue(sql, cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun names(db: SupportSQLiteDatabase, type: String): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = '$type'").use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
        }

    private companion object {
        const val DB_NAME = "migration-paths-test.db"
        const val CURRENT_VERSION = 25

        // Le versioni da cui parte una migrazione di ALL_MIGRATIONS (le pubblicate piu' la 24, la precedente alla corrente).
        val RELEASED_STARTS = listOf(3, 5, 6, 9, 11, 15, 17, 24)

        // Versioni di sviluppo di cui core/data/schemas ha lo schema esportato.
        val EXPORTED_UNRELEASED = setOf(16, 18, 19, 20, 21, 22, 23)

        const val POI_NAME_SQL =
            "SELECT name FROM poi WHERE name = 'Da Mario'"

        val FTS_TRIGGERS = listOf("guide_sections", "city_sections").flatMap { table ->
            listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT").map { "room_fts_content_sync_${table}_fts_$it" }
        }.toSet()

        val EXPECTED_INDEXES = setOf(
            "index_guide_sections_regionId", "index_city_sections_regionId_city", "index_diplomatic_missions_sending_host",
            "index_poi_regionRef", "index_poi_regionRef_latE6", "index_poi_regionRef_code",
            "index_poi_region_regionId", "index_poi_code_category_osmTag",
        )
    }
}
