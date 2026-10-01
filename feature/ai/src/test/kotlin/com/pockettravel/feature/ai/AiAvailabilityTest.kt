package com.pockettravel.feature.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAvailabilityTest {

    @Test
    fun senzaChiaveNeModelloNonDisponibile() {
        assertFalse(isAiAvailable(hasApiKey = false, ramTier = RamTier.AMPIA, downloadedModelIds = emptySet(), language = "it"))
    }

    @Test
    fun laChiaveBastaAncheSenzaModello() {
        assertTrue(isAiAvailable(hasApiKey = true, ramTier = RamTier.AMPIA, downloadedModelIds = emptySet(), language = "it"))
    }

    @Test
    fun laChiaveContaAncheSottoI4GbDiRam() {
        assertTrue(isAiAvailable(hasApiKey = true, ramTier = RamTier.INSUFFICIENTE, downloadedModelIds = emptySet(), language = "en"))
    }

    @Test
    fun ilModelloScaricatoBasta() {
        assertTrue(isAiAvailable(hasApiKey = false, ramTier = RamTier.MINIMO, downloadedModelIds = setOf("qwen3.5-0.8b"), language = "en"))
    }

    @Test
    fun sottoI4GbDiRamUnModelloScaricatoNonConta() {
        assertFalse(isAiAvailable(hasApiKey = false, ramTier = RamTier.INSUFFICIENTE, downloadedModelIds = setOf("qwen3.5-0.8b"), language = "it"))
    }

    @Test
    fun unModelloAddestratoInItalianoNonContaConLeGuideInInglese() {
        assertFalse(isAiAvailable(hasApiKey = false, ramTier = RamTier.MINIMO, downloadedModelIds = setOf("pt-qwen3.5-0.8b"), language = "en"))
        assertTrue(isAiAvailable(hasApiKey = false, ramTier = RamTier.MINIMO, downloadedModelIds = setOf("pt-qwen3.5-0.8b"), language = "it"))
    }

    @Test
    fun unModelloOltreLaFasciaDelDispositivoNonConta() {
        assertFalse(isAiAvailable(hasApiKey = false, ramTier = RamTier.MINIMO, downloadedModelIds = setOf("qwen3-4b-instruct-2507"), language = "it"))
    }
}
