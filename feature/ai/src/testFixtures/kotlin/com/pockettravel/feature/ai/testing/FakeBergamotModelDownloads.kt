package com.pockettravel.feature.ai.testing

import com.pockettravel.feature.ai.BergamotDownloadStatus
import com.pockettravel.feature.ai.BergamotModelDownloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [BergamotModelDownloads] in memoria: [status] e' quello che la schermata osserva; conta le richieste e gli annullamenti. */
class FakeBergamotModelDownloads : BergamotModelDownloads {
    val status = MutableStateFlow<BergamotDownloadStatus>(BergamotDownloadStatus.Idle)
    var enqueued = 0
    var cancelled = 0

    override fun enqueue() {
        enqueued++
    }

    override fun cancel() {
        cancelled++
    }

    override fun observe(): Flow<BergamotDownloadStatus> = status
}
