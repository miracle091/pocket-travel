package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Hilbert
import ch.poole.geo.pmtiles.Reader
import com.pockettravel.core.data.RegionStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

/**
 * RegionAddressGridInstaller.mergeInto ricostruisce addresses.pmtiles dalle celle,
 * leggendo le tile con PmtilesDirectoryReader (cammina la directory, non le coordinate:
 * vedi l'ultimo test qui sotto per una cella molto rada, dove la differenza conta davvero).
 */
class RegionAddressGridInstallerTest {

    private val sha = "a".repeat(64)

    private fun newStorageAndInstaller(): Pair<RegionStorage, RegionAddressGridInstaller> {
        val root = createTempDirectory("pocket-travel-address-grid-installer-test").toFile()
        val storage = RegionStorage(File(root, "regions").apply { mkdirs() }, File(root, "staging").apply { mkdirs() })
        return storage to RegionAddressGridInstaller(storage)
    }

    private fun cellFile(name: String) = RegionManifestFile(name, "https://github.com/o/r/releases/download/address-cells-1/$name", 1_000L, sha)

    // Database di ricerca come nel catalogo: dimensione e sha256 veri, che la decompressione controlla.
    private fun searchDb(name: String, content: String): RegionManifestFile {
        val bytes = content.toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return RegionManifestFile(name, "https://github.com/o/r/releases/download/address-cells-1/$name", bytes.size.toLong(), sha256)
    }

    private fun zoomOffset(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / 3L

    private fun tileId(z: Int, x: Int, y: Int): Long = zoomOffset(z) + Hilbert.zxyToIndex(z, x.toLong(), y.toLong())

    /** File pmtiles sorgente di una sola cella (o dell'addresses.pmtiles gia' installato), zoom 14 come i civici. */
    private fun writeFixture(file: File, tiles: Map<Triple<Int, Int, Int>, ByteArray>, tileCompression: Byte = Constants.COMPRESSION_GZIP) {
        val entries = tiles.map { (zxy, data) -> PmtilesEntry(tileId(zxy.first, zxy.second, zxy.third), data) }
        PmtilesWriter.write(
            outputFile = file,
            entries = entries,
            metadataJson = """{"name":"addresses"}""",
            tileCompression = tileCompression,
            tileType = Constants.TYPE_MVT,
            minZoom = 14,
            maxZoom = 14,
            minLon = -1.0,
            minLat = -1.0,
            maxLon = 1.0,
            maxLat = 1.0,
        )
    }

    private val mapSource = MapExtractionSource("https://build.protomaps.com/x.pmtiles", -1.0, -1.0, 1.0, 1.0, 0, 14)

    @Test
    fun `celle nuove si uniscono in un solo addresses pmtiles e addresses-cells json elenca le celle`() {
        val (storage, installer) = newStorageAndInstaller()
        val staging = storage.stagingDirectoryFor("san-marino", "v1").apply { mkdirs() }
        val cellA = AddressGridCell("12/0/0", "v1", cellFile("cell-a.pmtiles")) // z14 discendenti: x 0..3, y 0..3
        val cellB = AddressGridCell("12/1/0", "v1", cellFile("cell-b.pmtiles")) // z14 discendenti: x 4..7, y 0..3
        writeFixture(File(staging, cellA.file.name), mapOf(Triple(14, 1, 1) to byteArrayOf(0xAA.toByte())))
        writeFixture(File(staging, cellB.file.name), mapOf(Triple(14, 5, 2) to byteArrayOf(0xBB.toByte())))

        val plan = installer.plan("san-marino", RegionAddressGridEntry(listOf(cellA, cellB)))
        assertEquals(listOf(cellA, cellB).map { it.id }.toSet(), plan.toDownload.map { it.id }.toSet())
        assertEquals(emptyList<AddressGridCell>(), plan.unchanged)

        installer.mergeInto("san-marino", mapSource, plan, staging)

        val output = File(staging, RegionStorage.ADDRESSES_FILE)
        Reader(output).use { reader ->
            assertArrayEquals(byteArrayOf(0xAA.toByte()), reader.getTile(14, 1, 1))
            assertArrayEquals(byteArrayOf(0xBB.toByte()), reader.getTile(14, 5, 2))
            assertNull(reader.getTile(14, 0, 0))
        }
        // le due sorgenti per-cella non servono piu': solo le loro tile, gia' nell'archivio unito.
        assertFalse(File(staging, cellA.file.name).exists())
        assertFalse(File(staging, cellB.file.name).exists())

        val cellsFile = File(staging, RegionStorage.ADDRESSES_CELLS_FILE)
        assertEquals(mapOf("12/0/0" to "v1", "12/1/0" to "v1"), RegionStorage.decodeAddressCells(cellsFile.readText()))
    }

    private fun xzBytes(text: String): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        org.tukaani.xz.XZOutputStream(out, org.tukaani.xz.LZMA2Options()).use { it.write(text.toByteArray()) }
    }.toByteArray()

