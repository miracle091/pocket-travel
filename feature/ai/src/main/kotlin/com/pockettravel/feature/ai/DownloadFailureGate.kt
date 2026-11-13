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

/** Il messaggio di un download del modello non riuscito, dal motivo scritto da [LlmModelDownloadWorker]. */
internal fun downloadFailureMessage(reason: String?): Int = when (reason) {
    LlmModelDownloadWorker.FAILURE_REASON_INTEGRITY -> R.string.ai_error_integrity
    LlmModelDownloadWorker.FAILURE_REASON_SPACE -> R.string.ai_error_space
    else -> R.string.ai_error_download
}

/** La parte del modello gia' scaricata, da 0 a 1; 0 finche' la dimensione non e' nota. */
internal fun WorkInfo.downloadFraction(): Float {
    val downloaded = progress.getLong(LlmModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
    val total = progress.getLong(LlmModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
    return if (total > 0) downloaded / total.toFloat() else 0f
}
