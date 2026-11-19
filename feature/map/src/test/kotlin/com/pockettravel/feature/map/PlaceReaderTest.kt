package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceReaderTest {
    private fun point(text: String): PlaceReading.Coordinates = readPlace(text) as PlaceReading.Coordinates

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
        listOf("", "   ", "Colosseo", "via Roma 10", "43.6", "43.6, 1.44, 12", "2026-11-19", "+39 0612345678", "43.6 N, 1.44 E", "43°36'00\"N 1°26'24\"E")
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

    // --- Link brevi e testi condivisi ---

    @Test
    fun `i link brevi chiedono la rete e lo dicono`() {
        assertEquals(PlaceReading.ShortLink, readPlace("https://maps.app.goo.gl/AbC123"))
        assertEquals(PlaceReading.ShortLink, readPlace("https://goo.gl/maps/AbC123"))
        assertEquals(PlaceReading.ShortLink, readPlace("https://osm.org/go/0EEQjE=="))
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
