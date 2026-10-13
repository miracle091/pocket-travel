package com.pockettravel.app.regions

import com.pockettravel.core.data.RegionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionZoneEdgeCasesTest {

    @Test
    fun `all'equatore un grado misura circa 111 km in entrambe le direzioni`() {
        assertEquals(111 to 111, boundsSizeKm(0.0, -0.5, 1.0, 0.5))
    }

    @Test
    fun `un riquadro senza superficie misura zero`() {
        assertEquals(0 to 0, boundsSizeKm(10.0, 45.0, 10.0, 45.0))
    }

    @Test
    fun `la larghezza si accorcia salendo di latitudine`() {
        val (equatorWidth, _) = boundsSizeKm(0.0, 0.0, 1.0, 1.0)
        val (northWidth, _) = boundsSizeKm(0.0, 65.0, 1.0, 66.0)

        assertTrue(northWidth < equatorWidth / 2)
    }

    @Test
    fun `la soglia delle regioni grandi sta intorno ai 200 mila km quadrati`() {
        assertFalse(RegionBbox(0.0, 0.0, 4.0, 4.0).isLarge())
        assertTrue(RegionBbox(0.0, 0.0, 4.1, 4.1).isLarge())
    }

    @Test
    fun `un riquadro senza superficie non e' grande`() {
        assertFalse(RegionBbox(10.0, 45.0, 10.0, 45.0).isLarge())
    }

    @Test
    fun `senza regioni nell'elenco non c'e' nessun bersaglio`() {
        assertEquals(emptyList<String>(), zoneTargets("italia", emptyList(), installedWhole = setOf("italia")))
    }

    @Test
    fun `la regione aperta che non e' nell'elenco non viene aggiunta`() {
        assertEquals(listOf("francia"), zoneTargets("italia", listOf("francia"), installedWhole = emptySet()))
    }

    @Test
    fun `l'ordine dell'elenco si conserva e con tutte le altre installate per intero resta la sola aperta`() {
        val ids = listOf("svizzera", "italia", "francia", "austria")

        assertEquals(listOf("svizzera", "italia", "francia", "austria"), zoneTargets("italia", ids, installedWhole = emptySet()))
        assertEquals(listOf("italia"), zoneTargets("italia", ids, installedWhole = setOf("svizzera", "italia", "francia", "austria")))
    }

    private val everywhere = { _: Double, _: Double -> "xx" }
    private fun candidate(id: String, country: String, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) =
        ZoneCandidate(id, country, RegionBbox(minLon, minLat, maxLon, maxLat))

    @Test
    fun `senza candidate non ci sono quote`() {
        assertEquals(emptyList<Pair<String, Int>>(), zoneShares(RegionZone(0.0, 0.0, 2.0, 2.0), emptyList(), everywhere))
    }

    @Test
    fun `se tutta la zona e' in mare non ci sono quote`() {
        val candidates = listOf(candidate("a", "xx", -10.0, -10.0, 10.0, 10.0))

        assertEquals(emptyList<Pair<String, Int>>(), zoneShares(RegionZone(0.0, 0.0, 2.0, 2.0), candidates, { _, _ -> null }))
    }

    @Test
    fun `i punti campionati sono il quadrato dei campioni e cadono al centro delle celle`() {
        val candidates = listOf(candidate("a", "xx", -10.0, -10.0, 10.0, 10.0))
        val seen = mutableListOf<Pair<Double, Double>>()

        val shares = zoneShares(RegionZone(0.0, 0.0, 2.0, 2.0), candidates, { lat, lon -> seen += lat to lon; "xx" }, samples = 2)

        assertEquals(listOf("a" to 4), shares)
        assertEquals(setOf(0.5 to 0.5, 0.5 to 1.5, 1.5 to 0.5, 1.5 to 1.5), seen.toSet())
    }

    @Test
    fun `con un solo campione conta il centro della zona`() {
        val candidates = listOf(candidate("a", "xx", 0.9, 0.9, 1.1, 1.1), candidate("b", "xx", 5.0, 5.0, 6.0, 6.0))

        assertEquals(listOf("a" to 1), zoneShares(RegionZone(0.0, 0.0, 2.0, 2.0), candidates, everywhere, samples = 1))
    }

    @Test
    fun `il codice del paese si confronta senza distinguere le maiuscole`() {
        val candidates = listOf(candidate("italia", "IT", -10.0, -10.0, 10.0, 10.0))

        assertEquals(listOf("italia" to 4), zoneShares(RegionZone(0.0, 0.0, 2.0, 2.0), candidates, { _, _ -> "it" }, samples = 2))
    }

    @Test
    fun `tra regioni dello stesso paese che contengono il punto vince la piu' piccola`() {
        val candidates = listOf(
            candidate("stati-uniti", "us", -130.0, 20.0, -60.0, 55.0),
            candidate("stati-uniti-ohio", "us", -85.0, 38.0, -80.0, 42.0),
        )

        val shares = zoneShares(RegionZone(-84.0, 39.0, -81.0, 41.0), candidates, { _, _ -> "us" }, samples = 4)

        assertEquals(listOf("stati-uniti-ohio" to 16), shares)
    }

    @Test
    fun `un punto fuori dai riquadri del suo paese non conta`() {
        val candidates = listOf(candidate("italia", "it", 6.0, 35.0, 19.0, 48.0))

        assertEquals(emptyList<Pair<String, Int>>(), zoneShares(RegionZone(30.0, 30.0, 32.0, 32.0), candidates, { _, _ -> "it" }))
    }

    @Test
    fun `le quote vanno dalla regione piu' presente alla meno presente`() {
        val candidates = listOf(candidate("ovest", "aa", 0.0, 0.0, 1.0, 2.0), candidate("est", "bb", 0.5, 0.0, 2.0, 2.0))
        // Le colonne a est del meridiano 0,5 sono tre su quattro.
        val countryAt = { _: Double, lon: Double -> if (lon < 0.5) "aa" else "bb" }

        val shares = zoneShares(RegionZone(0.0, 0.0, 2.0, 2.0), candidates, countryAt, samples = 4)

        assertEquals(listOf("est" to 12, "ovest" to 4), shares)
    }
}
