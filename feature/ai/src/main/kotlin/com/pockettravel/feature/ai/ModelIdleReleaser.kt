package com.pockettravel.feature.ai

import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Toglie il modello dalla memoria quando l'assistente non lo usa: [IDLE_MILLIS] dopo l'ultimo uso, [HIDDEN_DELAY_MILLIS]
 * dopo la risposta in corso quando l'app va in background o lo schermo si spegne, e con la memoria scarsa anche in primo piano se non c'e' una
 * generazione. Cosi' i 0,5-2,5 GB del modello tornano al sistema prima che chiuda l'app mentre si usano Mappa e
 * Navigatore; la domanda dopo ricarica il modello.
 *
 * [release] gira sotto [AiModelCoordinator.withModelLock] e solo se nessun uso e' cominciato da quando il rilascio
 * e' stato deciso. Un uso si conta prima dell'attesa del lock: una domanda partita mentre il rilascio aspetta lo
 * stesso lock (dietro un download) ferma il rilascio, invece di trovare il modello appena tolto.
 */
class ModelIdleReleaser(
    private val scope: CoroutineScope,
    private val coordinator: AiModelCoordinator,
    private val release: suspend () -> Unit,
) {
    private val uses = AtomicLong()
    private var pending: Job? = null

    // App nascosta (anche solo lo schermo spento): il rilascio avviene HIDDEN_DELAY_MILLIS dopo l'ultimo uso. Resta
    // chiesto finche' non avviene, perche' una risposta usa il modello in piu' passi di fila.
    @Volatile
    private var releaseWhenHidden = false

    /** Un uso del modello (caricamento o generazione) sotto il lock del modello. */
    suspend fun <T> withModelUse(block: suspend () -> T): T {
        uses.incrementAndGet()
        synchronized(this) {
            pending?.cancel()
            pending = null
        }
        return coordinator.withModelLock {
            try {
                block()
            } finally {
                schedule(if (releaseWhenHidden) HIDDEN_DELAY_MILLIS else IDLE_MILLIS)
            }
        }
    }

    // Da Android 14 arrivano solo UI_HIDDEN e BACKGROUND (>= RUNNING_LOW); i livelli RUNNING_* restano per le versioni prima.
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                releaseWhenHidden = true
                if (!coordinator.isLocked) schedule(HIDDEN_DELAY_MILLIS)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !coordinator.isLocked -> schedule(0)
        }
    }

    private fun schedule(delayMillis: Long) {
        val seen = uses.get()
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(delayMillis)
                coordinator.withModelLock {
                    if (uses.get() == seen) {
                        release()
                        releaseWhenHidden = false
                    }
                }
            }
        }
    }

    companion object {
        const val IDLE_MILLIS = 5 * 60 * 1000L

        // Piu' della pausa tra i passi di una risposta (millisecondi), meno di quanto serve al sistema per chiudere
        // un'app in background con la memoria scarsa.
        const val HIDDEN_DELAY_MILLIS = 10_000L
    }
}
