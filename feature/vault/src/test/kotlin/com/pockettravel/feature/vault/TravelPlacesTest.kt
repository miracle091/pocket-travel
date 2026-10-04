package com.pockettravel.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelPlacesTest {
    private val airports = SuggestionIndex(
        parseAirports(
            listOf(
                "FCO\tLIRF\tRome–Fiumicino Leonardo da Vinci International Airport\tRome\tIT\tRoma",
                "MXP\tLIMC\tMilan Malpensa Airport\tFerno (VA)\tIT\tMilano / Varese",
                "JFK\tKJFK\tJohn F. Kennedy International Airport\tNew York\tUS",
                "ZRH\tLSZH\tZürich Airport\tZürich\tCH",
                "LIN\tLIML\tMilano Linate Airport\tSegrate (MI)\tIT",
                "XYZ\t\tCampo senza città\t\tAQ",
                "",
                "riga troppo corta",
            ),
        ),
    )
    private val airlines = SuggestionIndex(
        parseAirlines(
            listOf(
                "FR\tRYR\tRyanair",
                "AZ\tITY\tITA Airways",
                "\tABC\tAir Breve",
            ),
        ),
    )

    private fun codes(index: SuggestionIndex, query: String) = index.search(query).map { it.code }

    @Test
    fun `le righe vuote o incomplete sono scartate`() {
        assertEquals(listOf("XYZ"), codes(airports, "campo"))
    }

    @Test
    fun `si cerca per codice IATA e ICAO`() {
        assertEquals(listOf("JFK"), codes(airports, "jfk"))
        assertEquals(listOf("JFK"), codes(airports, "KJF"))
    }

    @Test
    fun `si cerca per comune e nome senza accenti e senza maiuscole`() {
        assertEquals(listOf("ZRH"), codes(airports, "zurich"))
        assertEquals(listOf("ZRH"), codes(airports, "ZÜR"))
        assertEquals(listOf("FCO"), codes(airports, "fiumi"))
        assertEquals(listOf("JFK"), codes(airports, "new yo"))
    }

    @Test
    fun `il codice uguale precede il prefisso e il resto`() {
        val index = SuggestionIndex(
            parseAirports(
                listOf(
                    "ABC\t\tRoma Alfa\tAbc Town\tIT",
                    "XYZ\t\tAbcdef Airport\tTown\tIT",
                    "ZZZ\t\tAltro\tTown\tIT",
                    "AB\t\tAltra pista\tTown\tIT",
                    "QQQ\t\tPort Abx\tTown\tIT",
                ),
            ),
        )
        assertEquals(listOf("AB", "ABC", "XYZ", "QQQ"), codes(index, "ab"))
    }

    @Test
    fun `una query vuota o non trovata non da suggerimenti`() {
        assertTrue(airports.search("  ").isEmpty())
        assertTrue(airports.search("qqq").isEmpty())
    }

    @Test
    fun `i suggerimenti sono al massimo sei`() {
        val many = SuggestionIndex(parseAirports((1..20).map { "A$it\t\tAirport $it\tTown\tIT" }))
        assertEquals(MAX_SUGGESTIONS, many.search("airport").size)
    }

    @Test
    fun `il testo salvato per un aeroporto e' codice e comune`() {
        assertEquals("FCO Rome", airports.search("fco").single().text(italian = false).value)
        assertEquals("XYZ Campo senza città", airports.search("xyz").single().text(italian = false).value)
    }

    @Test
    fun `le citta italiane si cercano e in italiano finiscono nel campo`() {
        assertEquals(listOf("FCO"), codes(airports, "roma"))
        assertEquals(listOf("MXP"), codes(airports, "varese"))
        val fco = airports.search("roma").single()
        assertEquals("FCO Roma", fco.text(italian = true).value)
        assertEquals("Roma, IT", fco.text(italian = true).detail)
        assertEquals("Rome, IT", fco.text(italian = false).detail)
        val mxp = airports.search("mxp").single()
        assertEquals("MXP Milano", mxp.text(italian = true).value)
        assertEquals("Milano / Varese, IT", mxp.text(italian = true).detail)
    }

    @Test
    fun `senza nome italiano il testo resta lo stesso`() {
        val jfk = airports.search("jfk").single()
        assertEquals("JFK New York", jfk.text(italian = true).value)
    }

    @Test
    fun `le compagnie si cercano per nome e per codice e salvano il nome`() {
        assertEquals(listOf("FR"), codes(airlines, "ryan"))
        assertEquals(listOf("AZ"), codes(airlines, "ity"))
        val breve = airlines.search("abc").single()
        assertEquals("ABC", breve.code)
        assertEquals("Air Breve", breve.text(italian = true).value)
    }

    @Test
    fun `la tratta si divide e si ricompone`() {
        assertEquals("FCO Rome" to "JFK New York", splitRoute("FCO Rome – JFK New York"))
        assertEquals("Roma" to "Milano", splitRoute("Roma - Milano"))
        assertEquals("" to "Milano", splitRoute(joinRoute("", "Milano")))
        assertEquals("FCO Rome – JFK New York", joinRoute("FCO Rome", "JFK New York"))
    }

    @Test
    fun `un testo libero senza separatore resta intero`() {
        assertEquals("Frecciarossa 9612" to "", splitRoute("Frecciarossa 9612"))
        assertEquals("Frecciarossa 9612", joinRoute("Frecciarossa 9612", ""))
    }
}
