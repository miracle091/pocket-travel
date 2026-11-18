package com.pockettravel.feature.guide

import com.pockettravel.core.data.GuideTerm
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideTermLinksTest {
    private fun term(name: String, vararg aliases: String) = GuideTerm("peru", name, aliases.toList(), name, "Spiegazione.", "u")

    private val ceviche = term("Ceviche", "Cebiche")
    private val aji = term("Ají")
    private val ajiDeGallina = term("Ají de gallina")

    private fun linked(paragraphs: List<String>, terms: List<GuideTerm>) =
        termLinks(paragraphs, terms).mapIndexed { i, links -> links.map { (range, term) -> paragraphs[i].substring(range) to term.term } }

    @Test
    fun `nome o alias come parola intera senza accenti, intervallo nel testo originale`() {
        assertEquals(listOf(listOf("CEBICHE" to "Ceviche", "aji" to "Ají")), linked(listOf("Il CEBICHE con l'aji."), listOf(ceviche, aji)))
    }

    @Test
    fun `ogni termine solo alla prima occorrenza della sezione`() {
        assertEquals(listOf(listOf("Ceviche" to "Ceviche"), emptyList()), linked(listOf("Ceviche e ceviche.", "Ancora cebiche."), listOf(ceviche)))
    }

    @Test
    fun `tra nomi sovrapposti vince il piu lungo, il corto resta per un altra occorrenza`() {
        assertEquals(
            listOf(listOf("Ají de gallina" to "Ají de gallina", "ají" to "Ají")),
            linked(listOf("Ají de gallina, poi ají."), listOf(aji, ajiDeGallina)),
        )
    }

    @Test
    fun `dentro un'altra parola non vale`() {
        assertEquals(listOf(emptyList<Pair<String, String>>()), linked(listOf("Una cevicheria al porto."), listOf(ceviche)))
    }
}
