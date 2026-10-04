package com.pockettravel.feature.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Trasforma i token del modello nel testo accumulato finora, emesso al massimo ogni [minIntervalMs]: la UI
 * non si ricompone a ogni token (decine al secondo). L'ultima emissione e' sempre il testo completo, anche se
 * cade dentro l'intervallo; se il flusso non produce token non emette nulla. Se il flusso a monte fallisce o
 * viene cancellato l'eccezione passa invariata e l'eventuale testo non ancora emesso si perde.
 * [now] e' l'orologio in millisecondi (iniettabile per i test).
 */
internal fun Flow<String>.accumulated(
    minIntervalMs: Long,
    now: () -> Long = System::currentTimeMillis,
): Flow<String> = flow {
    val text = StringBuilder()
    var lastEmitAt = 0L
    var emittedAny = false
    var pending = false
    collect { token ->
        text.append(token)
        val time = now()
        if (!emittedAny || time - lastEmitAt >= minIntervalMs) {
            emit(text.toString())
            emittedAny = true
            lastEmitAt = time
            pending = false
        } else {
            pending = true
        }
    }
    if (pending) emit(text.toString())
}
