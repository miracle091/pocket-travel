package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideCategory
import org.junit.Assert.assertEquals
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

    @Test
    fun `clima in testa per le domande di clima, trovato o della capitale`() {
        val altro = AssistantSection("da fare", GuideCategory.COSA_VEDERE, "Do")
        val avvisi = AssistantSection("disastri naturali", GuideCategory.SICUREZZA, "Canada")
        val clima = AssistantSection("clima della citta'", GuideCategory.CLIMA, "Climate")
        val capitale = AssistantSection("clima della capitale", GuideCategory.CLIMA, "Climate (Valletta)")

        // Come "Is it hot in Malta in July?": il Clima trovato era terzo e non entrava nel contesto.
        assertEquals(listOf(clima, avvisi, altro), climateFirst(setOf("CLIMA"), listOf(avvisi, altro, clima), capitale))
        assertEquals(listOf(capitale, avvisi, altro), climateFirst(setOf("CLIMA"), listOf(avvisi, altro), capitale))
        assertEquals(listOf(avvisi, altro), climateFirst(setOf("CLIMA"), listOf(avvisi, altro), null))
        assertEquals(listOf(avvisi, altro, clima), climateFirst(setOf("STORIA"), listOf(avvisi, altro, clima), capitale))
        // Con un altro gruppo il Clima trovato resta dov'e'; quello della capitale, se manca, va comunque in testa.
        assertEquals(listOf(avvisi, altro, clima), climateFirst(setOf("COSA_VEDERE", "CLIMA"), listOf(avvisi, altro, clima), null))
        assertEquals(listOf(capitale, avvisi, altro), climateFirst(setOf("COSA_VEDERE", "CLIMA"), listOf(avvisi, altro), capitale))
    }
}
