package com.pockettravel.feature.ai

import com.pockettravel.core.sync.OnlineModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Comando /model e scelte dei pulsanti dei messaggi dell'app.
class ModelChoicesTest {

    private val options = listOf(
        OnlineModelEntry("gemini-3.6-flash", "auto", false, 0.75, 3.75),
        OnlineModelEntry("gemini-4-argon", "capable", false, 2.0, 10.0),
        OnlineModelEntry("gemini-3.5-flash-lite", "capable", true, 0.30, 2.50),
    )

    private fun state(mode: AiEngineMode = AiEngineMode.ONLINE, setting: String = "", key: Boolean = true, downloaded: Boolean = true) = AiUiState(
        mode = mode,
        isDeviceCapable = true,
        selectedModelId = "qwen3.5-0.8b",
        isModelDownloaded = downloaded,
        isApiKeyConfigured = key,
        onlineProvider = OnlineProvider.GEMINI,
        onlineModelSetting = setting,
        onlineModelOptions = options,
    )

    private val onDevice = ModelChoice.OnDevice("Qwen3.5 0.8B")

    @Test
    fun `the command is recognised with or without a name, a question is not`() {
        assertEquals("", parseModelCommand("/model"))
        assertEquals("", parseModelCommand("  /Model  "))
        assertEquals("gemini-3.5-flash-lite", parseModelCommand("/model gemini-3.5-flash-lite"))
        assertEquals("sul dispositivo", parseModelCommand("/modello sul dispositivo"))
        assertNull(parseModelCommand("Quale modello usi?"))
        assertNull(parseModelCommand("/models"))
        assertNull(parseModelCommand("cosa vedere /model"))
    }

    @Test
    fun `choices leave out the current one and the exhausted models`() {
        assertEquals(
            listOf(ModelChoice.Online("gemini-4-argon"), ModelChoice.Online("gemini-3.5-flash-lite"), onDevice),
            state().modelChoices(emptySet()),
        )
        assertEquals(
            listOf(ModelChoice.Automatic, ModelChoice.Online("gemini-4-argon"), onDevice),
            state(setting = "gemini-3.5-flash-lite").modelChoices(emptySet()),
        )
        assertEquals(listOf(ModelChoice.Online("gemini-3.5-flash-lite"), onDevice), state().modelChoices(setOf("gemini-4-argon")))
        // Senza chiave, sul dispositivo, non resta altro; senza modello scaricato niente dispositivo.
        assertEquals(emptyList<ModelChoice>(), state(mode = AiEngineMode.ON_DEVICE, key = false).modelChoices(emptySet()))
        assertEquals(listOf(onDevice), state(key = false).modelChoices(emptySet()))
        assertEquals(listOf(ModelChoice.Automatic, ModelChoice.Online("gemini-4-argon"), ModelChoice.Online("gemini-3.5-flash-lite")),
            state(mode = AiEngineMode.ON_DEVICE).modelChoices(emptySet()))
        assertEquals(emptyList<ModelChoice>(), state(downloaded = false).modelChoices(setOf("gemini-4-argon", "gemini-3.5-flash-lite")))
    }

    @Test
    fun `a name matches automatic, the device or a proposed model, without case`() {
        assertEquals(ModelChoice.Automatic, state(setting = "gemini-4-argon").matchModelChoice("Automatico"))
        assertEquals(onDevice, state().matchModelChoice("dispositivo"))
        assertEquals(onDevice, state().matchModelChoice("phone"))
        assertEquals(ModelChoice.Online("gemini-3.6-flash"), state().matchModelChoice("GEMINI-3.6-flash"))
        assertNull(state().matchModelChoice("gemini-9"))
        assertNull(state(key = false).matchModelChoice("auto"))
        assertNull(state(downloaded = false).matchModelChoice("device"))
        val other = OnlineModelEntry("gemini-2.5-pro", "capable", true, 1.25, 10.0)
        assertEquals(ModelChoice.Online("gemini-2.5-pro"), state().copy(onlineOtherModels = listOf(other)).matchModelChoice("Gemini-2.5-Pro"))
    }

    @Test
    fun `the current choice follows engine and setting`() {
        assertEquals(ModelChoice.Automatic, state().currentChoice())
        assertEquals(ModelChoice.Online("gemini-4-argon"), state(setting = " gemini-4-argon ").currentChoice())
        assertEquals(onDevice, state(mode = AiEngineMode.ON_DEVICE).currentChoice())
    }

    @Test
    fun `the question to retry survives a later model message, only while the quota error is shown`() {
        val failed = state().copy(
            askedQuestion = "Quale bus va a Mdina?",
            errorMessage = R.string.ai_error_daily_quota,
            quota = ChatNotice.QuotaExhausted("gemini-3.6-flash", 0L, emptyList()),
            notice = ChatNotice.ModelList(ModelChoice.Automatic, emptyList()),
        )
        assertEquals("Quale bus va a Mdina?", failed.questionToRetry())
        assertEquals("", failed.copy(errorMessage = R.string.ai_error_credit).questionToRetry())
        assertEquals("", failed.copy(quota = null).questionToRetry())
    }
}
