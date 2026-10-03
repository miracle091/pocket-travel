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

    private fun zoomOffset(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / 3L

    private fun tileId(z: Int, x: Int, y: Int): Long = zoomOffset(z) + Hilbert.zxyToIndex(z, x.toLong(), y.toLong())

    /** File pmtiles sorgente di una sola cella (o dell'addresses.pmtiles gia' installato), zoom 14 come i civici. */
    private fun writeFixture(file: File, tiles: Map<Triple<Int, Int, Int>, ByteArray>) {
        val entries = tiles.map { (zxy, data) -> PmtilesEntry(tileId(zxy.first, zxy.second, zxy.third), data) }
        PmtilesWriter.write(
            outputFile = file,
            entries = entries,
            metadataJson = """{"name":"addresses"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
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

    @Test
    fun `i database di ricerca, scaricato per la cella nuova, copiato per l'invariata che ce l'ha, assente senza search`() {
        val (storage, installer) = newStorageAndInstaller()
        val regionDir = storage.directoryFor("san-marino").apply { mkdirs() }
        writeFixture(File(regionDir, RegionStorage.ADDRESSES_FILE), mapOf(Triple(14, 1, 1) to byteArrayOf(0xAA.toByte())))
        File(regionDir, RegionStorage.ADDRESSES_CELLS_FILE).writeText(RegionStorage.encodeAddressCells(mapOf("12/0/0" to "v1", "12/1/0" to "v1")))
        File(regionDir, RegionStorage.ADDRESSES_SEARCH_DIR).apply { mkdirs() }.resolve(RegionStorage.addressSearchFileName("12/0/0")).writeText("vecchio A")

        fun search(name: String) = AddressSearchEntry(cellFile(name))
        val cellA = AddressGridCell("12/0/0", "v1", cellFile("cell-a.pmtiles"), search = search("search-a.db")) // invariata, ricerca gia' installata
        val cellB = AddressGridCell("12/1/0", "v2", cellFile("cell-b.pmtiles"), search = search("search-b.db")) // cambiata
        val cellC = AddressGridCell("12/0/1", "v1", cellFile("cell-c.pmtiles")) // senza ricerca
        val cells = listOf(cellA, cellB, cellC)
        val staging = storage.stagingDirectoryFor("san-marino", "v2").apply { mkdirs() }
        File(staging, "search-b.db").writeText("nuovo B")
        writeFixture(File(staging, cellB.file.name), mapOf(Triple(14, 5, 2) to byteArrayOf(0xBB.toByte())))
        writeFixture(File(staging, cellC.file.name), mapOf(Triple(14, 1, 5) to byteArrayOf(0xCC.toByte())))

        val plan = installer.plan("san-marino", RegionAddressGridEntry(cells))
        assertEquals(listOf("12/1/0"), plan.searchToDownload.map { it.id })
        val merged = mutableListOf<Pair<List<String>, File>>()
        installer.mergeInto("san-marino", mapSource, plan, staging, mergeSearch = { cellFiles, target -> merged += cellFiles.map { it.name } to target })

        val searchDir = File(staging, RegionStorage.ADDRESSES_SEARCH_DIR)
        assertEquals(listOf("12-0-0.db", "12-1-0.db"), searchDir.list()!!.sorted())
        // il database unico si costruisce dai soli file per cella, dentro la cartella che si attiva
        assertEquals(listOf(listOf("12-0-0.db", "12-1-0.db") to File(searchDir, RegionStorage.ADDRESSES_SEARCH_DB)), merged)
        assertEquals("vecchio A", File(searchDir, "12-0-0.db").readText())
        assertEquals("nuovo B", File(searchDir, "12-1-0.db").readText())
        // la copia lascia intatto il file installato (resta il pacchetto attivo fino a activatePackage)
        assertTrue(File(regionDir, "${RegionStorage.ADDRESSES_SEARCH_DIR}/12-0-0.db").isFile)
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
