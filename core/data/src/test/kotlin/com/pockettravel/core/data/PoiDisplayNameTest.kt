package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PoiDisplayNameTest {

    private fun poi(regionId: String, nameEn: String? = "Trevi Fountain", nameIt: String? = null) = Poi(
        id = 1, regionId = regionId, name = "Fontana di Trevi", category = "attraction", latitude = 41.9, longitude = 12.48,
        osmTag = "tourism=attraction", phone = null, nameEn = nameEn, nameIt = nameIt,
    )

    @Test
    fun `in italiano, nelle regioni di lingua italiana, vale il nome locale e non quello inglese`() {
        listOf("italia", "san-marino", "citta-del-vaticano").forEach {
            assertEquals("Fontana di Trevi", poi(it).displayName("it"))
        }
    }

    @Test
    fun `in italiano, nelle altre regioni, senza name it resta il nome inglese`() {
        assertEquals("Trevi Fountain", poi("giappone").displayName("it"))
    }

    @Test
    fun `in Svizzera vale il nome locale solo nella parte di lingua italiana`() {
        val italian = mapOf(
            "Lugano" to (46.005 to 8.952), "Bellinzona" to (46.195 to 9.024), "Locarno" to (46.170 to 8.800), "Airolo" to (46.528 to 8.610),
            "Olivone" to (46.530 to 8.940), "Roveredo" to (46.236 to 9.126), "San Bernardino" to (46.460 to 9.190),
            "Soglio" to (46.341 to 9.538), "Poschiavo" to (46.325 to 10.060), "Brusio" to (46.259 to 10.124),
        )
        val other = mapOf(
            "Zurigo" to (47.370 to 8.540), "Andermatt" to (46.635 to 8.594), "Hinterrhein" to (46.522 to 9.200),
            "Sils" to (46.429 to 9.760), "Pontresina" to (46.492 to 9.900), "Brig" to (46.316 to 7.988), "Vals" to (46.616 to 9.180),
        )
        italian.forEach { (town, at) -> assertEquals(town, true, isItalianSpeakingPlace("svizzera", at.first, at.second)) }
        other.forEach { (town, at) -> assertEquals(town, false, isItalianSpeakingPlace("svizzera", at.first, at.second)) }
        assertEquals(false, isItalianSpeakingPlace("francia", 46.005, 8.952))
    }

    @Test
    fun `name it vince sempre`() {
        assertEquals("Fontana", poi("italia", nameIt = "Fontana").displayName("it"))
        assertEquals("Fontana", poi("giappone", nameIt = "Fontana").displayName("it"))
    }

    @Test
    fun `in inglese non cambia nulla`() {
        assertEquals("Trevi Fountain", poi("italia").displayName("en"))
        assertEquals("Fontana di Trevi", poi("italia", nameEn = null).displayName("en"))
    }

    @Test
    fun `le rappresentanze OSM in Italia si mostrano con il nome italiano`() {
        val embassy = Poi(
            id = 2, regionId = "san-marino", name = "Ambasciata d'Italia a San Marino", category = "embassy", latitude = 43.9, longitude = 12.4,
            osmTag = "amenity=embassy", phone = null, nameEn = "Embassy of Italy in San Marino",
        )
        assertEquals("Ambasciata d'Italia a San Marino", mergeEmbassies(listOf(embassy), emptyList(), emptyList(), "it").single().name)
    }
}
