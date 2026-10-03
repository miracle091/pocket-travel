package com.pockettravel.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

class GenerateWorldMapTest {
    private lateinit var dir: File
    private lateinit var source: File
    private lateinit var outputDir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pocket-travel-world").toFile()
        source = File(dir, "ne.geojson")
        outputDir = File(dir, "out/world")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun feature(props: JSONObject, geometry: JSONObject) =
        JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry)

    private fun polygon(vararg ring: Pair<Double, Double>) = JSONObject()
        .put("type", "Polygon")
        .put("coordinates", JSONArray().put(JSONArray(ring.map { JSONArray(listOf(it.first, it.second)) })))

    private fun props(iso: String?, nameIt: String = "Italia", name: String = "Italy", x: Double = 12.0, y: Double = 42.0) =
        JSONObject().put("NAME_IT", nameIt).put("NAME", name).put("LABEL_X", x).put("LABEL_Y", y).apply { iso?.let { put("ISO_A2_EH", it) } }

    private fun run(features: List<JSONObject>) {
        source.writeText(JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features)).toString())
        runMain(arrayOf(source.path, outputDir.path))
    }

    // main(args) e' sovraccaricato da altri file dello stesso package: si chiama dalla classe generata.
    private fun runMain(args: Array<String>) {
        try {
            Class.forName("com.pockettravel.pipeline.GenerateWorldMapKt").getMethod("main", Array<String>::class.java).invoke(null, args as Any)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun features(name: String): JSONArray = JSONObject(File(outputDir, name).readText()).getJSONArray("features")

    private fun ringOf(feature: JSONObject): JSONArray = feature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)

    @Test
    fun `senza i due argomenti da' un errore d'uso`() {
        assertThrows(IllegalArgumentException::class.java) { runMain(arrayOf(source.path)) }
        assertThrows(IllegalArgumentException::class.java) { runMain(arrayOf(source.path, outputDir.path, "extra")) }
    }

    @Test
    fun `senza paesi scrive due raccolte vuote e crea la cartella di output`() {
        run(emptyList())

        assertEquals(0, features("countries.geojson").length())
        assertEquals(0, features("country-labels.geojson").length())
    }

    @Test
    fun `iso minuscolo, arrotondamento a tre decimali e solo iso nelle proprieta' del paese`() {
        run(listOf(feature(props("IT"), polygon(0.0 to 0.0, 1.23449 to -2.0006, 5.0 to 5.0, 0.0 to 0.0))))

        val country = features("countries.geojson").getJSONObject(0)
        assertEquals(setOf("iso"), country.getJSONObject("properties").keySet())
        assertEquals("it", country.getJSONObject("properties").getString("iso"))
        val ring = ringOf(country)
        assertEquals(1.234, ring.getJSONArray(1).getDouble(0), 1e-9)
        assertEquals(-2.001, ring.getJSONArray(1).getDouble(1), 1e-9)
    }

    @Test
    fun `i vertici consecutivi uguali dopo l'arrotondamento si fondono`() {
        run(listOf(feature(props("SM"), polygon(0.0 to 0.0, 0.0004 to 0.0004, 1.0 to 1.0, 1.0001 to 1.0001, 0.0 to 0.0))))

        val ring = ringOf(features("countries.geojson").getJSONObject(0))
        assertEquals(3, ring.length())
        assertEquals(1.0, ring.getJSONArray(1).getDouble(0), 1e-9)
    }

    @Test
    fun `l'ultimo vertice dell'anello resta anche se uguale al precedente arrotondato`() {
        run(listOf(feature(props("SM"), polygon(0.0 to 0.0, 1.0 to 1.0, 1.0001 to 1.0001))))

        assertEquals(3, ringOf(features("countries.geojson").getJSONObject(0)).length())
    }

    @Test
    fun `i MultiPolygon si arrotondano poligono per poligono`() {
        val multi = JSONObject().put("type", "MultiPolygon").put(
            "coordinates",
            JSONArray()
                .put(JSONArray().put(JSONArray().put(JSONArray(listOf(1.00049, 2.0))).put(JSONArray(listOf(3.0, 4.0)))))
                .put(JSONArray().put(JSONArray().put(JSONArray(listOf(9.0, 9.0))).put(JSONArray(listOf(9.0001, 9.0001))))),
        )
        run(listOf(feature(props("US"), multi)))

        val geometry = features("countries.geojson").getJSONObject(0).getJSONObject("geometry")
        assertEquals("MultiPolygon", geometry.getString("type"))
        val polygons = geometry.getJSONArray("coordinates")
        assertEquals(2, polygons.length())
        assertEquals(1.0, polygons.getJSONArray(0).getJSONArray(0).getJSONArray(0).getDouble(0), 1e-9)
    }

    @Test
    fun `una geometria di altro tipo passa con le coordinate invariate`() {
        val point = JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(1.23456, 2.0)))
        run(listOf(feature(props("VA"), point)))

        val geometry = features("countries.geojson").getJSONObject(0).getJSONObject("geometry")
        assertEquals("Point", geometry.getString("type"))
        assertEquals(1.23456, geometry.getJSONArray("coordinates").getDouble(0), 1e-9)
    }

    @Test
    fun `un codice iso assente o non di due lettere non da' la proprieta' iso`() {
        run(
            listOf(
                feature(props(null), polygon(0.0 to 0.0, 1.0 to 1.0)),
                feature(props("-99"), polygon(0.0 to 0.0, 1.0 to 1.0)),
                feature(props(""), polygon(0.0 to 0.0, 1.0 to 1.0)),
            ),
        )

        for (name in listOf("countries.geojson", "country-labels.geojson")) {
            val all = features(name)
            assertEquals(3, all.length())
            for (i in 0 until all.length()) assertFalse(all.getJSONObject(i).getJSONObject("properties").has("iso"))
        }
    }

    @Test
    fun `l'etichetta ha nome italiano, rank e coordinate arrotondate`() {
        run(listOf(feature(props("IT", x = 12.12349, y = -41.9996).put("LABELRANK", 2), polygon(0.0 to 0.0, 1.0 to 1.0))))

        val label = features("country-labels.geojson").getJSONObject(0)
        val properties = label.getJSONObject("properties")
        assertEquals("Italia", properties.getString("name"))
        assertEquals(2, properties.getInt("rank"))
        assertEquals("it", properties.getString("iso"))
        val coordinates = label.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals("Point", label.getJSONObject("geometry").getString("type"))
        assertEquals(12.123, coordinates.getDouble(0), 1e-9)
        assertEquals(-42.0, coordinates.getDouble(1), 1e-9)
    }

    @Test
    fun `senza nome italiano usa quello inglese e senza LABELRANK il rank e' 5`() {
        run(listOf(feature(props("XK", nameIt = " ", name = "Kosovo"), polygon(0.0 to 0.0, 1.0 to 1.0))))

        val properties = features("country-labels.geojson").getJSONObject(0).getJSONObject("properties")
        assertEquals("Kosovo", properties.getString("name"))
        assertEquals(5, properties.getInt("rank"))
    }

    @Test
    fun `i paesi escono nello stesso ordine in entrambi i file`() {
        run(
            listOf(
                feature(props("IT", nameIt = "Italia"), polygon(0.0 to 0.0, 1.0 to 1.0)),
                feature(props("FR", nameIt = "Francia"), polygon(0.0 to 0.0, 1.0 to 1.0)),
            ),
        )

        assertEquals(listOf("it", "fr"), (0 until 2).map { features("countries.geojson").getJSONObject(it).getJSONObject("properties").getString("iso") })
        assertEquals(listOf("Italia", "Francia"), (0 until 2).map { features("country-labels.geojson").getJSONObject(it).getJSONObject("properties").getString("name") })
    }
}
