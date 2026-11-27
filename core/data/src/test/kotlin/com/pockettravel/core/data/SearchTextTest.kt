package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTextTest {
    private fun words(text: String) = normalizeForSearch(text).text.let { normalized -> searchWords(normalized).map { normalized.substring(it.first, it.last + 1) } }

    @Test
    fun `gli accenti e le maiuscole non contano`() {
        assertEquals("citta", normalizeForSearch("Città").text)
        assertEquals("citta", normalizeForSearch("CITTÀ").text)
        assertEquals("perche e un hotel", normalizeForSearch("Perché è un HÔTEL").text)
    }

    @Test
    fun `la scomposizione dei diacritici dello stesso testo da' lo stesso risultato`() {
        assertEquals("cafe bar", normalizeForSearch("Café bar").text)
        assertEquals(normalizeForSearch("café").text, normalizeForSearch("café").text)
    }

    @Test
    fun `ß e İ restano un carattere solo`() {
        assertEquals("straße", normalizeForSearch("Straße").text)
        assertEquals("istanbul", normalizeForSearch("İstanbul").text)
        assertEquals("ß", normalizeForSearch("ẞ").text)
    }

    @Test
    fun `l'apostrofo separa le parole e le cifre sono parole`() {
        assertEquals(listOf("l", "hotel"), words("l'hotel"))
        assertEquals(listOf("hotel", "24", "ore", "a", "roma", "3"), words("Hotel 24 ore, a Roma-3!"))
        assertEquals(emptyList<String>(), words("  ... ' "))
    }

    @Test
    fun `gli scostamenti puntano al carattere giusto del testo originale`() {
        val original = "Una Città d'arte, Café Ŝ"
        val normalized = normalizeForSearch(original)
        // "cafe" e' dopo la virgola: il segno combinante dell'originale non ha un carattere suo.
        val cafe = normalized.text.indexOf("cafe")
        assertEquals(original.indexOf("Cafe"), normalized.originStart(cafe))
        assertEquals(original.indexOf("Cafe") + 4, normalized.originEnd(cafe + 3))
        val citta = normalized.text.indexOf("citta")
        assertEquals("Città", original.substring(normalized.originStart(citta), normalized.originEnd(citta + 4)))
        // Dopo il segno combinante gli scostamenti restano allineati.
        val last = normalized.text.length - 1
        assertEquals("Ŝ", original.substring(normalized.originStart(last), normalized.originEnd(last)))
    }

    @Test
    fun `ogni parola cercata deve iniziare una parola del testo`() {
        val text = normalizeForSearch("L'Hotel Centrale a Roma").text
        assertEquals(listOf(2..4, 8..10), prefixMatches(text, listOf("hot", "cen")))
        assertNull(prefixMatches(text, listOf("otel")))
        assertNull(prefixMatches(text, listOf("hot", "xyz")))
        assertNull(prefixMatches(text, emptyList()))
    }

    @Test
    fun `la domanda cercata si normalizza e non ripete le parole`() {
        assertEquals(listOf("citta", "hotel"), queryWords("  Città, CITTÀ Hotel"))
        assertEquals(emptyList<String>(), queryWords(" - "))
    }
}
