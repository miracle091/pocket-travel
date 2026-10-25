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
        assertEquals(R.string.poi_type_pharmacy, poiTypeLabel("amenity=pharmacy"))
    }

    @Test
    fun `negozi e luoghi storici senza un tipo proprio hanno quello generico`() {
        assertEquals(R.string.poi_type_bakery, poiTypeLabel("shop=bakery"))
        assertEquals(R.string.poi_type_shop, poiTypeLabel("shop=fabric"))
        assertEquals(R.string.poi_type_historic, poiTypeLabel("historic=wayside_cross"))
    }

    @Test
    fun `luoghi famosi con wikidata hanno il loro tipo`() {
        assertEquals(R.string.poi_type_bridge, poiTypeLabel("man_made=bridge"))
        assertEquals(R.string.poi_type_square, poiTypeLabel("place=square"))
        assertEquals(R.string.poi_type_square, poiTypeLabel("highway=pedestrian"))
        assertEquals(R.string.poi_type_square, poiTypeLabel("type=associatedStreet"))
        assertEquals(R.string.poi_type_national_park, poiTypeLabel("boundary=national_park"))
        assertEquals(R.string.poi_type_peak, poiTypeLabel("natural=peak"))
        assertEquals(R.string.poi_type_mountain_range, poiTypeLabel("natural=mountain_range"))
        assertEquals(R.string.poi_type_historic, poiTypeLabel("type=site"))
        assertEquals(R.string.poi_type_library, poiTypeLabel("amenity=library"))
    }

    @Test
    fun `senza tipo vale la categoria`() {
        assertNull(poiTypeLabel("amenity=toilets"))
    }
}
