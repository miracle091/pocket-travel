package com.pockettravel.app.onboarding

import com.pockettravel.app.regions.PackageUiState
import com.pockettravel.app.regions.RegionBbox
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.core.data.PackageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendedRegionsBoundaryTest {
    private fun region(id: String, country: String?, bbox: RegionBbox?) =
        RegionUiItem(id, id, 0, RegionStatus.NOT_INSTALLED, countryCode = country, bbox = bbox)

    private fun box(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) = RegionBbox(minLon, minLat, maxLon, maxLat)

    private fun withPackages(item: RegionUiItem, sizes: Map<PackageKind, Long>) =
        item.copy(packages = sizes.map { (kind, bytes) -> PackageUiState(kind, RegionStatus.NOT_INSTALLED, downloadBytes = bytes, installedBytes = null) })

    // Paese di prova: rettangolo 10..11 x 40..41, centro (10,5; 40,5).
    private val home = region("casa", "xx", box(10.0, 40.0, 11.0, 41.0))

    @Test
    fun `senza nessun vicino consentito restano solo le regioni del paese`() {
        val near = region("vicina", "yy", box(11.0, 40.0, 12.0, 41.0))

        assertEquals(listOf(home), recommendedRegions(listOf(home, near), "xx", maxNeighbours = 0))
    }

    @Test
    fun `i vicini sono ordinati per distanza dal centro del paese, non per ordine del catalogo`() {
        val east = region("est", "aa", box(11.2, 40.0, 12.0, 41.0))
        val north = region("nord", "bb", box(10.0, 41.3, 11.0, 42.3))
        val west = region("ovest", "cc", box(9.2, 40.0, 9.8, 41.0))

        assertEquals(listOf(home, west, east, north), recommendedRegions(listOf(east, north, west, home), "xx"))
    }

    @Test
    fun `una regione a esattamente il margine conta come vicina, appena oltre no`() {
        val onMargin = region("sul-margine", "aa", box(11.5, 40.0, 12.0, 41.0))
        val beyond = region("oltre", "bb", box(11.6, 40.0, 12.0, 41.0))

        assertEquals(listOf(home, onMargin), recommendedRegions(listOf(home, onMargin, beyond), "xx"))
    }

    @Test
    fun `la distanza in latitudine conta quanto quella in longitudine`() {
        val southNear = region("sud", "aa", box(10.0, 38.5, 11.0, 39.5))
        val southFar = region("sud-lontano", "bb", box(10.0, 38.0, 11.0, 39.4))

        assertEquals(listOf(home, southNear), recommendedRegions(listOf(home, southNear, southFar), "xx"))
    }

    @Test
    fun `le regioni senza rettangolo non sono mai vicine`() {
        val noBox = region("senza-riquadro", "aa", null)

        assertEquals(listOf(home), recommendedRegions(listOf(home, noBox), "xx"))
    }

    @Test
    fun `le regioni senza codice paese non sono del paese`() {
        val noCode = region("senza-codice", null, box(10.0, 40.0, 11.0, 41.0))

        // Non e' del paese, ma il suo rettangolo tocca quello della regione: e' una vicina.
        assertEquals(listOf(home, noCode), recommendedRegions(listOf(noCode, home), "xx"))
    }

    @Test
    fun `il codice del paese si confronta senza distinguere le maiuscole nei due sensi`() {
        val upper = region("maiuscolo", "XX", box(10.0, 40.0, 11.0, 41.0))

        assertEquals(listOf(upper), recommendedRegions(listOf(upper), "xx"))
        assertEquals(listOf(upper), recommendedRegions(listOf(upper), "Xx"))
    }

    @Test
    fun `un paese con piu' regioni le consiglia tutte, nell'ordine del catalogo, prima dei vicini`() {
        val partA = region("parte-a", "xx", box(10.0, 40.0, 10.5, 41.0))
        val partB = region("parte-b", "xx", box(10.5, 40.0, 11.0, 41.0))
        val near = region("vicina", "yy", box(11.2, 40.0, 12.0, 41.0))

        assertEquals(listOf(partB, partA, near), recommendedRegions(listOf(near, partB, partA), "xx"))
    }

    @Test
    fun `i rettangoli mancanti di alcune regioni del paese non impediscono di cercare i vicini`() {
        val partNoBox = region("parte-senza-riquadro", "xx", null)
        val near = region("vicina", "yy", box(11.2, 40.0, 12.0, 41.0))

        assertEquals(listOf(home, partNoBox, near), recommendedRegions(listOf(home, partNoBox, near), "xx"))
    }

    @Test
    fun `un catalogo vuoto non consiglia nulla`() {
        assertEquals(emptyList<RegionUiItem>(), recommendedRegions(emptyList(), "xx"))
    }

    @Test
    fun `senza pacchetti offerti la scelta di default e' vuota`() {
        assertEquals(emptySet<PackageKind>(), defaultPackageChoice(home, withRouting = true, withTransit = true))
    }

    @Test
    fun `citta' e civici offerti si scelgono sempre, i POI extra mai`() {
        val item = withPackages(home, PackageKind.entries.associateWith { 1L })

        assertEquals(
            setOf(PackageKind.MAP, PackageKind.POI, PackageKind.ADDRESSES, PackageKind.CITIES),
            defaultPackageChoice(item, withRouting = false),
        )
        assertEquals(
            PackageKind.entries.toSet() - PackageKind.POI_EXTRA,
            defaultPackageChoice(item, withRouting = true, withTransit = true),
        )
    }

    @Test
    fun `la rete stradale offerta non si sceglie senza percorsi di navigazione anche con i mezzi pubblici`() {
        val item = withPackages(home, mapOf(PackageKind.ROUTING to 5L, PackageKind.TRANSIT to 5L))

        assertEquals(setOf(PackageKind.TRANSIT), defaultPackageChoice(item, withRouting = false, withTransit = true))
        assertEquals(setOf(PackageKind.ROUTING), defaultPackageChoice(item, withRouting = true, withTransit = false))
    }

    @Test
    fun `il peso somma solo i pacchetti richiesti che la regione offre`() {
        val item = withPackages(home, mapOf(PackageKind.MAP to 0L, PackageKind.ROUTING to 100L, PackageKind.POI to 20L, PackageKind.TRANSIT to 3L))

        assertEquals(120L, downloadBytes(item, setOf(PackageKind.ROUTING, PackageKind.POI, PackageKind.ADDRESSES)))
        assertEquals(0L, downloadBytes(item, setOf(PackageKind.MAP)))
        assertEquals(123L, downloadBytes(item, PackageKind.entries.toSet()))
    }
}
