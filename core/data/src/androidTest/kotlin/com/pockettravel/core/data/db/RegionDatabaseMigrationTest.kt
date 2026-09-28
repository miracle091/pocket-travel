package com.pockettravel.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrazioni tra i database delle versioni pubblicate (3, 5, 6, 9, 11, 15) e la prossima (16), sugli
 * schemi esportati in core/data/schemas, piu' la catena completa da 3 a 16.
 */
@RunWith(AndroidJUnit4::class)
class RegionDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegionDatabase::class.java)

    @Test
    fun migrazione3a5AggiungeIlTelefonoVuotoAiPoiEINumeriDiEmergenza() {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL("INSERT INTO poi (regionId, name, category, lat, lon, osmTag) VALUES ('italia', 'Ambasciata', 'embassy', 45.0, 9.0, 'amenity=embassy')")
        }

        helper.runMigrationsAndValidate(DB_NAME, 5, true, MIGRATION_3_5).use { db ->
            db.query("SELECT name, phone FROM poi").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Ambasciata", cursor.getString(0))
                assertTrue("i POI gia' importati non hanno il telefono", cursor.isNull(1))
            }
            db.execSQL("INSERT INTO emergency_numbers VALUES ('italia', '112', '113', '118', '115')")
        }
    }

    @Test
    fun migrazione5a6ConservaLeRegioniConLaStessaVersionePerOgniPacchetto() {
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.execSQL("INSERT INTO installed_regions VALUES ('italia', 'Italia', '2026.09.01', 1000, 42)")
            db.execSQL("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES ('italia', 'TRASPORTI', 't', 'b', 'https://example.org')")
        }

        // Valida anche lo schema risultante contro quello atteso dalla versione 6.
        helper.runMigrationsAndValidate(DB_NAME, 6, true, MIGRATION_5_6).use { db ->
            db.query("SELECT regionId, displayName, mapVersion, routingVersion, poiVersion, sizeBytes, installedAt, poiSizeBytes FROM installed_regions").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("italia", cursor.getString(0))
                assertEquals("Italia", cursor.getString(1))
                assertEquals("2026.09.01", cursor.getString(2))
                assertEquals("2026.09.01", cursor.getString(3))
                assertEquals("2026.09.01", cursor.getString(4))
                assertEquals(1000L, cursor.getLong(5))
                assertEquals(42L, cursor.getLong(6))
                assertTrue("la dimensione dei POI non era separata prima della 6", cursor.isNull(7))
            }
            db.query("SELECT * FROM installed_guides").use { cursor ->
                assertFalse("le guide vanno scaricate come pacchetto unico", cursor.moveToFirst())
            }
            db.query("SELECT COUNT(*) FROM guide_sections").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrazione6a9AggiungeRegioniSenzaEmergenzaCodicePaeseECivici() {
        helper.createDatabase(DB_NAME, 6).use { db ->
            db.execSQL("INSERT INTO emergency_numbers VALUES ('italia', '112', '113', '118', '115')")
            db.execSQL("INSERT INTO installed_regions VALUES ('italia', 'Italia', '1', '1', '1', 10, 1000, 42)")
        }

        helper.runMigrationsAndValidate(DB_NAME, 9, true, MIGRATION_6_9).use { db ->
            db.query("SELECT COUNT(*) FROM emergency_numbers_none").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            db.query("SELECT police FROM emergency_numbers WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("113", cursor.getString(0))
            }
            db.query("SELECT displayName, countryCode, mapVersion, sizeBytes, addressesVersion FROM installed_regions WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Italia", cursor.getString(0))
                assertTrue("il codice arriva dal manifest, non dalla migrazione", cursor.isNull(1))
                assertEquals("1", cursor.getString(2))
                assertEquals(1000L, cursor.getLong(3))
                assertTrue("nessuna regione ha gia' i civici", cursor.isNull(4))
            }
        }
    }

    @Test
    fun migrazione9a11AggiungeIPoiExtraEAccessibilitaVuota() {
        helper.createDatabase(DB_NAME, 9).use { db ->
            db.execSQL("INSERT INTO installed_regions (regionId, displayName, countryCode, mapVersion, routingVersion, poiVersion, addressesVersion, poiSizeBytes, sizeBytes, installedAt) VALUES ('italia', 'Italia', 'it', '1', '1', '1', NULL, 10, 1000, 42)")
            db.execSQL("INSERT INTO poi (regionId, name, category, lat, lon, osmTag, phone) VALUES ('italia', 'Da Mario', 'restaurant', 45.0, 9.0, 'amenity=restaurant', NULL)")
        }

        helper.runMigrationsAndValidate(DB_NAME, 11, true, MIGRATION_9_11).use { db ->
            db.query("SELECT poiVersion, poiExtraVersion, poiExtraSizeBytes FROM installed_regions WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("1", cursor.getString(0))
                assertTrue("nessuna regione ha gia' i POI extra", cursor.isNull(1) && cursor.isNull(2))
            }
            db.query("SELECT name, extra, wheelchair FROM poi WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Da Mario", cursor.getString(0))
                assertEquals("i POI gia' importati sono del pacchetto base", 0, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    @Test
    fun migrazione11a15IndiciAnteprimaUnicode61CittaENote() {
        helper.createDatabase(DB_NAME, 11).use { db ->
            db.execSQL("INSERT INTO poi (regionId, name, category, lat, lon, osmTag, phone, extra, wheelchair) VALUES ('italia', 'Da Mario', 'restaurant', 45.0, 9.0, 'amenity=restaurant', NULL, 0, 'yes')")
            db.execSQL(
                "INSERT INTO installed_regions (regionId, displayName, countryCode, mapVersion, routingVersion, poiVersion, poiExtraVersion, addressesVersion, poiSizeBytes, poiExtraSizeBytes, sizeBytes, installedAt) " +
                    "VALUES ('italia', 'Italia', 'it', '1', '1', '1', NULL, NULL, 10, NULL, 1000, 42)",
            )
            db.execSQL(
                "INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES ('peru', 'TRASPORTI', 'PERÙ in autobus', 'corpo', 'https://example.org')",
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 15, true, MIGRATION_11_15).use { db ->
            for (index in listOf("index_poi_regionId", "index_guide_sections_regionId", "index_city_sections_regionId_city")) {
                db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$index'").use { cursor ->
                    assertTrue(index, cursor.moveToFirst())
                }
            }
            db.query("SELECT name FROM poi WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Da Mario", cursor.getString(0))
            }
            db.query("SELECT mapVersion, previewVersion, citiesVersion, citiesSizeBytes FROM installed_regions WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("1", cursor.getString(0))
                assertTrue("nessuna regione ha gia' anteprima e citta'", (1..3).all { cursor.isNull(it) })
            }
            // Guide gia' importate reindicizzate con unicode61, che casefolda le maiuscole accentate.
            db.query(
                "SELECT guide_sections.title FROM guide_sections " +
                    "JOIN guide_sections_fts ON guide_sections.id = guide_sections_fts.rowid " +
                    "WHERE guide_sections_fts MATCH 'perù'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PERÙ in autobus", cursor.getString(0))
            }

            db.execSQL(
                "INSERT INTO city_sections (regionId, city, category, title, body, sourceUrl) VALUES ('peru', 'Lima', 'COSA_VEDERE', 'Cosa vedere a PERÙ', 'corpo', 'https://it.wikivoyage.org/wiki/Lima')",
            )
            db.query(
                "SELECT city_sections.title FROM city_sections " +
                    "JOIN city_sections_fts ON city_sections.id = city_sections_fts.rowid " +
                    "WHERE city_sections_fts MATCH 'perù'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Cosa vedere a PERÙ", cursor.getString(0))
            }

            db.execSQL("INSERT INTO notes (title, body, updatedAt) VALUES ('cifrato-titolo', 'cifrato-corpo', 100)")
            db.query("SELECT title, body, updatedAt FROM notes").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("cifrato-titolo", cursor.getString(0))
                assertEquals("cifrato-corpo", cursor.getString(1))
                assertEquals(100L, cursor.getLong(2))
            }
        }
    }

    @Test
    fun migrazione15a16AggiungeIDettagliVuotiAiPoi() {
        helper.createDatabase(DB_NAME, 15).use { db ->
            db.execSQL("INSERT INTO poi (regionId, name, category, lat, lon, osmTag, phone, extra, wheelchair) VALUES ('italia', 'Da Mario', 'restaurant', 45.0, 9.0, 'amenity=restaurant', NULL, 0, 'yes')")
        }

        helper.runMigrationsAndValidate(DB_NAME, 16, true, MIGRATION_15_16).use { db ->
            db.query("SELECT name, wheelchair, openingHours, address, website, email, country FROM poi").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Da Mario", cursor.getString(0))
                assertEquals("yes", cursor.getString(1))
                assertTrue((2..6).all { cursor.isNull(it) })
            }
        }
    }

    // Chi aggiorna dalla prima versione pubblicata (v0.2.0) all'ultima: nessun dato perso.
    @Test
    fun catenaCompletaDa3a16ConservaCassaforteRegioniPoiEGuide() {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL("INSERT INTO passport_vault VALUES ('p1', 'cifrato', 1, 2)")
            db.execSQL("INSERT INTO installed_regions VALUES ('italia', 'Italia', '2026.09.01', 1000, 42)")
            db.execSQL("INSERT INTO poi (regionId, name, category, lat, lon, osmTag) VALUES ('italia', 'Da Mario', 'restaurant', 45.0, 9.0, 'amenity=restaurant')")
            db.execSQL("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES ('italia', 'TRASPORTI', 'In treno', 'corpo', 'https://example.org')")
        }

        helper.runMigrationsAndValidate(DB_NAME, 16, true, *ALL_MIGRATIONS).use { db ->
            db.query("SELECT encryptedPayload FROM passport_vault WHERE id = 'p1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("cifrato", cursor.getString(0))
            }
            db.query("SELECT displayName, mapVersion, poiVersion FROM installed_regions WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Italia", cursor.getString(0))
                assertEquals("2026.09.01", cursor.getString(1))
                assertEquals("2026.09.01", cursor.getString(2))
            }
            db.query("SELECT name, extra FROM poi WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Da Mario", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            db.query(
                "SELECT guide_sections.title FROM guide_sections " +
                    "JOIN guide_sections_fts ON guide_sections.id = guide_sections_fts.rowid " +
                    "WHERE guide_sections_fts MATCH 'treno'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("In treno", cursor.getString(0))
            }
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
