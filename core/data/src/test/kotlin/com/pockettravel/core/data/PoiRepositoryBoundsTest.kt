package com.pockettravel.core.data

import com.pockettravel.core.data.db.CATEGORY_TAGS_IN_REGION
import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.db.POIS_IN_BOUNDS
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.SPREAD_IN_BOUNDS
import com.pockettravel.core.data.db.SPREAD_IN_WIDE_BOUNDS
import com.pockettravel.core.data.db.TransportCount
import com.pockettravel.core.poi.PoiCategory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * I segnalini della mappa a campione: le categorie filtrate si escludono prima del conteggio e della scelta per cella,
 * con le query vere del DAO eseguite su sqlite-jdbc (android.database.sqlite non c'e' nei test JVM).
 */
class PoiRepositoryBoundsTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use {
            it.execute("CREATE TABLE poi(id INTEGER PRIMARY KEY AUTOINCREMENT, regionId TEXT, name TEXT, category TEXT, lat REAL, lon REAL, osmTag TEXT, wheelchair TEXT, toiletsWheelchair TEXT)")
            // Lo stesso nome dell'indice di PoiEntity: SPREAD_IN_WIDE_BOUNDS lo impone con INDEXED BY.
            it.execute("CREATE INDEX index_poi_regionId ON poi(regionId)")
        }
    }

    @After
    fun tearDown() = connection.close()

    private fun insert(name: String, category: String, osmTag: String, lat: Double, lon: Double, wheelchair: String? = null) {
        connection.prepareStatement("INSERT INTO poi(regionId, name, category, lat, lon, osmTag, wheelchair) VALUES ('roma', ?, ?, ?, ?, ?, ?)").use {
            it.setString(1, name)
            it.setString(2, category)
            it.setDouble(3, lat)
            it.setDouble(4, lon)
            it.setString(5, osmTag)
            it.setString(6, wheelchair)
            it.executeUpdate()
        }
    }

    // Esegue la query del DAO: `(:excluded)` diventa un parametro per elemento (come fa Room) e i parametri con nome
    // diventano ?NNN con l'indice della prima comparsa (come in SQLite), anche quando si ripetono.
    private fun <T> run(sql: String, params: Map<String, Any>, excluded: List<String>, read: (ResultSet) -> T): List<T> {
        val placeholders = excluded.indices.joinToString(",") { ":excluded$it" }
        val expanded = sql.replace("(:excluded)", "($placeholders)")
        val names = Regex(":(\\w+)").findAll(expanded).map { it.groupValues[1] }.distinct().toList()
        val values = params + excluded.mapIndexed { i, tag -> "excluded$i" to tag }
        return connection.prepareStatement(Regex(":(\\w+)").replace(expanded) { "?" + (names.indexOf(it.groupValues[1]) + 1) }).use { statement ->
            names.forEachIndexed { i, name -> statement.setObject(i + 1, values.getValue(name)) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    }

    private inner class JdbcPoiDao : PoiDao {
        private fun area(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) =
            mapOf("regionId" to regionId, "minLat" to minLat, "maxLat" to maxLat, "minLon" to minLon, "maxLon" to maxLon)

        private fun entity(rs: ResultSet) = PoiEntity(
            id = rs.getLong("id"), regionId = rs.getString("regionId"), name = rs.getString("name"), category = rs.getString("category"),
            lat = rs.getDouble("lat"), lon = rs.getDouble("lon"), osmTag = rs.getString("osmTag"),
        )

        override suspend fun poisInBounds(
            regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, excluded: List<String>, accessibility: Int, limit: Int,
        ): List<PoiEntity> =
            run(POIS_IN_BOUNDS, area(regionId, minLat, maxLat, minLon, maxLon) + mapOf("accessibility" to accessibility, "limit" to limit), excluded, ::entity)

        override suspend fun spreadInBounds(
            regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>,
            accessibility: Int,
        ): List<PoiEntity> = run(
            SPREAD_IN_BOUNDS,
            area(regionId, minLat, maxLat, minLon, maxLon) + mapOf("cellLat" to cellLat, "cellLon" to cellLon, "accessibility" to accessibility),
            excluded,
            ::entity,
        )

        override suspend fun spreadInWideBounds(
            regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>,
            accessibility: Int,
        ): List<PoiEntity> = run(
            SPREAD_IN_WIDE_BOUNDS,
            area(regionId, minLat, maxLat, minLon, maxLon) + mapOf("cellLat" to cellLat, "cellLon" to cellLon, "accessibility" to accessibility),
            excluded,
            ::entity,
        )

        override suspend fun categoryTagsInRegion(regionId: String): List<CategoryTag> =
            run(CATEGORY_TAGS_IN_REGION, mapOf("regionId" to regionId), emptyList()) { CategoryTag(it.getString("category"), it.getString("osmTag")) }

        override suspend fun insertAll(pois: List<PoiEntity>) = Unit
        override suspend fun poisForRegion(regionId: String): List<PoiEntity> = emptyList()
        override suspend fun transportCounts(regionId: String): List<TransportCount> = emptyList()
        override suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity> = emptyList()
        override suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity> = emptyList()
        override suspend fun nearest(
            regionIds: List<String>, lat: Double, lon: Double, lonScale: Double,
            minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
        ): List<PoiEntity> = emptyList()
        override suspend fun deleteForRegion(regionId: String, extra: Boolean?) = Unit
    }

    // Area di 1 x 1 grado, maxPois = 4 (celle da 0,5 gradi): 30 ristoranti con i rowid piu' bassi, tutti nella cella
    // della prima farmacia, e altre due farmacie in celle diverse.
    private fun fill() {
        repeat(30) { insert("Ristorante $it", "restaurant", "amenity=restaurant", 41.10 + it * 0.001, 12.10) }
        insert("Farmacia A", "pharmacy", "amenity=pharmacy", 41.12, 12.12)
        insert("Farmacia B", "pharmacy", "amenity=pharmacy", 41.70, 12.12)
        insert("Farmacia C", "pharmacy", "amenity=pharmacy", 41.70, 12.70)
    }

    @Test
    fun `con una categoria filtrata le altre non spariscono dalle celle`() = runBlocking {
        fill()
        val result = PoiRepository(JdbcPoiDao()).let { it.inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4, setOf(PoiCategory.CIBO_BEVANDE), tags = it.categoryTags("roma")) }
        // Prima: le 33 righe superano la soglia, la cella della Farmacia A sceglie un ristorante e la farmacia sparisce.
        assertEquals(listOf("Farmacia A", "Farmacia B", "Farmacia C"), result.pois.map { it.name }.sorted())
    }

    @Test
    fun `senza filtri resta il campione e le categorie presenti comprendono quelle filtrate`() = runBlocking {
        fill()
        val repo = PoiRepository(JdbcPoiDao())
        assertTrue(repo.inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4).pois.size <= 4)
        val filtered = repo.inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4, setOf(PoiCategory.CIBO_BEVANDE), tags = repo.categoryTags("roma"))
        assertEquals(setOf(PoiCategory.CIBO_BEVANDE, PoiCategory.FARMACIA), filtered.categories)
    }

    @Test
    fun `il campione per cella vale sia per le aree larghe sia per quelle strette`() = runBlocking {
        fill()
        val repo = PoiRepository(JdbcPoiDao())
        // Sopra WIDE_AREA_DEGREES (0,5 gradi di latitudine) le righe si leggono in ordine di rowid, sotto per fascia di latitudine:
        // stesso risultato. Fascia stretta (41,0-41,4): 31 righe, tutte nella stessa cella (0,2 x 0,5 gradi) del primo ristorante.
        val wide = repo.inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4, setOf(PoiCategory.CIBO_BEVANDE), tags = repo.categoryTags("roma"))
        val narrow = repo.inBounds("roma", 41.0, 41.4, 12.0, 13.0, 4)
        assertEquals(listOf("Farmacia A", "Farmacia B", "Farmacia C"), wide.pois.map { it.name }.sorted())
        assertEquals(listOf("Ristorante 0"), narrow.pois.map { it.name })
    }

    @Test
    fun `con solo accessibili il campione si sceglie fra i POI accessibili`() = runBlocking {
        // 30 ristoranti senza dati di accessibilita' nella cella della farmacia accessibile, con i rowid piu' bassi.
        repeat(30) { insert("Ristorante $it", "restaurant", "amenity=restaurant", 41.10 + it * 0.001, 12.10) }
        insert("Farmacia accessibile", "pharmacy", "amenity=pharmacy", 41.12, 12.12, wheelchair = "yes")
        insert("Bar non accessibile", "bar", "amenity=bar", 41.70, 12.70, wheelchair = "no")
        val repo = PoiRepository(JdbcPoiDao())

        val only = repo.inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4, accessibility = MapAccessibility.ONLY_ACCESSIBLE)
        assertEquals(listOf("Farmacia accessibile"), only.pois.map { it.name })
        val noInaccessible = repo.inBounds("roma", 41.0, 42.0, 12.0, 13.0, 40, accessibility = MapAccessibility.NO_INACCESSIBLE)
        assertTrue(noInaccessible.pois.none { it.name == "Bar non accessibile" })
        assertEquals(31, noInaccessible.pois.size)
    }
}
