package com.pockettravel.feature.vault

import com.pockettravel.core.data.DocumentType
import com.pockettravel.core.data.Passport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Casi limite di lettura dei TSV, ordinamento, divisione della tratta e regole dei campi del documento non coperti altrove.
class TravelSearchEdgeCasesTest {

    private fun airportIndex(vararg lines: String) = SuggestionIndex(parseAirports(lines.toList()))

    private fun codes(index: SuggestionIndex, query: String) = index.search(query).map { it.code }

    private fun document(type: DocumentType) = Passport(
        id = "1",
        fullName = "Nome",
        documentNumber = "N1",
        nationality = "Italiana",
        expiryDate = "2030-01-01",
        documentType = type,
    )

    @Test
    fun `an airport row with four columns is dropped`() {
        assertTrue(parseAirports(listOf("AAA\tBBB\tName\tCity")).isEmpty())
    }

    @Test
    fun `an empty sixth column reuses the English texts`() {
        val entry = parseAirports(listOf("AAA\t\tName\tCity\tIT\t")).single()

        assertSame(entry.english, entry.italian)
        assertEquals("City, IT", entry.text(italian = true).detail)
    }

    @Test
    fun `an airport with only the country has the country as detail`() {
        val entry = parseAirports(listOf("AAA\t\tName\t\tIT")).single()

        assertEquals("IT", entry.text(italian = false).detail)
        assertEquals("AAA Name", entry.text(italian = false).value)
    }

    @Test
    fun `an airline without IATA uses the ICAO code and is found once`() {
        val index = SuggestionIndex(parseAirlines(listOf("\tABC\tAir Breve")))

        assertEquals(listOf("ABC"), codes(index, "abc"))
        assertEquals(listOf("ABC"), codes(index, "breve"))
    }

    @Test
    fun `an airline without any code is found by name only`() {
        val entry = parseAirlines(listOf("\t\tNameless Air")).single()
        val index = SuggestionIndex(listOf(entry))

        assertEquals("", entry.code)
        assertEquals("Nameless Air", entry.text(italian = true).value)
        assertEquals(1, index.search("nameless").size)
    }

    @Test
    fun `a zero limit gives no completions`() {
        val index = airportIndex("AAA\t\tAlpha\tTown\tIT")

        assertTrue(index.search("alpha", limit = 0).isEmpty())
    }

    @Test
    fun `numbers in names are searchable`() {
        val index = airportIndex("AAA\t\tTerminal 2 Field\tTown\tIT")

        assertEquals(listOf("AAA"), codes(index, "2"))
        assertEquals(listOf("AAA"), codes(index, "terminal 2"))
    }

    @Test
    fun `an exact ICAO code precedes a name that starts with the query`() {
        val index = airportIndex(
            "LRF\t\tLirf Field\tTown\tIT",
            "FCO\tLIRF\tFiumicino\tRome\tIT",
        )

        assertEquals(listOf("FCO", "LRF"), codes(index, "lirf"))
    }

    @Test
    fun `a single word starting the name ranks first, two words that do not keep the data order`() {
        val index = airportIndex(
            "FCO\t\tFiumicino\tRome\tIT",
            "ROM\t\tRome Town Field\tRome\tIT",
        )

        assertEquals(listOf("ROM", "FCO"), codes(index, "rome"))
        assertEquals(listOf("FCO", "ROM"), codes(index, "rome it"))
    }

    @Test
    fun `a multi-word query that starts the name ranks before a word in the middle`() {
        val index = airportIndex(
            "B1\t\tBeta Alpha Beta\tTown\tIT",
            "A1\t\tAlpha Beta\tTown\tIT",
        )

        assertEquals(listOf("A1", "B1"), codes(index, "alpha be"))
    }

    @Test
    fun `an apostrophe splits a name into words`() {
        val entry = parseAirports(listOf("ORD\tKORD\tChicago O'Hare\tChicago\tUS")).single()
        val index = SuggestionIndex(listOf(entry))

        assertTrue(entry.words.containsAll(listOf("o", "hare")))
        assertEquals(listOf("ORD"), codes(index, "o'hare"))
        assertEquals(listOf("ORD"), codes(index, "hare"))
    }

    @Test
    fun `normalization drops cedillas and breves and is idempotent`() {
        assertEquals("cg", normalize("ÇĞ"))
        assertEquals(normalize("Zürich"), normalize(normalize("Zürich")))
    }

    @Test
    fun `a leading or trailing route separator leaves one side empty`() {
        assertEquals("" to "Milano", splitRoute(" – Milano"))
        assertEquals("Roma" to "", splitRoute("Roma – "))
    }

    @Test
    fun `a hyphen without spaces is not a route separator`() {
        assertEquals("Roma-Milano" to "", splitRoute("Roma-Milano"))
    }

    @Test
    fun `the first separator wins when both kinds follow each other`() {
        assertEquals("A" to "B - C", splitRoute("A – B - C"))
    }

    @Test
    fun `only the passport requires the number`() {
        assertTrue(DocumentType.PASSPORT.fields().numberRequired)
        assertFalse(DocumentType.TICKET.fields().numberRequired)
        assertFalse(DocumentType.OTHER.fields().numberRequired)
    }

    @Test
    fun `a document needs a name whatever the type`() {
        DocumentType.entries.forEach {
            assertFalse(canSaveDocument(it, "", "N1"))
            assertFalse(canSaveDocument(it, "\t ", ""))
        }
    }

    @Test
    fun `hiding fields keeps the other values`() {
        val cleaned = document(DocumentType.OTHER).withoutHiddenFields(previousType = DocumentType.PASSPORT)

        assertEquals("1", cleaned.id)
        assertEquals("Nome", cleaned.fullName)
        assertEquals("N1", cleaned.documentNumber)
        assertEquals(DocumentType.OTHER, cleaned.documentType)
    }

    @Test
    fun `a passport with no previous type keeps nationality and expiry`() {
        val kept = document(DocumentType.PASSPORT).withoutHiddenFields(previousType = null)

        assertEquals("Italiana", kept.nationality)
        assertEquals("2030-01-01", kept.expiryDate)
    }

    @Test
    fun `senza apostrofo la ricerca trova il nome con apostrofo`() {
        val index = airportIndex(
            "ORD	KORD	Chicago O'Hare	Chicago	US",
            "AQ1		L’Aquila Field	L'Aquila	IT",
            "DO1		Dell'Orso	Town	IT",
        )

        assertEquals(listOf("ORD"), codes(index, "ohare"))
        assertEquals(listOf("ORD"), codes(index, "o’hare"))
        assertEquals(listOf("ORD"), codes(index, "hare"))
        assertEquals(listOf("AQ1"), codes(index, "laquila"))
        assertEquals(listOf("AQ1"), codes(index, "aquila"))
        assertEquals(listOf("DO1"), codes(index, "dellorso"))
        assertEquals(listOf("DO1"), codes(index, "dell'orso"))
    }
}
