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

/** Migrazione 5 -> 6 (pacchetti separati) sugli schemi esportati in core/data/schemas. */
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

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
