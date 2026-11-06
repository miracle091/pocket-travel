package com.pockettravel.app.assistant

import com.pockettravel.feature.ai.LlmModelCatalog
import com.pockettravel.feature.ai.ModelOrigin
import com.pockettravel.feature.ai.RamTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelLanguageNoticeTest {

    private val tier = RamTier.CONFORTEVOLE
    private fun trained(language: String) = LlmModelCatalog.defaultFor(tier, language).also { check(it.origin == ModelOrigin.ADDESTRATO) }
    private val official = LlmModelCatalog.ALL.first { it.origin == ModelOrigin.UFFICIALE }

    @Test
    fun `con solo il modello italiano e l'app in inglese si propone quello inglese`() {
        val proposal = modelForLanguage(setOf(trained("it").id), tier, "en")

        assertEquals(trained("it").id to trained("en").id, proposal?.let { it.downloaded.id to it.proposed.id })
    }

    @Test
    fun `si propone la stessa taglia del modello scaricato, se il dispositivo la mostra`() {
        val biggest = LlmModelCatalog.ALL.filter { it.origin == ModelOrigin.ADDESTRATO }.maxOf { it.minRamTier }
        val italian = LlmModelCatalog.ALL.first { it.origin == ModelOrigin.ADDESTRATO && it.language == "it" && it.minRamTier == biggest }

        assertEquals(biggest to "en", modelForLanguage(setOf(italian.id), tier, "en")?.proposed?.let { it.minRamTier to it.language })
        // Con meno RAM quella taglia non compare: il predefinito per la RAM.
        assertEquals(LlmModelCatalog.defaultFor(RamTier.MINIMO, "en").id, modelForLanguage(setOf(italian.id), RamTier.MINIMO, "en")?.proposed?.id)
    }

    @Test
    fun `niente proposta se un modello scaricato risponde gia' nella lingua dell'app`() {
        assertNull(modelForLanguage(setOf(trained("it").id, trained("en").id), tier, "en"))
        assertNull(modelForLanguage(setOf(trained("it").id, official.id), tier, "en"))
        assertNull(modelForLanguage(setOf(trained("it").id), tier, "it"))
    }

    @Test
    fun `niente proposta senza un modello addestrato scaricato`() {
        assertNull(modelForLanguage(emptySet(), tier, "en"))
    }
}