    private fun readXz(file: File): String = org.tukaani.xz.XZInputStream(file.inputStream()).use { String(it.readBytes()) }

    @Test
    fun `le ricerche per cella restano compresse, il merge riceve i database decompressi e li toglie dopo`() {
        val (storage, installer) = newStorageAndInstaller()
        val regionDir = storage.directoryFor("san-marino").apply { mkdirs() }
        writeFixture(File(regionDir, RegionStorage.ADDRESSES_FILE), mapOf(Triple(14, 1, 1) to byteArrayOf(0xAA.toByte())))
        File(regionDir, RegionStorage.ADDRESSES_CELLS_FILE).writeText(
            RegionStorage.encodeAddressCells(mapOf("12/0/0" to "v1", "12/2/0" to "v1", "12/1/0" to "v1")),
        )
        val installedDir = File(regionDir, RegionStorage.ADDRESSES_SEARCH_DIR).apply { mkdirs() }
        // A: installazione vecchia (.db non compresso); D: gia' compressa
        installedDir.resolve(RegionStorage.addressSearchFileName("12/0/0")).writeText("vecchio A")
        installedDir.resolve(RegionStorage.addressSearchArchiveName("12/2/0")).writeBytes(xzBytes("archivio D"))

        fun search(name: String, content: String) = AddressSearchEntry(searchDb(name, content), cellFile("$name.xz"))
        val cellA = AddressGridCell("12/0/0", "v1", cellFile("cell-a.pmtiles"), search = search("search-a.db", "vecchio A")) // invariata, .db vecchio
        val cellD = AddressGridCell("12/2/0", "v1", cellFile("cell-d.pmtiles"), search = search("search-d.db", "archivio D")) // invariata, gia' .xz
        val cellB = AddressGridCell("12/1/0", "v2", cellFile("cell-b.pmtiles"), search = search("search-b.db", "nuovo B")) // cambiata
        val cellC = AddressGridCell("12/0/1", "v1", cellFile("cell-c.pmtiles")) // senza ricerca
        val cells = listOf(cellA, cellB, cellC, cellD)
        val staging = storage.stagingDirectoryFor("san-marino", "v2").apply { mkdirs() }
        val downloadedB = xzBytes("nuovo B")
        File(staging, "search-b.db.xz").writeBytes(downloadedB)
        writeFixture(File(staging, cellB.file.name), mapOf(Triple(14, 5, 2) to byteArrayOf(0xBB.toByte())))
        writeFixture(File(staging, cellC.file.name), mapOf(Triple(14, 1, 5) to byteArrayOf(0xCC.toByte())))

        val plan = installer.plan("san-marino", RegionAddressGridEntry(cells))
        assertEquals("il .db vecchio e l'.xz installati non si riscaricano", listOf("12/1/0"), plan.searchToDownload.map { it.id })
        val merged = mutableListOf<Triple<String, String, File>>()
        installer.mergeInto(
            "san-marino", mapSource, plan, staging,
            mergeSearch = { cellFiles, target -> cellFiles.forEach { merged += Triple(it.name, it.readText(), target) } },
        )

        val searchDir = File(staging, RegionStorage.ADDRESSES_SEARCH_DIR)
        // il database unico lo crea mergeSearch (qui un fake): in cartella restano i soli archivi
        assertEquals(listOf("12-0-0.db.xz", "12-1-0.db.xz", "12-2-0.db.xz"), searchDir.list()!!.sorted())
        assertEquals("vecchio A", readXz(File(searchDir, "12-0-0.db.xz")))
        assertEquals("nuovo B", readXz(File(searchDir, "12-1-0.db.xz")))
        assertEquals("archivio D", readXz(File(searchDir, "12-2-0.db.xz")))
        // il merge vede i database decompressi, in ordine di nome, e scrive nella cartella che si attiva
        val target = File(searchDir, RegionStorage.ADDRESSES_SEARCH_DB)
        assertEquals(
            listOf(Triple("12-0-0.db", "vecchio A", target), Triple("12-1-0.db", "nuovo B", target), Triple("12-2-0.db", "archivio D", target)),
            merged,
        )
        // l'archivio scaricato e' stato spostato, i database decompressi tolti: in staging non resta nulla delle ricerche
        assertFalse(File(staging, "search-b.db.xz").exists())
        assertFalse(File(staging, "addresses-search-unpacked").exists())
        // la copia lascia intatto il file installato (resta il pacchetto attivo fino a activatePackage)
        assertTrue(File(installedDir, "12-0-0.db").isFile)
    }

