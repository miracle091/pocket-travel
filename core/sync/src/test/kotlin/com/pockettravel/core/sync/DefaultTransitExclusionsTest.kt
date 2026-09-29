package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultTransitExclusionsTest {
    private fun feed(id: String, bbox: List<Double>?) = TransitFeed(
        id, id, listOf("regno-unito"), "OGL-UK-3.0", "DfT", null, "v1",
        RegionManifestFile("transit.db", "https://github.com/x/$id.db", 1, "0".repeat(64)), bbox = bbox,
    )

    // Quattro aree inglesi approssimate: Londra, Nord-ovest, Scozia, Galles.
    private val uk = listOf(
        feed("london", listOf(-0.51, 51.28, 0.33, 51.69)),
        feed("north-west", listOf(-3.6, 52.9, -2.0, 55.2)),
        feed("scotland", listOf(-7.6, 54.6, -0.7, 60.9)),
        feed("wales", listOf(-5.4, 51.3, -2.6, 53.5)),
    )

    @Test
    fun `a Londra resta solo Londra`() {
        assertEquals(setOf("north-west", "scotland", "wales"), defaultTransitExclusions(uk, 51.5074, -0.1278))
    }

    @Test
    fun `fra due aree entro 50 km restano entrambe`() {
        // Chester: nel Nord-ovest e a pochi km dal Galles.
        assertEquals(setOf("london", "scotland"), defaultTransitExclusions(uk, 53.19, -2.89))
    }

    @Test
    fun `lontano da tutte resta la piu' vicina`() {
        // Parigi: Londra e' la piu' vicina.
        assertEquals(setOf("north-west", "scotland", "wales"), defaultTransitExclusions(uk, 48.85, 2.35))
    }

    @Test
    fun `senza posizione o con poche reti si scaricano tutte`() {
        assertEquals(emptySet<String>(), defaultTransitExclusions(uk, null, null))
        assertEquals(emptySet<String>(), defaultTransitExclusions(uk.take(3), 51.5074, -0.1278))
    }
}
