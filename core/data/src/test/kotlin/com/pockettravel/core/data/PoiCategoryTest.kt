package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiCategoryTest {

    private fun poi(category: String, osmTag: String, name: String = "test") = Poi(
        id = 1,
        regionId = "italia",
        name = name,
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
        assertEquals(PoiCategory.ALLOGGIO, poi("caravan_site", "tourism=caravan_site").poiCategory())
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
        assertEquals(PoiCategory.ATTRAZIONI, poi("monastery", "amenity=monastery").poiCategory())
        assertEquals(PoiCategory.ATTRAZIONI, poi("fountain", "amenity=fountain").poiCategory())
        assertEquals(PoiCategory.ATTRAZIONI, poi("playground", "leisure=playground").poiCategory())
        assertEquals(PoiCategory.ATTRAZIONI, poi("nature_reserve", "leisure=nature_reserve").poiCategory())
        assertEquals(PoiCategory.ATTRAZIONI, poi("park", "leisure=park").poiCategory())
        assertEquals(PoiCategory.ATTRAZIONI, poi("water_park", "leisure=water_park").poiCategory())
        assertEquals(PoiCategory.ATTRAZIONI, poi("theme_park", "tourism=theme_park").poiCategory())
        assertEquals(PoiCategory.ATTRAZIONI, poi("place_of_worship", "amenity=place_of_worship").poiCategory())
    }

    @Test
    fun `il resto va in ALTRO`() {
        assertEquals(PoiCategory.ALTRO, poi("library", "amenity=library").poiCategory())
        assertEquals(PoiCategory.ALTRO, poi("library", "amenity=library").poiCategory())
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
        assertEquals(PoiCategory.RICARICA, poi("charging_station", "amenity=charging_station").poiCategory())
        listOf("amenity=police", "amenity=toilets", "amenity=fuel").forEach { tag ->
            assertFalse("$tag non va nascosto", poi(tag.substringAfter("="), tag).isHiddenOnMap())
        }
    }

    @Test
    fun `cestini, riciclo, scuole e fontanelle non vanno sulla mappa`() {
        listOf(
            "amenity=waste_basket", "amenity=waste_disposal", "amenity=recycling", "amenity=school",
            "amenity=drinking_water", "leisure=beach_resort",
        ).forEach { tag -> assertTrue("$tag va nascosto", poi(tag.substringAfter("="), tag).isHiddenOnMap()) }
        assertFalse(poi("library", "amenity=library").isHiddenOnMap())
    }

    @Test
    fun `cinema, teatri, discoteche e sale giochi vanno in SVAGO`() {
        listOf("amenity=cinema", "amenity=theatre", "amenity=nightclub", "leisure=amusement_arcade").forEach { tag ->
            assertEquals(tag, PoiCategory.SVAGO, poi(tag.substringAfter("="), tag).poiCategory())
        }
    }

    @Test
    fun `farmacie, ospedali, vigili del fuoco, veterinari, banche, bancomat, poste e uffici informazioni hanno una categoria propria`() {
        assertEquals(PoiCategory.FARMACIA, poi("pharmacy", "amenity=pharmacy").poiCategory())
        assertEquals(PoiCategory.OSPEDALE, poi("hospital", "amenity=hospital").poiCategory())
        assertEquals(PoiCategory.VIGILI_DEL_FUOCO, poi("fire_station", "amenity=fire_station").poiCategory())
        assertEquals(PoiCategory.VETERINARIO, poi("veterinary", "amenity=veterinary").poiCategory())
        assertEquals(PoiCategory.BANCA, poi("bank", "amenity=bank").poiCategory())
        assertEquals(PoiCategory.BANCOMAT, poi("atm", "amenity=atm").poiCategory())
        assertEquals(PoiCategory.UFFICIO_POSTALE, poi("post_office", "amenity=post_office").poiCategory())
        assertEquals(PoiCategory.INFORMAZIONI, poi("information_office", "tourism=information").poiCategory())
        assertFalse(poi("information_office", "tourism=information").isHiddenOnMap())
        assertTrue("i cartelli informativi vanno nascosti", poi("information", "tourism=information").isHiddenOnMap())
    }

    @Test
    fun `noleggi di auto, bici, moto e barche vanno in NOLEGGIO`() {
        listOf("amenity=car_rental", "amenity=bicycle_rental", "amenity=motorcycle_rental", "amenity=boat_rental").forEach { tag ->
            assertEquals(tag, PoiCategory.NOLEGGIO, poi(tag.substringAfter("="), tag).poiCategory())
        }
        assertTrue(poi("bus_rental", "amenity=bus_rental").isHiddenOnMap())
    }

    @Test
    fun `parcheggi privati, stalli e panchine nascosti`() {
        assertEquals(PoiCategory.PARCHEGGIO, poi("parking", "amenity=parking").poiCategory())
        assertFalse(poi("parking", "amenity=parking").isHiddenOnMap())
        assertTrue(poi("parking_private", "amenity=parking").isHiddenOnMap())
        assertEquals(PoiCategory.PARCHEGGIO, poi("parking_entrance", "amenity=parking_entrance").poiCategory())
        assertEquals(PoiCategory.PARCHEGGIO, poi("bicycle_parking", "amenity=bicycle_parking").poiCategory())
        assertEquals(PoiCategory.PARCHEGGIO, poi("motorcycle_parking", "amenity=motorcycle_parking").poiCategory())
        assertEquals(PoiCategory.ALTRO, poi("boat_parking", "amenity=boat_parking").poiCategory())
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
        assertEquals(PoiCategory.TAXI, poi("taxi", "amenity=taxi").poiCategory())
        assertEquals(PoiCategory.TRAGHETTO, poi("ferry_terminal", "amenity=ferry_terminal").poiCategory())
        assertEquals(PoiCategory.AEROPORTO, poi("aerodrome", "aeroway=aerodrome").poiCategory())
    }

    @Test
    fun `senza nome nascosti, tranne i servizi e le fontane`() {
        // Senza nome in OSM la pipeline mette come nome il valore del tag.
        assertTrue(poi("restaurant", "amenity=restaurant", name = "restaurant").isHiddenOnMap())
        assertTrue(poi("park", "leisure=park", name = "park").isHiddenOnMap())
        assertFalse(poi("restaurant", "amenity=restaurant", name = "Da Mario").isHiddenOnMap())
        listOf("amenity=toilets", "amenity=atm", "amenity=parking", "amenity=fuel", "amenity=charging_station", "amenity=pharmacy",
            "amenity=fountain",
        )
            .forEach { tag ->
                val value = tag.substringAfter("=")
                assertFalse("$tag senza nome resta", poi(value, tag, name = value).isHiddenOnMap())
            }
    }
}
