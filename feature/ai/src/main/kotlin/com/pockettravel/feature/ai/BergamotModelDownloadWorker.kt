package com.pockettravel.feature.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Download della coppia di modelli di traduzione, mai automatico: parte dal pulsante delle Impostazioni. */
@HiltWorker
class BergamotModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: BergamotModelManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        try {
            // Solo quando cambia la percentuale: ogni setProgress e' una scrittura nel database di WorkManager.
            var lastPercent = -1L
            manager.download { downloaded, total ->
                val percent = if (total > 0) downloaded * PERCENT / total else PERCENT
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgress(
                        workDataOf(
                            LlmModelDownloadWorker.KEY_BYTES_DOWNLOADED to downloaded,
                            LlmModelDownloadWorker.KEY_TOTAL_BYTES to total,
                        ),
                    )
                }
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: ModelIntegrityException) {
            Result.failure(workDataOf(LlmModelDownloadWorker.KEY_FAILURE_REASON to LlmModelDownloadWorker.FAILURE_REASON_INTEGRITY))
        } catch (_: ModelDownloadFailedException) {
            Result.failure()
        } catch (error: IOException) {
            when {
                error.isNoSpaceLeft() ->
                    Result.failure(workDataOf(LlmModelDownloadWorker.KEY_FAILURE_REASON to LlmModelDownloadWorker.FAILURE_REASON_SPACE))
                shouldRetryDownload(runAttemptCount) -> Result.retry()
                else -> Result.failure()
            }
        } catch (_: Exception) {
            Result.failure()
        }

    private companion object {
        const val PERCENT = 100L
    }
}
