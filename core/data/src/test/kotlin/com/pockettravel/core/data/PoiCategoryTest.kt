package com.pockettravel.core.data

import org.junit.Assert.assertEquals
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
}
