package com.pockettravel.app.regions

import com.pockettravel.core.data.RegionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionZoneDialogTest {

    @Test
    fun `la zona si misura in km alla latitudine media`() {
        // 1 grado a 60 gradi nord: ~56 km in larghezza, ~111 in altezza.
        assertEquals(56 to 111, boundsSizeKm(10.0, 59.5, 11.0, 60.5))
    }

    @Test
    fun `solo le nazioni grandi propongono una zona`() {
        assertTrue(RegionBbox(6.6, 35.5, 18.5, 47.1).isLarge()) // Italia
        assertFalse(RegionBbox(20.9, 55.6, 28.3, 58.1).isLarge()) // Lettonia
        assertFalse(RegionBbox(12.4, 43.89, 12.52, 43.99).isLarge()) // San Marino
    }

    @Test
    fun `la zona non tocca i paesi vicini installati per intero`() {
        // Aperto dall'Italia: la Svizzera e' installata intera, la Francia no (o ha gia' una zona).
        val targets = zoneTargets("italia", listOf("italia", "svizzera", "francia"), installedWhole = setOf("svizzera"))
        assertEquals(listOf("italia", "francia"), targets)
    }

    @Test
    fun `la regione aperta riceve sempre la zona anche se installata per intero`() {
        assertEquals(listOf("italia"), zoneTargets("italia", listOf("italia"), installedWhole = setOf("italia")))
    }

    // Confine finto a 10 gradi est: a ovest 'fr', a est 'it'; sotto i 44 gradi nord e' mare.
    private val countryAt = { lat: Double, lon: Double -> if (lat < 44.0) null else if (lon < 10.0) "fr" else "it" }
    private val candidates = listOf(
        ZoneCandidate("italia", "it", RegionBbox(6.0, 35.0, 19.0, 48.0)),
        ZoneCandidate("francia", "fr", RegionBbox(-5.0, 41.0, 10.0, 51.0)),
    )

    @Test
    fun `una zona a cavallo del confine conta i punti di ogni paese, il mare no`() {
        val shares = zoneShares(RegionZone(9.0, 43.0, 13.0, 47.0), candidates, countryAt)
        // 3/4 a est del confine; 3/4 sopra i 44 gradi: 20x15 punti di terra, 15 colonne in Italia e 5 in Francia.
        assertEquals(listOf("italia" to 225, "francia" to 75), shares)
    }

    @Test
    fun `paesi senza regioni nel catalogo non contano`() {
        assertEquals(listOf("italia" to 400), zoneShares(RegionZone(11.0, 45.0, 12.0, 46.0), candidates.take(1), countryAt))
        assertTrue(zoneShares(RegionZone(5.0, 45.0, 6.0, 46.0), candidates.take(1), countryAt).isEmpty())
    }
}
