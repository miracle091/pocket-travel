package com.pockettravel.feature.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pockettravel.core.sync.NetworkBlock
import com.pockettravel.core.sync.networkBlockFor
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
            resultFor(error)
        } catch (_: Exception) {
            Result.failure()
        }

    private fun resultFor(error: IOException): Result {
        val block = applicationContext.networkBlockFor(error)
        return when {
            error.isNoSpaceLeft() ->
                Result.failure(workDataOf(LlmModelDownloadWorker.KEY_FAILURE_REASON to LlmModelDownloadWorker.FAILURE_REASON_SPACE))
            // Ritentare non serve finché l'utente non toglie il Risparmio dati per l'app.
            block == NetworkBlock.DATA_SAVER ->
                Result.failure(workDataOf(LlmModelDownloadWorker.KEY_FAILURE_REASON to LlmModelDownloadWorker.FAILURE_REASON_DATA_SAVER))
            shouldRetryDownload(runAttemptCount) -> Result.retry()
            // Un errore DNS passeggero non è un blocco: lo si dice solo quando i tentativi sono finiti.
            block == NetworkBlock.SUSPECTED ->
                Result.failure(workDataOf(LlmModelDownloadWorker.KEY_FAILURE_REASON to LlmModelDownloadWorker.FAILURE_REASON_NETWORK_BLOCKED))
            else -> Result.failure()
        }
    }

    private companion object {
        const val PERCENT = 100L
    }
}
