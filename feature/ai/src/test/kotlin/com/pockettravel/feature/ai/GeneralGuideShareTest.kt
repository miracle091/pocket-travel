package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// La quota del contesto per la pagina della guida generale in testa (withGeneralGuideShare), nei due motori.
class GeneralGuideShareTest {

    // Pagina della guida generale di 21 paragrafi (2037 caratteri): 20 da 99 caratteri e, al sedicesimo posto, quello con la
    // radice della domanda. Gli stessi dati sono in test_eval_retrieval.py (test_quota_della_guida_generale_in_testa).
    private val generalPage = (0 until 20).map { paragraph(it) }
        .toMutableList().apply { add(15, "Il passaporto smarrito va denunciato.") }.joinToString("\n")
    private val general = AssistantSection(generalPage, GuideCategory.DOGANE, "Fonte: Wikivoyage, sezione Documenti", fromGeneralGuide = true)
    private val country = AssistantSection("Per entrare serve il passaporto.", GuideCategory.DOGANE, "Fonte: Wikivoyage, sezione Dogane")

    private fun paragraph(i: Int) = "g%02d ".format(i) + "x".repeat(95)

    @Test
    fun `la pagina della guida generale in testa lascia spazio alla sezione del paese`() {
        val context = buildOnDeviceContext(listOf(general, country), note = null, focusStems = setOf("passa"))

        val kept = (0 until 13).map { paragraph(it) } + "Il passaporto smarrito va denunciato."
        assertEquals(kept.joinToString("\n") + "\n\nPer entrare serve il passaporto.", context)
        assertTrue(context.substringBefore("\n\n").length <= 2_000 * GENERAL_GUIDE_SHARE)
    }

    @Test
    fun `la quota della guida generale vale solo in testa e con altre sezioni dopo`() {
        assertEquals(listOf(general), withGeneralGuideShare(listOf(general), setOf("passa"), 2_000))
        assertEquals(listOf(country, general), withGeneralGuideShare(listOf(country, general), setOf("passa"), 2_000))
        // Da sola la pagina prende tutto lo spazio.
        assertEquals(1_937, buildOnDeviceContext(listOf(general), note = null, focusStems = setOf("passa")).length)
    }

    @Test
    fun `online la pagina della guida generale in testa prende al piu' la sua quota`() {
        val context = buildOnlineContext(listOf(general, country), note = null, maxChars = 2_000, focusStems = setOf("passa"))

        assertEquals(listOf(general.citation, country.citation), context.citations)
        assertTrue("Per entrare serve il passaporto." in context.text)
        assertFalse("g13 " in context.text)
    }
}
