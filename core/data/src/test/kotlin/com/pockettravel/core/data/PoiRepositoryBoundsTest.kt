package com.pockettravel.core.data

import com.pockettravel.core.data.db.CATEGORY_TAGS_IN_BOUNDS
import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.db.POIS_IN_BOUNDS
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.SPREAD_IN_BOUNDS
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
            it.execute("CREATE TABLE poi(id INTEGER PRIMARY KEY AUTOINCREMENT, regionId TEXT, name TEXT, category TEXT, lat REAL, lon REAL, osmTag TEXT)")
        }
    }

    @After
    fun tearDown() = connection.close()

    private fun insert(name: String, category: String, osmTag: String, lat: Double, lon: Double) {
        connection.prepareStatement("INSERT INTO poi(regionId, name, category, lat, lon, osmTag) VALUES ('roma', ?, ?, ?, ?, ?)").use {
            it.setString(1, name)
            it.setString(2, category)
            it.setDouble(3, lat)
            it.setDouble(4, lon)
            it.setString(5, osmTag)
            it.executeUpdate()
        }
    }

    // Esegue la query del DAO: `(:excluded)` diventa un parametro per elemento (come fa Room) e i parametri con nome
    // prendono l'indice della prima comparsa (come in SQLite).
    private fun <T> run(sql: String, params: Map<String, Any>, excluded: List<String>, read: (ResultSet) -> T): List<T> {
        val placeholders = excluded.indices.joinToString(",") { ":excluded$it" }
        val expanded = sql.replace("(:excluded)", "($placeholders)")
        val names = Regex(":(\\w+)").findAll(expanded).map { it.groupValues[1] }.distinct().toList()
        val values = params + excluded.mapIndexed { i, tag -> "excluded$i" to tag }
        return connection.prepareStatement(Regex(":\\w+").replace(expanded, "?")).use { statement ->
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

        override suspend fun poisInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, excluded: List<String>): List<PoiEntity> =
            run(POIS_IN_BOUNDS, area(regionId, minLat, maxLat, minLon, maxLon), excluded, ::entity)

        override suspend fun spreadInBounds(
            regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>,
        ): List<PoiEntity> =
            run(SPREAD_IN_BOUNDS, area(regionId, minLat, maxLat, minLon, maxLon) + mapOf("cellLat" to cellLat, "cellLon" to cellLon), excluded, ::entity)

        override suspend fun categoryTagsInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<CategoryTag> =
            run(CATEGORY_TAGS_IN_BOUNDS, area(regionId, minLat, maxLat, minLon, maxLon), emptyList()) {
                CategoryTag(it.getString("category"), it.getString("osmTag"), it.getInt("count"))
            }

        override suspend fun insertAll(pois: List<PoiEntity>) = Unit
        override suspend fun poisForRegion(regionId: String): List<PoiEntity> = emptyList()
        override suspend fun transportCounts(regionId: String): List<TransportCount> = emptyList()
        override suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity> = emptyList()
        override suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity> = emptyList()
        override suspend fun nearest(
            regionIds: List<String>, lat: Double, lon: Double, lonScale: Double,
            minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
        ): List<PoiEntity> = emptyList()
        override suspend fun deleteForRegion(regionId: String) = Unit
        override suspend fun deletePackageForRegion(regionId: String, extra: Boolean) = Unit
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
        val result = PoiRepository(JdbcPoiDao()).inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4, setOf(PoiCategory.CIBO_BEVANDE))
        // Prima: le 33 righe superano la soglia, la cella della Farmacia A sceglie un ristorante e la farmacia sparisce.
        assertEquals(listOf("Farmacia A", "Farmacia B", "Farmacia C"), result.pois.map { it.name }.sorted())
    }

    @Test
    fun `senza filtri resta il campione e le categorie presenti comprendono quelle filtrate`() = runBlocking {
        fill()
        val repo = PoiRepository(JdbcPoiDao())
        assertTrue(repo.inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4).pois.size <= 4)
        val filtered = repo.inBounds("roma", 41.0, 42.0, 12.0, 13.0, 4, setOf(PoiCategory.CIBO_BEVANDE))
        assertEquals(setOf(PoiCategory.CIBO_BEVANDE, PoiCategory.FARMACIA), filtered.categories)
    }
}