    @Test
    fun `una ricerca scaricata senza xz nel catalogo si comprime`() {
        val (storage, installer) = newStorageAndInstaller()
        val cell = AddressGridCell("12/0/0", "v1", cellFile("cell-a.pmtiles"), search = AddressSearchEntry(searchDb("search-a.db", "contenuto A")))
        val staging = storage.stagingDirectoryFor("san-marino", "v1").apply { mkdirs() }
        File(staging, "search-a.db").writeText("contenuto A")
        writeFixture(File(staging, cell.file.name), mapOf(Triple(14, 1, 1) to byteArrayOf(0xAA.toByte())))

        val plan = installer.plan("san-marino", RegionAddressGridEntry(listOf(cell)))
        installer.mergeInto("san-marino", mapSource, plan, staging, mergeSearch = { _, _ -> })

        assertEquals("contenuto A", readXz(File(staging, "${RegionStorage.ADDRESSES_SEARCH_DIR}/12-0-0.db.xz")))
        assertFalse(File(staging, "search-a.db").exists())
    }

    @Test
    fun `una ricerca che decompressa supera la dimensione del catalogo o non ha il suo sha256 e' rifiutata`() {
        val unpacked = createTempDirectory("pocket-travel-xz-test").toFile()
        val archive = File(unpacked, "a.db.xz").apply { writeBytes(xzBytes("x".repeat(10_000))) }

        val tooBig = runCatching { decompressXz(archive, File(unpacked, "a.db"), searchDb("a.db", "x".repeat(100))) }.exceptionOrNull()
        val wrongSha = runCatching { decompressXz(archive, File(unpacked, "b.db"), searchDb("b.db", "y".repeat(10_000))) }.exceptionOrNull()

        assertTrue(tooBig is PermanentRegionPackageException)
        assertTrue(wrongSha is PermanentRegionPackageException)
    }

    @Test
    fun `una cella invariata con search ma senza il database installato lo riscarica`() {
        val (storage, installer) = newStorageAndInstaller()
        val regionDir = storage.directoryFor("san-marino").apply { mkdirs() }
        File(regionDir, RegionStorage.ADDRESSES_CELLS_FILE).writeText(RegionStorage.encodeAddressCells(mapOf("12/0/0" to "v1")))
        val cell = AddressGridCell("12/0/0", "v1", cellFile("cell-a.pmtiles"), search = AddressSearchEntry(cellFile("search-a.db")))

        val plan = installer.plan("san-marino", RegionAddressGridEntry(listOf(cell)))

        assertEquals(emptyList<AddressGridCell>(), plan.toDownload)
        assertEquals(listOf(cell), plan.searchToDownload)
    }

