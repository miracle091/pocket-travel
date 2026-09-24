package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiCategoryTest {

    private fun poi(category: String, osmTag: String) = Poi(
        id = 1,
        regionId = "italia",
        name = "test",
        category = category,
        latitude = 0.0,
        longitude = 0.0,
        osmTag = osmTag,
        phone = null,
    )

    @Test
    fun `tourism di alloggio va in ALLOGGIO`() {
        assertEquals(PoiCategory.ALLOGGIO, poi("hotel", "tourism=hotel").poiCategory())
        assertEquals(PoiCategory.ALLOGGIO, poi("guest_house", "tourism=guest_house").poiCategory())
    }

    @Test
    fun `amenity di cibo e bevande va in CIBO_BEVANDE`() {
        assertEquals(PoiCategory.CIBO_BEVANDE, poi("restaurant", "amenity=restaurant").poiCategory())
        assertEquals(PoiCategory.CIBO_BEVANDE, poi("cafe", "amenity=cafe").poiCategory())
    }

    @Test
    fun `qualunque valore shop va in NEGOZI`() {
        assertEquals(PoiCategory.NEGOZI, poi("bakery", "shop=bakery").poiCategory())
        assertEquals(PoiCategory.NEGOZI, poi("supermarket", "shop=supermarket").poiCategory())
    }

    @Test
    fun `historic e i valori tourism di attrazione vanno in ATTRAZIONI`() {
        assertEquals(PoiCategory.ATTRAZIONI, poi("monument", "historic=monument").poiCategory())
        assertEquals(PoiCategory.ATTRAZIONI, poi("viewpoint", "tourism=viewpoint").poiCategory())
        assertEquals(PoiCategory.ATTRAZIONI, poi("museum", "tourism=museum").poiCategory())
    }

    @Test
    fun `il resto va in ALTRO`() {
        assertEquals(PoiCategory.ALTRO, poi("bank", "amenity=bank").poiCategory())
        assertEquals(PoiCategory.ALTRO, poi("pharmacy", "amenity=pharmacy").poiCategory())
    }

    @Test
    fun `embassy va in AMBASCIATA_CONSOLATO`() {
        assertEquals(PoiCategory.AMBASCIATA_CONSOLATO, poi("embassy", "amenity=embassy").poiCategory())
    }

    @Test
    fun `polizia, bagni pubblici e carburante hanno una categoria propria e restano sulla mappa`() {
        assertEquals(PoiCategory.POLIZIA, poi("police", "amenity=police").poiCategory())
        assertEquals(PoiCategory.BAGNI_PUBBLICI, poi("toilets", "amenity=toilets").poiCategory())
        assertEquals(PoiCategory.CARBURANTE, poi("fuel", "amenity=fuel").poiCategory())
        listOf("amenity=police", "amenity=toilets", "amenity=fuel").forEach { tag ->
            assertFalse("$tag non va nascosto", poi(tag.substringAfter("="), tag).isHiddenOnMap())
        }
    }

    @Test
    fun `i cestini non vanno sulla mappa, la raccolta differenziata si`() {
        assertTrue(poi("waste_basket", "amenity=waste_basket").isHiddenOnMap())
        assertTrue(poi("waste_disposal", "amenity=waste_disposal").isHiddenOnMap())
        assertFalse(poi("recycling", "amenity=recycling").isHiddenOnMap())
    }

    @Test
    fun `parcheggi pubblici e privati, stalli e panchine nascosti`() {
        assertEquals(PoiCategory.PARCHEGGIO, poi("parking", "amenity=parking").poiCategory())
        assertEquals(PoiCategory.PARCHEGGIO, poi("parking_entrance", "amenity=parking_entrance").poiCategory())
        assertEquals(PoiCategory.PARCHEGGIO_PRIVATO, poi("parking_private", "amenity=parking").poiCategory())
        assertTrue(poi("parking_space", "amenity=parking_space").isHiddenOnMap())
        assertTrue(poi("bench", "amenity=bench").isHiddenOnMap())
        assertTrue(poi("picnic_table", "leisure=picnic_table").isHiddenOnMap())
    }

    @Test
    fun `stazioni, metro, autostazioni e aeroporti`() {
        assertEquals(PoiCategory.TRENO, poi("station", "railway=station").poiCategory())
        assertEquals(PoiCategory.TRENO, poi("halt", "railway=halt").poiCategory())
        assertEquals(PoiCategory.METRO, poi("subway_station", "railway=station").poiCategory())
        assertEquals(PoiCategory.AUTOBUS, poi("bus_station", "amenity=bus_station").poiCategory())
        assertEquals(PoiCategory.AEROPORTO, poi("aerodrome", "aeroway=aerodrome").poiCategory())
    }
}
