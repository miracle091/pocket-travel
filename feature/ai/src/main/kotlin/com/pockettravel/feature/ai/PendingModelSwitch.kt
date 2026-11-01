package com.pockettravel.feature.ai

/**
 * Il modello da mostrare scelto nell'elenco: quello in attesa di download se c'e', altrimenti quello in uso. Finche' il
 * download non e' installato resta in uso il modello precedente (se gia' scaricato), cosi' l'assistente continua a rispondere.
 */
internal val AiUiState.listedModelId: String get() = pendingModelId ?: selectedModelId

/** Vero se scegliere [modelId], non ancora scaricato, deve lasciare in uso il modello attuale (scaricato). */
internal fun AiUiState.keepsModelInUse(modelId: String, isDownloaded: (LlmModelDefinition) -> Boolean): Boolean {
    val target = LlmModelCatalog.ALL.firstOrNull { it.id == modelId } ?: return false
    return modelId != selectedModelId && isModelDownloaded && !isDownloaded(target)
}

/** Lo stato con l'errore che impedisce di scaricare [definition], null se il download puo' partire. */
internal fun AiUiState.downloadRefusal(definition: LlmModelDefinition, isCapable: Boolean, freeBytes: Long): AiUiState? {
    // Dimensione pubblicata in app-status.json, se gia' letta.
    val sizeBytes = availableModels.firstOrNull { it.id == definition.id }?.sizeBytes ?: definition.sizeBytes
    return when {
        !isCapable -> copy(isDeviceCapable = false, errorMessage = R.string.ai_error_ram)
        definition.sha256 == null -> copy(errorMessage = R.string.ai_error_not_available)
        freeBytes < sizeBytes -> copy(errorMessage = R.string.ai_error_space)
        else -> null
    }
}

/** Lo stato a download finito: il modello in uso e quello in attesa si rileggono dalle impostazioni, che il lavoro ha aggiornato. */
internal fun AiUiState.afterModelDownload(store: AiSettingsStore, manager: LlmModelManager): AiUiState {
    val id = store.selectedModelId()
    return copy(
        downloadProgress = null,
        selectedModelId = id,
        pendingModelId = store.pendingModelId(),
        isModelDownloaded = manager.isDownloaded(store.selectedModelDefinition()),
        benchmarkResult = store.benchmarkResult(id),
    )
}
