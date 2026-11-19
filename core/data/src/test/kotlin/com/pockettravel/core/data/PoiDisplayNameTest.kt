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
