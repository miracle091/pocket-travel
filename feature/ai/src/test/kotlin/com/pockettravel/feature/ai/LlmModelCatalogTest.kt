package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `in inglese il predefinito e' il nostro addestrato in inglese della fascia di RAM`() {
        assertEquals("pt-qwen3.5-0.8b-en", LlmModelCatalog.defaultFor(RamTier.INSUFFICIENTE, "en").id)
        assertEquals("pt-qwen3.5-0.8b-en", LlmModelCatalog.defaultFor(RamTier.MINIMO, "en").id)
        assertEquals("pt-qwen3.5-2b-en", LlmModelCatalog.defaultFor(RamTier.CONFORTEVOLE, "en").id)
        assertEquals("pt-qwen3-4b-2507-en", LlmModelCatalog.defaultFor(RamTier.AMPIA, "en").id)
    }

    @Test
    fun `in una lingua senza modelli addestrati il predefinito e' l'ufficiale della fascia`() {
        assertEquals("qwen3.5-2b", LlmModelCatalog.defaultFor(RamTier.CONFORTEVOLE, "de").id)
    }

    @Test
    fun `un modello addestrato vale solo nella sua lingua, gli ufficiali in tutte`() {
        val italian = LlmModelCatalog.ALL.first { it.id == "pt-qwen3.5-2b" }
        val english = LlmModelCatalog.ALL.first { it.id == "pt-qwen3.5-2b-en" }
        val official = LlmModelCatalog.ALL.first { it.id == "qwen3.5-2b" }
        assertTrue(LlmModelCatalog.isUsableIn(italian, "it"))
        assertFalse(LlmModelCatalog.isUsableIn(italian, "en"))
        assertTrue(LlmModelCatalog.isUsableIn(english, "en"))
        assertFalse(LlmModelCatalog.isUsableIn(english, "it"))
        assertTrue(LlmModelCatalog.isUsableIn(official, "it"))
        assertTrue(LlmModelCatalog.isUsableIn(official, "en"))
    }

    @Test
    fun `la lista mostra gli addestrati della lingua dell'interfaccia e gli ufficiali`() {
        for (language in listOf("it", "en")) {
            val visible = LlmModelCatalog.visibleFor(RamTier.AMPIA, language)
            assertEquals(3, visible.count { it.origin == ModelOrigin.ADDESTRATO && it.language == language })
            assertTrue(visible.none { it.origin == ModelOrigin.ADDESTRATO && it.language != language })
            assertEquals(3, visible.count { it.origin == ModelOrigin.UFFICIALE })
        }
    }

    @Test
    fun `id e file del catalogo dei modelli sono tutti diversi`() {
        assertEquals(LlmModelCatalog.ALL.size, LlmModelCatalog.ALL.map { it.id }.toSet().size)
        assertEquals(LlmModelCatalog.ALL.size, LlmModelCatalog.ALL.map { it.fileName }.toSet().size)
    }
}
