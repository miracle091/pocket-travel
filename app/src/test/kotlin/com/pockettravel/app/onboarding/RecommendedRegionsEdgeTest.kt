package com.pockettravel.app.onboarding

import com.pockettravel.app.regions.PackageUiState
import com.pockettravel.app.regions.RegionBbox
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.core.data.PackageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendedRegionsEdgeTest {
    private fun region(id: String, country: String, bbox: RegionBbox?) =
        RegionUiItem(id, id, 0, RegionStatus.NOT_INSTALLED, countryCode = country, bbox = bbox)

    private val italia = region("italia", "it", RegionBbox(6.6, 35.5, 18.5, 47.1))
    private val svizzera = region("svizzera", "ch", RegionBbox(5.9, 45.8, 10.5, 47.8))

    @Test
    fun `un paese senza riquadro si consiglia da solo, senza vicini`() {
        val senzaRiquadro = region("italia", "it", null)

        assertEquals(listOf(senzaRiquadro), recommendedRegions(listOf(senzaRiquadro, svizzera), "it"))
    }

    @Test
    fun `il codice vuoto non consiglia nulla`() {
        assertEquals(emptyList<RegionUiItem>(), recommendedRegions(listOf(italia, svizzera), ""))
        assertEquals(emptyList<RegionUiItem>(), recommendedRegions(listOf(italia, svizzera), "  "))
    }

    @Test
    fun `una regione a meno del margine conta come vicina, una piu' lontana no`() {
        val quasiAdiacente = region("vicina", "xx", RegionBbox(18.9, 40.0, 20.0, 41.0))
        val lontana = region("lontana", "yy", RegionBbox(19.5, 40.0, 21.0, 41.0))

        assertEquals(listOf(italia, quasiAdiacente), recommendedRegions(listOf(italia, quasiAdiacente, lontana), "it"))
    }

    @Test
    fun `senza vicini il consiglio sono le sole regioni del paese`() {
        assertEquals(listOf(italia), recommendedRegions(listOf(italia), "IT"))
    }

    @Test
    fun `i pacchetti scelti sono solo tra quelli che la regione offre, e senza pacchetti il peso e' zero`() {
        val item = italia.copy(packages = listOf(PackageUiState(PackageKind.MAP, RegionStatus.NOT_INSTALLED, downloadBytes = 10, installedBytes = null)))

        assertEquals(setOf(PackageKind.MAP), defaultPackageChoice(item, withRouting = true, withTransit = true))
        assertEquals(0L, downloadBytes(item, emptySet()))
        assertEquals(0L, downloadBytes(italia, setOf(PackageKind.MAP)))
    }
}