    @Test
    fun `un addresses pmtiles legacy senza sidecar non si legge, tutte le celle si scaricano`() {
        val (storage, installer) = newStorageAndInstaller()
        val regionDir = storage.directoryFor("san-marino").apply { mkdirs() }
        // Civici installati prima del passaggio alla griglia: addresses.pmtiles c'e' ma senza
        // addresses-cells.json (mai scritto senza griglia) — il primo giro a griglia deve scaricare
        // tutte le celle e ricostruire il file, non leggere tile da quello vecchio.
        writeFixture(File(regionDir, RegionStorage.ADDRESSES_FILE), mapOf(Triple(14, 1, 1) to byteArrayOf(0xAA.toByte())))

        val cellA = AddressGridCell("12/0/0", "v1", cellFile("cell-a.pmtiles")) // z14 discendenti: x 0..3, y 0..3
        val staging = storage.stagingDirectoryFor("san-marino", "v1").apply { mkdirs() }
        writeFixture(File(staging, cellA.file.name), mapOf(Triple(14, 1, 1) to byteArrayOf(0xBB.toByte())))

        val plan = installer.plan("san-marino", RegionAddressGridEntry(listOf(cellA)))
        assertEquals(listOf("12/0/0"), plan.toDownload.map { it.id })
        assertEquals("nessuna cella e' 'gia' installata' senza il sidecar: tutte da scaricare", emptyList<AddressGridCell>(), plan.unchanged)

        installer.mergeInto("san-marino", mapSource, plan, staging)

        Reader(File(staging, RegionStorage.ADDRESSES_FILE)).use { reader ->
            assertArrayEquals(
                "la tile viene dalla cella appena scaricata, non dal vecchio addresses.pmtiles (mai letto)",
                byteArrayOf(0xBB.toByte()),
                reader.getTile(14, 1, 1),
            )
        }
    }

    @Test
    fun `una cella invariata si tiene dal file installato, una cambiata si riscarica, una sparita non torna`() {
        val (storage, installer) = newStorageAndInstaller()
        val regionDir = storage.directoryFor("san-marino").apply { mkdirs() }
        // Stato gia' installato: cellA (12/0/0, z14 x 0..3 y 0..3) e cellB (11/1/0, z14 x 8..15 y 0..7).
        writeFixture(
            File(regionDir, RegionStorage.ADDRESSES_FILE),
            mapOf(Triple(14, 1, 1) to byteArrayOf(0xAA.toByte()), Triple(14, 10, 4) to byteArrayOf(0xBB.toByte())),
        )
        File(regionDir, RegionStorage.ADDRESSES_CELLS_FILE).writeText(RegionStorage.encodeAddressCells(mapOf("12/0/0" to "v1", "11/1/0" to "v1")))

        // Nuovo giro: cellA invariata, cellB sparita (divisa dalla pipeline nelle sue figlie a z12).
        val cellA = AddressGridCell("12/0/0", "v1", cellFile("cell-a.pmtiles"))
        val cellB1 = AddressGridCell("12/2/0", "v2", cellFile("cell-b1.pmtiles")) // z14 discendenti: x 8..11, y 0..3
        val cellB2 = AddressGridCell("12/3/1", "v2", cellFile("cell-b2.pmtiles")) // z14 discendenti: x 12..15, y 4..7
        val staging = storage.stagingDirectoryFor("san-marino", "v2").apply { mkdirs() }
        writeFixture(File(staging, cellB1.file.name), mapOf(Triple(14, 9, 1) to byteArrayOf(0xC1.toByte())))
        writeFixture(File(staging, cellB2.file.name), mapOf(Triple(14, 13, 5) to byteArrayOf(0xC2.toByte())))

        val plan = installer.plan("san-marino", RegionAddressGridEntry(listOf(cellA, cellB1, cellB2)))
        assertEquals(listOf("12/0/0"), plan.unchanged.map { it.id })
        assertEquals(setOf("12/2/0", "12/3/1"), plan.toDownload.map { it.id }.toSet())

        installer.mergeInto("san-marino", mapSource, plan, staging)

        Reader(File(staging, RegionStorage.ADDRESSES_FILE)).use { reader ->
            assertArrayEquals("la tile di cellA, invariata, viene dal file gia' installato", byteArrayOf(0xAA.toByte()), reader.getTile(14, 1, 1))
            assertArrayEquals(byteArrayOf(0xC1.toByte()), reader.getTile(14, 9, 1))
            assertArrayEquals(byteArrayOf(0xC2.toByte()), reader.getTile(14, 13, 5))
            assertNull("la tile della vecchia cellB (sparita, divisa in figlie) non deve tornare", reader.getTile(14, 10, 4))
        }
        assertEquals(
            mapOf("12/0/0" to "v1", "12/2/0" to "v2", "12/3/1" to "v2"),
            RegionStorage.decodeAddressCells(File(staging, RegionStorage.ADDRESSES_CELLS_FILE).readText()),
        )
    }

    private fun gzip(data: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.GZIPOutputStream(out).use { it.write(data) }
    }.toByteArray()

