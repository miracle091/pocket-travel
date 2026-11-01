package com.pockettravel.feature.ai

import com.pockettravel.core.sync.ONLINE_MODEL_ROLE_AUTO

/** Messaggio dell'app nella conversazione, non del modello: quota finita, cambio di modello, risposta a /model. */
sealed interface ChatNotice {
    /**
     * La quota giornaliera di [model] e' finita e torna a [resetAtMillis], o il piano gratuito non lo include
     * ([notInFreePlan]); ogni scelta di [choices] rifa' la domanda.
     */
    data class QuotaExhausted(
        val model: String,
        val resetAtMillis: Long,
        val choices: List<ModelChoice>,
        val notInFreePlan: Boolean = false,
    ) : ChatNotice

    /** In Automatico ha risposto [to] perche' la quota giornaliera di [from] e' finita (torna a [resetAtMillis]). */
    data class ModelSwitched(val from: String, val to: String, val resetAtMillis: Long) : ChatNotice

    /** Risposta a /model: il modello in uso e gli altri da scegliere. */
    data class ModelList(val current: ModelChoice, val choices: List<ModelChoice>) : ChatNotice

    /** Dopo una scelta, da un pulsante o da /model con un nome. */
    data class ModelChosen(val choice: ModelChoice) : ChatNotice

    /** /model con un nome che non e' tra le scelte. */
    data class UnknownModel(val name: String, val choices: List<ModelChoice>) : ChatNotice
}

/** Un modello che risponde alle domande: l'opzione Automatico, un modello online o quello sul dispositivo. */
sealed interface ModelChoice {
    data object Automatic : ModelChoice

    data class Online(val id: String) : ModelChoice

    /** Il modello scelto sul dispositivo, gia' scaricato; [name] quello mostrato. */
    data class OnDevice(val name: String) : ModelChoice
}

/**
 * Il nome dopo "/model" (o "/modello"), vuoto se non c'e'; null per una domanda normale. Riconosciuto sul dispositivo:
 * il comando non va mai al modello.
 */
internal fun parseModelCommand(question: String): String? =
    MODEL_COMMAND.matchEntire(question.trim())?.groupValues?.get(1)?.trim()

private val MODEL_COMMAND = Regex("""/model(?:lo)?(?:\s+(.*))?""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Il modello che risponde con lo stato attuale. */
internal fun AiUiState.currentChoice(): ModelChoice = when (mode) {
    AiEngineMode.ON_DEVICE -> ModelChoice.OnDevice(onDeviceModelCredit(selectedModelId)?.name ?: selectedModelId)
    AiEngineMode.ONLINE -> onlineModelSetting.trim().ifEmpty { null }?.let { ModelChoice.Online(it) } ?: ModelChoice.Automatic
}

/**
 * Le scelte diverse da quella attuale: Automatico e gli altri modelli proposti del servizio (con una chiave salvata),
 * senza quelli in [exhausted]; poi il modello sul dispositivo, se e' scaricato e il dispositivo lo regge.
 */
internal fun AiUiState.modelChoices(exhausted: Set<String>): List<ModelChoice> {
    val online = if (isApiKeyConfigured) {
        listOf(ModelChoice.Automatic) + onlineModelOptions
            .filter { it.role != ONLINE_MODEL_ROLE_AUTO && it.id !in exhausted }
            .map { ModelChoice.Online(it.id) }
    } else {
        emptyList()
    }
    val current = currentChoice()
    return (online + listOfNotNull(onDeviceChoice())).filter { it != current }
}

/**
 * La scelta che corrisponde a [name] dopo /model: Automatico, il dispositivo, un modello proposto o un altro modello del
 * servizio che l'account vede, senza maiuscole.
 */
internal fun AiUiState.matchModelChoice(name: String): ModelChoice? {
    val wanted = name.trim().lowercase()
    return when {
        wanted in ON_DEVICE_NAMES -> onDeviceChoice()
        !isApiKeyConfigured -> null
        wanted in AUTOMATIC_NAMES -> ModelChoice.Automatic
        else -> (onlineModelOptions + onlineOtherModels).firstOrNull { it.id.lowercase() == wanted }?.let { ModelChoice.Online(it.id) }
    }
}

/** La scelta salvata del modello online per [this] ("" = Automatico); null per il modello sul dispositivo. */
internal fun ModelChoice.onlineSetting(): String? = when (this) {
    ModelChoice.Automatic -> ""
    is ModelChoice.Online -> id
    is ModelChoice.OnDevice -> null
}

/** Il motore che usa [this]. */
internal fun ModelChoice.engineMode(): AiEngineMode = if (this is ModelChoice.OnDevice) AiEngineMode.ON_DEVICE else AiEngineMode.ONLINE

/** In Automatico risponde un altro modello: l'etichetta della risposta lo nomina e la riga sopra dice perche'. */
internal fun AiUiState.switchedModel(progress: AssistantProgress.ModelSwitched): AiUiState = copy(
    answerCredit = ModelCredit(progress.to, onlineProvider.modelsPageUrl),
    notice = ChatNotice.ModelSwitched(progress.from, progress.to, progress.resetAtMillis),
)

/** Il messaggio per "/model" seguito da [name] e la scelta da applicare: null senza nome o con un nome sconosciuto. */
internal fun AiUiState.modelCommandNotice(name: String, exhausted: Set<String>): Pair<ChatNotice, ModelChoice?> {
    val choice = name.takeIf { it.isNotEmpty() }?.let { matchModelChoice(it) }
    return when {
        name.isEmpty() -> ChatNotice.ModelList(currentChoice(), modelChoices(exhausted)) to null
        choice == null -> ChatNotice.UnknownModel(name, modelChoices(exhausted)) to null
        else -> ChatNotice.ModelChosen(choice) to choice
    }
}

/** Dopo la quota finita, la domanda rimasta senza risposta da rifare con il modello scelto; "" altrimenti. */
internal fun AiUiState.questionToRetry(): String =
    askedQuestion?.takeIf { quota != null && errorMessage == R.string.ai_error_daily_quota && answer == null }.orEmpty()

private fun AiUiState.onDeviceChoice(): ModelChoice.OnDevice? =
    onDeviceModelCredit(selectedModelId)?.takeIf { isDeviceCapable && isModelDownloaded }?.let { ModelChoice.OnDevice(it.name) }

internal val AUTOMATIC_NAMES = setOf("auto", "automatico", "automatic")
internal val ON_DEVICE_NAMES = setOf("dispositivo", "sul dispositivo", "device", "on device", "telefono", "phone")
