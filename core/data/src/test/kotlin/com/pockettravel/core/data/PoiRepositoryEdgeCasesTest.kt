package com.pockettravel.core.data

import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.PoiRow
import com.pockettravel.core.data.db.TransportCount
import com.pockettravel.core.poi.PoiCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** Logica di PoiRepository (scelta delle query, filtri in Kotlin, mapping) con un DAO finto che registra le chiamate. */
class PoiRepositoryEdgeCasesTest {

    private class Call(val name: String, val args: Map<String, Any?>)

    private class RecordingDao : PoiDao {
        val calls = mutableListOf<Call>()
        var inBounds: List<PoiEntity> = emptyList()
        var spread: List<PoiEntity> = emptyList()
        var wideSpread: List<PoiEntity> = emptyList()
        var tags: List<CategoryTag> = emptyList()
        var transport: List<TransportCount> = emptyList()
        var embassies: List<PoiEntity> = emptyList()
        var named: List<PoiEntity> = emptyList()
        var near: List<PoiEntity> = emptyList()

        override suspend fun poisInBounds(
            regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, excluded: List<String>, accessibility: Int, limit: Int,
        ): List<PoiEntity> {
            calls += Call("poisInBounds", mapOf("excluded" to excluded, "accessibility" to accessibility, "limit" to limit, "regionId" to regionId))
            return inBounds.take(limit)
        }

        override suspend fun spreadInBounds(
            regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>,
            accessibility: Int,
        ): List<PoiEntity> {
            calls += Call("spreadInBounds", mapOf("cellLat" to cellLat, "cellLon" to cellLon, "excluded" to excluded, "accessibility" to accessibility))
            return spread
        }

        override suspend fun spreadInWideBounds(
            regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>,
            accessibility: Int,
        ): List<PoiEntity> {
            calls += Call("spreadInWideBounds", mapOf("cellLat" to cellLat, "cellLon" to cellLon, "excluded" to excluded, "accessibility" to accessibility))
            return wideSpread
        }

        override suspend fun categoryTagsInRegion(regionId: String): List<CategoryTag> {
            calls += Call("categoryTagsInRegion", mapOf("regionId" to regionId))
            return tags
        }

        override suspend fun transportCounts(regionId: String): List<TransportCount> = transport

        override suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity> {
            calls += Call("embassiesOf", mapOf("regionId" to regionId, "country" to country))
            return embassies
        }

        override suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity> {
            calls += Call("searchByName", mapOf("regionIds" to regionIds, "pattern" to pattern, "limit" to limit))
            return named.take(limit)
        }

        override suspend fun nearest(
            regionIds: List<String>, lat: Double, lon: Double, lonScale: Double,
            minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
        ): List<PoiEntity> {
            calls += Call(
                "nearest",
                mapOf("lonScale" to lonScale, "minLat" to minLat, "maxLat" to maxLat, "minLon" to minLon, "maxLon" to maxLon, "limit" to limit),
            )
            return near.take(limit)
        }

        override suspend fun insertAll(pois: List<PoiRow>) = Unit
        override suspend fun poisForRegion(regionId: String): List<PoiEntity> = emptyList()
        override suspend fun deleteForRegion(regionId: String, extra: Boolean?) = Unit
    }

    private fun entity(
        id: Long,
        name: String = "Da Mario $id",
        category: String = "restaurant",
        osmTag: String = "amenity=restaurant",
        lat: Double = 45.0,
        lon: Double = 9.0,
    ) = PoiEntity(id = id, regionId = "italia", name = name, category = category, lat = lat, lon = lon, osmTag = osmTag)

    // Un metro di latitudine in gradi, come nel repository (111.320 m per grado).
    private fun metersNorth(meters: Double) = meters / 111_320.0

    // ---- searchByName ----

