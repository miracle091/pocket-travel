package com.pockettravel.core.data.db

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrazioni tra i database delle versioni pubblicate (3, 5, 6, 9, 11, 15, 17), la 24 e quella corrente (25),
 * sugli schemi esportati in core/data/schemas, piu' la catena completa da 3 a 25.
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
    fun migrazione15a17AggiungeIDettagliVuotiAiPoiELaVersioneDegliOrari() {
        helper.createDatabase(DB_NAME, 15).use { db ->
            db.execSQL("INSERT INTO poi (regionId, name, category, lat, lon, osmTag, phone, extra, wheelchair) VALUES ('italia', 'Da Mario', 'restaurant', 45.0, 9.0, 'amenity=restaurant', NULL, 0, 'yes')")
        }

        helper.runMigrationsAndValidate(DB_NAME, 17, true, MIGRATION_15_17).use { db ->
            db.query("SELECT name, wheelchair, openingHours, address, website, email, country FROM poi").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Da Mario", cursor.getString(0))
                assertEquals("yes", cursor.getString(1))
                assertTrue((2..6).all { cursor.isNull(it) })
            }
        }
    }

    @Test
    fun migrazione17a24AggiungeMissioniDiplomaticheVacciniPopolazioneIndiciCoordinateESezioniTradotteSenzaToccareIDati() {
        helper.createDatabase(DB_NAME, 17).use { db ->
            db.execSQL("INSERT INTO emergency_numbers VALUES ('italia', '112', '113', '118', '115')")
            db.execSQL("INSERT INTO poi (regionId, name, category, lat, lon, osmTag, phone, extra, wheelchair) VALUES ('italia', 'Da Mario', 'restaurant', 45.0, 9.0, 'amenity=restaurant', NULL, 0, 'yes')")
            db.execSQL("INSERT INTO city_sections (regionId, city, category, title, body, sourceUrl) VALUES ('italia', 'Roma', 'COSA_VEDERE', 'Cosa vedere', 'Colosseo', 'u')")
            db.execSQL("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES ('italia', 'TRASPORTI', 'In treno', 'corpo', 'u')")
        }

        helper.runMigrationsAndValidate(DB_NAME, 24, true, MIGRATION_17_24).use { db ->
            // 17 -> 18: accessibilita' dei POI e rappresentanze diplomatiche vuote.
            db.query("SELECT name, wheelchair, toiletsWheelchair, capacityDisabled FROM poi").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Da Mario", cursor.getString(0))
                assertEquals("yes", cursor.getString(1))
                assertTrue(cursor.isNull(2) && cursor.isNull(3))
            }
            db.query("SELECT COUNT(*) FROM diplomatic_missions").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL(
                "INSERT INTO diplomatic_missions (wikidata, sending, host, kind, name) VALUES ('Q1', 'it', 'es', 'embassy', 'Embajada de Italia')",
            )
            db.query("SELECT name, nameEn, lat FROM diplomatic_missions WHERE sending = 'it' AND host = 'es'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Embajada de Italia", cursor.getString(0))
                assertTrue(cursor.isNull(1) && cursor.isNull(2))
            }
            // 18 -> 19: tabelle vaccinali vuote e scrivibili.
            listOf("vacc_yf_risk", "vacc_yf_entry", "vacc_polio_status", "vacc_polio_entry", "vacc_special", "vacc_recommended", "vacc_meta")
                .forEach { table ->
                    db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                        cursor.moveToFirst()
                        assertEquals(table, 0, cursor.getInt(0))
                    }
                }
            db.execSQL("INSERT INTO vacc_yf_entry (iso2, rule, minAgeMonths, transit, fromList, exitRequired, noteIt, noteEn, sources, verified) VALUES ('in', 'FROM_LIST', NULL, 'GT12H', 'ke,ug', 0, '', '', 'F7', '2026-10-03')")
            db.execSQL("INSERT INTO vacc_polio_entry (iso2, origin, vaccine, timeWindow, applies, noteIt, noteEn, sources, verified) VALUES ('sa', 'CAT:CVDPV2', 'BOPV_OR_IPV', 'ANY', 'HAJJ_UMRAH', '', '', 'F7', '2026-10-03')")
            db.execSQL("INSERT INTO vacc_meta (key, value) VALUES ('last_review', '2026-10-03')")
            db.query("SELECT fromList, minAgeMonths FROM vacc_yf_entry WHERE iso2 = 'in'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ke,ug", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
            // 19 -> 20, 22 -> 23 e 23 -> 24: popolazione e coordinate delle citta' vuote, sezioni intatte e
            // non tradotte.
            db.query("SELECT city, population, capital, latitude, longitude, translated FROM city_sections").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Roma", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals(0, cursor.getInt(2))
                assertTrue("le citta' gia' importate non hanno coordinate", cursor.isNull(3) && cursor.isNull(4))
                assertEquals(0, cursor.getInt(5))
            }
            db.query("SELECT title, translated FROM guide_sections").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("In treno", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            // 20 -> 21 e 21 -> 22: indici dei POI per regione e latitudine, e per regione, categoria e tag.
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'poi'").use { cursor ->
                val indexes = generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
                assertTrue(indexes.contains("index_poi_regionId_lat"))
                assertTrue(indexes.contains("index_poi_regionId_category_osmTag"))
                // Con INDEXED BY: PoiDao.searchByName e la lettura a righe in sequenza dei segnalini sulle aree larghe.
                assertTrue(indexes.contains("index_poi_regionId"))
            }
            db.query("SELECT DISTINCT category, osmTag FROM poi WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("restaurant", cursor.getString(0))
                assertEquals("amenity=restaurant", cursor.getString(1))
            }
            db.query("SELECT police FROM emergency_numbers WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("113", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrazione24a25ConvertePoiInFormaCompattaConGliStessiDatiEIdentificatori() {
        helper.createDatabase(DB_NAME, 24).use { db ->
            insertInstalledRegion(db, "italia", poiVersion = "1", poiExtraVersion = "1", poiSizeBytes = 100, poiExtraSizeBytes = 20, sizeBytes = 1000)
            insertOldPoi(db, 7, "italia", "Da Mario", "restaurant", 45.123456, 9.654321, "amenity=restaurant", extra = 0, wheelchair = "yes")
            insertOldPoi(db, 9, "italia", "Farmacia", "pharmacy", -33.5, -70.25, "amenity=pharmacy", extra = 0)
            insertOldPoi(db, 12, "italia", "Altra Trattoria", "restaurant", 41.9, 12.5, "amenity=restaurant", extra = 1)
            insertOldPoi(db, 13, "san-marino", "Bar Titano", "bar", 43.9333, 12.45, "amenity=bar", extra = 0)
        }

        helper.runMigrationsAndValidate(DB_NAME, 25, true, MIGRATION_24_25).use { db ->
            // Una riga per regione e una per coppia (category, osmTag), non per POI.
            assertEquals(2, scalar(db, "SELECT COUNT(*) FROM poi_region"))
            assertEquals(3, scalar(db, "SELECT COUNT(*) FROM poi_code"))
            assertEquals(4, scalar(db, "SELECT COUNT(*) FROM poi"))
            // Gli id restano, i valori tornano uguali con la join e le coordinate sono microgradi interi.
            db.query(
                "SELECT poi.id, poi_region.regionId, poi_code.category, poi_code.osmTag, poi.latE6, poi.lonE6, poi.extra, poi.name, poi.wheelchair, poi.phone " +
                    "FROM poi JOIN poi_region ON poi_region.ref = poi.regionRef JOIN poi_code ON poi_code.code = poi.code ORDER BY poi.id",
            ).use { cursor ->
                assertEquals(4, cursor.count)
                cursor.moveToFirst()
                assertEquals(7L, cursor.getLong(0))
                assertEquals("italia", cursor.getString(1))
                assertEquals("restaurant", cursor.getString(2))
                assertEquals("amenity=restaurant", cursor.getString(3))
                assertEquals(45_123_456L, cursor.getLong(4))
                assertEquals(9_654_321L, cursor.getLong(5))
                assertEquals(0, cursor.getInt(6))
                assertEquals("Da Mario", cursor.getString(7))
                assertEquals("yes", cursor.getString(8))
                assertTrue(cursor.isNull(9))
                cursor.moveToNext()
                assertEquals(-33_500_000L, cursor.getLong(4))
                assertEquals(-70_250_000L, cursor.getLong(5))
                cursor.moveToNext()
                assertEquals("il POI extra resta extra", 1, cursor.getInt(6))
                cursor.moveToNext()
                assertEquals("san-marino", cursor.getString(1))
            }
            // Le ricerche del DAO leggono l'indice per regione e per (regione, codice).
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'poi'").use { cursor ->
                val indexes = generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
                assertTrue(indexes.containsAll(listOf("index_poi_regionRef", "index_poi_regionRef_latE6", "index_poi_regionRef_code")))
                assertFalse(indexes.contains("index_poi_regionId"))
            }
            // L'id continua dopo l'ultimo (AUTOINCREMENT): un nuovo POI non riusa un id.
            db.execSQL("INSERT INTO poi (regionRef, code, latE6, lonE6, name) VALUES (1, 1, 1, 1, 'Nuovo')")
            assertEquals(14, scalar(db, "SELECT MAX(id) FROM poi"))
            // La regione resta installata con i suoi pacchetti.
            db.query("SELECT poiVersion, poiExtraVersion, poiSizeBytes, sizeBytes FROM installed_regions WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("1", cursor.getString(0))
                assertEquals("1", cursor.getString(1))
                assertEquals(100L, cursor.getLong(2))
                assertEquals(1000L, cursor.getLong(3))
            }
        }
    }

    // Senza spazio per la conversione (hasRoomToConvertPoi) la tabella si ricrea vuota e i POI risultano da riscaricare.
    @Test
    fun migrazione24a25SenzaSpazioSvuotaIPoiELiSegnaDaRiscaricare() {
        helper.createDatabase(DB_NAME, 24).use { db ->
            insertInstalledRegion(db, "italia", mapVersion = "1", poiVersion = "1", poiExtraVersion = "1", poiSizeBytes = 100, poiExtraSizeBytes = 20, sizeBytes = 1000)
            // Una regione con i soli POI: senza non resta alcun pacchetto, non c'e' piu'.
            insertInstalledRegion(db, "solo-poi", poiVersion = "1", poiSizeBytes = 50, sizeBytes = 50)
            insertOldPoi(db, 1, "italia", "Da Mario", "restaurant", 45.0, 9.0, "amenity=restaurant", extra = 0)
            db.execSQL("INSERT INTO notes (title, body, updatedAt) VALUES ('t', 'b', 5)")
        }

        val withoutRoom = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) = migratePoiToCompact(db, convert = false)
        }
        helper.runMigrationsAndValidate(DB_NAME, 25, true, withoutRoom).use { db ->
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM poi"))
            db.query("SELECT poiVersion, poiExtraVersion, poiSizeBytes, poiExtraSizeBytes, mapVersion, sizeBytes FROM installed_regions WHERE regionId = 'italia'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue((0..3).all { cursor.isNull(it) })
                assertEquals("1", cursor.getString(4))
                assertEquals(880L, cursor.getLong(5))
            }
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM installed_regions WHERE regionId = 'solo-poi'"))
            assertEquals("i dati dell'utente non si toccano", 1, scalar(db, "SELECT COUNT(*) FROM notes"))
        }
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Int = db.query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private fun insertOldPoi(
        db: SupportSQLiteDatabase, id: Long, regionId: String, name: String, category: String, lat: Double, lon: Double, osmTag: String,
        extra: Int, wheelchair: String? = null,
    ) {
        db.execSQL(
            "INSERT INTO poi (id, regionId, name, category, lat, lon, osmTag, wheelchair, extra) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, regionId, name, category, lat, lon, osmTag, wheelchair, extra),
        )
    }

    private fun insertInstalledRegion(
        db: SupportSQLiteDatabase, regionId: String, mapVersion: String? = null, poiVersion: String? = null, poiExtraVersion: String? = null,
        poiSizeBytes: Long? = null, poiExtraSizeBytes: Long? = null, sizeBytes: Long,
    ) {
        db.execSQL(
            "INSERT INTO installed_regions (regionId, displayName, mapVersion, poiVersion, poiExtraVersion, poiSizeBytes, poiExtraSizeBytes, sizeBytes, installedAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 42)",
            arrayOf<Any?>(regionId, regionId, mapVersion, poiVersion, poiExtraVersion, poiSizeBytes, poiExtraSizeBytes, sizeBytes),
        )
    }

    // Chi aggiorna dalla prima versione pubblicata (v0.2.0) all'ultima: nessun dato perso.
    @Test
    fun catenaCompletaDa3a25ConservaCassaforteRegioniPoiEGuide() {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL("INSERT INTO passport_vault VALUES ('p1', 'cifrato', 1, 2)")
            db.execSQL("INSERT INTO installed_regions VALUES ('italia', 'Italia', '2026.09.01', 1000, 42)")
            db.execSQL("INSERT INTO poi (regionId, name, category, lat, lon, osmTag) VALUES ('italia', 'Da Mario', 'restaurant', 45.0, 9.0, 'amenity=restaurant')")
            db.execSQL("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES ('italia', 'TRASPORTI', 'In treno', 'corpo', 'https://example.org')")
        }

        helper.runMigrationsAndValidate(DB_NAME, 25, true, *ALL_MIGRATIONS).use { db ->
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
            db.query(
                "SELECT poi.name, poi.extra, poi_code.osmTag, poi.latE6 FROM poi JOIN poi_region ON poi_region.ref = poi.regionRef " +
                    "JOIN poi_code ON poi_code.code = poi.code WHERE poi_region.regionId = 'italia'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Da Mario", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals("amenity=restaurant", cursor.getString(2))
                assertEquals(45_000_000L, cursor.getLong(3))
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
