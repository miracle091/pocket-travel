package com.pockettravel.feature.ai

import kotlinx.coroutines.flow.StateFlow

/**
 * Se l'assistente IA e' utilizzabile: una chiave API salvata (online) o almeno un modello on-device
 * scaricato che funziona con la lingua delle guide. Chi non e' configurato non vede la scheda IA
 * nell'hub delle regioni; la configurazione sta nelle Impostazioni (vedi [AiSettingsSection]).
 */
interface AiAvailability {
    val available: StateFlow<Boolean>

    /** Rilegge la lingua delle guide dopo un cambio lingua e ricalcola [available]. */
    fun refresh()
}
