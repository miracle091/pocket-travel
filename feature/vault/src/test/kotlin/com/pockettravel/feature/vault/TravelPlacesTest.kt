package com.pockettravel.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

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
    fun `una query vuota o non trovata non da completamenti`() {
        assertTrue(airports.search("  ").isEmpty())
        assertTrue(airports.search("qqq").isEmpty())
    }

    @Test
    fun `i completamenti sono al massimo sei`() {
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

    private val cities = SuggestionIndex(
        parseAirports(
            listOf(
                "ROM\t\tRome\tRome\tIT\tRoma\tCIA,FCO",
                "MIL\t\tMilan\tMilan\tIT\tMilano\tBGY,LIN,MXP,PMF",
                "FCO\tLIRF\tRome–Fiumicino Leonardo da Vinci International Airport\tRome\tIT\tRoma",
                "MXP\tLIMC\tMilan Malpensa Airport\tFerno (VA)\tIT\tMilano",
                "LIN\tLIML\tMilano Linate Airport\tSegrate (MI)\tIT\tMilano",
                "CIA\tLIRA\tCiampino–G. B. Pastine International Airport\tRome\tIT\tRoma",
                "BGY\tLIME\tIl Caravaggio International Airport\tOrio al Serio (BG)\tIT\tBergamo",
                "PMF\tLIMP\tParma Airport\tParma (PR)\tIT",
                "XIK\t\tMilano Centrale Railway Station\tMilan\tIT\tMilano",
            ),
        ),
    )

    @Test
    fun `una citta' con piu' aeroporti viene prima dei suoi aeroporti`() {
        assertEquals(listOf("ROM", "FCO", "CIA"), codes(cities, "ROM"))
        assertEquals(listOf("MIL", "MXP", "LIN", "BGY", "PMF", "XIK"), codes(cities, "Milano"))
        assertEquals(listOf("MIL", "MXP", "LIN", "BGY", "PMF", "XIK"), codes(cities, "mil"))
    }

    @Test
    fun `gli aeroporti di una citta' si mostrano ma non si cercano`() {
        assertEquals(listOf("FCO"), codes(cities, "fco"))
        val rom = cities.search("rom").first()
        assertEquals("ROM · Roma · CIA, FCO", rom.title(italian = true))
        assertEquals("ROM · Rome · CIA, FCO", rom.title(italian = false))
        assertEquals("ROM Roma", rom.text(italian = true).value)
        assertEquals("FCO · Rome–Fiumicino Leonardo da Vinci International Airport", cities.search("fco").single().title(italian = true))
    }

    @Test
    fun `una stazione si cerca per nome`() {
        val xik = cities.search("Milano Centrale").single()
        assertEquals("XIK", xik.code)
        assertEquals("XIK Milano", xik.text(italian = true).value)
    }

    @Test
    fun `gli asset hanno citta', stazioni e le compagnie di OpenTravelData`() {
        fun asset(name: String) = GZIPInputStream(File("src/main/assets/$name").inputStream()).bufferedReader().use { it.readLines() }
        val airports = SuggestionIndex(parseAirports(asset("airports.tsv.gz")))
        val airlines = SuggestionIndex(parseAirlines(asset("airlines.tsv.gz")))
        // FCO e CIA sono entrambi aeroporti grandi: fra loro vale l'ordine del codice.
        assertEquals(listOf("ROM", "CIA", "FCO"), codes(airports, "ROM").take(3))
        assertEquals("MIL", codes(airports, "Milano").first())
        assertEquals("XIK", codes(airports, "Milano Centrale").first())
        listOf("KE", "AD", "AR", "S7").forEach { assertEquals(it, it, codes(airlines, it).first()) }
        // Chiuse secondo Wikidata, anche se OPTD le tiene valide.
        listOf("9W", "NK").forEach { assertTrue(it, codes(airlines, it).none { code -> code == it }) }
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
