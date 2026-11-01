package com.pockettravel.feature.ai

/** Una voce del menu che compare sopra il campo quando la domanda comincia con "/". */
sealed interface CommandSuggestion {
    /** Un comando da completare nel campo. */
    data class Command(val command: String) : CommandSuggestion

    /** Una scelta di /model, applicata al tocco. */
    data class Choice(val choice: ModelChoice) : CommandSuggestion
}

/**
 * Il menu per la domanda scritta finora: con "/" e un pezzo di comando i comandi che cominciano cosi'; dopo "/model" le
 * scelte il cui nome contiene quello scritto (anche un nome alternativo, come "dispositivo" o "device") e, con
 * qualcosa scritto, fino a [MAX_OTHER_MODELS] altri modelli del servizio che l'account vede.
 */
internal fun AiUiState.commandSuggestions(): List<CommandSuggestion> {
    val typed = question.trimStart().lowercase()
    if (!typed.startsWith("/")) return emptyList()
    val argument = parseModelCommand(typed)?.lowercase()
    return when {
        argument != null -> {
            val listed = modelChoices(emptySet()).filter { choice -> choice.names().any { argument in it } }
            val others = onlineOtherModels.takeIf { argument.isNotEmpty() }.orEmpty()
                .filter { argument in it.id.lowercase() }.take(MAX_OTHER_MODELS).map { ModelChoice.Online(it.id) }
            (listed + others).map { CommandSuggestion.Choice(it) }
        }
        typed.none(Char::isWhitespace) && MODEL_COMMAND_NAMES.any { it.startsWith(typed) } -> listOf(CommandSuggestion.Command("/model"))
        else -> emptyList()
    }
}

private fun ModelChoice.names(): Set<String> = when (this) {
    ModelChoice.Automatic -> AUTOMATIC_NAMES
    is ModelChoice.Online -> setOf(id.lowercase())
    is ModelChoice.OnDevice -> ON_DEVICE_NAMES
}

private const val MAX_OTHER_MODELS = 6

private val MODEL_COMMAND_NAMES = listOf("/model", "/modello")
