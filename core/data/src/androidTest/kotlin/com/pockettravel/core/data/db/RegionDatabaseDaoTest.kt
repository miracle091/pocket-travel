package com.pockettravel.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.FtsMatchInfo
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.accentInsensitiveGlob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le query dei DAO su un database Room vero in memoria, con le tabelle FTS e le loro trigger: quello che i fake dei test sulla
 * JVM non possono provare (ordinamenti, filtri, MATCH, sincronizzazione dell'indice).
 */
@RunWith(AndroidJUnit4::class)
class RegionDatabaseDaoTest {

    private lateinit var db: RegionDatabase

    @Before
    fun open() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, RegionDatabase::class.java).build()
    }

    @After
    fun close() {
        db.close()
    }

    // --- GuideDao ---

    @Test
    fun guideLeSezioniDiUnaRegioneSonoOrdinatePerCategoriaESoloDiQuellaRegione() = runBlocking {
        db.guideDao().insertAll(
            listOf(
                guide("italia", GuideCategory.TRASPORTI, "In treno", "corpo"),
                guide("italia", GuideCategory.SALUTE, "Farmacie", "corpo"),
                guide("spagna", GuideCategory.SALUTE, "Sanidad", "corpo"),
            ),
        )

        assertEquals(listOf("Farmacie", "In treno"), db.guideDao().sectionsForRegion("italia").map { it.title })
        assertEquals(emptyList<GuideSectionEntity>(), db.guideDao().sectionsForRegion("francia"))
    }

    @Test
    fun guideLaRicercaFiltraPerRegioneEDaIlMatchinfoDelleDueColonne() = runBlocking {
        db.guideDao().insertAll(
            listOf(
                guide("italia", GuideCategory.TRASPORTI, "In treno", "i treni regionali"),
                guide("spagna", GuideCategory.TRASPORTI, "En tren", "treno veloce"),
                guide("francia", GuideCategory.TRASPORTI, "En train", "treno notturno"),
            ),
        )

        val both = db.guideDao().searchInRegionsRanked(listOf("italia", "spagna"), "treno", 10)
        assertEquals(setOf("italia", "spagna"), both.map { it.section.regionId }.toSet())
        val onlyItaly = db.guideDao().searchInRegionsRanked(listOf("italia"), "treno", 10)
        assertEquals(listOf("In treno"), onlyItaly.map { it.section.title })
        assertEquals(1, onlyItaly.size)

        val info = FtsMatchInfo.parse(onlyItaly.single().matchinfo)
        assertEquals(1, info.phraseCount)
        assertEquals("title e body", 2, info.columnCount)
        assertEquals("il titolo contiene la parola", 1, info.hitsInRow(0, 0))
        // 'x' e 'n' valgono per tutta la tabella, non per le sole regioni richieste.
        assertEquals(3, info.rowCount)
        assertEquals("solo la sezione italiana ha la parola nel titolo", 1, info.rowsWithHit(0, 0))
        assertEquals("nel corpo la hanno la spagnola e la francese", 2, info.rowsWithHit(0, 1))
    }

    @Test
    fun guideLaRicercaRispettaIlLimiteDeiCandidatiELaRicercaPerPrefisso() = runBlocking {
        db.guideDao().insertAll((1..5).map { guide("italia", GuideCategory.TRASPORTI, "Treno $it", "corpo") })

        assertEquals(2, db.guideDao().searchInRegionsRanked(listOf("italia"), "treno", 2).size)
        assertEquals(5, db.guideDao().searchInRegionsRanked(listOf("italia"), "tre*", 10).size)
        assertEquals(emptyList<GuideSectionMatch>(), db.guideDao().searchInRegionsRanked(listOf("italia"), "aereo", 10))
    }

    @Test
    fun guideLaRicercaIgnoraLeMaiuscoleAccentate() = runBlocking {
        db.guideDao().insertAll(listOf(guide("peru", GuideCategory.DOGANE, "PERÙ in autobus", "corpo")))

        assertEquals(1, db.guideDao().searchInRegionsRanked(listOf("peru"), "perù", 10).size)
    }

    @Test
    fun guideUnReimportCompletoNonLasciaNelIndiceIlTestoVecchio() = runBlocking {
        db.guideDao().insertAll(listOf(guide("italia", GuideCategory.SALUTE, "Farmacie", "aspirina")))
        // Come GuidesImporter: si svuota la tabella e si reinserisce, le trigger tengono allineato l'indice.
        db.guideDao().deleteAll()
        db.guideDao().insertAll(listOf(guide("italia", GuideCategory.SALUTE, "Farmacie", "paracetamolo")))

        assertEquals(emptyList<GuideSectionMatch>(), db.guideDao().searchInRegionsRanked(listOf("italia"), "aspirina", 10))
        assertEquals(1, db.guideDao().searchInRegionsRanked(listOf("italia"), "paracetamolo", 10).size)
    }

    @Test
    fun guideDeleteAllSvuotaTabellaEIndiceEOptimizeNonRompeNulla() = runBlocking {
        db.guideDao().insertAll(listOf(guide("italia", GuideCategory.SALUTE, "Farmacie", "aspirina")))
        db.guideDao().optimizeFts()
        assertEquals(1, db.guideDao().searchInRegionsRanked(listOf("italia"), "aspirina", 10).size)

        db.guideDao().deleteAll()
        db.guideDao().optimizeFts()

        assertEquals(emptyList<GuideSectionEntity>(), db.guideDao().sectionsForRegion("italia"))
        assertEquals(emptyList<GuideSectionMatch>(), db.guideDao().searchInRegionsRanked(listOf("italia"), "aspirina", 10))
    }

    // --- CityDao ---

    @Test
    fun cityLeCittaDiUnaRegioneSonoDistinteEInOrdineAlfabetico() = runBlocking {
        db.cityDao().insertAll(
            listOf(
                city("italia", "Roma", GuideCategory.COSA_VEDERE),
                city("italia", "Roma", GuideCategory.CIBO_BEVANDE),
                city("italia", "Bari", GuideCategory.COSA_VEDERE),
                city("spagna", "Madrid", GuideCategory.COSA_VEDERE),
            ),
        )

        assertEquals(listOf("Bari", "Roma"), db.cityDao().citiesForRegion("italia").first())
    }

    @Test
    fun cityLeCittaPrincipaliPerPopolazioneEPoiPerLunghezzaDellaGuidaSenzaPopolazione() = runBlocking {
        db.cityDao().insertAll(
            listOf(
                city("italia", "Roma", GuideCategory.COSA_VEDERE, population = 2_800_000, capital = true),
                city("italia", "Roma", GuideCategory.CIBO_BEVANDE, population = 2_800_000, capital = true),
                city("italia", "Milano", GuideCategory.COSA_VEDERE, population = 1_400_000),
                city("italia", "Matera", GuideCategory.COSA_VEDERE, body = "x".repeat(10)),
                city("italia", "Bari", GuideCategory.COSA_VEDERE, body = "x".repeat(500)),
            ),
        )

        val main = db.cityDao().mainCitiesForRegion("italia", 10).first()

        // Prima chi ha la popolazione (decrescente), poi chi non ce l'ha dalla guida piu' lunga.
        assertEquals(listOf("Roma", "Milano", "Bari", "Matera"), main.map { it.city })
        assertEquals(CityPopulation("Roma", 2_800_000, capital = true), main.first())
        assertNull(main.last().population)
        assertEquals(2, db.cityDao().mainCitiesForRegion("italia", 2).first().size)
    }

    @Test
    fun cityLeCoordinateSonoQuelleDellaCittaEManCanoSeIgnote() = runBlocking {
        db.cityDao().insertAll(
            listOf(
                city("italia", "Roma", GuideCategory.COSA_VEDERE, latitude = 41.9, longitude = 12.5),
                city("italia", "Roma", GuideCategory.CIBO_BEVANDE),
                city("italia", "Bari", GuideCategory.COSA_VEDERE),
            ),
        )

        assertEquals(CityCoordinates(41.9, 12.5), db.cityDao().coordinatesFor("italia", "Roma"))
        assertNull("citta' senza coordinate", db.cityDao().coordinatesFor("italia", "Bari"))
        assertNull("regione diversa", db.cityDao().coordinatesFor("spagna", "Roma"))
    }

    @Test
    fun citySectionsForFiltraPerRegioneECittaEOrdinaPerCategoria() = runBlocking {
        db.cityDao().insertAll(
            listOf(
                city("italia", "Roma", GuideCategory.COSA_VEDERE, title = "Colosseo"),
                city("italia", "Roma", GuideCategory.CIBO_BEVANDE, title = "Carbonara"),
                city("spagna", "Roma", GuideCategory.COSA_VEDERE, title = "Omonima"),
            ),
        )

        assertEquals(listOf("Carbonara", "Colosseo"), db.cityDao().sectionsFor("italia", "Roma").map { it.title })
    }

    @Test
    fun cityLaRicercaFiltraPerRegioneEOpzionalmentePerCitta() = runBlocking {
        db.cityDao().insertAll(
            listOf(
                city("italia", "Roma", GuideCategory.COSA_VEDERE, title = "Colosseo", body = "anfiteatro antico"),
                city("italia", "Napoli", GuideCategory.COSA_VEDERE, title = "Scavi", body = "anfiteatro di Pompei"),
                city("spagna", "Merida", GuideCategory.COSA_VEDERE, title = "Teatro", body = "anfiteatro romano"),
            ),
        )

        val all = db.cityDao().searchInRegionRanked("italia", "anfiteatro", null, 10)
        assertEquals(setOf("Roma", "Napoli"), all.map { it.section.city }.toSet())
        val onlyRome = db.cityDao().searchInRegionRanked("italia", "anfiteatro", "Roma", 10)
        assertEquals(listOf("Roma"), onlyRome.map { it.section.city })
        assertEquals(1, FtsMatchInfo.parse(onlyRome.single().matchinfo).phraseCount)
        assertEquals(1, db.cityDao().searchInRegionRanked("italia", "anfiteatro", null, 1).size)
    }

    @Test
    fun cityDeleteForRegionTogliePureDallIndice() = runBlocking {
        db.cityDao().insertAll(
            listOf(
                city("italia", "Roma", GuideCategory.COSA_VEDERE, body = "anfiteatro"),
                city("spagna", "Merida", GuideCategory.COSA_VEDERE, body = "anfiteatro"),
            ),
        )

        db.cityDao().deleteForRegion("italia")
        db.cityDao().optimizeFts()

        assertEquals(emptyList<CitySectionMatch>(), db.cityDao().searchInRegionRanked("italia", "anfiteatro", null, 10))
        assertEquals(1, db.cityDao().searchInRegionRanked("spagna", "anfiteatro", null, 10).size)
    }

    // --- NoteDao ---

    @Test
    fun noteUpsertDaUnIdESostituisceUnaNotaEsistenteSenzaDuplicarla() = runBlocking {
        val first = db.noteDao().upsert(NoteEntity(title = "a", body = "1", updatedAt = 10))
        val second = db.noteDao().upsert(NoteEntity(title = "b", body = "2", updatedAt = 20))
        assertNotEquals(first, second)

        db.noteDao().upsert(NoteEntity(id = first, title = "a2", body = "1b", updatedAt = 30))

        assertEquals(2, db.noteDao().all().size)
        // La piu' recente per prima.
        assertEquals(listOf("a2", "b"), db.noteDao().observeAll().first().map { it.title })
        db.noteDao().delete(first)
        assertEquals(listOf("b"), db.noteDao().all().map { it.title })
    }

    // --- InstalledRegionDao ---

    @Test
    fun regioniInstallateSiTrovanoSiOrdinanoPerNomeESiEliminano() = runBlocking {
        db.installedRegionDao().upsert(region("spagna", "Spagna"))
        db.installedRegionDao().upsert(region("italia", "Italia"))
        db.installedRegionDao().upsert(region("italia", "Italia", mapVersion = "2"))

        assertEquals(listOf("Italia", "Spagna"), db.installedRegionDao().observeAll().first().map { it.displayName })
        assertEquals("2", db.installedRegionDao().findById("italia")?.mapVersion)
        db.installedRegionDao().deleteById("italia")
        assertNull(db.installedRegionDao().findById("italia"))
    }

    @Test
    fun ilCodicePaeseSiRiempieSoloSeVuoto() = runBlocking {
        db.installedRegionDao().upsert(region("italia", "Italia"))
        db.installedRegionDao().upsert(region("spagna", "Spagna", countryCode = "es"))

        db.installedRegionDao().fillCountryCode("italia", "it")
        db.installedRegionDao().fillCountryCode("spagna", "xx")

        assertEquals("it", db.installedRegionDao().findById("italia")?.countryCode)
        assertEquals("un codice gia' presente non si sovrascrive", "es", db.installedRegionDao().findById("spagna")?.countryCode)
    }

    @Test
    fun laVersioneDelPacchettoGuideHaUnaSolaRiga() = runBlocking {
        assertNull(db.installedRegionDao().guidesVersion())
        db.installedRegionDao().upsertGuides(InstalledGuidesEntity(version = "1", sizeBytes = 10))
        db.installedRegionDao().upsertGuides(InstalledGuidesEntity(version = "2", sizeBytes = 20))

        assertEquals("2", db.installedRegionDao().guidesVersion())
        assertEquals(20L, db.installedRegionDao().observeGuides().first()?.sizeBytes)
    }

    // --- PoiCodeDao e PoiDao ---

    @Test
    fun regioniECodiciDeiPoiSiRegistranoUnaVoltaSola() = runBlocking {
        val codes = db.poiCodeDao()
        codes.ensureRegion("italia")
        codes.ensureRegion("italia")
        codes.ensureRegion("spagna")
        codes.ensureCode("restaurant", "amenity=restaurant")
        codes.ensureCode("restaurant", "amenity=restaurant")
        codes.ensureCode("bar", "amenity=bar")

        assertNotEquals(codes.regionRef("italia"), codes.regionRef("spagna"))
        assertNotEquals(codes.codeOf("restaurant", "amenity=restaurant"), codes.codeOf("bar", "amenity=bar"))
        // Il riferimento resta lo stesso dopo la seconda registrazione.
        val ref = codes.regionRef("italia")
        codes.ensureRegion("italia")
        assertEquals(ref, codes.regionRef("italia"))
    }

    @Test
    fun poiForRegionRicostruisceRegioneCategoriaTagECoordinateInGradi() = runBlocking {
        insertPoi("italia", "Da Mario", "restaurant", "amenity=restaurant", 45.123456, 9.654321, wheelchair = "yes")
        insertPoi("spagna", "Bar Sol", "bar", "amenity=bar", -33.5, -70.25)

        val italy = db.poiDao().poisForRegion("italia").single()

        assertEquals("italia", italy.regionId)
        assertEquals("restaurant", italy.category)
        assertEquals("amenity=restaurant", italy.osmTag)
        assertEquals(45.123456, italy.lat, 1e-9)
        assertEquals(9.654321, italy.lon, 1e-9)
        assertEquals("yes", italy.wheelchair)
        assertEquals(-33.5, db.poiDao().poisForRegion("spagna").single().lat, 1e-9)
    }

    @Test
    fun searchByNameCercaNeiTreNomiSenzaAccentiEsclusiGliExtra() = runBlocking {
        insertPoi("lettonia", "Rīgas Centrs", "museum", "tourism=museum", 56.9, 24.1)
        insertPoi("lettonia", "Centro", "museum", "tourism=museum", 56.9, 24.1, nameIt = "Piazza Vecchia")
        insertPoi("lettonia", "Centrs", "museum", "tourism=museum", 56.9, 24.1, nameEn = "Old Square")
        insertPoi("lettonia", "Rīga Extra", "museum", "tourism=museum", 56.9, 24.1, extra = true)
        insertPoi("estonia", "Riga Bar", "bar", "amenity=bar", 59.4, 24.7)

        suspend fun find(query: String, regions: List<String> = listOf("lettonia")) =
            db.poiDao().searchByName(regions, accentInsensitiveGlob(query), 10).map { it.name }.sorted()

        assertEquals(listOf("Rīgas Centrs"), find("riga"))
        assertEquals(listOf("Centro"), find("vecchia"))
        assertEquals(listOf("Centrs"), find("old square"))
        assertEquals("il pacchetto extra non e' una destinazione", emptyList<String>(), find("extra"))
        assertEquals(listOf("Riga Bar", "Rīgas Centrs"), find("riga", listOf("lettonia", "estonia")))
        assertEquals(1, db.poiDao().searchByName(listOf("lettonia"), accentInsensitiveGlob("cent"), 1).size)
    }

    @Test
    fun nearestOrdinaPerDistanzaDentroIlRiquadroEEscludeGliExtra() = runBlocking {
        insertPoi("italia", "Lontano", "bar", "amenity=bar", 45.5, 9.5)
        insertPoi("italia", "Vicino", "bar", "amenity=bar", 45.01, 9.01)
        insertPoi("italia", "Fuori", "bar", "amenity=bar", 50.0, 9.0)
        insertPoi("italia", "Extra", "bar", "amenity=bar", 45.0, 9.0, extra = true)

        val result = db.poiDao().nearest(listOf("italia"), 45.0, 9.0, 1.0, 44.0, 46.0, 8.0, 10.0, 10)

        assertEquals(listOf("Vicino", "Lontano"), result.map { it.name })
        assertEquals(1, db.poiDao().nearest(listOf("italia"), 45.0, 9.0, 1.0, 44.0, 46.0, 8.0, 10.0, 1).size)
    }

    @Test
    fun embassiesOfDaLeAmbasciateDelPaeseInOrdineDiNome() = runBlocking {
        insertPoi("francia", "Ambasciata B", "embassy", "amenity=embassy", 48.0, 2.0, country = "it")
        insertPoi("francia", "Ambasciata A", "embassy", "amenity=embassy", 48.1, 2.1, country = "it")
        insertPoi("francia", "Ambasciata US", "embassy", "amenity=embassy", 48.2, 2.2, country = "us")
        insertPoi("francia", "Ristorante", "restaurant", "amenity=restaurant", 48.3, 2.3, country = "it")
        insertPoi("spagna", "Ambasciata ES", "embassy", "amenity=embassy", 40.0, -3.0, country = "it")

        assertEquals(listOf("Ambasciata A", "Ambasciata B"), db.poiDao().embassiesOf("francia", "it").map { it.name })
    }

    @Test
    fun transportCountsContaStazioniEPortiDellaRegioneECategoryTagsElencaLeCoppie() = runBlocking {
        insertPoi("italia", "Centrale", "transport", "railway=station", 45.0, 9.0)
        insertPoi("italia", "Garibaldi", "transport", "railway=station", 45.1, 9.1)
        insertPoi("italia", "Porto", "transport", "amenity=ferry_terminal", 44.0, 8.0)
        insertPoi("italia", "Da Mario", "restaurant", "amenity=restaurant", 45.0, 9.0)
        insertPoi("spagna", "Atocha", "transport", "railway=station", 40.0, -3.0)

        val counts = db.poiDao().transportCounts("italia").associate { it.osmTag to it.count }

        assertEquals(mapOf("railway=station" to 2, "amenity=ferry_terminal" to 1), counts)
        assertEquals(
            setOf(CategoryTag("transport", "railway=station"), CategoryTag("transport", "amenity=ferry_terminal"), CategoryTag("restaurant", "amenity=restaurant")),
            db.poiDao().categoryTagsInRegion("italia").toSet(),
        )
    }

    @Test
    fun deleteForRegionTogliTuttiIPoiOSoloBaseOExtraSenzaToccareLeAltreRegioni() = runBlocking {
        insertPoi("italia", "Base", "bar", "amenity=bar", 45.0, 9.0)
        insertPoi("italia", "Extra", "bar", "amenity=bar", 45.0, 9.0, extra = true)
        insertPoi("spagna", "Altro", "bar", "amenity=bar", 40.0, -3.0)

        db.poiDao().deleteForRegion("italia", extra = true)
        assertEquals(listOf("Base"), db.poiDao().poisForRegion("italia").map { it.name })
        db.poiDao().deleteForRegion("italia", extra = null)
        assertTrue(db.poiDao().poisForRegion("italia").isEmpty())
        assertEquals(listOf("Altro"), db.poiDao().poisForRegion("spagna").map { it.name })
    }

    // --- Helper ---

    private fun guide(regionId: String, category: GuideCategory, title: String, body: String) =
        GuideSectionEntity(regionId = regionId, category = category, title = title, body = body, sourceUrl = "https://example.org")

    private fun city(
        regionId: String, city: String, category: GuideCategory, title: String = "titolo", body: String = "corpo",
        population: Long? = null, capital: Boolean = false, latitude: Double? = null, longitude: Double? = null,
    ) = CitySectionEntity(
        regionId = regionId, city = city, category = category, title = title, body = body, sourceUrl = "https://example.org",
        population = population, capital = capital, latitude = latitude, longitude = longitude,
    )

    private fun region(regionId: String, name: String, countryCode: String? = null, mapVersion: String? = null) = InstalledRegionEntity(
        regionId = regionId, displayName = name, countryCode = countryCode, mapVersion = mapVersion, routingVersion = null, poiVersion = null,
        poiExtraVersion = null, addressesVersion = null, previewVersion = null, poiSizeBytes = null, poiExtraSizeBytes = null, sizeBytes = 100,
        installedAt = 1, citiesVersion = null, citiesSizeBytes = null,
    )

    private suspend fun insertPoi(
        regionId: String, name: String, category: String, osmTag: String, lat: Double, lon: Double,
        wheelchair: String? = null, country: String? = null, nameIt: String? = null, nameEn: String? = null, extra: Boolean = false,
    ) {
        db.poiCodeDao().ensureRegion(regionId)
        db.poiCodeDao().ensureCode(category, osmTag)
        db.poiDao().insertAll(
            listOf(
                PoiRow(
                    regionRef = db.poiCodeDao().regionRef(regionId), code = db.poiCodeDao().codeOf(category, osmTag),
                    latE6 = Math.round(lat * 1_000_000), lonE6 = Math.round(lon * 1_000_000), extra = extra, name = name,
                    wheelchair = wheelchair, country = country, nameIt = nameIt, nameEn = nameEn,
                ),
            ),
        )
    }
}
