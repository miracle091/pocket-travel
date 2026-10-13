package com.pockettravel.feature.vault

import com.pockettravel.core.data.DocumentType
import com.pockettravel.core.data.Passport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Dettagli di TravelPlaces che i test della ricerca non guardano: testo normalizzato, voci, ordine e tratta.
class TravelEntryTest {

    private fun airportIndex(vararg lines: String) = SuggestionIndex(parseAirports(lines.toList()))

    private fun codes(index: SuggestionIndex, query: String, limit: Int = MAX_SUGGESTIONS) = index.search(query, limit).map { it.code }

    @Test
    fun `il testo normalizzato e' minuscolo e senza accenti`() {
        assertEquals("zurich", normalize("Zürich"))
        assertEquals("aei", normalize("ÀÉÎ"))
        assertEquals("", normalize(""))
    }

    @Test
    fun `una voce cerca per codice e per le parole di nome, comune e paese`() {
        val entry = parseAirports(listOf("ZRH\tLSZH\tZürich Airport\tZürich\tCH")).single()

        assertEquals(listOf("zrh", "lszh"), entry.codes)
        assertEquals("zurich airport", entry.nameKey)
        assertTrue(entry.words.containsAll(listOf("zurich", "airport", "ch")))
    }

    @Test
    fun `senza comune ne' paese il dettaglio e' vuoto e il campo prende il nome`() {
        val entry = parseAirports(listOf("XYZ\t\tCampo\t\t")).single()

        assertEquals("", entry.text(italian = false).detail)
        assertEquals("XYZ Campo", entry.text(italian = false).value)
    }

    @Test
    fun `con piu' citta' italiane il campo prende la prima`() {
        val entry = parseAirports(listOf("MXP\tLIMC\tMilan Malpensa Airport\tFerno (VA)\tIT\tMilano / Varese")).single()

        assertEquals("MXP Milano", entry.text(italian = true).value)
        assertEquals("MXP Ferno (VA)", entry.text(italian = false).value)
    }

    @Test
    fun `una compagnia mostra sotto il nome il codice ICAO, e senza IATA nessun dettaglio`() {
        val airlines = parseAirlines(listOf("FR\tRYR\tRyanair", "\tABC\tAir Breve"))

        assertEquals("RYR", airlines[0].text(italian = false).detail)
        assertEquals("", airlines[1].text(italian = false).detail)
        assertEquals("ABC", airlines[1].code)
    }

    @Test
    fun `una compagnia con meno di tre colonne e' scartata`() {
        assertTrue(parseAirlines(listOf("FR\tRYR", "FR")).isEmpty())
    }

    @Test
    fun `il limite dei completamenti si sceglie`() {
        val index = SuggestionIndex(parseAirports((1..10).map { "A$it\t\tAirport $it\tTown\tIT" }))

        assertEquals(2, index.search("airport", limit = 2).size)
        assertEquals(10, index.search("airport", limit = 20).size)
    }

    @Test
    fun `tutte le parole scritte devono corrispondere, in qualunque ordine`() {
        val index = airportIndex("JFK\tKJFK\tJohn F. Kennedy International Airport\tNew York\tUS")

        assertEquals(listOf("JFK"), codes(index, "new york"))
        assertEquals(listOf("JFK"), codes(index, "york new"))
        assertEquals(listOf("JFK"), codes(index, "kennedy us"))
        assertTrue(codes(index, "kennedy paris").isEmpty())
    }

    @Test
    fun `una query di sola punteggiatura non da' completamenti`() {
        val index = airportIndex("JFK\tKJFK\tJohn F. Kennedy International Airport\tNew York\tUS")

        assertTrue(index.search("--- ,").isEmpty())
    }

    @Test
    fun `il codice ICAO che inizia cosi' viene prima del nome`() {
        val index = airportIndex(
            "AAA\t\tLirico Airport\tTown\tIT",
            "FCO\tLIRF\tFiumicino\tRome\tIT",
        )

        assertEquals(listOf("FCO", "AAA"), codes(index, "lir"))
    }

    @Test
    fun `il nome che inizia con la query viene prima di una parola nel mezzo, a parita' resta l'ordine dei dati`() {
        val index = airportIndex(
            "B1\t\tBeta Alpha\tTown\tIT",
            "A1\t\tAlpha Beta\tTown\tIT",
            "C1\t\tAlpha Gamma\tTown\tIT",
        )

        assertEquals(listOf("A1", "C1", "B1"), codes(index, "alpha"))
    }

    @Test
    fun `la tratta usa il primo separatore, quello lungo prima di quello semplice`() {
        assertEquals("A" to "B – C", splitRoute("A – B – C"))
        assertEquals("A - B" to "C", splitRoute("A - B – C"))
        assertEquals("" to "", splitRoute(""))
    }

    @Test
    fun `un arrivo vuoto o di soli spazi lascia solo la partenza`() {
        assertEquals("FCO Rome", joinRoute("FCO Rome", "   "))
        assertEquals("", joinRoute("", ""))
        assertEquals("FCO Rome" to "", splitRoute(joinRoute("FCO Rome", "")))
    }

    private fun document(type: DocumentType) = Passport(
        id = "1",
        fullName = "Nome",
        documentNumber = "N1",
        nationality = "Italiana",
        expiryDate = "2030-01-01",
        documentType = type,
    )

    @Test
    fun `i tre tipi di documento hanno etichette proprie per il primo campo`() {
        val names = DocumentType.entries.map { it.fields().name }

        assertEquals(DocumentType.entries.size, names.toSet().size)
        assertEquals(R.string.vault_field_ticket_carrier, DocumentType.TICKET.fields().name)
    }

    @Test
    fun `un numero di soli spazi non basta per il passaporto`() {
        assertFalse(canSaveDocument(DocumentType.PASSPORT, "Mario Rossi", "   "))
        assertFalse(canSaveDocument(DocumentType.TICKET, "   ", ""))
    }

    @Test
    fun `da biglietto ad altro si svuotano nazionalita' e data, da altro a passaporto no`() {
        val other = document(DocumentType.OTHER).withoutHiddenFields(previousType = DocumentType.TICKET)
        assertEquals("", other.nationality)
        assertEquals("", other.expiryDate)

        val passport = document(DocumentType.PASSPORT).withoutHiddenFields(previousType = DocumentType.OTHER)
        assertEquals("Italiana", passport.nationality)
        assertEquals("2030-01-01", passport.expiryDate)
    }
}
