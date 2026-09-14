package com.pockettravel.feature.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Download del modello IA richiesto dall'utente — mai automatico. Il token HuggingFace non
 * viaggia mai come input data del Worker (persistito in chiaro nel database interno di
 * WorkManager): viene letto qui direttamente da AiSettingsStore, che lo tiene cifrato in
 * Android Keystore — vedi RegionPackageDownloadWorker per lo stesso pattern di persistenza
 * del download applicato ai pacchetti regionali.
 */
@HiltWorker
class LlmModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val modelManager: LlmModelManager,
    private val aiSettingsStore: AiSettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            modelManager.download(hfToken = aiSettingsStore.huggingFaceToken()) { downloaded, total ->
                setProgress(workDataOf(KEY_BYTES_DOWNLOADED to downloaded, KEY_TOTAL_BYTES to total))
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: ModelAuthException) {
            Result.failure(workDataOf(KEY_FAILURE_REASON to FAILURE_REASON_AUTH))
        } catch (_: ModelIntegrityException) {
            Result.failure(workDataOf(KEY_FAILURE_REASON to FAILURE_REASON_INTEGRITY))
        } catch (_: ModelDownloadFailedException) {
            Result.failure()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FAILURE_REASON = "failure_reason"
        const val FAILURE_REASON_AUTH = "auth"
        const val FAILURE_REASON_INTEGRITY = "integrity"
    }
}
