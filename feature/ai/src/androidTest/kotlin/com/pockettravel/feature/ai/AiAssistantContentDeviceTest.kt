package com.pockettravel.feature.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.ui.PocketTravelTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Schermata dell'assistente con stato e azioni finti: conversazione, invio di una domanda e configurazione
// quando non c'e' ancora un modello pronto. Nessun modello, rete o ViewModel reali.
@RunWith(AndroidJUnit4::class)
class AiAssistantContentDeviceTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val noOpActions = AiActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private val model = LlmModelDefinition(
        id = "test-model",
        displayName = "Modello di prova",
        url = "https://example.invalid/model.gguf",
        fileName = "model.gguf",
        sha256 = "0".repeat(64),
        sizeBytes = 500L shl 20,
        minRamTier = RamTier.MINIMO,
    )

    private fun readyState(question: String = "") = AiUiState(
        mode = AiEngineMode.ON_DEVICE,
        isDeviceCapable = true,
        isModelDownloaded = true,
        isApiKeyConfigured = false,
        question = question,
    )

    @Test
    fun conversationShowsQuestionAnswerAndCitation() {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                AiAssistantContent(
                    uiState = readyState().copy(
                        askedQuestion = "Serve il visto per San Marino?",
                        answer = AssistantAnswer(
                            text = "No, per i cittadini italiani non serve alcun visto.",
                            sourceCitations = listOf("Fonte: Wikivoyage - San Marino"),
                            showOfficialSourceBanner = true,
                            officialSourceUrl = "https://www.viaggiaresicuri.it",
                        ),
                    ),
                    actions = noOpActions,
                    onOpenOfficialSource = {},
                )
            }
        }

        compose.onNodeWithText("Serve il visto per San Marino?").assertIsDisplayed()
        compose.onNodeWithText("No, per i cittadini italiani non serve alcun visto.").assertIsDisplayed()
        compose.onNodeWithText("Fonte: Wikivoyage - San Marino").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_verify_official)).assertIsDisplayed()
    }

    @Test
    fun officialSourceChipOpensItsUrl() {
        var opened: String? = null
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                AiAssistantContent(
                    uiState = readyState().copy(
                        askedQuestion = "Serve il visto?",
                        answer = AssistantAnswer("No.", emptyList(), showOfficialSourceBanner = true, officialSourceUrl = "https://www.viaggiaresicuri.it"),
                    ),
                    actions = noOpActions,
                    onOpenOfficialSource = { opened = it },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.ai_verify_official)).performClick()

        assertEquals("https://www.viaggiaresicuri.it", opened)
    }

    @Test
    fun typedQuestionIsSentWithTheAskAction() {
        var asked: String? = null
        compose.setContent {
            var uiState by remember { mutableStateOf(readyState()) }
            PocketTravelTheme(dynamicColor = false) {
                AiAssistantContent(
                    uiState = uiState,
                    actions = noOpActions.copy(
                        onQuestionChanged = { uiState = uiState.copy(question = it) },
                        onAsk = { asked = uiState.question },
                    ),
                    onOpenOfficialSource = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.ai_empty_title)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Dove si cambia moneta?")
        compose.onNodeWithContentDescription(context.getString(R.string.ai_send)).performClick()

        assertEquals("Dove si cambia moneta?", asked)
    }

    @Test
    fun withoutModelTheModelListReplacesTheQuestionBar() {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                AiAssistantContent(
                    uiState = readyState().copy(
                        isModelDownloaded = false,
                        availableModels = listOf(model),
                        selectedModelId = model.id,
                    ),
                    actions = noOpActions,
                    onOpenOfficialSource = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.ai_choose_model)).assertIsDisplayed()
        compose.onNodeWithText("Modello di prova").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_model_download)).assertExists()
        compose.onNodeWithText(context.getString(R.string.ai_question_hint)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.ai_send)).assertDoesNotExist()
    }

    @Test
    fun onlineModeWithoutKeyShowsKeySetupInsteadOfTheQuestionBar() {
        var saved = 0
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                AiAssistantContent(
                    uiState = readyState().copy(mode = AiEngineMode.ONLINE, isModelDownloaded = false, apiKeyInput = "chiave-di-prova"),
                    actions = noOpActions.copy(onSaveApiKey = { saved++ }),
                    onOpenOfficialSource = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.ai_question_hint)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ai_api_key_save)).performScrollTo().performClick()

        assertEquals(1, saved)
    }
}
