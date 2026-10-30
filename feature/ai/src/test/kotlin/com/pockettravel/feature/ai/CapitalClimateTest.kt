package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapitalClimateTest {
    private val section = { category: GuideCategory -> AssistantSection("testo", category, "Fonte") }

    @Test
    fun `clima della capitale per le domande di clima senza il clima nelle sezioni trovate`() {
        assertTrue(wantsCapitalClimate(setOf("CLIMA"), listOf(section(GuideCategory.COSA_VEDERE))))
        assertTrue(wantsCapitalClimate(setOf("PERIODI"), emptyList()))
        assertTrue(wantsCapitalClimate(setOf("METEO"), emptyList()))
        // Con il clima gia' trovato, o per domande d'altro, nessuna sezione in piu'.
        assertFalse(wantsCapitalClimate(setOf("CLIMA"), listOf(section(GuideCategory.CLIMA))))
        assertFalse(wantsCapitalClimate(setOf("STORIA"), emptyList()))
        assertFalse(wantsCapitalClimate(emptySet(), emptyList()))
    }
}
