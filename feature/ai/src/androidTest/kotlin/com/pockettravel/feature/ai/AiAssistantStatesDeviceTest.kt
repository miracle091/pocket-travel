package com.pockettravel.feature.ai

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.ui.PocketTravelTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// L'assistente e le Impostazioni a partire da zero modelli (l'app non ne ha nessuno all'avvio): scelta, download,
// avanzamento, errori e modello gia' presente. Stati e azioni finti: nessun modello, rete o file sul dispositivo.
@RunWith(AndroidJUnit4::class)
class AiAssistantStatesDeviceTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val noOpActions = AiActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    // Piccolo, per non incontrare l'avviso dei download grandi su rete mobile.
    private fun model(id: String, name: String) = LlmModelDefinition(
        id = id,
        displayName = name,
        url = "https://example.invalid/$id.gguf",
        fileName = "$id.gguf",
        sha256 = "0".repeat(64),
        sizeBytes = 10L shl 20,
        minRamTier = RamTier.MINIMO,
    )

    private val modelA = model("a", "Modello A")
    private val modelB = model("b", "Modello B")

    private fun noModelState() = AiUiState(
        mode = AiEngineMode.ON_DEVICE,
        isDeviceCapable = true,
        availableModels = listOf(modelA),
        selectedModelId = modelA.id,
        isModelDownloaded = false,
        isApiKeyConfigured = false,
    )

    private fun showAssistant(uiState: AiUiState, actions: AiActions = noOpActions) {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                AiAssistantContent(uiState = uiState, actions = actions, onOpenOfficialSource = {})
            }
        }
    }

    private fun showSettings(uiState: AiUiState, actions: AiActions = noOpActions) {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                AiSettingsContent(uiState = uiState, actions = actions)
            }
        }
    }

    @Test
    fun downloadButtonStartsTheDownloadOfTheSelectedModel() {
        var downloads = 0
        showAssistant(noModelState(), noOpActions.copy(onDownloadModel = { downloads++ }))

        compose.onNodeWithText(context.getString(R.string.ai_model_download)).performScrollTo().performClick()

        assertEquals(1, downloads)
    }

    @Test
    fun downloadProgressReplacesTheDownloadButton() {
        showAssistant(noModelState().copy(downloadProgress = 0.4f))

        compose.onNodeWithText(context.getString(R.string.ai_model_downloading, 40)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_model_download)).assertDoesNotExist()
    }

    @Test
    fun errorMessageIsShownUnderTheSelectedModel() {
        showAssistant(noModelState().copy(errorMessage = R.string.ai_error_space))

        compose.onNodeWithText(context.getString(R.string.ai_error_space)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anotherDownloadedModelCanBeUsedAtOnce() {
        var selected: String? = null
        showAssistant(
            noModelState().copy(availableModels = listOf(modelA, modelB), downloadedModelIds = setOf(modelB.id)),
            noOpActions.copy(onModelSelected = { selected = it }),
        )

        compose.onNodeWithText(context.getString(R.string.ai_use_model, modelB.displayName)).performScrollTo().performClick()

        assertEquals(modelB.id, selected)
    }

    @Test
    fun savedKeyWithoutModelOffersTheOnlineMode() {
        var mode: AiEngineMode? = null
        showAssistant(
            noModelState().copy(isApiKeyConfigured = true),
            noOpActions.copy(onModeChanged = { mode = it }),
        )

        compose.onNodeWithText(context.getString(R.string.ai_use_online)).performScrollTo().performClick()

        assertEquals(AiEngineMode.ONLINE, mode)
    }

    @Test
    fun settingsModeSelectorReportsTheChosenMode() {
        var mode: AiEngineMode? = null
        showSettings(noModelState(), noOpActions.copy(onModeChanged = { mode = it }))

        compose.onNodeWithText(context.getString(R.string.ai_settings_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_mode_online)).performClick()

        assertEquals(AiEngineMode.ONLINE, mode)
    }

    @Test
    fun settingsWithADownloadedModelAskBeforeDeletingIt() {
        var deleted = 0
        showSettings(noModelState().copy(isModelDownloaded = true), noOpActions.copy(onDeleteModel = { deleted++ }))

        compose.onNodeWithText(context.getString(R.string.ai_model_downloaded)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_delete_model)).performClick()

        compose.onNodeWithText(context.getString(R.string.ai_delete_model_title)).assertIsDisplayed()
        assertEquals(0, deleted)
    }

    @Test
    fun deviceWithoutEnoughRamHasOnlyTheOnlineSetup() {
        showSettings(noModelState().copy(mode = AiEngineMode.ONLINE, isDeviceCapable = false))

        compose.onNodeWithText(context.getString(R.string.ai_mode_on_device)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ai_api_key_intro)).assertIsDisplayed()
    }

    @Test
    fun deviceWithoutEnoughRamShowsTheTitleInsteadOfTheModeSelector() {
        showAssistant(noModelState().copy(mode = AiEngineMode.ONLINE, isDeviceCapable = false))

        compose.onNodeWithText(context.getString(R.string.ai_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_mode_on_device)).assertDoesNotExist()
    }
}