    @Test
    fun `searchByName con testo troppo corto o vuoto non interroga il database`() = runBlocking {
        val dao = RecordingDao()
        val repo = PoiRepository(dao)
        assertEquals(emptyList<Poi>(), repo.searchByName(listOf("italia"), "", 5))
        assertEquals(emptyList<Poi>(), repo.searchByName(listOf("italia"), "a", 5))
        assertEquals(emptyList<Poi>(), repo.searchByName(listOf("italia"), "   ", 5))
        // Un solo carattere dopo il trim: "  a  ".
        assertEquals(emptyList<Poi>(), repo.searchByName(listOf("italia"), "  a  ", 5))
        assertTrue(dao.calls.isEmpty())
    }

    @Test
    fun `searchByName senza regioni non interroga il database`() = runBlocking {
        val dao = RecordingDao()
        assertEquals(emptyList<Poi>(), PoiRepository(dao).searchByName(emptyList(), "roma", 5))
        assertTrue(dao.calls.isEmpty())
    }

    @Test
    fun `searchByName toglie gli spazi ai bordi e passa il pattern senza accenti`() = runBlocking {
        val dao = RecordingDao()
        PoiRepository(dao).searchByName(listOf("lettonia", "estonia"), "  Rīga ", 3)

        val call = dao.calls.single()
        assertEquals(listOf("lettonia", "estonia"), call.args["regionIds"])
        assertEquals(accentInsensitiveGlob("Rīga"), call.args["pattern"])
        assertEquals(12, call.args["limit"])
    }

    @Test
    fun `searchByName con due caratteri cerca e con limite zero non restituisce nulla`() = runBlocking {
        val dao = RecordingDao().apply { named = listOf(entity(1)) }
        val repo = PoiRepository(dao)
        assertEquals(1, repo.searchByName(listOf("italia"), "ma", 5).size)
        dao.calls.clear()
        assertEquals(emptyList<Poi>(), repo.searchByName(listOf("italia"), "ma", 0))
        assertTrue(dao.calls.isEmpty())
    }

    @Test
    fun `searchByName con limite negativo torna vuoto senza eccezioni`() = runBlocking {
        val dao = RecordingDao().apply { named = listOf(entity(1)) }
        assertEquals(emptyList<Poi>(), PoiRepository(dao).searchByName(listOf("italia"), "ma", -3))
        assertEquals(emptyList<Poi>(), PoiRepository(dao).searchByName(listOf("italia"), "ma", Int.MIN_VALUE))
        assertTrue(dao.calls.isEmpty())
    }

    @Test
    fun `searchByName con un limite enorme non va in overflow del contatore`() = runBlocking {
        val dao = RecordingDao().apply { named = listOf(entity(1)) }
        // limit * 4 su Int: 1_000_000 resta nel campo, e il risultato e' comunque tagliato dopo il filtro.
        val result = PoiRepository(dao).searchByName(listOf("italia"), "ma", 1_000_000)
        assertEquals(1, result.size)
        assertEquals(4_000_000, dao.calls.single().args["limit"])
    }

    @Test
    fun `searchByName con un limite oltre un quarto di Int satura il contatore`() = runBlocking {
        val dao = RecordingDao().apply { named = listOf(entity(1)) }
        val repo = PoiRepository(dao)
        assertEquals(1, repo.searchByName(listOf("italia"), "ma", Int.MAX_VALUE).size)
        assertEquals(Int.MAX_VALUE, dao.calls.last().args["limit"])
        repo.searchByName(listOf("italia"), "ma", Int.MAX_VALUE / 4 + 1)
        assertEquals(Int.MAX_VALUE, dao.calls.last().args["limit"])
        repo.searchByName(listOf("italia"), "ma", Int.MAX_VALUE / 4)
        assertEquals(Int.MAX_VALUE / 4 * 4, dao.calls.last().args["limit"])
    }

