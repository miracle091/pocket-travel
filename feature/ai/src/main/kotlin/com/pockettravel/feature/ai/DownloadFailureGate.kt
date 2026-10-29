package com.pockettravel.feature.ai

import androidx.work.WorkInfo

/**
 * Decide se un FAILED del download del modello va mostrato. WorkManager ripete l'ultimo stato del lavoro a ogni nuova
 * osservazione: un errore di un download vecchio, visto senza che questa osservazione ne abbia visto l'avvio, non va
 * mostrato di nuovo.
 */
internal class DownloadFailureGate {
    private var sawActive = false

    /** True se [state] è un fallimento di un download seguito da questa osservazione. */
    fun reportsFailure(state: WorkInfo.State): Boolean = when (state) {
        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
            sawActive = true
            false
        }
        WorkInfo.State.FAILED -> sawActive.also { sawActive = false }
        WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> {
            sawActive = false
            false
        }
        WorkInfo.State.BLOCKED -> false
    }
}
