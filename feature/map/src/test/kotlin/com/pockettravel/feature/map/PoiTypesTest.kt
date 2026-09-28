package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoiTypesTest {

    @Test
    fun `tipo preciso dal tag OSM`() {
        assertEquals(R.string.poi_type_restaurant, poiTypeLabel("amenity=restaurant"))
        assertEquals(R.string.poi_type_place_of_worship, poiTypeLabel("amenity=place_of_worship"))
        assertEquals(R.string.poi_type_viewpoint, poiTypeLabel("tourism=viewpoint"))
    }

    @Test
    fun `negozi e luoghi storici senza un tipo proprio hanno quello generico`() {
        assertEquals(R.string.poi_type_bakery, poiTypeLabel("shop=bakery"))
        assertEquals(R.string.poi_type_shop, poiTypeLabel("shop=fabric"))
        assertEquals(R.string.poi_type_historic, poiTypeLabel("historic=wayside_cross"))
    }

    @Test
    fun `senza tipo vale la categoria`() {
        assertNull(poiTypeLabel("amenity=pharmacy"))
    }
}
