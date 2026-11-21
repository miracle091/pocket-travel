package com.pockettravel.feature.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Dove si e' arrivati con il download della coppia di traduzione, come lo vede la schermata. */
sealed interface BergamotDownloadStatus {
    /** Nessun download in corso e nessun fallimento da mostrare. */
    data object Idle : BergamotDownloadStatus

    /** In coda o in corso; [fraction] da 0 a 1. */
    data class Running(val fraction: Float) : BergamotDownloadStatus

    data class Failed(val reason: BergamotDownloadFailure) : BergamotDownloadStatus
}

enum class BergamotDownloadFailure { INTEGRITY, SPACE, OTHER }

/** Il download della coppia di traduzione, persistente a un kill del processo (WorkManager), come quello dei modelli dell'assistente. */
interface BergamotModelDownloads {
    fun enqueue()

    fun cancel()

    fun observe(): Flow<BergamotDownloadStatus>
}

class WorkManagerBergamotModelDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
) : BergamotModelDownloads {
    private val workManager get() = WorkManager.getInstance(context)

    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<BergamotModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    override fun observe(): Flow<BergamotDownloadStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
            infos.firstOrNull()?.let { downloadStatus(it.state, it.progress, it.outputData) } ?: BergamotDownloadStatus.Idle
        }

    private companion object {
        const val WORK_NAME = "bergamot_model_download"
    }
}

/** Lo stato di [BergamotModelDownloads.observe] dallo stato del lavoro, dall'avanzamento e dall'esito. */
internal fun downloadStatus(state: WorkInfo.State, progress: Data, output: Data): BergamotDownloadStatus = when (state) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
        val total = progress.getLong(LlmModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
        val downloaded = progress.getLong(LlmModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
        BergamotDownloadStatus.Running(if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f)
    }
    WorkInfo.State.FAILED -> BergamotDownloadStatus.Failed(
        when (output.getString(LlmModelDownloadWorker.KEY_FAILURE_REASON)) {
            LlmModelDownloadWorker.FAILURE_REASON_INTEGRITY -> BergamotDownloadFailure.INTEGRITY
            LlmModelDownloadWorker.FAILURE_REASON_SPACE -> BergamotDownloadFailure.SPACE
            else -> BergamotDownloadFailure.OTHER
        },
    )
    WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> BergamotDownloadStatus.Idle
}
