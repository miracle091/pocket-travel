package com.pockettravel.feature.ai

import com.pockettravel.core.data.DefaultDispatcher
import com.pockettravel.core.data.currentGuidesLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Implementazione di [AiAvailability] sulle impostazioni e sui modelli scaricati. */
@Singleton
class DefaultAiAvailability @Inject constructor(
    private val settingsStore: AiSettingsStore,
    private val modelManager: LlmModelManager,
    private val deviceAiCapability: DeviceAiCapability,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : AiAvailability {
    // La lingua delle guide e' letta a ogni calcolo e non e' un flusso: [refresh] la rilegge dopo un cambio lingua.
    private val languageTick = MutableStateFlow(0)

    override val available: StateFlow<Boolean> = combine(
        settingsStore.hasApiKeyFlow,
        modelManager.downloadedModelIds,
        languageTick,
    ) { hasApiKey, downloaded, _ -> compute(hasApiKey, downloaded) }
        .stateIn(CoroutineScope(SupervisorJob() + defaultDispatcher), SharingStarted.Eagerly, compute(settingsStore.hasApiKeyFlow.value, modelManager.downloadedModelIds.value))

    override fun refresh() {
        languageTick.value++
    }

    private fun compute(hasApiKey: Boolean, downloaded: Set<String>): Boolean =
        isAiAvailable(hasApiKey, deviceAiCapability.ramTier(), downloaded, currentGuidesLanguage())
}

/**
 * Pura, per i test. In una fascia senza modelli ([RamTier.INSUFFICIENTE], oggi anche [RamTier.MINIMO]) conta solo la
 * chiave: nessun modello del catalogo dei modelli e' visibile li' ([LlmModelCatalog.visibleFor]). Un modello addestrato in un'altra lingua
 * (es. quello italiano con le guide in inglese) non conta.
 */
internal fun isAiAvailable(hasApiKey: Boolean, ramTier: RamTier, downloadedModelIds: Set<String>, language: String): Boolean =
    hasApiKey || LlmModelCatalog.visibleFor(ramTier, language).any { it.id in downloadedModelIds }
