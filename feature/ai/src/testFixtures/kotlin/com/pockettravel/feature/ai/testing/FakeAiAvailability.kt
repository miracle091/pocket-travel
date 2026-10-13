package com.pockettravel.feature.ai.testing

import com.pockettravel.feature.ai.AiAvailability
import kotlinx.coroutines.flow.MutableStateFlow

/** Disponibilita' dell'IA in memoria per i test JVM: [available] si cambia a mano, [refreshCalls] conta le riletture. */
class FakeAiAvailability(initial: Boolean = false) : AiAvailability {

    override val available = MutableStateFlow(initial)

    var refreshCalls = 0
        private set

    override fun refresh() {
        refreshCalls++
    }
}
