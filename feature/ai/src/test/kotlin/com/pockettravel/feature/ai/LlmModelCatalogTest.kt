package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelCatalogTest {

    @Test
    fun `il modello predefinito e' il nostro addestrato della fascia di RAM`() {
        assertEquals("pt-qwen3.5-0.8b", LlmModelCatalog.defaultFor(RamTier.MINIMO).id)
        assertEquals("pt-qwen3.5-2b", LlmModelCatalog.defaultFor(RamTier.CONFORTEVOLE).id)
        assertEquals("pt-qwen3-4b-2507", LlmModelCatalog.defaultFor(RamTier.AMPIA).id)
    }

    @Test
    fun `con RAM insufficiente il predefinito e' il modello piu' leggero`() {
        assertEquals("pt-qwen3.5-0.8b", LlmModelCatalog.defaultFor(RamTier.INSUFFICIENTE).id)
    }

    @Test
    fun `il predefinito e' sempre scaricabile`() {
        for (tier in RamTier.entries) {
            val model = LlmModelCatalog.defaultFor(tier)
            assertEquals(ModelOrigin.ADDESTRATO, model.origin)
            assertTrue(model.sha256 != null)
        }
    }

    @Test
    fun `in inglese, senza modelli addestrati in inglese, il predefinito e' l'ufficiale della fascia`() {
        assertEquals(ModelOrigin.UFFICIALE, LlmModelCatalog.defaultFor(RamTier.CONFORTEVOLE, "en").origin)
        assertEquals(RamTier.CONFORTEVOLE, LlmModelCatalog.defaultFor(RamTier.CONFORTEVOLE, "en").minRamTier)
    }
}
