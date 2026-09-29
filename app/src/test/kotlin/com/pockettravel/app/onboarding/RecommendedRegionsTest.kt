package com.pockettravel.app.onboarding

import com.pockettravel.app.regions.PackageUiState
import com.pockettravel.app.regions.RegionBbox
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.core.data.PackageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendedRegionsTest {

    private fun region(id: String, country: String, bbox: RegionBbox) =
        RegionUiItem(id, id, 0, RegionStatus.NOT_INSTALLED, countryCode = country, bbox = bbox)

    private val italia = region("italia", "it", RegionBbox(6.6, 35.5, 18.5, 47.1))
    private val sanMarino = region("san-marino", "sm", RegionBbox(12.40, 43.89, 12.52, 43.99))
    private val svizzera = region("svizzera", "ch", RegionBbox(5.9, 45.8, 10.5, 47.8))
    private val grecia = region("grecia", "gr", RegionBbox(19.4, 34.8, 28.2, 41.7))
    private val giappone = region("giappone", "jp", RegionBbox(122.9, 24.0, 153.9, 45.5))
    private val all = listOf(giappone, grecia, svizzera, sanMarino, italia)

    @Test
    fun `prima il proprio paese, poi i vicini dal piu' vicino, non i lontani`() {
        assertEquals(listOf(italia, sanMarino, svizzera), recommendedRegions(all, "IT"))
    }

    @Test
    fun `i vicini sono al massimo quelli richiesti`() {
        assertEquals(listOf(italia, sanMarino), recommendedRegions(all, "it", maxNeighbours = 1))
    }

    @Test
    fun `nessun consiglio senza paese o se il paese non e' nel catalogo`() {
        assertEquals(emptyList<RegionUiItem>(), recommendedRegions(all, null))
        assertEquals(emptyList<RegionUiItem>(), recommendedRegions(all, "br"))
    }

    @Test
    fun `pacchetti di default come Scarica, percorsi solo con le indicazioni`() {
        val item = italia.copy(
            packages = listOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI, PackageKind.POI_EXTRA)
                .map { PackageUiState(it, RegionStatus.NOT_INSTALLED, downloadBytes = 10, installedBytes = null) },
        )

        assertEquals(setOf(PackageKind.MAP, PackageKind.POI), defaultPackageChoice(item, withRouting = false))
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), defaultPackageChoice(item, withRouting = true))
        assertEquals(30L, downloadBytes(item, setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI)))
    }

    @Test
    fun `gli orari dei mezzi pubblici si spuntano solo se la modalita' li propone, e solo se la regione li offre`() {
        val withTransit = italia.copy(
            packages = listOf(PackageKind.MAP, PackageKind.POI, PackageKind.TRANSIT)
                .map { PackageUiState(it, RegionStatus.NOT_INSTALLED, downloadBytes = 10, installedBytes = null) },
        )
        val without = italia.copy(packages = withTransit.packages.filter { it.kind != PackageKind.TRANSIT })

        assertEquals(setOf(PackageKind.MAP, PackageKind.POI, PackageKind.TRANSIT), defaultPackageChoice(withTransit, withRouting = false, withTransit = true))
        assertEquals(setOf(PackageKind.MAP, PackageKind.POI), defaultPackageChoice(withTransit, withRouting = false, withTransit = false))
        assertEquals(setOf(PackageKind.MAP, PackageKind.POI), defaultPackageChoice(withTransit, withRouting = false))
        assertEquals(setOf(PackageKind.MAP, PackageKind.POI), defaultPackageChoice(without, withRouting = false, withTransit = true))
    }
}
