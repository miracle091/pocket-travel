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

/** Migrazioni 5 -> 6 (pacchetti separati), 6 -> 7, 7 -> 8 e 8 -> 9 sugli schemi esportati in core/data/schemas. */
@RunWith(AndroidJUnit4::class)
class RegionDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegionDatabase::class.java)

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
    fun migrazione6a7AggiungeLaTabellaDelleRegioniSenzaNumeroDiEmergenza() {
        helper.createDatabase(DB_NAME, 6).use { db ->
            db.execSQL("INSERT INTO emergency_numbers VALUES ('italia', '112', '113', '118', '115')")
        }

        helper.runMigrationsAndValidate(DB_NAME, 7, true, MIGRATION_6_7).use { db ->
            db.query("SELECT COUNT(*) FROM emergency_numbers_none").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            db.query("SELECT police FROM emergency_numbers WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("113", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrazione7a8AggiungeIlCodicePaeseVuoto() {
        helper.createDatabase(DB_NAME, 7).use { db ->
            db.execSQL("INSERT INTO installed_regions VALUES ('italia', 'Italia', '1', '1', '1', 10, 1000, 42)")
        }

        helper.runMigrationsAndValidate(DB_NAME, 8, true, MIGRATION_7_8).use { db ->
            db.query("SELECT displayName, countryCode, mapVersion, sizeBytes FROM installed_regions WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Italia", cursor.getString(0))
                assertTrue("il codice arriva dal manifest, non dalla migrazione", cursor.isNull(1))
                assertEquals("1", cursor.getString(2))
                assertEquals(1000L, cursor.getLong(3))
            }
        }
    }

    @Test
    fun migrazione8a9AggiungeLaVersioneDeiCivici() {
        helper.createDatabase(DB_NAME, 8).use { db ->
            db.execSQL("INSERT INTO installed_regions (regionId, displayName, countryCode, mapVersion, routingVersion, poiVersion, poiSizeBytes, sizeBytes, installedAt) VALUES ('italia', 'Italia', 'it', '1', '1', '1', 10, 1000, 42)")
        }

        helper.runMigrationsAndValidate(DB_NAME, 9, true, MIGRATION_8_9).use { db ->
            db.query("SELECT countryCode, poiVersion, addressesVersion FROM installed_regions WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("it", cursor.getString(0))
                assertEquals("1", cursor.getString(1))
                assertTrue("nessuna regione ha gia' i civici", cursor.isNull(2))
            }
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
