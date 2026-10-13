package com.pockettravel.core.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiRulesCoverageTest {

    // Senza nome la pipeline mette il valore del tag come nome (vedi GeneratePoi.kt).
    private fun unnamedHidden(osmTag: String): Boolean {
        val category = osmTag.substringAfter("=")
        return isPoiHiddenOnMap(category, category, osmTag)
    }

    private fun unnamedPackage(osmTag: String): PoiPackage? {
        val category = osmTag.substringAfter("=")
        return poiPackageOf(category, category, osmTag)
    }

    @Test
    fun `i servizi di base hanno la loro categoria`() {
        mapOf(
            "amenity=police" to PoiCategory.POLIZIA,
            "amenity=toilets" to PoiCategory.BAGNI_PUBBLICI,
            "amenity=fuel" to PoiCategory.CARBURANTE,
            "amenity=charging_station" to PoiCategory.RICARICA,
            "amenity=pharmacy" to PoiCategory.FARMACIA,
            "amenity=hospital" to PoiCategory.OSPEDALE,
            "amenity=clinic" to PoiCategory.AMBULATORI,
            "amenity=doctors" to PoiCategory.AMBULATORI,
            "amenity=dentist" to PoiCategory.AMBULATORI,
            "amenity=townhall" to PoiCategory.MUNICIPIO,
            "amenity=library" to PoiCategory.BIBLIOTECHE,
            "amenity=fire_station" to PoiCategory.VIGILI_DEL_FUOCO,
            "amenity=veterinary" to PoiCategory.VETERINARIO,
            "amenity=bank" to PoiCategory.BANCA,
            "amenity=payment_centre" to PoiCategory.BANCA,
            "amenity=atm" to PoiCategory.BANCOMAT,
            "amenity=post_office" to PoiCategory.UFFICIO_POSTALE,
            "amenity=bicycle_repair_station" to PoiCategory.RIPARAZIONE_BICI,
            "amenity=car_wash" to PoiCategory.SERVIZI_CAMPER,
            "amenity=sanitary_dump_station" to PoiCategory.SERVIZI_CAMPER,
            "tourism=wilderness_hut" to PoiCategory.RIPARI,
            "tourism=lean_to" to PoiCategory.RIPARI,
            "leisure=firepit" to PoiCategory.TAVOLI_PICNIC,
            "amenity=love_hotel" to PoiCategory.ALLOGGIO,
        ).forEach { (tag, expected) -> assertEquals(tag, expected, poiCategoryOf(tag.substringAfter("="), tag)) }
    }

    @Test
    fun `i trasporti hanno la loro categoria`() {
        mapOf(
            "amenity=bus_station" to PoiCategory.AUTOBUS,
            "amenity=taxi" to PoiCategory.TAXI,
            "amenity=ferry_terminal" to PoiCategory.TRAGHETTO,
            "leisure=marina" to PoiCategory.PORTI_TURISTICI,
            "aeroway=aerodrome" to PoiCategory.AEROPORTO,
            "amenity=parking_entrance" to PoiCategory.PARCHEGGIO,
            "amenity=car_rental" to PoiCategory.NOLEGGIO,
            "amenity=boat_rental" to PoiCategory.NOLEGGIO,
        ).forEach { (tag, expected) -> assertEquals(tag, expected, poiCategoryOf(tag.substringAfter("="), tag)) }
    }

    @Test
    fun `lo svago e lo sport hanno la loro categoria`() {
        listOf(
            "amenity=cinema", "amenity=theatre", "amenity=nightclub", "amenity=casino", "leisure=bowling_alley",
            "amenity=exhibition_centre",
        ).forEach { assertEquals(it, PoiCategory.SVAGO, poiCategoryOf(it.substringAfter("="), it)) }
        listOf(
            "leisure=sports_centre", "leisure=fitness_centre", "leisure=stadium", "amenity=spa", "leisure=sauna",
            "amenity=dive_centre",
        ).forEach { assertEquals(it, PoiCategory.SPORT, poiCategoryOf(it.substringAfter("="), it)) }
    }

    @Test
    fun `la categoria della pipeline decide quando il tag non basta`() {
        assertEquals(PoiCategory.ALLOGGIO, poiCategoryOf("hostel", "tourism=hostel"))
        assertEquals(PoiCategory.ALLOGGIO, poiCategoryOf("camp_pitch", "tourism=camp_pitch"))
        assertEquals(PoiCategory.CIBO_BEVANDE, poiCategoryOf("food_court", "amenity=food_court"))
        assertEquals(PoiCategory.LUOGHI_DI_CULTO, poiCategoryOf("monastery", "amenity=monastery"))
        assertEquals(PoiCategory.MUSEI_ARTE, poiCategoryOf("gallery", "tourism=gallery"))
        assertEquals(PoiCategory.MUSEI_ARTE, poiCategoryOf("artwork", "tourism=artwork"))
        assertEquals(PoiCategory.NATURA, poiCategoryOf("nature_reserve", "leisure=nature_reserve"))
        assertEquals(PoiCategory.ATTRAZIONI, poiCategoryOf("attraction", "tourism=attraction"))
    }

    @Test
    fun `i tag historic prevalgono sulla categoria della pipeline`() {
        assertEquals(PoiCategory.LUOGHI_STORICI, poiCategoryOf("hotel", "historic=castle"))
        assertEquals(PoiCategory.LUOGHI_STORICI, poiCategoryOf("museum", "historic=memorial"))
    }

    @Test
    fun `il riconoscimento e' esatto, senza adattare le maiuscole`() {
        assertEquals(PoiCategory.ALTRO, poiCategoryOf("police", "Amenity=Police"))
        assertEquals(PoiCategory.ALTRO, poiCategoryOf("Embassy", "office=diplomatic"))
        assertEquals(PoiCategory.ALTRO, poiCategoryOf("Hotel", "tourism=hotel"))
    }

    @Test
    fun `uno stallo e' un parcheggio solo se non e' amenity=parking_space`() {
        assertEquals(PoiCategory.ALTRO, poiCategoryOf("parking", "amenity=parking_space"))
        assertEquals(PoiCategory.PARCHEGGIO, poiCategoryOf("parking_disabled", "amenity=parking"))
        assertEquals(PoiCategory.PARCHEGGIO_DISABILI, poiCategoryOf("parking_disabled", "amenity=parking_space"))
    }

    @Test
    fun `senza nome restano sulla mappa tutti i servizi previsti`() {
        listOf(
            "amenity=toilets", "amenity=atm", "amenity=parking", "amenity=fuel", "amenity=charging_station",
            "amenity=pharmacy", "amenity=hospital", "amenity=taxi", "amenity=post_office", "amenity=water_point",
            "amenity=shelter", "amenity=bicycle_repair_station", "amenity=bicycle_rental", "tourism=picnic_site",
        ).forEach { assertFalse(it, unnamedHidden(it)) }
        assertFalse(isPoiHiddenOnMap("parking_space", "parking_disabled", "amenity=parking_space"))
    }

    @Test
    fun `senza nome si nascondono gli altri servizi e i luoghi`() {
        listOf(
            "amenity=police", "amenity=bank", "amenity=clinic", "amenity=fire_station", "amenity=library",
            "railway=station", "amenity=bus_station", "amenity=ferry_terminal", "aeroway=aerodrome",
            "tourism=hotel", "tourism=museum", "amenity=cinema", "leisure=pitch", "amenity=car_rental",
        ).forEach { assertTrue(it, unnamedHidden(it)) }
    }

    @Test
    fun `con il nome gli stessi luoghi sono sulla mappa`() {
        listOf(
            "amenity=police" to "police", "amenity=bank" to "bank", "railway=station" to "station",
            "tourism=hotel" to "hotel", "tourism=museum" to "museum", "leisure=pitch" to "pitch",
        ).forEach { (tag, category) -> assertFalse(tag, isPoiHiddenOnMap("Nome vero", category, tag)) }
    }

    @Test
    fun `un'ambasciata con il nome e' sulla mappa, senza nome no`() {
        assertFalse(isPoiHiddenOnMap("Ambasciata d'Italia", "embassy", "office=diplomatic"))
        assertTrue(isPoiHiddenOnMap("diplomatic", "embassy", "office=diplomatic"))
    }

    @Test
    fun `i tipi nascosti sul base vanno nell'extra anche con il nome`() {
        assertEquals(PoiPackage.EXTRA, poiPackageOf("Fontana Verdi", "drinking_water", "amenity=drinking_water"))
        assertEquals(PoiPackage.EXTRA, poiPackageOf("Tavolo del lago", "picnic_table", "leisure=picnic_table"))
    }

    @Test
    fun `distributori, cassette e parchi giochi con il nome sono sul base`() {
        assertEquals(PoiPackage.BASE, poiPackageOf("Latte Fresco", "vending_machine", "amenity=vending_machine"))
        assertEquals(PoiPackage.BASE, poiPackageOf("Posta centrale", "post_box", "amenity=post_box"))
        assertEquals(PoiPackage.BASE, poiPackageOf("Giardini", "playground", "leisure=playground"))
    }

    @Test
    fun `senza nome nell'extra vanno solo cibo e negozi, non alloggi, musei o servizi`() {
        assertEquals(PoiPackage.EXTRA, unnamedPackage("amenity=cafe"))
        assertEquals(PoiPackage.EXTRA, unnamedPackage("shop=convenience"))
        assertNull(unnamedPackage("tourism=hotel"))
        assertNull(unnamedPackage("tourism=museum"))
        assertNull(unnamedPackage("amenity=police"))
        assertNull(unnamedPackage("amenity=bank"))
        assertNull(unnamedPackage("amenity=school"))
    }

    @Test
    fun `il nome vale come assente solo se uguale al valore del tag`() {
        // Il confronto e' esatto: "Bakery" con la maiuscola e' un nome.
        assertEquals(PoiPackage.BASE, poiPackageOf("Bakery", "bakery", "shop=bakery"))
        assertEquals(PoiPackage.EXTRA, poiPackageOf("bakery", "bakery", "shop=bakery"))
    }

    @Test
    fun `senza il segno uguale il valore del tag e' tutta la stringa`() {
        assertFalse(poiHasName("amenity", "amenity"))
        assertTrue(poiHasName("Bar Sport", "amenity"))
        assertFalse(poiHasName("", ""))
    }
}
