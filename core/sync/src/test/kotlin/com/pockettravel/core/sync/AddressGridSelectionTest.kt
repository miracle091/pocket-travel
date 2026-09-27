package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressGridSelectionTest {

    private val sha = "a".repeat(64)

    private fun file(name: String) = RegionManifestFile(name, "https://github.com/o/r/releases/download/address-cells/$name", 1_000L, sha)

    private fun cell(id: String, version: String = "v1") = AddressGridCell(id, version, file("cell-${id.replace('/', '-')}--addresses.pmtiles"))

    // ---- parseCellId / isSameOrDescendantOf ----

    @Test
    fun `parseCellId legge z x y validi e rifiuta gli altri`() {
        assertEquals(CellId(12, 2178, 1500), parseCellId("12/2178/1500"))
        assertEquals(CellId(0, 0, 0), parseCellId("0/0/0"))
        assertEquals("z14 = tileZoom, la singola tile: valido", CellId(14, 0, 0), parseCellId("14/0/0"))
        assertNull("z oltre il massimo delle celle (tileZoom)", parseCellId("15/0/0"))
        assertNull("x fuori dall'intervallo dello zoom", parseCellId("1/2/0"))
        assertNull("y fuori dall'intervallo dello zoom", parseCellId("1/0/2"))
        assertNull("formato non valido", parseCellId("1/0"))
        assertNull("componenti non numeriche", parseCellId("a/b/c"))
    }

    @Test
    fun `isSameOrDescendantOf riconosce lo stesso ramo del quadtree`() {
        val cell = CellId(1, 0, 0)
        assertTrue("una cella e' discendente di se stessa", CellId(1, 0, 0).isSameOrDescendantOf(cell))
        assertTrue("un figlio e' discendente del genitore", CellId(2, 0, 0).isSameOrDescendantOf(cell))
        assertTrue("un nipote e' discendente del genitore", CellId(3, 1, 1).isSameOrDescendantOf(cell))
        assertTrue("una tile z14 nel ramo e' discendente", CellId(14, 0, 0).isSameOrDescendantOf(cell))
        assertTrue("il ramo opposto non e' discendente", !CellId(2, 2, 0).isSameOrDescendantOf(cell))
        assertTrue("uno zoom piu' basso non e' mai discendente", !CellId(0, 0, 0).isSameOrDescendantOf(cell))
    }

    // ---- regionGridCells ----

    private val westernHemisphere = MapExtractionSource("https://build.protomaps.com/x.pmtiles", -170.0, -80.0, -10.0, 80.0, 0, 14)

    @Test
    fun `regionGridCells sceglie solo le celle che intersecano il riquadro della regione`() {
        val index = AddressGridIndex(
            version = "2026.09.30.1",
            tileZoom = 14,
            cells = listOf(cell("1/0/0"), cell("1/1/0"), cell("1/0/1"), cell("1/1/1")),
        )

        val selected = regionGridCells(index, westernHemisphere)

        assertEquals(setOf("1/0/0", "1/0/1"), selected.map { it.id }.toSet())
    }

    @Test
    fun `regionGridCells ignora un id di cella non valido nell'indice`() {
        val index = AddressGridIndex("v1", 14, cells = listOf(cell("1/0/0"), cell("99/0/0")))

        val selected = regionGridCells(index, westernHemisphere)

        assertEquals(listOf("1/0/0"), selected.map { it.id })
    }

    @Test
    fun `regionGridCells e' vuoto senza celle nel riquadro`() {
        val index = AddressGridIndex("v1", 14, cells = listOf(cell("1/1/0"), cell("1/1/1")))

        assertEquals(emptyList<AddressGridCell>(), regionGridCells(index, westernHemisphere))
    }

    // ---- regionAddressesGridVersion ----

    @Test
    fun `regionAddressesGridVersion e' indipendente dall'ordine delle celle e cambia se una cella cambia`() {
        val cells = listOf(cell("12/1/1", "v1"), cell("12/1/2", "v2"))
        val sameShuffled = listOf(cell("12/1/2", "v2"), cell("12/1/1", "v1"))
        val changed = listOf(cell("12/1/1", "v1"), cell("12/1/2", "v3"))

        val version = regionAddressesGridVersion(cells)

        assertTrue(version.startsWith("grid-"))
        assertEquals(version, regionAddressesGridVersion(sameShuffled))
        assertTrue(version != regionAddressesGridVersion(changed))
        assertTrue(version != regionAddressesGridVersion(emptyList()))
    }

    // ---- attachAddressGridCells ----

    private val mapEntry = MapPackageEntry("m1", westernHemisphere)
    private val baseEntry = RegionManifestEntry(
        regionId = "san-marino",
        displayName = "San Marino",
        updatedAt = "2026-09-23T00:00:00Z",
        map = mapEntry,
        routing = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5"))),
        poi = PoiPackageEntry("p1", file("poi.db")),
    )

    @Test
    fun `attachAddressGridCells lascia le regioni invariate senza indice`() {
        assertEquals(listOf(baseEntry), attachAddressGridCells(listOf(baseEntry), null))
    }

    @Test
    fun `attachAddressGridCells non tocca una regione col percorso di oggi`() {
        val withAddresses = baseEntry.copy(addresses = AddressesPackageEntry("a1", file("addresses.pmtiles")))
        val index = AddressGridIndex("v1", 14, cells = listOf(cell("1/0/0")))

        val result = attachAddressGridCells(listOf(withAddresses), index)

        assertEquals(withAddresses, result.single())
        assertNull(result.single().addressGrid)
    }

    @Test
    fun `attachAddressGridCells aggiunge le celle della regione, vuoto se nessuna interseca`() {
        val index = AddressGridIndex("v1", 14, cells = listOf(cell("1/0/0"), cell("1/1/0")))

        val result = attachAddressGridCells(listOf(baseEntry), index).single()

        assertEquals(listOf("1/0/0"), result.addressGrid!!.cells.map { it.id })

        val noIntersection = AddressGridIndex("v1", 14, cells = listOf(cell("1/1/0")))
        assertNull(attachAddressGridCells(listOf(baseEntry), noIntersection).single().addressGrid)
    }
}
