package com.pockettravel.feature.ai

import com.pockettravel.core.data.currentGuidesLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Se l'assistente IA e' utilizzabile: una chiave API salvata (online) o almeno un modello on-device
 * scaricato che funziona con la lingua delle guide. Chi non e' configurato non vede la tab IA
 * nell'hub delle regioni; la configurazione sta nelle Impostazioni (vedi [AiSettingsSection]).
 */
@Singleton
class AiAvailability @Inject constructor(
    private val settingsStore: AiSettingsStore,
    private val modelManager: LlmModelManager,
    private val deviceAiCapability: DeviceAiCapability,
) {
    // La lingua delle guide e' letta a ogni calcolo e non e' un flusso: [refresh] la rilegge dopo un cambio lingua.
    private val languageTick = MutableStateFlow(0)

    val available: StateFlow<Boolean> = combine(
        settingsStore.hasApiKeyFlow,
        modelManager.downloadedModelIds,
        languageTick,
    ) { hasApiKey, downloaded, _ -> compute(hasApiKey, downloaded) }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, compute(settingsStore.hasApiKeyFlow.value, modelManager.downloadedModelIds.value))

    fun refresh() {
        languageTick.value++
    }

    private fun compute(hasApiKey: Boolean, downloaded: Set<String>): Boolean =
        isAiAvailable(hasApiKey, deviceAiCapability.ramTier(), downloaded, currentGuidesLanguage())
}

/**
 * Pura, per i test. Sotto i 4 GB di RAM ([RamTier.INSUFFICIENTE]) conta solo la chiave: nessun modello
 * del catalogo e' visibile li' ([LlmModelCatalog.visibleFor]). Un modello addestrato in un'altra lingua
 * (es. quello italiano con le guide in inglese) non conta.
 */
internal fun isAiAvailable(hasApiKey: Boolean, ramTier: RamTier, downloadedModelIds: Set<String>, language: String): Boolean =
    hasApiKey || LlmModelCatalog.visibleFor(ramTier, language).any { it.id in downloadedModelIds }
