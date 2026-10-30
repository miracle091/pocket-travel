package com.pockettravel.feature.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiene l'app sveglia mentre l'assistente risponde: la risposta (caricamento del modello compreso, sul dispositivo e
 * online) gira nel ViewModel, e con lo schermo spento Android puo' rallentarla o fermarla. [during] avvia
 * [AnswerService] alla prima risposta in corso; il servizio si ferma quando [active] torna false. "Ferma" della
 * notifica arriva alla schermata da [stopRequests].
 */
class AnswerKeepAlive(private val startService: () -> Unit) {
    private val answers = AtomicInteger()
    private val _active = MutableStateFlow(false)
    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** true mentre almeno una risposta e' in corso. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    val stopRequests: Flow<Unit> = _stopRequests.asSharedFlow()

    /** [block] e' una risposta: il servizio resta finche' finisce, anche con un errore o una cancellazione. */
    suspend fun <T> during(block: suspend () -> T): T {
        if (answers.getAndIncrement() == 0) {
            _active.value = true
            startService()
        }
        try {
            return block()
        } finally {
            if (answers.decrementAndGet() == 0) _active.value = false
        }
    }

    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }
}
