package com.pockettravel.core.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiRulesTest {

    @Test
    fun `la categoria embassy prevale sul tag OSM`() {
        assertEquals(PoiCategory.AMBASCIATA_CONSOLATO, poiCategoryOf("embassy", "amenity=toilets"))
    }

    @Test
    fun `i parcheggi si distinguono per categoria della pipeline`() {
        assertEquals(PoiCategory.PARCHEGGIO_PRIVATO, poiCategoryOf("parking_private", "amenity=parking"))
        assertEquals(PoiCategory.PARCHEGGIO, poiCategoryOf("parking", "amenity=parking"))
        assertEquals(PoiCategory.PARCHEGGIO, poiCategoryOf("parking_entrance", "amenity=parking_entrance"))
        // parking_private vale solo su amenity=parking.
        assertEquals(PoiCategory.ALTRO, poiCategoryOf("parking_private", "amenity=bench"))
    }

    @Test
    fun `i parcheggi per bici e moto sono parcheggi, quelli delle barche no`() {
        assertEquals(PoiCategory.PARCHEGGIO, poiCategoryOf("bicycle_parking", "amenity=bicycle_parking"))
        assertEquals(PoiCategory.PARCHEGGIO, poiCategoryOf("motorcycle_parking", "amenity=motorcycle_parking"))
        assertEquals(PoiCategory.ALTRO, poiCategoryOf("boat_parking", "amenity=boat_parking"))
    }

    @Test
    fun `la stazione della metro e' distinta da quella del treno`() {
        assertEquals(PoiCategory.METRO, poiCategoryOf("subway_station", "railway=station"))
        assertEquals(PoiCategory.TRENO, poiCategoryOf("station", "railway=station"))
        assertEquals(PoiCategory.TRENO, poiCategoryOf("halt", "railway=halt"))
        // subway_station senza railway=station non e' una metro.
        assertEquals(PoiCategory.ALTRO, poiCategoryOf("subway_station", "public_transport=station"))
    }

    @Test
    fun `chiese e monasteri storici sono luoghi di culto, il resto di historic e' storico`() {
        assertEquals(PoiCategory.LUOGHI_DI_CULTO, poiCategoryOf("church", "historic=church"))
        assertEquals(PoiCategory.LUOGHI_DI_CULTO, poiCategoryOf("monastery", "historic=monastery"))
        assertEquals(PoiCategory.LUOGHI_STORICI, poiCategoryOf("castle", "historic=castle"))
        assertEquals(PoiCategory.LUOGHI_STORICI, poiCategoryOf("lavoir", "amenity=lavoir"))
    }

    @Test
    fun `ponti, piazze, complessi religiosi e parchi nazionali famosi sono da vedere e sul pacchetto base`() {
        mapOf(
            "man_made=bridge" to PoiCategory.ATTRAZIONI,
            "place=square" to PoiCategory.ATTRAZIONI,
            "landuse=religious" to PoiCategory.LUOGHI_DI_CULTO,
            "boundary=national_park" to PoiCategory.NATURA,
        ).forEach { (tag, category) ->
            assertEquals(tag, category, poiCategoryOf(tag.substringAfter("="), tag))
            assertEquals(tag, PoiPackage.BASE, poiPackageOf("Con un nome", tag.substringAfter("="), tag))
        }
    }

    @Test
    fun `i negozi sono riconosciuti dal prefisso shop`() {
        assertEquals(PoiCategory.NEGOZI, poiCategoryOf("bakery", "shop=bakery"))
        assertEquals(PoiCategory.NEGOZI, poiCategoryOf("supermarket", "shop=supermarket"))
    }

    @Test
    fun `le categorie "da vedere" dipendono dal valore di categoria`() {
        assertEquals(PoiCategory.ALLOGGIO, poiCategoryOf("hotel", "tourism=hotel"))
        assertEquals(PoiCategory.ALLOGGIO, poiCategoryOf("alpine_hut", "tourism=alpine_hut"))
        // "Residence Capo Grosso", "Limone Beach": leisure=resort su OSM.
        assertEquals(PoiCategory.ALLOGGIO, poiCategoryOf("resort", "leisure=resort"))
        // "Mercato del Sabato": amenity=marketplace su OSM.
        assertEquals(PoiCategory.NEGOZI, poiCategoryOf("marketplace", "amenity=marketplace"))
        assertEquals(PoiCategory.CIBO_BEVANDE, poiCategoryOf("cafe", "amenity=cafe"))
        assertEquals(PoiCategory.LUOGHI_DI_CULTO, poiCategoryOf("place_of_worship", "amenity=place_of_worship"))
        assertEquals(PoiCategory.MUSEI_ARTE, poiCategoryOf("museum", "tourism=museum"))
        assertEquals(PoiCategory.PANORAMI, poiCategoryOf("viewpoint", "tourism=viewpoint"))
        assertEquals(PoiCategory.NATURA, poiCategoryOf("park", "leisure=park"))
        assertEquals(PoiCategory.PARCHI_DIVERTIMENTO, poiCategoryOf("theme_park", "tourism=theme_park"))
        assertEquals(PoiCategory.ZOO, poiCategoryOf("zoo", "tourism=zoo"))
        assertEquals(PoiCategory.PARCHI_ACQUATICI, poiCategoryOf("water_park", "leisure=water_park"))
        assertEquals(PoiCategory.ATTRAZIONI, poiCategoryOf("fountain", "amenity=fountain"))
    }

    @Test
    fun `i tag sconosciuti finiscono in ALTRO, che non si mostra e non si pubblica`() {
        assertEquals(PoiCategory.ALTRO, poiCategoryOf("", ""))
        assertEquals(PoiCategory.ALTRO, poiCategoryOf("xyz", "foo=xyz"))
        listOf(
            "amenity=community_centre", "amenity=college", "amenity=courthouse", "amenity=public_building",
            "amenity=coworking_space",
        ).forEach { tag ->
            assertEquals(tag, PoiCategory.ALTRO, poiCategoryOf(tag.substringAfter("="), tag))
            assertTrue(tag, isPoiHiddenOnMap("Con un nome", tag.substringAfter("="), tag))
            assertEquals(tag, null, poiPackageOf("Con un nome", tag.substringAfter("="), tag))
        }
    }

    @Test
    fun `i tipi rari utili hanno una categoria`() {
        mapOf(
            "leisure=pitch" to PoiCategory.SPORT, "leisure=swimming_pool" to PoiCategory.SPORT,
            "amenity=public_bath" to PoiCategory.SPORT, "leisure=dance" to PoiCategory.SVAGO,
            "amenity=gambling" to PoiCategory.SVAGO, "amenity=music_venue" to PoiCategory.SVAGO,
            "leisure=indoor_play" to PoiCategory.PARCO_GIOCHI, "tourism=aquarium" to PoiCategory.ZOO,
            "amenity=planetarium" to PoiCategory.MUSEI_ARTE, "amenity=mosque" to PoiCategory.LUOGHI_DI_CULTO,
            "tourism=cabin" to PoiCategory.ALLOGGIO, "amenity=juice_bar" to PoiCategory.CIBO_BEVANDE,
            "amenity=health_post" to PoiCategory.AMBULATORI, "amenity=dispensary" to PoiCategory.FARMACIA,
            "amenity=shower" to PoiCategory.BAGNI_PUBBLICI, "amenity=money_transfer" to PoiCategory.BANCA,
            "amenity=mobile_money_agent" to PoiCategory.BANCA, "amenity=bureau_de_change" to PoiCategory.CAMBIO_VALUTA,
            "amenity=car_sharing" to PoiCategory.NOLEGGIO, "amenity=shared_taxi" to PoiCategory.TAXI,
            "leisure=slipway" to PoiCategory.PORTI_TURISTICI, "amenity=bbq" to PoiCategory.TAVOLI_PICNIC,
            "amenity=ranger_station" to PoiCategory.INFORMAZIONI, "leisure=bathing_place" to PoiCategory.NATURA,
            "amenity=grave_yard" to PoiCategory.LUOGHI_STORICI, "amenity=cemetery" to PoiCategory.LUOGHI_STORICI,
            "amenity=internet_cafe" to PoiCategory.INTERNET_CAFE,
        ).forEach { (tag, expected) -> assertEquals(tag, expected, poiCategoryOf(tag.substringAfter("="), tag)) }
    }

    @Test
    fun `i cimiteri con nome sono sulla mappa, senza nome no`() {
        assertFalse(isPoiHiddenOnMap("Cimitero monumentale di Staglieno", "grave_yard", "amenity=grave_yard"))
        assertTrue(isPoiHiddenOnMap("grave_yard", "grave_yard", "amenity=grave_yard"))
    }

    @Test
    fun `SIGHT_CATEGORIES contiene le sole categorie di da vedere`() {
        assertEquals(9, SIGHT_CATEGORIES.size)
        assertTrue(PoiCategory.ATTRAZIONI in SIGHT_CATEGORIES)
        assertFalse(PoiCategory.SVAGO in SIGHT_CATEGORIES)
        assertFalse(PoiCategory.ALTRO in SIGHT_CATEGORIES)
    }

    @Test
    fun `orari e indirizzo solo per le categorie con dettagli`() {
        assertTrue(poiHasDetails("restaurant", "amenity=restaurant"))
        assertTrue(poiHasDetails("hotel", "tourism=hotel"))
        assertTrue(poiHasDetails("supermarket", "shop=supermarket"))
        assertTrue(poiHasDetails("clinic", "amenity=clinic"))
        assertFalse(poiHasDetails("atm", "amenity=atm"))
        assertFalse(poiHasDetails("museum", "tourism=museum"))
    }

    @Test
    fun `sito ed email solo per alloggi e ambasciate`() {
        assertTrue(poiHasContacts("hotel", "tourism=hotel"))
        assertTrue(poiHasContacts("embassy", "office=diplomatic"))
        assertFalse(poiHasContacts("restaurant", "amenity=restaurant"))
        assertFalse(poiHasContacts("pharmacy", "amenity=pharmacy"))
    }

    @Test
    fun `il nome coincidente con il valore del tag vale come senza nome`() {
        assertFalse(poiHasName("toilets", "amenity=toilets"))
        assertTrue(poiHasName("Bagni del parco", "amenity=toilets"))
        // Il confronto e' esatto: un nome che differisce solo per maiuscole conta come nome.
        assertTrue(poiHasName("Toilets", "amenity=toilets"))
    }

    @Test
    fun `arredo urbano e tipi inutili sono nascosti anche con il nome`() {
        assertTrue(isPoiHiddenOnMap("Panchina", "bench", "amenity=bench"))
        assertTrue(isPoiHiddenOnMap("Fontanella", "drinking_water", "amenity=drinking_water"))
        assertTrue(isPoiHiddenOnMap("Liceo Dante", "school", "amenity=school"))
        assertTrue(isPoiHiddenOnMap("Tavolo", "picnic_table", "leisure=picnic_table"))
    }

    @Test
    fun `i parcheggi privati sono nascosti, gli stalli disabili no`() {
        assertTrue(isPoiHiddenOnMap("Garage Rossi", "parking_private", "amenity=parking"))
        assertTrue(isPoiHiddenOnMap("parking_space", "parking", "amenity=parking_space"))
        assertFalse(isPoiHiddenOnMap("parking_space", "parking_disabled", "amenity=parking_space"))
    }

    @Test
    fun `i cartelli informativi sono nascosti, gli uffici informazioni no`() {
        assertTrue(isPoiHiddenOnMap("Mappa del paese", "information", "tourism=information"))
        assertFalse(isPoiHiddenOnMap("Info Point", "information_office", "tourism=information"))
    }

    @Test
    fun `senza nome restano visibili solo i servizi previsti`() {
        assertFalse(isPoiHiddenOnMap("atm", "atm", "amenity=atm"))
        assertFalse(isPoiHiddenOnMap("toilets", "toilets", "amenity=toilets"))
        assertFalse(isPoiHiddenOnMap("fountain", "fountain", "amenity=fountain"))
        assertFalse(isPoiHiddenOnMap("viewpoint", "viewpoint", "tourism=viewpoint"))
        assertTrue(isPoiHiddenOnMap("restaurant", "restaurant", "amenity=restaurant"))
        assertTrue(isPoiHiddenOnMap("bakery", "bakery", "shop=bakery"))
        assertFalse(isPoiHiddenOnMap("Da Mario", "restaurant", "amenity=restaurant"))
    }

    @Test
    fun `un negozio sfitto con nome e' sul base, senza nome non si pubblica`() {
        assertEquals(PoiPackage.BASE, poiPackageOf("Ex panificio", "vacant", "shop=vacant"))
        assertEquals(null, poiPackageOf("vacant", "vacant", "shop=vacant"))
    }
}
