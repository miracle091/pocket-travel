package com.pockettravel.feature.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pockettravel.core.sync.AiModelManifestEntry
import com.pockettravel.core.sync.AppStatusClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Download del modello IA richiesto dall'utente, mai automatico: i modelli del catalogo non
 * richiedono un token HuggingFace. Stesso pattern di persistenza del download di
 * RegionPackageDownloadWorker per i pacchetti regionali.
 */
@HiltWorker
class LlmModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val modelManager: LlmModelManager,
    private val aiSettingsStore: AiSettingsStore,
    private val appStatusClient: AppStatusClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val definition = aiSettingsStore.selectedModelDefinition().withPublishedFingerprint(publishedModels())
            // Un solo modello installato alla volta (vedi LlmModelManager.selectAndDownload):
            // se un altro modello e' gia' su disco va eliminato prima di scaricare questo.
            val currentlyInstalled = LlmModelCatalog.ALL.firstOrNull {
                it.id != definition.id && modelManager.isDownloaded(it)
            }
            modelManager.selectAndDownload(
                newDefinition = definition,
                currentlyInstalled = currentlyInstalled,
            ) { downloaded, total ->
                setProgress(workDataOf(KEY_BYTES_DOWNLOADED to downloaded, KEY_TOTAL_BYTES to total))
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
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

    // Senza rete verso GitHub, o con un file non firmato, restano le impronte del catalogo dell'APK.
    private suspend fun publishedModels(): List<AiModelManifestEntry> = try {
        appStatusClient.fetchAppStatus().aiModels
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val PERCENT = 100L
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FAILURE_REASON = "failure_reason"
        const val FAILURE_REASON_INTEGRITY = "integrity"
    }
}

/**
 * [this] con sha256 e dimensione pubblicati in app-status.json (firmato, verificato da AppStatusClient) per lo
 * stesso file: un modello ricaricato su HuggingFace con lo stesso nome si scarica senza un nuovo rilascio
 * dell'app. Senza una voce per lo stesso id e nome file restano quelli del catalogo dell'APK.
 */
internal fun LlmModelDefinition.withPublishedFingerprint(published: List<AiModelManifestEntry>): LlmModelDefinition {
    val entry = published.firstOrNull { it.modelId == id && it.modelVersion == fileName } ?: return this
    return copy(sha256 = entry.sha256.lowercase(), sizeBytes = entry.sizeBytes)
}
