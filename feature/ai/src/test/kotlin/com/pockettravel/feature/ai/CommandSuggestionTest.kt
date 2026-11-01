package com.pockettravel.feature.ai

import com.pockettravel.core.sync.OnlineModelEntry
import org.junit.Assert.assertEquals
import org.junit.Test

// Menu sopra il campo della domanda quando comincia con "/".
class CommandSuggestionTest {

    private fun state(question: String) = AiUiState(
        mode = AiEngineMode.ONLINE,
        isDeviceCapable = true,
        selectedModelId = "qwen3.5-0.8b",
        isModelDownloaded = true,
        isApiKeyConfigured = true,
        onlineProvider = OnlineProvider.GEMINI,
        onlineModelOptions = listOf(
            OnlineModelEntry("gemini-3.6-flash", "auto", false, 0.75, 3.75),
            OnlineModelEntry("gemini-3.5-flash-lite", "capable", true, 0.30, 2.50),
        ),
        question = question,
    )

    private val model = listOf(CommandSuggestion.Command("/model"))

    @Test
    fun `a slash and part of a command suggest the command`() {
        assertEquals(model, state("/").commandSuggestions())
        assertEquals(model, state("/mo").commandSuggestions())
        assertEquals(model, state("/MODE").commandSuggestions())
        assertEquals(model, state("/modell").commandSuggestions())
        assertEquals(emptyList<CommandSuggestion>(), state("/x").commandSuggestions())
        assertEquals(emptyList<CommandSuggestion>(), state("Cosa vedere?").commandSuggestions())
    }

    @Test
    fun `after the command the choices follow what is typed, also by another name or part of the name`() {
        val all = listOf(ModelChoice.Online("gemini-3.5-flash-lite"), ModelChoice.OnDevice("Qwen3.5 0.8B")).map { CommandSuggestion.Choice(it) }
        assertEquals(all, state("/model ").commandSuggestions())
        assertEquals(all, state("/model").commandSuggestions())
        assertEquals(all.take(1), state("/model gem").commandSuggestions())
        assertEquals(all.take(1), state("/model lite").commandSuggestions())
        assertEquals(all.drop(1), state("/model disp").commandSuggestions())
        assertEquals(all.drop(1), state("/modello Dev").commandSuggestions())
        assertEquals(emptyList<CommandSuggestion>(), state("/model gpt").commandSuggestions())
    }
}
