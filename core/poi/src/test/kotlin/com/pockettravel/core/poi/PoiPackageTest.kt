package com.pockettravel.core.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoiPackageTest {

    // Senza nome la pipeline mette il valore del tag come nome (vedi GeneratePoi.kt).
    private fun unnamed(osmTag: String, category: String = osmTag.substringAfter("=")) =
        poiPackageOf(category, category, osmTag)

    @Test
    fun `i POI mostrati sulla mappa vanno nel pacchetto base`() {
        assertEquals(PoiPackage.BASE, poiPackageOf("Da Mario", "restaurant", "amenity=restaurant"))
        assertEquals(PoiPackage.BASE, poiPackageOf("Parco giochi Verdi", "playground", "leisure=playground"))
        assertEquals(PoiPackage.BASE, unnamed("amenity=toilets"))
    }

    @Test
    fun `i tipi scelti per l'extra ci vanno anche senza nome`() {
        assertEquals(PoiPackage.EXTRA, unnamed("amenity=drinking_water"))
        assertEquals(PoiPackage.EXTRA, unnamed("leisure=picnic_table"))
        assertEquals(PoiPackage.EXTRA, unnamed("leisure=playground"))
        assertEquals(PoiPackage.EXTRA, unnamed("amenity=vending_machine"))
        assertEquals(PoiPackage.EXTRA, unnamed("amenity=post_box"))
    }

    @Test
    fun `bar, ristoranti e negozi senza nome vanno nell'extra`() {
        assertEquals(PoiPackage.EXTRA, unnamed("amenity=bar"))
        assertEquals(PoiPackage.EXTRA, unnamed("amenity=restaurant"))
        assertEquals(PoiPackage.EXTRA, unnamed("shop=bakery"))
    }

    @Test
    fun `gli altri POI nascosti non si pubblicano`() {
        assertNull(unnamed("amenity=bench"))
        assertNull(unnamed("amenity=waste_basket"))
        assertNull(unnamed("amenity=recycling"))
        assertNull(unnamed("leisure=swimming_pool"))
        assertNull(unnamed("shop=vacant"))
        assertNull(poiPackageOf("Scuola Dante", "school", "amenity=school"))
        assertNull(poiPackageOf("Cartello", "information", "tourism=information"))
    }

    @Test
    fun `i tipi dell'extra hanno una categoria propria`() {
        assertEquals(PoiCategory.ACQUA_POTABILE, poiCategoryOf("drinking_water", "amenity=drinking_water"))
        assertEquals(PoiCategory.TAVOLI_PICNIC, poiCategoryOf("picnic_table", "leisure=picnic_table"))
        assertEquals(PoiCategory.PARCO_GIOCHI, poiCategoryOf("playground", "leisure=playground"))
        assertEquals(PoiCategory.DISTRIBUTORI, poiCategoryOf("vending_machine", "amenity=vending_machine"))
        assertEquals(PoiCategory.CASSETTA_POSTALE, poiCategoryOf("post_box", "amenity=post_box"))
    }

    @Test
    fun `belvedere, noleggio bici, servizi camper, aree picnic e ripari vanno nel base anche senza nome`() {
        listOf(
            "tourism=viewpoint", "amenity=bicycle_rental", "amenity=sanitary_dump_station", "amenity=water_point",
            "tourism=picnic_site", "amenity=shelter",
        ).forEach { assertEquals(it, PoiPackage.BASE, unnamed(it)) }
        assertEquals(PoiCategory.SERVIZI_CAMPER, poiCategoryOf("water_point", "amenity=water_point"))
        assertEquals(PoiCategory.RIPARI, poiCategoryOf("shelter", "amenity=shelter"))
        assertEquals(PoiCategory.TAVOLI_PICNIC, poiCategoryOf("picnic_site", "tourism=picnic_site"))
        // Gli altri tipi delle stesse categorie restano come prima.
        assertEquals(PoiPackage.EXTRA, unnamed("leisure=picnic_table"))
        assertNull(unnamed("amenity=car_rental"))
    }
}
