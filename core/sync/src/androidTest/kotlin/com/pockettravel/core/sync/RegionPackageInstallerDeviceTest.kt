package com.pockettravel.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Hilbert
import ch.poole.geo.pmtiles.Reader
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.db.RegionDatabase
import java.io.ByteArrayOutputStream
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

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
        repository = RegionRepository(db.regionPackageDao(), db.poiDao(), storage, db, db.cityDao())
        installer = RegionPackageInstaller(
            RegionPackageDownloader(OkHttpClient(), storage),
            repository,
            storage,
            PoiImporter(db.poiDao(), db),
            RegionRoutingGraphInstaller(),
            PmtilesExtractor(),
            CityImporter(db.cityDao(), db),
            RegionAddressGridInstaller(storage),
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

    private fun entryWithPreview(previewVersion: String = "pv1") =
        entry().copy(preview = PreviewPackageEntry(previewVersion, 9, manifestFile("preview.pmtiles")))

    @Test
    fun laPreviewSiInstallaDaSolaConUnDownloadEDiSiAggiornaSenzaEsserRichiesta() = runBlocking {
        files["preview.pmtiles"] = "anteprima 1".toByteArray()

        installer.install(entryWithPreview(), setOf(PackageKind.POI))

        var installed = repository.installed("san-marino")!!
        assertEquals("pv1", installed.previewVersion)
        assertEquals("anteprima 1", File(regionDir, RegionStorage.PREVIEW_FILE).readText())
        val previewModified = File(regionDir, RegionStorage.PREVIEW_FILE).lastModified()

        // Stessa versione dell'anteprima: aggiornando un altro pacchetto non la ritocca.
        installer.install(entryWithPreview(), setOf(PackageKind.MAP))
        assertEquals(previewModified, File(regionDir, RegionStorage.PREVIEW_FILE).lastModified())

        // Versione dell'anteprima cambiata: si aggiorna anche se l'utente aggiorna solo un altro pacchetto.
        files["preview.pmtiles"] = "anteprima 2".toByteArray()
        installer.install(entryWithPreview(previewVersion = "pv2"), setOf(PackageKind.MAP))
        installed = repository.installed("san-marino")!!
        assertEquals("pv2", installed.previewVersion)
        assertEquals("anteprima 2", File(regionDir, RegionStorage.PREVIEW_FILE).readText())
    }

    @Test
    fun eliminareLaMappaNonToccaLAnteprimaSoloLEliminazioneDellaRegioneLaCancella() = runBlocking {
        files["preview.pmtiles"] = "anteprima".toByteArray()
        val entryConPreview = entryWithPreview()
        installer.install(entryConPreview, entryConPreview.availableKinds)

        repository.removePackage("san-marino", PackageKind.MAP)
        assertTrue(File(regionDir, RegionStorage.PREVIEW_FILE).isFile)
        assertEquals("pv1", repository.installed("san-marino")!!.previewVersion)

        repository.remove("san-marino")
        assertFalse(regionDir.exists())
    }

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
        installer.install(entry(), entry().availableKinds)
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
        installer.install(entry(), entry().availableKinds)

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

    @Test
    fun iPoiCompressiSiScaricanoEDecomprimonoPrimaDellImport() = runBlocking {
        val compressed = compressedPoiEntry()

        installer.install(compressed, setOf(PackageKind.POI))

        val installed = repository.installed("san-marino")!!
        assertEquals("p1", installed.poiVersion)
        assertEquals(708, db.poiDao().poisForRegion("san-marino").size)
        assertEquals(compressed.poi.file.sizeBytes, installed.sizeBytes)
    }

    @Test
    fun unPoiDecompressoDiversoDalManifestNonSiInstalla() {
        val compressed = compressedPoiEntry().let { it.copy(poi = it.poi.copy(file = it.poi.file.copy(sha256 = "0".repeat(64)))) }

        assertThrows(PermanentRegionPackageException::class.java) { runBlocking { installer.install(compressed, setOf(PackageKind.POI)) } }
        assertNull(runBlocking { repository.installed("san-marino") })
    }

    /** poi.db servito solo compresso con xz, come lo pubblica la pipeline. */
    private fun compressedPoiEntry(): RegionManifestEntry {
        files["poi.db.xz"] = ByteArrayOutputStream().also { out -> XZOutputStream(out, LZMA2Options()).use { it.write(files.getValue("poi.db")) } }.toByteArray()
        val entry = entry().let { it.copy(poi = it.poi.copy(fileXz = manifestFile("poi.db.xz"))) }
        files.remove("poi.db") // il file non compresso non e' pubblicato
        return entry
    }

    @Test
    fun iCiviciCompressiSiScaricanoEDecomprimonoPrimaDiEssereAttivati() = runBlocking {
        val compressed = compressedAddressesEntry()

        installer.install(compressed, setOf(PackageKind.ADDRESSES))

        val installed = repository.installed("san-marino")!!
        assertEquals(compressed.versionOf(PackageKind.ADDRESSES), installed.addressesVersion)
        assertTrue(installed.addressesVersion!!.startsWith("grid-"))
        Reader(File(regionDir, RegionStorage.ADDRESSES_FILE)).use { reader ->
            assertArrayEquals("civici sammarinesi".toByteArray(), reader.getTile(14, 0, 0))
        }
    }

    @Test
    fun unCivicoDecompressoDiversoDalManifestNonSiInstalla() {
        val compressed = compressedAddressesEntry().let { entry ->
            val cell = entry.addressGrid!!.cells.single()
            entry.copy(addressGrid = RegionAddressGridEntry(listOf(cell.copy(file = cell.file.copy(sha256 = "0".repeat(64))))))
        }

        assertThrows(PermanentRegionPackageException::class.java) { runBlocking { installer.install(compressed, setOf(PackageKind.ADDRESSES)) } }
        assertNull(runBlocking { repository.installed("san-marino") })
    }

    /** Cella dei civici a griglia (address-grid-plan.md), servita solo compressa con xz, come la pubblica la pipeline. */
    private fun compressedAddressesEntry(): RegionManifestEntry {
        val cellName = "cell-0-0-0--addresses.pmtiles"
        val cellFile = File(workDir, cellName)
        PmtilesWriter.write(
            outputFile = cellFile,
            entries = listOf(PmtilesEntry(tileId(14, 0, 0), "civici sammarinesi".toByteArray())),
            metadataJson = """{"name":"addresses"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 14, maxZoom = 14,
            minLon = 12.40, minLat = 43.89, maxLon = 12.52, maxLat = 43.99,
        )
        files[cellName] = cellFile.readBytes()
        files["$cellName.xz"] = ByteArrayOutputStream().also { out -> XZOutputStream(out, LZMA2Options()).use { it.write(files.getValue(cellName)) } }.toByteArray()
        val cell = AddressGridCell("0/0/0", "a1", manifestFile(cellName), manifestFile("$cellName.xz"))
        val entry = entry().copy(addressGrid = RegionAddressGridEntry(listOf(cell)))
        files.remove(cellName) // il file non compresso non e' pubblicato
        return entry
    }

    private fun tileId(z: Int, x: Int, y: Int): Long {
        val zoomOffset = ((1L shl (2 * z)) - 1L) / 3L
        return zoomOffset + Hilbert.zxyToIndex(z, x.toLong(), y.toLong())
    }

    @Test
    fun leGuideDiCittaCompresseSiScaricanoDecomprimonoEImportanoInRegionDb() = runBlocking {
        val compressed = compressedCitiesEntry()

        installer.install(compressed, setOf(PackageKind.CITIES))

        val installed = repository.installed("san-marino")!!
        assertEquals("c1", installed.citiesVersion)
        assertEquals(
            listOf("Cosa vedere"),
            db.cityDao().sectionsFor("san-marino", "Citta di San Marino").map { it.title },
        )
    }

    @Test
    fun unaGuidaDiCittaDecompressaDiversaDalManifestNonSiInstalla() {
        val compressed = compressedCitiesEntry().let { it.copy(cities = it.cities!!.copy(file = it.cities.file.copy(sha256 = "0".repeat(64)))) }

        assertThrows(PermanentRegionPackageException::class.java) { runBlocking { installer.install(compressed, setOf(PackageKind.CITIES)) } }
        assertNull(runBlocking { repository.installed("san-marino") })
    }

    /** cities.db servito solo compresso con xz, come lo pubblica la pipeline. */
    private fun compressedCitiesEntry(): RegionManifestEntry {
        files["cities.db"] = citiesDbBytes()
        files["cities.db.xz"] = ByteArrayOutputStream().also { out -> XZOutputStream(out, LZMA2Options()).use { it.write(files.getValue("cities.db")) } }.toByteArray()
        val entry = entry().copy(cities = CitiesPackageEntry("c1", manifestFile("cities.db"), manifestFile("cities.db.xz")))
        files.remove("cities.db") // il file non compresso non e' pubblicato
        return entry
    }

    /** cities.db minimo (stesso schema pubblicato da tools/data-pipeline), senza bisogno di un asset. */
    private fun citiesDbBytes(): ByteArray {
        val file = File(workDir, "cities-src.db")
        file.delete()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { cities ->
            cities.execSQL("CREATE TABLE city_sections (city TEXT NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, sourceUrl TEXT NOT NULL)")
            cities.execSQL("INSERT INTO city_sections VALUES ('Citta di San Marino', 'COSA_VEDERE', 'Cosa vedere', 'corpo', 'https://it.wikivoyage.org/wiki/Citta_di_San_Marino')")
        }
        val bytes = file.readBytes()
        file.delete()
        return bytes
    }
}