    private fun gunzip(data: ByteArray): ByteArray = java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }

    @Test
    fun `celle gzip e senza compressione si uniscono in un file con tutte le tile in gzip`() {
        val (storage, installer) = newStorageAndInstaller()
        val regionDir = storage.directoryFor("san-marino").apply { mkdirs() }
        val tileA = "tile A invariata".toByteArray()
        val tileB = "tile B gzip".toByteArray()
        val tileC = "tile C senza compressione".toByteArray()
        writeFixture(File(regionDir, RegionStorage.ADDRESSES_FILE), mapOf(Triple(14, 1, 1) to gzip(tileA)))
        File(regionDir, RegionStorage.ADDRESSES_CELLS_FILE).writeText(RegionStorage.encodeAddressCells(mapOf("12/0/0" to "v1")))

        val cellA = AddressGridCell("12/0/0", "v1", cellFile("cell-a.pmtiles")) // invariata, dal file installato
        val cellB = AddressGridCell("12/1/0", "v2", cellFile("cell-b.pmtiles")) // nuova, tile gzip
        val cellC = AddressGridCell("12/2/0", "v2", cellFile("cell-c.pmtiles")) // nuova, tile senza compressione
        val staging = storage.stagingDirectoryFor("san-marino", "v2").apply { mkdirs() }
        // C prima di B: la compressione del file unito non dipende dalla prima cella letta.
        writeFixture(File(staging, cellC.file.name), mapOf(Triple(14, 9, 1) to tileC), Constants.COMPRESSION_NONE)
        writeFixture(File(staging, cellB.file.name), mapOf(Triple(14, 5, 2) to gzip(tileB)))

        val plan = installer.plan("san-marino", RegionAddressGridEntry(listOf(cellA, cellC, cellB)))
        assertEquals(listOf("12/2/0", "12/1/0"), plan.toDownload.map { it.id })
        installer.mergeInto("san-marino", mapSource, plan, staging)

        val output = File(staging, RegionStorage.ADDRESSES_FILE)
        PmtilesDirectoryReader(output).use { assertEquals(Constants.COMPRESSION_GZIP, it.tileCompression) }
        Reader(output).use { reader ->
            assertArrayEquals(tileA, gunzip(reader.getTile(14, 1, 1)))
            assertArrayEquals(tileB, gunzip(reader.getTile(14, 5, 2)))
            assertArrayEquals(tileC, gunzip(reader.getTile(14, 9, 1)))
        }
    }

    @Test
    fun `una cella invariata molto rada (seme z3-z6) non itera le coordinate discendenti`() {
        val (storage, installer) = newStorageAndInstaller()
        val regionDir = storage.directoryFor("cella-rada").apply { mkdirs() }
        // cellA a z4 (4/3/2): 1024 x 1024 tile z14 discendenti (x 3072..4095, y 2048..3071), quasi
        // tutte vuote — con getTile(z,x,y) per ognuna il test non finirebbe in tempo utile. Una tile
        // fuori dall'intervallo (0,0) prova che il filtro funziona comunque, non solo che e' veloce.
        writeFixture(
            File(regionDir, RegionStorage.ADDRESSES_FILE),
            mapOf(Triple(14, 3100, 2200) to byteArrayOf(0xAA.toByte()), Triple(14, 0, 0) to byteArrayOf(0xBB.toByte())),
        )
        File(regionDir, RegionStorage.ADDRESSES_CELLS_FILE).writeText(RegionStorage.encodeAddressCells(mapOf("4/3/2" to "v1")))
        val cellA = AddressGridCell("4/3/2", "v1", cellFile("cell-a.pmtiles"))
        val staging = storage.stagingDirectoryFor("cella-rada", "v2").apply { mkdirs() }

        val plan = installer.plan("cella-rada", RegionAddressGridEntry(listOf(cellA)))
        assertEquals(listOf("4/3/2"), plan.unchanged.map { it.id })

        val elapsedMillis = kotlin.system.measureTimeMillis {
            installer.mergeInto("cella-rada", mapSource, plan, staging)
        }
        assertTrue("una camminata della directory non deve avvicinarsi al tempo di un milione di getTile", elapsedMillis < 5_000)

        Reader(File(staging, RegionStorage.ADDRESSES_FILE)).use { reader ->
            assertArrayEquals(byteArrayOf(0xAA.toByte()), reader.getTile(14, 3100, 2200))
            assertNull("fuori dall'intervallo della cella, non deve tornare", reader.getTile(14, 0, 0))
        }
    }
}
