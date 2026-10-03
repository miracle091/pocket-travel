package com.pockettravel.core.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.vaccination.PolioCategory
import com.pockettravel.core.data.vaccination.TransitRule
import com.pockettravel.core.data.vaccination.VaccinationRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PackageImporterSchemaTest (JVM) verifica solo le query via JDBC. Qui GuidesImporter e
 * PoiImporter girano con android.database.sqlite e Room reali, su guides.db e poi.db prodotti da
 * tools/data-pipeline (asset androidTest: guide di San Marino e Lettonia, POI di San Marino, content.db
 * v1 di Andorra).
 */
@RunWith(AndroidJUnit4::class)
class PackageImporterDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.cacheDir, "package-importer-test")
    private lateinit var db: RegionDatabase
    private lateinit var repository: RegionRepository

    @Before
    fun setUp() {
        workDir.deleteRecursively()
        workDir.mkdirs()
        db = Room.inMemoryDatabaseBuilder(context, RegionDatabase::class.java).build()
        val storage = RegionStorage(File(workDir, "regions").apply { mkdirs() }, File(workDir, "staging").apply { mkdirs() })
        repository = RegionRepository(db.regionPackageDao(), db.poiDao(), storage, db, db.cityDao())
    }

    @After
    fun tearDown() {
        db.close()
        workDir.deleteRecursively()
    }

    @Test
    fun importaLeGuideDiTutteLeRegioniERegistraLaVersione() = runBlocking {
        val importer = GuidesImporter(db.guideDao(), db.emergencyNumbersDao(), db.diplomaticMissionDao(), db.vaccinationDao(), repository, db)
        assertNull(repository.installedGuidesVersion())

        val file = copyAsset("guides.db")
        importer.import(file, "2026.09.23")

        assertTrue(db.guideDao().sectionsForRegion("san-marino").isNotEmpty())
        assertTrue(db.guideDao().sectionsForRegion("lettonia").isNotEmpty())
        assertEquals("113", db.emergencyNumbersDao().forRegion("san-marino")?.police)
        assertEquals("2026.09.23", repository.installedGuidesVersion())
        assertTrue("senza la tabella nel guides.db non ci sono rappresentanze", db.diplomaticMissionDao().missions("it", "es").isEmpty())
        assertFalse("guides.db va cancellato dopo l'import", file.exists())

        // Un secondo import sostituisce, non accoda.
        val sanMarinoSections = db.guideDao().sectionsForRegion("san-marino").size
        importer.import(copyAsset("guides.db"), "2026.09.30")
        assertEquals(sanMarinoSections, db.guideDao().sectionsForRegion("san-marino").size)
        assertEquals("2026.09.30", repository.installedGuidesVersion())
    }

    @Test
    fun importaLeRappresentanzeDiplomaticheSeLaTabellaCeESostituisceLePrecedenti() = runBlocking {
        val importer = GuidesImporter(db.guideDao(), db.emergencyNumbersDao(), db.diplomaticMissionDao(), db.vaccinationDao(), repository, db)
        importer.import(guidesDbWithMissions("guides-missions.db", "Q1", "Q2"), "2026.10.01")

        val missions = db.diplomaticMissionDao().missions("it", "es")
        assertEquals(setOf("Q1", "Q2"), missions.map { it.wikidata }.toSet())
        val first = missions.single { it.wikidata == "Q1" }
        assertEquals("Embassy of Italy", first.nameEn)
        assertNull(first.address)
        assertEquals(40.4, first.lat!!, 1e-9)

        // Un secondo import sostituisce, non accoda.
        importer.import(guidesDbWithMissions("guides-missions-2.db", "Q3"), "2026.10.08")
        assertEquals(listOf("Q3"), db.diplomaticMissionDao().missions("it", "es").map { it.wikidata })

        // Un guides.db senza la tabella svuota quelle importate: il dato e' sempre quello del pacchetto.
        importer.import(copyAsset("guides.db"), "2026.10.15")
        assertTrue(db.diplomaticMissionDao().missions("it", "es").isEmpty())
    }

    @Test
    fun importaIDatiVaccinaliSeLeTabelleCeranoESenzaDiLoroNonLasciaNulla() = runBlocking {
        val importer = GuidesImporter(db.guideDao(), db.emergencyNumbersDao(), db.diplomaticMissionDao(), db.vaccinationDao(), repository, db)
        assertNull("senza import non ci sono dati vaccinali", VaccinationRepository(db.vaccinationDao()).load())

        importer.import(guidesDbWithVaccinations("guides-vacc.db"), "2026.10.01")
        val data = VaccinationRepository(db.vaccinationDao()).load()!!
        assertEquals(listOf("br"), data.yfRisk.map { it.iso2 })
        assertEquals(setOf("ke", "ug"), data.yfEntry.single { it.iso2 == "in" }.fromList)
        assertEquals(TransitRule.GT12H, data.yfEntry.single { it.iso2 == "in" }.transit)
        assertEquals(PolioCategory.WPV1_CVDPV1_CVDPV3, data.polioEntry.single().originCategory)
        assertEquals(12, data.special.single().minAgeMonths)
        assertEquals("2026-10-03", data.meta.lastReview)

        // Un guides.db vecchio, senza le tabelle, svuota quelle importate.
        importer.import(copyAsset("guides.db"), "2026.10.08")
        assertNull(VaccinationRepository(db.vaccinationDao()).load())
    }

    @Test
    fun importaIPoiDiUnaRegioneSostituendoQuelliPrecedenti() = runBlocking {
        val importer = PoiImporter(db.poiDao(), db)

        val file = copyAsset("poi.db")
        importer.import("san-marino", file)

        val pois = db.poiDao().poisForRegion("san-marino")
        assertEquals(708, pois.size)
        assertFalse("poi.db va cancellato dopo l'import", file.exists())

        importer.import("san-marino", copyAsset("poi.db"))
        assertEquals(708, db.poiDao().poisForRegion("san-marino").size)
    }

    @Test
    fun importaIlContentDbV1SenzaColonnaPhoneComePacchettoPoi() = runBlocking {
        // content.db pubblicato di Andorra (2026.09.18, prima della colonna phone): le regioni non
        // ancora rigenerate lo usano come pacchetto POI dopo la conversione del manifest v1.
        PoiImporter(db.poiDao(), db).import("andorra", copyAsset("content-v1.db"))

        val pois = db.poiDao().poisForRegion("andorra")
        assertEquals(2681, pois.size)
        assertTrue(pois.all { it.phone == null })
    }

    @Test
    fun importaIPoiNelFormatoCompattoConLaTabellaDeiCodici() = runBlocking {
        val file = compactPoiDbFile("poi-compact.db")
        PoiImporter(db.poiDao(), db).import("test-region", file)

        val pois = db.poiDao().poisForRegion("test-region")
        assertEquals(1, pois.size)
        val poi = pois.single()
        assertEquals("Ambasciata", poi.name)
        assertEquals("embassy", poi.category)
        assertEquals("amenity=embassy", poi.osmTag)
        assertEquals(45.4646, poi.lat, 1e-6)
        assertEquals(9.1908, poi.lon, 1e-6)
        assertEquals("+39 06 1234567", poi.phone)
        assertFalse("poi.db va cancellato dopo l'import", file.exists())
    }

    @Test
    fun importaLeGuideDiCittaDiUnaRegioneSostituendoQuellePrecedentiESaltandoLeCategorieSconosciute() = runBlocking {
        val importer = CityImporter(db.cityDao(), db)

        val file = citiesDbFile("cities-san-marino.db") { cities ->
            cities.execSQL("INSERT INTO city_sections VALUES ('Citta di San Marino', 'COSA_VEDERE', 'Cosa vedere', 'corpo', 'https://it.wikivoyage.org/wiki/Citta_di_San_Marino')")
            // Categoria non ancora nota a questa build: va saltata, non deve far fallire l'import.
            cities.execSQL("INSERT INTO city_sections VALUES ('Citta di San Marino', 'CATEGORIA_FUTURA', 'Sezione futura', 'corpo', 'https://it.wikivoyage.org/wiki/Citta_di_San_Marino')")
        }

        importer.import("san-marino", file)

        val sections = db.cityDao().sectionsFor("san-marino", "Citta di San Marino")
        assertEquals(1, sections.size)
        assertEquals("Cosa vedere", sections.single().title)
        assertFalse("cities.db va cancellato dopo l'import", file.exists())

        // Un secondo import sostituisce, non accoda.
        val secondFile = citiesDbFile("cities-san-marino-2.db") { cities ->
            cities.execSQL("INSERT INTO city_sections VALUES ('Citta di San Marino', 'COSA_VEDERE', 'Cosa vedere', 'corpo aggiornato', 'https://it.wikivoyage.org/wiki/Citta_di_San_Marino')")
        }
        importer.import("san-marino", secondFile)
        val sectionsAfterUpdate = db.cityDao().sectionsFor("san-marino", "Citta di San Marino")
        assertEquals(1, sectionsAfterUpdate.size)
        assertEquals("corpo aggiornato", sectionsAfterUpdate.single().body)
    }

    /** Crea un poi.db minimo nel formato compatto (poi_code + poi, PRAGMA user_version=1), vedi GeneratePoi.kt. */
    private fun compactPoiDbFile(name: String): File {
        val file = File(workDir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { poi ->
            poi.execSQL("CREATE TABLE poi_code (code INTEGER NOT NULL PRIMARY KEY, category TEXT NOT NULL, osmTag TEXT NOT NULL)")
            poi.execSQL("INSERT INTO poi_code VALUES (0, 'embassy', 'amenity=embassy')")
            poi.execSQL(
                "CREATE TABLE poi (name TEXT NOT NULL, code INTEGER NOT NULL, latE6 INTEGER NOT NULL, lonE6 INTEGER NOT NULL, phone TEXT, wheelchair TEXT)"
            )
            poi.execSQL("INSERT INTO poi VALUES ('Ambasciata', 0, 45464600, 9190800, '+39 06 1234567', NULL)")
            poi.version = 1
        }
        return file
    }

    /** Crea un guides.db minimo con la sola tabella diplomatic_missions (schema di tools/data-pipeline) e le rappresentanze [wikidataIds]. */
    private fun guidesDbWithMissions(name: String, vararg wikidataIds: String): File {
        val file = File(workDir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { guides ->
            guides.execSQL("CREATE TABLE guide_sections (regionId TEXT NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, sourceUrl TEXT NOT NULL)")
            guides.execSQL("CREATE TABLE emergency_numbers (regionId TEXT NOT NULL, general TEXT, police TEXT NOT NULL, ambulance TEXT NOT NULL, fire TEXT NOT NULL)")
            guides.execSQL(
                "CREATE TABLE diplomatic_missions (wikidata TEXT NOT NULL PRIMARY KEY, sending TEXT NOT NULL, host TEXT NOT NULL, kind TEXT NOT NULL, " +
                    "name TEXT NOT NULL, name_en TEXT, city TEXT, address TEXT, phone TEXT, website TEXT, email TEXT, lat REAL, lon REAL)",
            )
            for (id in wikidataIds) {
                guides.execSQL(
                    "INSERT INTO diplomatic_missions VALUES ('$id', 'it', 'es', 'embassy', 'Embajada de Italia', 'Embassy of Italy', 'Madrid', NULL, " +
                        "'+34 91 1234567', 'https://amb.esteri.it', NULL, 40.4, -3.7)",
                )
            }
        }
        return file
    }

    private fun guidesDbWithVaccinations(name: String): File {
        val file = File(workDir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { guides ->
            guides.execSQL("CREATE TABLE guide_sections (regionId TEXT NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, sourceUrl TEXT NOT NULL)")
            guides.execSQL("CREATE TABLE emergency_numbers (regionId TEXT NOT NULL, general TEXT, police TEXT NOT NULL, ambulance TEXT NOT NULL, fire TEXT NOT NULL)")
            guides.execSQL("CREATE TABLE vacc_yf_risk (iso2 TEXT NOT NULL, scope TEXT NOT NULL, areasIt TEXT NOT NULL, areasEn TEXT NOT NULL, sources TEXT NOT NULL, verified TEXT NOT NULL)")
            guides.execSQL("INSERT INTO vacc_yf_risk VALUES ('br', 'PARTIAL', 'Gran parte', 'Most of the country', 'F6+F7', '2026-10-03')")
            guides.execSQL(
                "CREATE TABLE vacc_yf_entry (iso2 TEXT NOT NULL, rule TEXT NOT NULL, minAgeMonths INTEGER, transit TEXT NOT NULL, fromList TEXT NOT NULL, " +
                    "exitRequired INTEGER NOT NULL, noteIt TEXT NOT NULL, noteEn TEXT NOT NULL, sources TEXT NOT NULL, verified TEXT NOT NULL)",
            )
            guides.execSQL("INSERT INTO vacc_yf_entry VALUES ('in', 'FROM_LIST', 9, 'GT12H', 'ke,ug', 0, '', '', 'F7', '2026-10-03')")
            guides.execSQL("CREATE TABLE vacc_polio_status (iso2 TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL, sources TEXT NOT NULL, verified TEXT NOT NULL)")
            guides.execSQL("INSERT INTO vacc_polio_status VALUES ('af', 'WPV1_CVDPV1_CVDPV3', 'IHR EC 45, 2026-08-21', 'F3', '2026-10-03')")
            guides.execSQL(
                "CREATE TABLE vacc_polio_entry (iso2 TEXT NOT NULL, origin TEXT NOT NULL, vaccine TEXT NOT NULL, timeWindow TEXT NOT NULL, applies TEXT NOT NULL, " +
                    "noteIt TEXT NOT NULL, noteEn TEXT NOT NULL, sources TEXT NOT NULL, verified TEXT NOT NULL)",
            )
            guides.execSQL("INSERT INTO vacc_polio_entry VALUES ('sa', 'CAT:WPV1_CVDPV1_CVDPV3', 'BOPV_OR_IPV', 'ANY', 'HAJJ_UMRAH', '', '', 'F7', '2026-10-03')")
            guides.execSQL(
                "CREATE TABLE vacc_special (iso2 TEXT NOT NULL, purpose TEXT NOT NULL, vaccine TEXT NOT NULL, minAgeMonths INTEGER, minDaysBefore INTEGER, " +
                    "validityYears INTEGER, noteIt TEXT NOT NULL, noteEn TEXT NOT NULL, sources TEXT NOT NULL, verified TEXT NOT NULL)",
            )
            guides.execSQL("INSERT INTO vacc_special VALUES ('sa', 'HAJJ_UMRAH', 'MENACWY', 12, 10, NULL, '', '', 'F7', '2026-10-03')")
            guides.execSQL("CREATE TABLE vacc_recommended (iso2 TEXT NOT NULL, vaccine TEXT NOT NULL, level TEXT NOT NULL, conditionIt TEXT NOT NULL, conditionEn TEXT NOT NULL, sources TEXT NOT NULL, verified TEXT NOT NULL)")
            guides.execSQL("INSERT INTO vacc_recommended VALUES ('ke', 'YF', 'MOST', '', '', 'F7', '2026-10-03')")
            guides.execSQL("CREATE TABLE vacc_meta (key TEXT NOT NULL, value TEXT NOT NULL)")
            guides.execSQL("INSERT INTO vacc_meta VALUES ('last_review', '2026-10-03'), ('polio_verified', '2026-10-03'), ('polio_statement', 'IHR EC 45, 2026-08-21')")
        }
        return file
    }

    /** Crea un cities.db minimo (stesso schema pubblicato da tools/data-pipeline) senza bisogno di un asset. */
    private fun citiesDbFile(name: String, insertRows: (SQLiteDatabase) -> Unit): File {
        val file = File(workDir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { cities ->
            cities.execSQL("CREATE TABLE city_sections (city TEXT NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, sourceUrl TEXT NOT NULL)")
            insertRows(cities)
        }
        return file
    }

    private fun copyAsset(name: String): File {
        val target = File(workDir, name)
        context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }
}
