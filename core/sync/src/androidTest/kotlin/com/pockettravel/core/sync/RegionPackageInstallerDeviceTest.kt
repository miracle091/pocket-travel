package com.pockettravel.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.poole.geo.pmtiles.Constants
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.db.RegionDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RegionPackageInstaller con Room, SQLite e file system reali: ogni pacchetto (mappa, routing,
 * POI) si installa, aggiorna ed elimina senza toccare gli altri. I file arrivano da un
 * MockWebServer locale: poi.db vero della pipeline (asset), un .rd5 finto e una sorgente PMTiles
 * con una sola tile, servita a range come build.protomaps.com.
 */
@RunWith(AndroidJUnit4::class)
class RegionPackageInstallerDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.cacheDir, "package-installer-test")
    private val server = MockWebServer()
    private val files = mutableMapOf<String, ByteArray>()
    private lateinit var db: RegionDatabase
    private lateinit var storage: RegionStorage
    private lateinit var repository: RegionRepository
    private lateinit var installer: RegionPackageInstaller

    @Before
    fun setUp() {
        workDir.deleteRecursively()
        workDir.mkdirs()
        files["poi.db"] = context.assets.open("poi.db").use { it.readBytes() }
        files["E10_N40.rd5"] = "segmento".toByteArray()
        val pmtiles = File(workDir, "planet.pmtiles")
        PmtilesWriter.write(
            outputFile = pmtiles,
            entries = listOf(PmtilesEntry(0, byteArrayOf(1, 2, 3))),
            metadataJson = """{"name":"test"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 0, maxZoom = 0,
            minLon = -180.0, minLat = -85.0, maxLon = 180.0, maxLat = 85.0,
        )
        files["planet.pmtiles"] = pmtiles.readBytes()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bytes = files[request.path!!.removePrefix("/")] ?: return MockResponse().setResponseCode(404)
                // Come nel test JVM di PmtilesExtractor: 200 con i soli byte del range richiesto.
                val range = request.getHeader("Range")?.let { Regex("^bytes=([0-9]+)-([0-9]+)").find(it) }
                val body = if (range == null) bytes else {
                    val start = range.groupValues[1].toInt()
                    bytes.copyOfRange(start, minOf(range.groupValues[2].toInt() + 1, bytes.size))
                }
                return MockResponse().setBody(Buffer().write(body))
            }
        }
        server.start()

        db = Room.inMemoryDatabaseBuilder(context, RegionDatabase::class.java).build()
        storage = RegionStorage(File(workDir, "regions").apply { mkdirs() }, File(workDir, "staging").apply { mkdirs() })
        repository = RegionRepository(db.regionPackageDao(), db.poiDao(), storage, db)
        installer = RegionPackageInstaller(
            RegionPackageDownloader(OkHttpClient(), storage),
            repository,
            storage,
            PoiImporter(db.poiDao(), db),
            RegionRoutingGraphInstaller(),
            PmtilesExtractor(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        workDir.deleteRecursively()
    }

    private fun manifestFile(name: String) = RegionManifestFile(
        name = name,
        url = server.url("/$name").toString(),
        sizeBytes = files.getValue(name).size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(files.getValue(name)).joinToString("") { "%02x".format(it) },
    )

    private fun entry(mapVersion: String = "m1", routingVersion: String = "r1", poiVersion: String = "p1") = RegionManifestEntry(
        regionId = "san-marino",
        displayName = "San Marino",
        updatedAt = "2026-09-23T00:00:00Z",
        map = MapPackageEntry(mapVersion, MapExtractionSource(server.url("/planet.pmtiles").toString(), 12.40, 43.89, 12.52, 43.99, 0, 0)),
        routing = RoutingPackageEntry(routingVersion, listOf(manifestFile("E10_N40.rd5"))),
        poi = PoiPackageEntry(poiVersion, manifestFile("poi.db")),
    )

    private val regionDir get() = storage.directoryFor("san-marino")

    @Test
    fun ogniPacchettoSiInstallaDaSoloSenzaToccareGliAltri() = runBlocking {
        installer.install(entry(), setOf(PackageKind.POI))
        var installed = repository.installed("san-marino")!!
        assertEquals("p1", installed.poiVersion)
        assertNull(installed.mapVersion)
        assertNull(installed.routingVersion)
        assertEquals(708, db.poiDao().poisForRegion("san-marino").size)
        assertFalse(File(regionDir, RegionStorage.MAP_FILE).exists())

        installer.install(entry(), setOf(PackageKind.MAP))
        installed = repository.installed("san-marino")!!
        assertEquals("m1", installed.mapVersion)
        assertEquals("p1", installed.poiVersion)
        assertTrue(File(regionDir, RegionStorage.MAP_FILE).isFile)
        assertFalse(File(regionDir, RegionStorage.ROUTING_DIR).exists())

        installer.install(entry(), setOf(PackageKind.ROUTING))
        installed = repository.installed("san-marino")!!
        assertEquals("r1", installed.routingVersion)
        assertEquals("segmento", File(regionDir, "${RegionStorage.ROUTING_DIR}/E10_N40.rd5").readText())
        assertEquals(
            storage.packageBytes("san-marino", RegionStorage.MAP_FILE) + files.getValue("E10_N40.rd5").size + files.getValue("poi.db").size,
            installed.sizeBytes,
        )
    }

    @Test
    fun aggiornareSoloIPoiLasciaMappaERoutingAllaLoroVersione() = runBlocking {
        installer.install(entry(), PackageKind.entries.toSet())
        val mapBefore = File(regionDir, RegionStorage.MAP_FILE).lastModified()

        installer.install(entry(poiVersion = "p2"), setOf(PackageKind.POI))

        val installed = repository.installed("san-marino")!!
        assertEquals("p2", installed.poiVersion)
        assertEquals("m1", installed.mapVersion)
        assertEquals("r1", installed.routingVersion)
        assertEquals(mapBefore, File(regionDir, RegionStorage.MAP_FILE).lastModified())
        assertEquals(708, db.poiDao().poisForRegion("san-marino").size)
    }

    @Test
    fun eliminareUnPacchettoConservaGliAltriEFinitiTuttiLaRegioneSparisce() = runBlocking {
        installer.install(entry(), PackageKind.entries.toSet())

        repository.removePackage("san-marino", PackageKind.MAP)
        assertFalse(File(regionDir, RegionStorage.MAP_FILE).exists())
        assertTrue(File(regionDir, RegionStorage.ROUTING_DIR).isDirectory)
        assertNull(repository.installed("san-marino")!!.mapVersion)

        repository.removePackage("san-marino", PackageKind.POI)
        assertTrue(db.poiDao().poisForRegion("san-marino").isEmpty())
        assertEquals(files.getValue("E10_N40.rd5").size.toLong(), repository.installed("san-marino")!!.sizeBytes)

        repository.removePackage("san-marino", PackageKind.ROUTING)
        assertNull(repository.installed("san-marino"))
        assertFalse(regionDir.exists())
    }
}
