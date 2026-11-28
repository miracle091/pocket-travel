package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceReaderTest {
    private fun point(text: String): PlaceReading.Coordinates = readPlace(text) as PlaceReading.Coordinates

    // Per i punti calcolati da un codice: confronto con una tolleranza in gradi.
    private fun assertNear(latitude: Double, longitude: Double, text: String, tolerance: Double = 1e-6, name: String? = null) {
        val reading = point(text)
        assertEquals(text, latitude, reading.latitude, tolerance)
        assertEquals(text, longitude, reading.longitude, tolerance)
        assertEquals(text, name, reading.name)
    }

    private fun assertPoint(latitude: Double, longitude: Double, text: String, name: String? = null) =
        assertEquals(text, PlaceReading.Coordinates(latitude, longitude, name), readPlace(text))

    // --- Gradi decimali ---

    @Test
    fun `gradi decimali con virgola, punto e virgola o spazio`() {
        assertPoint(43.6, 1.44, "43.6, 1.44")
        assertPoint(43.6, 1.44, "43.6,1.44")
        assertPoint(43.6, 1.44, "43.6 1.44")
        assertPoint(43.6, 1.44, "43,6 1,44")
        assertPoint(43.6, 1.44, "43,6; 1,44")
        assertPoint(43.6, 1.44, "43,6, 1,44")
        assertPoint(43.0, 100.0, "43,100")
    }

    @Test
    fun `il segno e gli spazi attorno si leggono`() {
        assertPoint(-33.86, 151.21, "  -33.86 , +151.21  ")
        assertPoint(-34.6, -58.38, "-34,6 -58,38")
    }

    @Test
    fun `gli estremi dell'intervallo valgono, fuori no`() {
        assertPoint(90.0, 180.0, "90, 180")
        assertPoint(-90.0, -180.0, "-90, -180")
        assertEquals(PlaceReading.Unreadable, readPlace("90.5, 10"))
        assertEquals(PlaceReading.Unreadable, readPlace("10, 180.5"))
        assertEquals(PlaceReading.Unreadable, readPlace("-91, 0"))
    }

    @Test
    fun `testi che non sono una coppia di numeri non sono coordinate`() {
        listOf("", "   ", "Colosseo", "via Roma 10", "43.6", "43.6, 1.44, 12", "2026-11-19", "+39 0612345678", "43.6 N, 1.44 E")
            .forEach { assertEquals(it, PlaceReading.Unreadable, readPlace(it)) }
    }

    // --- geo: ---

    @Test
    fun `geo con latitudine e longitudine, anche con quota, incertezza e zoom`() {
        assertPoint(43.6, 1.44, "geo:43.6,1.44")
        assertPoint(43.6, 1.44, "GEO:43.6,1.44,120")
        assertPoint(43.6, 1.44, "geo:43.6,1.44;u=35")
        assertPoint(-43.6, -1.44, "geo:-43.6,-1.44?z=15")
    }

    @Test
    fun `geo con q prende il nome o le coordinate di q`() {
        assertPoint(43.6, 1.44, "geo:43.6,1.44?q=Capitole%20de%20Toulouse", name = "Capitole de Toulouse")
        assertPoint(43.6, 1.44, "geo:0,0?q=43.6,1.44(Casa%20mia)", name = "Casa mia")
        assertPoint(43.6, 1.44, "geo:0,0?q=43.6,1.44")
        assertPoint(43.6, 1.44, "geo:43.6,1.44?q=43.6,1.44(Casa)", name = "Casa")
    }

    @Test
    fun `geo 0,0 con un testo chiede di cercarlo, senza testo non indica niente`() {
        assertEquals(PlaceReading.Search("Via Roma 1, Rimini"), readPlace("geo:0,0?q=Via%20Roma%201,%20Rimini"))
        assertEquals(PlaceReading.Unreadable, readPlace("geo:0,0"))
        assertEquals(PlaceReading.Unreadable, readPlace("geo:"))
        assertEquals(PlaceReading.Unreadable, readPlace("geo:abc,def"))
        assertEquals(PlaceReading.Unreadable, readPlace("geo:91,0"))
    }

    // --- Google Maps ---

    @Test
    fun `link di Google Maps con la vista e il nome del posto`() {
        assertPoint(43.6, 1.44, "https://www.google.com/maps/@43.6,1.44,15z")
        assertPoint(48.8583, 2.2944, "https://www.google.com/maps/place/Eiffel+Tower/@48.8583,2.2944,17z", name = "Eiffel Tower")
    }

    @Test
    fun `il punto del posto vale piu' del centro della vista`() {
        assertPoint(
            48.8584, 2.2945,
            "https://www.google.com/maps/place/Eiffel+Tower/@48.86,2.30,17z/data=!3m1!4b1!4m6!3m5!1s0x47e66e2964e34e2d:0x8ddca9ee380ef7e0!8m2!3d48.8584!4d2.2945",
            name = "Eiffel Tower",
        )
    }

    @Test
    fun `link di Google Maps con i parametri di ricerca e di percorso`() {
        assertPoint(43.6, 1.44, "https://maps.google.com/?q=43.6,1.44")
        assertPoint(43.6, 1.44, "https://www.google.com/maps?q=43.6%2C1.44")
        assertPoint(43.6, 1.44, "https://www.google.com/maps?q=loc:43.6,1.44")
        assertPoint(43.6, 1.44, "https://www.google.com/maps/search/?api=1&query=43.6,1.44")
        assertPoint(43.6, 1.44, "https://www.google.it/maps?ll=43.6,1.44&z=12")
        assertPoint(43.6, 1.44, "https://www.google.com/maps/dir/?api=1&destination=43.6,1.44&travelmode=walking")
        assertPoint(43.6, 1.44, "http://maps.google.com/maps?daddr=43.6,1.44")
    }

    @Test
    fun `daddr vale piu' di ll, che nei link vecchi e' il centro della mappa`() {
        assertPoint(41.89, 12.49, "https://maps.google.com/maps?saddr=45.46,9.19&daddr=41.89,12.49&ll=43.5,10.9&z=7")
    }

    @Test
    fun `link di Google Maps senza coordinate o con host che non e' di Google`() {
        assertEquals(PlaceReading.Unreadable, readPlace("https://www.google.com/maps?q=Eiffel+Tower"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://www.google.com/search?q=43.6,1.44"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://evilgoogle.com/maps/@43.6,1.44,15z"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://maps.google.com@esempio.it/maps/@43.6,1.44,15z"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://www.google.com/maps/@95,1.44,15z"))
    }

    // --- OpenStreetMap ---

    @Test
    fun `link di OpenStreetMap con il segnaposto o con la vista`() {
        assertPoint(43.6, 1.44, "https://www.openstreetmap.org/?mlat=43.6&mlon=1.44#map=15/43.7/1.5")
        assertPoint(43.7, 1.5, "https://www.openstreetmap.org/#map=15/43.7/1.5")
        assertPoint(-43.7, -1.5, "https://osm.org/#map=15/-43.7/-1.5&layers=N")
        assertPoint(43.7, 1.5, "https://www.openstreetmap.org/#map=15.5/43.7/1.5")
    }

    @Test
    fun `link di OpenStreetMap senza posizione leggibile`() {
        assertEquals(PlaceReading.Unreadable, readPlace("https://www.openstreetmap.org/node/123456"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://www.openstreetmap.org/#map=15/95/1.5"))
    }

    @Test
    fun `link brevi di OpenStreetMap si leggono senza rete`() {
        // Punti dati dal server di OpenStreetMap, che risponde a /go/codice?m con mlat e mlon.
        assertPoint(51.510772705078125, 0.054931640625, "https://osm.org/go/0EEQjE==")
        assertPoint(41.890199929475784, 12.492198050022125, "https://osm.org/go/xcXpDVgJz-?m")
        assertPoint(-33.85680556297302, 151.21528387069702, "https://www.openstreetmap.org/go/uN~Ra6l7--")
        // "@" e' il vecchio "~".
        assertPoint(-33.85680556297302, 151.21528387069702, "https://osm.org/go/uN@Ra6l7--")
    }

    @Test
    fun `link brevi di OpenStreetMap malformati`() {
        assertEquals(PlaceReading.Unreadable, readPlace("https://osm.org/go/"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://osm.org/go/0EE%Q"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://osm.org/go/-"))
        // 12 caratteri sono 36 bit per coordinata, oltre i 32 del formato.
        assertEquals(PlaceReading.Unreadable, readPlace("https://osm.org/go/AAAAAAAAAAAA"))
    }

    // --- Gradi, primi e secondi ---

    @Test
    fun `gradi primi e secondi con l'emisfero dopo i numeri`() {
        // 41 + 53/60 + 24.8/3600 e 12 + 29/60 + 32.3/3600.
        assertNear(41.8902222, 12.4923056, "41°53'24.8\"N 12°29'32.3\"E")
        assertNear(41.8902222, 12.4923056, "41°53′24.8″N, 12°29′32.3″E")
        assertNear(41.8902222, 12.4923056, "41° 53' 24.8\" N; 12° 29' 32.3\" E")
        assertNear(-33.8568056, 151.2152778, "33°51'24.5\"S 151°12'55\"E")
        assertNear(-33.8568056, -151.2152778, "33°51'24.5\"S 151°12'55\"W")
        // Spazio unicode (non separabile) tra le due coordinate.
        assertNear(41.8902222, 12.4923056, "41°53'24.8\"N 12°29'32.3\"E")
    }

    @Test
    fun `gradi e primi decimali con l'emisfero prima o dopo`() {
        // 41 + 53.413/60 e 12 + 29.538/60.
        assertNear(41.8902167, 12.4923, "N 41° 53.413' E 12° 29.538'")
        assertNear(41.8902167, 12.4923, "41°53.413'N, 12°29.538'E")
        assertNear(41.8902167, 12.4923, "E 12° 29.538' N 41° 53.413'")
        assertNear(-41.8902167, -12.4923, "s 41° 53.413' w 12° 29.538'")
    }

    @Test
    fun `gradi primi e secondi malformati o fuori intervallo non sono coordinate`() {
        listOf("41°75'N 12°29'E", "41°53'61\"N 12°29'E", "41°53'N 12°29'N", "41°53'E 12°29'W", "91°00'N 12°29'E", "41°N 12°E", "41°53'24.8\"N")
            .forEach { assertEquals(it, PlaceReading.Unreadable, readPlace(it)) }
    }

    // --- Plus Code ---

    @Test
    fun `Plus Code completi si leggono al centro della cella`() {
        assertNear(37.4220625, -122.0840625, "849VCWC8+R9")
        assertNear(37.4220625, -122.0840625, "849vcwc8+r9")
        // Con una cifra di griglia: la cella e' piu' piccola.
        assertNear(37.4221125, -122.084078125, "849VCWC8+R9V")
        assertNear(41.8979375, 12.5944375, "8FHJVHXV+5Q")
        assertNear(37.4220625, -122.0840625, "Siamo qui: 849VCWC8+R9, grazie")
    }

    @Test
    fun `Plus Code in un link di plus-codes o di Google Maps`() {
        assertNear(41.8979375, 12.5944375, "https://plus.codes/8FHJVHXV+5Q")
        assertNear(41.8979375, 12.5944375, "https://plus.codes/8FHJVHXV%2B5Q")
        assertNear(37.4220625, -122.0840625, "https://www.google.com/maps/place/849VCWC8%2BR9+Mountain+View")
        assertNear(37.4220625, -122.0840625, "https://www.google.com/maps/place/849VCWC8+R9")
    }

    @Test
    fun `Plus Code corti o malformati non si leggono`() {
        // Un codice corto ha bisogno di un posto di riferimento che il testo non da'.
        listOf("VCWC8+R9", "9G8F+6W Zurigo", "849VCWC8+R", "849VCWC8+", "849VCWC8+R9A", "GGGGGGGG+GG", "https://plus.codes/", "https://plus.codes/VCWC8+R9")
            .forEach { assertEquals(it, PlaceReading.Unreadable, readPlace(it)) }
    }

    // --- Apple Maps ---

    @Test
    fun `link di Apple Maps con il punto`() {
        assertPoint(41.8902, 12.4922, "https://maps.apple.com/?ll=41.8902,12.4922&q=Colosseo")
        assertPoint(41.8902, 12.4922, "https://maps.apple.com/?q=41.8902,12.4922")
        assertPoint(41.8902, 12.4922, "https://maps.apple.com/?coordinate=41.8902%2C12.4922&name=Colosseo")
        assertPoint(41.8902, 12.4922, "https://maps.apple.com/?sll=41.8902,12.4922")
    }

    @Test
    fun `un percorso di Apple Maps da' la destinazione`() {
        assertPoint(41.8902, 12.4922, "https://maps.apple.com/?saddr=43.6,1.44&daddr=41.8902,12.4922")
        assertPoint(41.8902, 12.4922, "https://maps.apple.com/directions?source=43.6,1.44&destination=41.8902,12.4922")
        // La destinazione senza coordinate non si sostituisce con la vista o la partenza.
        assertEquals(PlaceReading.Unreadable, readPlace("https://maps.apple.com/?daddr=Roma&ll=43.6,1.44"))
    }

    @Test
    fun `link di Apple Maps abbreviati o senza coordinate`() {
        assertEquals(PlaceReading.ShortLink, readPlace("https://maps.apple/p/AbCdEf123"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://maps.apple.com/?address=Via+Roma+1"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://maps.apple.com/?ll=95,1"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://maps.apple.com.esempio.it/?ll=41.8,12.4"))
    }

    // --- Waze ---

    @Test
    fun `link di Waze con ll e con il geohash`() {
        assertPoint(45.69, -120.81, "https://www.waze.com/ul?ll=45.69,-120.81&navigate=yes&zoom=17")
        assertPoint(41.8902, 12.4922, "https://waze.com/ul?ll=41.8902%2C12.4922")
        // Geohash noto: "u4pruydqqvj" e' 57.64911, 10.40744; "ezs42" e' il centro della cella 42.605, -5.603.
        assertNear(57.64911, 10.40744, "https://waze.com/ul/hu4pruydqqvj", tolerance = 1e-4)
        assertNear(42.60498046875, -5.60302734375, "https://ul.waze.com/ul/hezs42")
        assertNear(42.60498046875, -5.60302734375, "https://www.waze.com/ul/hEZS42/")
    }

    @Test
    fun `link di Waze malformati`() {
        // "a" non e' nell'alfabeto del geohash.
        listOf("https://waze.com/ul/hu4pruyda", "https://waze.com/ul/h", "https://waze.com/ul?ll=95,1", "https://evilwaze.com/ul?ll=41.8,12.4")
            .forEach { assertEquals(it, PlaceReading.Unreadable, readPlace(it)) }
    }

    @Test
    fun `i link di ricerca di Waze, HERE e Mapy cercano il nome e non prendono la vista`() {
        assertEquals(PlaceReading.Search("Roma"), readPlace("https://waze.com/ul?q=Roma"))
        assertEquals(PlaceReading.Search("Via Roma 1"), readPlace("https://waze.com/ul?q=Via+Roma+1&ll=45.69,9.67"))
        assertEquals(PlaceReading.Search("colosseo"), readPlace("https://wego.here.com/search/colosseo?map=41.89,12.49,15,normal"))
        assertEquals(PlaceReading.Search("colosseo"), readPlace("https://wego.here.com/discover/colosseo?map=41.89,12.49,15,normal"))
        assertEquals(PlaceReading.Search("praha"), readPlace("https://mapy.cz/zakladni?q=praha&x=14.4&y=50.08&z=12"))
    }

    // --- Organic Maps (ge0) ---

    @Test
    fun `link ge0 di Organic Maps con il nome`() {
        // Codici scritti con l'algoritmo della descrizione di ge0 a partire dal Colosseo e dall'Opera House.
        assertNear(41.8902, 12.4922, "ge0://sysrWDqQGx/Colosseo", tolerance = 1e-5, name = "Colosseo")
        assertNear(41.8902, 12.4922, "https://omaps.app/sysrWDqQGx")
        assertNear(-33.8568, 151.2153, "https://omaps.app/sdO_il1a30/Sydney_Opera_House", tolerance = 1e-5, name = "Sydney Opera House")
        assertNear(-33.8568, 151.2153, "https://comaps.at/sdO_il1a30/", tolerance = 1e-5)
        // Casi dei test del parser di Organic Maps (libs/ge0/ge0_tests/parser_tests.cpp), indipendenti da questo codice.
        assertNear(64.5234, 12.1234, "ge0://B4srhdHVVt/Name", tolerance = 1e-5, name = "Name")
        assertNear(-20.0, 20.0, "ge0://BaF6F6F6F6/Name", tolerance = 1e-5, name = "Name")
        // Nel nome "_" e' uno spazio e "%20" un trattino basso.
        assertNear(64.5234, 12.1234, "ge0://B4srhdHVVt/A_B%20C", tolerance = 1e-5, name = "A B_C")
    }

    @Test
    fun `link ge0 malformati`() {
        listOf("ge0://s", "ge0://", "ge0://Ag", "https://omaps.app/news", "https://omaps.app/sysrWDqQG!x", "https://omaps.app/sysrWDqQGxxx", "https://omaps.app/")
            .forEach { assertEquals(it, PlaceReading.Unreadable, readPlace(it)) }
    }

    // --- OsmAnd ---

    @Test
    fun `link di OsmAnd`() {
        assertPoint(41.8902, 12.4922, "https://osmand.net/go?lat=41.8902&lon=12.4922&z=15")
        assertPoint(41.8902, 12.4922, "https://osmand.net/map#15/41.8902/12.4922")
        assertPoint(41.8902, 12.4922, "https://www.osmand.net/map?pin=41.8902,12.4922#15/1/1")
        assertEquals(PlaceReading.Unreadable, readPlace("https://osmand.net/go?lat=95&lon=12"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://osmand.net/map#15/41.8902"))
    }

    // --- HERE ---

    @Test
    fun `link di HERE WeGo`() {
        assertPoint(52.5163, 13.3777, "https://share.here.com/l/52.5163,13.3777")
        assertPoint(52.5163, 13.3777, "https://share.here.com/l/52.5163,13.3777,Brandenburger%20Tor?z=16")
        assertPoint(-33.8568, 151.2153, "https://wego.here.com/?map=-33.8568,151.2153,15,normal")
        assertEquals(PlaceReading.ShortLink, readPlace("https://share.here.com/p/s-YmI9NDguMg"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://share.here.com/l/abc,def"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://wego.here.com/?map=95,13,15"))
    }

    // --- Mapy ---

    @Test
    fun `link di Mapy con x e y o con il punto scelto`() {
        assertPoint(50.0875, 14.4208, "https://mapy.cz/zakladni?x=14.4208&y=50.0875&z=15")
        assertPoint(50.0875, 14.4208, "https://mapy.com/en/zakladni?source=coor&id=14.4208,50.0875")
        // Il punto scelto vale piu' della vista.
        assertPoint(50.0875, 14.4208, "https://www.mapy.cz/zakladni?source=coor&id=14.4208,50.0875&x=14.0&y=50.0")
    }

    @Test
    fun `link di Mapy abbreviati o malformati`() {
        assertEquals(PlaceReading.ShortLink, readPlace("https://mapy.cz/s/abcdef"))
        // L'hash `rc` dei percorsi non si legge.
        listOf("https://mapy.cz/?rc=9ujH5xXjpC", "https://mapy.cz/zakladni?x=14.4&y=95", "https://mapy.cz/zakladni?source=coor&id=14.4", "https://evilmapy.cz/?x=14.4&y=50.1")
            .forEach { assertEquals(it, PlaceReading.Unreadable, readPlace(it)) }
    }

    // --- Magic Earth ---

    @Test
    fun `link di Magic Earth`() {
        assertPoint(41.8902, 12.4922, "https://magicearth.com/?lat=41.8902&lon=12.4922")
        assertPoint(41.8902, 12.4922, "https://magicearth.com/?show_on_map&lat=41.8902&lon=12.4922&name=Colosseo&z=15", name = "Colosseo")
        assertEquals(PlaceReading.Unreadable, readPlace("https://magicearth.com/?lat=41.8902"))
        assertEquals(PlaceReading.Unreadable, readPlace("https://magicearth.com/?lat=41.8902&lon=181"))
    }

    // --- Percorsi di Google con piu' punti ---

    @Test
    fun `un percorso di Google Maps da' l'ultimo punto`() {
        assertPoint(45.07, 7.69, "https://www.google.com/maps/dir/41.9,12.5/43.6,1.44/45.07,7.69")
        assertPoint(43.6, 1.44, "https://www.google.com/maps/dir/41.9,12.5/43.6,1.44/@42.5,8.0,6z/data=!3m1!4b1")
        assertPoint(43.6, 1.44, "https://www.google.com/maps/dir//43.6,1.44")
        assertPoint(41.9, 12.5, "https://www.google.com/maps/dir/?api=1&origin=43.6,1.44&destination=41.9,12.5")
        assertPoint(43.6, 1.44, "https://www.google.com/maps/dir/41.9,12.5/43.6,1.44/@42.5,8.0,6z/am=t/data=!3m1!4b1")
    }

    @Test
    fun `un percorso con l'arrivo per nome non prende il centro della vista`() {
        // Il centro della vista di un percorso sta di solito a meta' strada: come destinazione sarebbe sbagliato.
        listOf(
            "https://www.google.com/maps/dir/41.9,12.5/Colosseo,+Roma/@41.95,12.6,12z/data=!3m1!4b1",
            "https://mapy.cz/zakladni?planovani-trasy&x=14.43&y=50.08&z=13&rc=9ujH5xXjpC",
            "https://wego.here.com/directions/drive/a/b?map=52.55,13.45,12,normal",
            "https://osmand.net/map/navigate/?start=52.1,4.3&end=52.3,4.9#9/52.2/4.8",
        ).forEach { assertEquals(it, PlaceReading.Unreadable, readPlace(it)) }
    }

    @Test
    fun `il Plus Code di un posto di Google vale piu' della vista`() {
        assertNear(37.4220625, -122.0840625, "https://www.google.com/maps/place/849VCWC8+R9/@37.43,-122.09,15z")
    }

    @Test
    fun `in Apple Maps sll e' dove cercare, non il posto`() {
        assertEquals(PlaceReading.Unreadable, readPlace("https://maps.apple.com/?q=pizzeria&sll=41.89,12.49"))
    }

    // --- Link brevi e testi condivisi ---

    @Test
    fun `i link brevi chiedono la rete e lo dicono`() {
        assertEquals(PlaceReading.ShortLink, readPlace("https://maps.app.goo.gl/AbC123"))
        assertEquals(PlaceReading.ShortLink, readPlace("https://goo.gl/maps/AbC123"))
    }

    @Test
    fun `un testo condiviso con il link in mezzo`() {
        assertPoint(43.6, 1.44, "Guarda dove siamo: https://www.google.com/maps/@43.6,1.44,15z grazie")
        assertPoint(43.6, 1.44, "Il posto\n<https://www.openstreetmap.org/?mlat=43.6&mlon=1.44>.")
        assertEquals(PlaceReading.ShortLink, readPlace("Torre Eiffel\nhttps://maps.app.goo.gl/AbC123"))
    }

    @Test
    fun `tra due link vale quello con le coordinate`() {
        assertPoint(43.6, 1.44, "https://maps.app.goo.gl/AbC123 https://www.google.com/maps/@43.6,1.44,15z")
    }

    @Test
    fun `un testo senza posto non si legge e non fa errori`() {
        listOf("Ciao a tutti", "https://esempio.it/pagina", "https://www.google.com/maps/@43.6", "ftp://x/@43.6,1.44", "https://%zz@")
            .forEach { assertEquals(it, PlaceReading.Unreadable, readPlace(it)) }
        assertEquals(PlaceReading.Unreadable, readPlace("x".repeat(100_000)))
    }

    @Test
    fun `una percentuale malformata non fa errori`() {
        assertPoint(43.6, 1.44, "https://www.google.com/maps/@43.6,1.44,15z?q=%zz")
        assertEquals(PlaceReading.Search("%"), readPlace("geo:0,0?q=%"))
    }

    @Test
    fun `il nome si ripulisce dai caratteri di controllo e si accorcia`() {
        val reading = point("geo:43.6,1.44?q=A%09B%0AC")
        assertEquals("A B C", reading.name)
        assertEquals(100, point("geo:43.6,1.44?q=" + "x".repeat(500)).name?.length)
    }

    @Test
    fun `looksLikeLink riconosce link e geo`() {
        assertEquals(true, looksLikeLink("https://www.google.com/maps"))
        assertEquals(true, looksLikeLink("geo:43.6,1.44"))
        assertEquals(false, looksLikeLink("via Roma 10"))
    }
}
