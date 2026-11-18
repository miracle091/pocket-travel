package com.pockettravel.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Come si ferma il giro della coda: vuota, con sole voci che aspettano il Wi-Fi, o da ritentare per un errore di rete. */
internal enum class QueueRunResult { FINISHED, WAITING_FOR_WIFI, RETRY }

/**
 * Il ciclo del worker della coda, senza WorkManager: una voce alla volta finche' ce ne sono. Una voce fallita resta
 * segnata e la coda va avanti; una da ritentare va in fondo e il giro si ferma, per il nuovo tentativo di WorkManager.
 * Se la voce in corso viene tolta dalla coda, si ferma solo il suo download.
 */
internal class RegionDownloadQueueRunner(
    private val queue: RegionDownloadQueue,
    private val isUnmetered: () -> Boolean,
    private val download: suspend (QueuedDownload) -> DownloadOutcome,
) {
    suspend fun run(): QueueRunResult {
        try {
            var item = queue.claimNext(isUnmetered())
            while (item != null) {
                // Null se tolta a meta': la coda l'ha gia' dimenticata.
                val outcome = downloadUnlessRemoved(item)
                if (outcome != null) queue.finish(item.key, outcome)
                if (outcome == DownloadOutcome.RETRY) return QueueRunResult.RETRY
                item = queue.claimNext(isUnmetered())
            }
        } finally {
            queue.release()
        }
        return if (queue.state.value.pending.isEmpty()) QueueRunResult.FINISHED else QueueRunResult.WAITING_FOR_WIFI
    }

    // Null se la voce e' stata tolta dalla coda durante il download.
    private suspend fun downloadUnlessRemoved(item: QueuedDownload): DownloadOutcome? = coroutineScope {
        val job = async { download(item) }
        val watcher = launch {
            queue.state.first { it.current?.key != item.key }
            job.cancel()
        }
        try {
            job.await()
        } catch (error: CancellationException) {
            // Fermato il lavoro intero (non solo questa voce): si propaga.
            currentCoroutineContext().ensureActive()
            if (queue.state.value.current?.key == item.key) throw error
            null
        } finally {
            watcher.cancel()
        }
    }
}
