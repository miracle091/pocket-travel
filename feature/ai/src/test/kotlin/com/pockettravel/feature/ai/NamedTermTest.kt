package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamedTermTest {
    @Test
    fun `namedTerm trova il termine per nome o alias, parola intera e senza accenti, il nome piu' lungo`() {
        fun term(name: String, vararg aliases: String) = GuideTerm("peru", name, aliases.toList(), name, "Spiegazione.", "https://it.wikipedia.org/wiki/$name")
        val ceviche = term("Ceviche", "Cebiche")
        val aji = term("Ají")
        val ajiDeGallina = term("Ají de gallina")
        val terms = listOf(ceviche, aji, ajiDeGallina)

        assertEquals(ceviche, namedTerm("Cos'è il CEBICHE?", terms))
        assertEquals(ajiDeGallina, namedTerm("com'e' l'aji de gallina?", terms))
        assertEquals(aji, namedTerm("Quanto piccante e' l'ají?", terms))
        assertNull(namedTerm("Dove trovo una cevicheria?", terms))
        assertNull(namedTerm("Serve il visto?", terms))
    }

    @Test
    fun `namedTerm a parita' di lunghezza preferisce il nome del termine a un alias`() {
        fun term(name: String, vararg aliases: String) = GuideTerm("finland", name, aliases.toList(), name, "Spiegazione.", "https://en.wikipedia.org/wiki/$name")
        val ryeBread = term("Finnish rye bread", "Ruisleipä")
        val ruisleipa = term("Ruisleipä")

        assertEquals(ruisleipa, namedTerm("Where can I try Ruisleipä?", listOf(ryeBread, ruisleipa)))
    }
}