    @Test
    fun `searchByName tiene l'ordine del database e scarta i POI nascosti`() = runBlocking {
        val dao = RecordingDao().apply {
            named = listOf(
                entity(1, name = "Zeta"),
                // Categoria sconosciuta: ALTRO e' nascosta sulla mappa.
                entity(2, name = "Tag raro", category = "boh", osmTag = "office=boh"),
                entity(3, name = "Alfa"),
            )
        }
        val result = PoiRepository(dao).searchByName(listOf("italia"), "al", 10)
        assertEquals(listOf("Zeta", "Alfa"), result.map { it.name })
    }

    // ---- inBounds ----

    @Test
    fun `inBounds con esattamente maxPois righe non usa il campione`() = runBlocking {
        val dao = RecordingDao().apply { inBounds = (1L..4L).map { entity(it) } }
        val result = PoiRepository(dao).inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4)

        assertEquals(4, result.pois.size)
        assertEquals(listOf("poisInBounds"), dao.calls.map { it.name })
        // Si chiede una riga in piu' della soglia.
        assertEquals(5, dao.calls.single().args["limit"])
    }

    @Test
    fun `inBounds con maxPois piu' uno righe passa al campione per celle`() = runBlocking {
        val dao = RecordingDao().apply {
            inBounds = (1L..10L).map { entity(it) }
            spread = listOf(entity(100))
        }
        val result = PoiRepository(dao).inBounds("roma", 41.0, 41.4, 12.0, 13.0, 4)

        assertEquals(listOf(100L), result.pois.map { it.id })
        assertEquals(listOf("poisInBounds", "spreadInBounds"), dao.calls.map { it.name })
        val spreadCall = dao.calls[1]
        // 4 POI: griglia 2 x 2, quindi celle di meta' lato.
        assertEquals(0.2, spreadCall.args["cellLat"] as Double, 1e-9)
        assertEquals(0.5, spreadCall.args["cellLon"] as Double, 1e-9)
    }

    @Test
    fun `inBounds a 0,5 gradi di latitudine e' ancora un'area stretta, oltre usa l'ordine di rowid`() = runBlocking {
        val dao = RecordingDao().apply { inBounds = (1L..10L).map { entity(it) } }
        val repo = PoiRepository(dao)

        repo.inBounds("roma", 41.0, 41.5, 12.0, 13.0, 4)
        assertEquals("spreadInBounds", dao.calls.last().name)

        repo.inBounds("roma", 41.0, 41.5001, 12.0, 13.0, 4)
        assertEquals("spreadInWideBounds", dao.calls.last().name)
    }

    @Test
    fun `inBounds esclude le coppie delle categorie nascoste e passa l'accessibilita' come ordinale`() = runBlocking {
        val dao = RecordingDao().apply { inBounds = (1L..10L).map { entity(it) } }
        val tags = listOf(
            CategoryTag("restaurant", "amenity=restaurant"),
            CategoryTag("cafe", "amenity=cafe"),
            CategoryTag("pharmacy", "amenity=pharmacy"),
        )
        PoiRepository(dao).inBounds(
            "roma", 41.0, 41.4, 12.0, 13.0, 4, setOf(PoiCategory.CIBO_BEVANDE),
            accessibility = MapAccessibility.ONLY_ACCESSIBLE, tags = tags,
        )

        assertEquals(listOf("restaurant|amenity=restaurant", "cafe|amenity=cafe"), dao.calls[0].args["excluded"])
        assertEquals(MapAccessibility.ONLY_ACCESSIBLE.ordinal, dao.calls[0].args["accessibility"])
        assertEquals(listOf("restaurant|amenity=restaurant", "cafe|amenity=cafe"), dao.calls[1].args["excluded"])
        assertEquals(2, dao.calls[1].args["accessibility"])
    }

    @Test
    fun `inBounds senza tags non esclude nulla e non dichiara categorie`() = runBlocking {
        val dao = RecordingDao().apply { inBounds = listOf(entity(1)) }
        val result = PoiRepository(dao).inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4, setOf(PoiCategory.CIBO_BEVANDE))

        assertEquals(emptyList<String>(), dao.calls.single().args["excluded"])
        assertEquals(emptySet<PoiCategory>(), result.categories)
        assertEquals(1, result.pois.size)
    }

    @Test
    fun `inBounds con maxPois zero o negativo non interroga il database e torna vuoto`() = runBlocking {
        val dao = RecordingDao().apply { inBounds = listOf(entity(1)) }
        val tags = listOf(CategoryTag("restaurant", "amenity=restaurant"))
        val repo = PoiRepository(dao)

        listOf(0, -1, Int.MIN_VALUE).forEach { maxPois ->
            val result = repo.inBounds("roma", 41.0, 41.4, 12.0, 13.0, maxPois, tags = tags)
            assertEquals(emptyList<Poi>(), result.pois)
            // Le categorie presenti restano, perche' l'utente possa riattivarle.
            assertEquals(setOf(PoiCategory.CIBO_BEVANDE), result.categories)
        }
        assertTrue(dao.calls.isEmpty())
    }

    @Test
    fun `inBounds converte le entita' in POI con tutti i campi`() = runBlocking {
        val full = PoiEntity(
            id = 7, regionId = "italia", name = "Museo", category = "museum", lat = 45.5, lon = 9.25, osmTag = "tourism=museum",
            phone = "+39 02", wheelchair = "yes", openingHours = "Mo-Su 09:00-18:00", address = "Via Roma 1", website = "https://example.org",
            email = "a@example.org", country = "IT", nameEn = "Museum", nameIt = "Museo civico", toiletsWheelchair = "limited",
            capacityDisabled = 3, extra = true,
        )
        val dao = RecordingDao().apply { inBounds = listOf(full) }

        val poi = PoiRepository(dao).inBounds("italia", 45.0, 46.0, 9.0, 10.0, 10).pois.single()

        assertEquals(
            Poi(
                id = 7, regionId = "italia", name = "Museo", category = "museum", latitude = 45.5, longitude = 9.25, osmTag = "tourism=museum",
                phone = "+39 02", wheelchair = "yes", openingHours = "Mo-Su 09:00-18:00", address = "Via Roma 1", website = "https://example.org",
                email = "a@example.org", country = "IT", nameEn = "Museum", nameIt = "Museo civico", extra = true,
                toiletsWheelchair = "limited", capacityDisabled = 3,
            ),
            poi,
        )
    }

    @Test
    fun `inBounds non scarta i POI nascosti arrivati dalla query`() = runBlocking {
        // inBounds non filtra i nascosti in Kotlin: li escludono la query e la pipeline.
        val dao = RecordingDao().apply { inBounds = listOf(entity(1, name = "restaurant")) }
        assertEquals(1, PoiRepository(dao).inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4).pois.size)
    }

    @Test
    fun `categoryTags e embassiesOf passano la regione e il paese`() = runBlocking {
        val dao = RecordingDao().apply {
            tags = listOf(CategoryTag("restaurant", "amenity=restaurant"))
            embassies = listOf(entity(1, name = "Ambasciata", category = "embassy", osmTag = "amenity=embassy"))
        }
        val repo = PoiRepository(dao)

        assertEquals(listOf(CategoryTag("restaurant", "amenity=restaurant")), repo.categoryTags("roma"))
        assertEquals(listOf("Ambasciata"), repo.embassiesOf("roma", "FR").map { it.name })
        assertEquals("FR", dao.calls.last().args["country"])
        assertEquals("roma", dao.calls.last().args["regionId"])
    }

    // ---- transportCounts ----

    @Test
    fun `transportCounts somma le righe della stessa categoria e separa la metro dal treno`() = runBlocking {
        val dao = RecordingDao().apply {
            transport = listOf(
                TransportCount("railway=station", "station", 10),
                TransportCount("railway=halt", "station", 5),
                TransportCount("railway=station", "subway_station", 7),
                TransportCount("aeroway=aerodrome", "aerodrome", 2),
                TransportCount("amenity=bus_station", "bus_station", 0),
            )
        }
        val counts = PoiRepository(dao).transportCounts("italia")

        assertEquals(15, counts[PoiCategory.TRENO])
        assertEquals(7, counts[PoiCategory.METRO])
        assertEquals(2, counts[PoiCategory.AEROPORTO])
        assertEquals(0, counts[PoiCategory.AUTOBUS])
        assertNull(counts[PoiCategory.TRAGHETTO])
    }

    @Test
    fun `transportCounts senza righe e' una mappa vuota`() = runBlocking {
        assertEquals(emptyMap<PoiCategory, Int>(), PoiRepository(RecordingDao()).transportCounts("italia"))
    }

    // ---- nearby ----

    @Test
    fun `nearby senza regioni torna vuoto col raggio minimo senza interrogare il database`() = runBlocking {
        val dao = RecordingDao()
        val result = PoiRepository(dao).nearby(emptyList(), 45.0, 9.0)
        assertEquals(NearbyPois(emptyList(), 150), result)
        assertTrue(dao.calls.isEmpty())
    }

    @Test
    fun `nearby chiede un rettangolo di 600 metri attorno alla posizione`() = runBlocking {
        val dao = RecordingDao()
        PoiRepository(dao).nearby(listOf("italia"), 60.0, 10.0)

        val args = dao.calls.single().args
        val dLat = 600.0 / 111_320.0
        assertEquals(60.0 - dLat, args["minLat"] as Double, 1e-9)
        assertEquals(60.0 + dLat, args["maxLat"] as Double, 1e-9)
        // A 60 gradi il grado di longitudine e' lungo la meta': il rettangolo e' largo il doppio.
        assertEquals(10.0 - 2 * dLat, args["minLon"] as Double, 1e-6)
        assertEquals(10.0 + 2 * dLat, args["maxLon"] as Double, 1e-6)
        assertEquals(cos(Math.toRadians(60.0)) * cos(Math.toRadians(60.0)), args["lonScale"] as Double, 1e-9)
        assertEquals(120, args["limit"])
    }

    @Test
    fun `nearby ai poli non divide per zero e il rettangolo resta finito`() = runBlocking {
        val dao = RecordingDao()
        PoiRepository(dao).nearby(listOf("antartide"), 90.0, 0.0)

        val args = dao.calls.single().args
        assertTrue((args["minLon"] as Double).isFinite())
        assertTrue((args["maxLon"] as Double).isFinite())
        assertTrue((args["maxLon"] as Double) > (args["minLon"] as Double))
    }

    @Test
    fun `nearby con cinque POI entro 150 metri sceglie 150 e scarta quelli piu' lontani`() = runBlocking {
        val near = (1L..5L).map { entity(it, lat = 45.0 + metersNorth(20.0 * it)) }
        val far = entity(6, lat = 45.0 + metersNorth(400.0))
        val dao = RecordingDao().apply { this.near = near + far }

        val result = PoiRepository(dao).nearby(listOf("italia"), 45.0, 9.0)

        assertEquals(150, result.radiusMeters)
        assertEquals((1L..5L).toList(), result.pois.map { it.id })
    }

    @Test
    fun `nearby allarga il raggio a 300 e a 600 se vicino ce ne sono meno di cinque`() = runBlocking {
        val dao = RecordingDao().apply {
            near = listOf(
                entity(1, lat = 45.0 + metersNorth(100.0)),
                entity(2, lat = 45.0 + metersNorth(250.0)),
                entity(3, lat = 45.0 + metersNorth(500.0)),
                entity(4, lat = 45.0 + metersNorth(700.0)),
            )
        }
        val result = PoiRepository(dao).nearby(listOf("italia"), 45.0, 9.0)

        assertEquals(600, result.radiusMeters)
        assertEquals(listOf(1L, 2L, 3L), result.pois.map { it.id })
    }

    @Test
    fun `nearby con cinque POI entro 300 metri usa 300`() = runBlocking {
        val dao = RecordingDao().apply { near = (1L..5L).map { entity(it, lat = 45.0 + metersNorth(200.0 + it)) } }
        assertEquals(300, PoiRepository(dao).nearby(listOf("italia"), 45.0, 9.0).radiusMeters)
    }

    @Test
    fun `nearby senza POI torna vuoto col raggio massimo`() = runBlocking {
        val result = PoiRepository(RecordingDao()).nearby(listOf("italia"), 45.0, 9.0)
        assertEquals(NearbyPois(emptyList(), 600), result)
    }

    @Test
    fun `nearby tiene al massimo trenta POI nell'ordine del database e salta i nascosti`() = runBlocking {
        val hidden = entity(0, name = "restaurant", lat = 45.0)
        val visible = (1L..40L).map { entity(it, lat = 45.0 + metersNorth(10.0)) }
        val dao = RecordingDao().apply { near = listOf(hidden) + visible }

        val result = PoiRepository(dao).nearby(listOf("italia"), 45.0, 9.0)

        assertEquals(150, result.radiusMeters)
        assertEquals((1L..30L).toList(), result.pois.map { it.id })
    }

    @Test
    fun `nearby tiene conto della longitudine in funzione della latitudine`() = runBlocking {
        // Alle alte latitudini un grado di longitudine e' corto: 0,0005 gradi a 60 N sono circa 28 m, non 56.
        val dao = RecordingDao().apply {
            near = (1L..5L).map { entity(it, lat = 60.0, lon = 10.0 + 0.0005 * it) }
        }
        val result = PoiRepository(dao).nearby(listOf("norvegia"), 60.0, 10.0)
        // L'ultimo e' a 5 * 28 = 140 m: entro 150 (a 56 m per passo sarebbe 280 m, raggio 300).
        assertEquals(150, result.radiusMeters)
        assertEquals(5, result.pois.size)
    }

    // ---- Poi.displayName e nearbyRadius ----

    private fun poi(nameEn: String?, nameIt: String?) = Poi(
        id = 1, regionId = "r", name = "Local", category = "c", latitude = 0.0, longitude = 0.0, osmTag = "t", phone = null,
        nameEn = nameEn, nameIt = nameIt,
    )

    @Test
    fun `displayName sceglie il nome nella lingua e ripiega sull'altro e sul locale`() {
        assertEquals("Museum", poi("Museum", "Museo").displayName("en"))
        assertEquals("Museo", poi("Museum", "Museo").displayName("it"))
        // Italiano senza nameIt: ripiega sull'inglese, poi sul locale.
        assertEquals("Museum", poi("Museum", null).displayName("it"))
        assertEquals("Local", poi(null, null).displayName("it"))
        // Inglese senza nameEn: il locale, non l'italiano.
        assertEquals("Local", poi(null, "Museo").displayName("en"))
        // Una lingua qualunque diversa da "en" vale come italiano.
        assertEquals("Museo", poi("Museum", "Museo").displayName("fr"))
        assertEquals("Museo", poi("Museum", "Museo").displayName(""))
    }

    @Test
    fun `nearbyRadius sui bordi esatti delle soglie`() {
        assertEquals(150, nearbyRadius(List(5) { 150.0 }))
        assertEquals(300, nearbyRadius(List(5) { 150.0001 }))
        assertEquals(300, nearbyRadius(List(4) { 10.0 } + List(1) { 300.0 }))
        assertEquals(600, nearbyRadius(List(4) { 10.0 } + List(1) { 300.0001 }))
        assertEquals(150, nearbyRadius(List(1000) { 0.0 }))
        assertEquals(600, nearbyRadius(List(5) { Double.POSITIVE_INFINITY }))
        assertEquals(600, nearbyRadius(List(5) { Double.NaN }))
    }
}
