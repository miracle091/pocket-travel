package com.pockettravel.feature.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pockettravel.core.data.runCatchingCancellable
import com.pockettravel.core.sync.AiModelManifestEntry
import com.pockettravel.core.sync.AppStatusClient
import com.pockettravel.core.sync.NetworkBlock
import com.pockettravel.core.sync.networkBlockFor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Download del modello IA richiesto dall'utente, mai automatico: i file del catalogo dei modelli non
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
            val requested = downloadDefinition(inputData.getString(KEY_MODEL_ID), aiSettingsStore.selectedModelDefinition())
            // Solo quando cambia la percentuale: ogni setProgress e' una scrittura nel database di WorkManager.
            var lastPercent = -1L
            val onProgress: suspend (Long, Long) -> Unit = { downloaded, total ->
                val percent = if (total > 0) downloaded * PERCENT / total else PERCENT
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgress(workDataOf(KEY_BYTES_DOWNLOADED to downloaded, KEY_TOTAL_BYTES to total))
                }
            }
            if (inputData.getBoolean(KEY_MMPROJ, false)) {
                // Il modello e' gia' sul dispositivo: si scarica solo il file per le foto.
                modelManager.downloadMmproj(requested, onProgress)
                return Result.success()
            }
            val definition = requested.withPublishedFingerprint(publishedModels())
            // Un solo modello installato alla volta (vedi LlmModelManager.selectAndDownload):
            // se un altro modello e' gia' su disco resta finche' questo non e' scaricato e verificato, poi si elimina.
            val currentlyInstalled = LlmModelCatalog.ALL.firstOrNull {
                it.id != definition.id && modelManager.isDownloaded(it)
            }
            modelManager.selectAndDownload(
                newDefinition = definition,
                currentlyInstalled = currentlyInstalled,
                // Solo a modello installato e verificato il download diventa il modello in uso, prima che il vecchio sparisca.
                onInstalled = {
                    if (definition.id != aiSettingsStore.selectedModelId()) aiSettingsStore.setSelectedModelId(definition.id)
                },
                onProgress = onProgress,
            )
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: ModelIntegrityException) {
            Result.failure(workDataOf(KEY_FAILURE_REASON to FAILURE_REASON_INTEGRITY))
        } catch (_: ModelDownloadFailedException) {
            Result.failure()
        } catch (error: IOException) {
            resultFor(error)
        } catch (_: Exception) {
            Result.failure()
        }
    }

    private fun resultFor(error: IOException): Result {
        val block = applicationContext.networkBlockFor(error)
        return when {
            error.isNoSpaceLeft() -> Result.failure(workDataOf(KEY_FAILURE_REASON to FAILURE_REASON_SPACE))
            // Ritentare non serve finché l'utente non toglie il Risparmio dati per l'app.
            block == NetworkBlock.DATA_SAVER -> Result.failure(workDataOf(KEY_FAILURE_REASON to FAILURE_REASON_DATA_SAVER))
            shouldRetryDownload(runAttemptCount) -> Result.retry()
            // Un errore DNS passeggero non è un blocco: lo si dice solo quando i tentativi sono finiti.
            block == NetworkBlock.SUSPECTED -> Result.failure(workDataOf(KEY_FAILURE_REASON to FAILURE_REASON_NETWORK_BLOCKED))
            else -> Result.failure()
        }
    }

    // Senza rete verso GitHub, o con un file non firmato, restano le impronte del catalogo dei modelli dell'APK.
    private suspend fun publishedModels(): List<AiModelManifestEntry> =
        runCatchingCancellable { appStatusClient.fetchAppStatus().aiModels }.getOrDefault(emptyList())

    companion object {
        private const val PERCENT = 100L
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FAILURE_REASON = "failure_reason"
        const val FAILURE_REASON_INTEGRITY = "integrity"
        const val FAILURE_REASON_SPACE = "space"
        const val FAILURE_REASON_DATA_SAVER = "data_saver"
        const val FAILURE_REASON_NETWORK_BLOCKED = "network_blocked"

        /** Id del modello da scaricare: il lavoro resta legato a quello richiesto, anche se poi se ne sceglie un altro. */
        const val KEY_MODEL_ID = "model_id"

        /** true: si scarica solo l'mmproj del modello [KEY_MODEL_ID] (le foto), non il modello. */
        const val KEY_MMPROJ = "mmproj"

        /** Etichetta del lavoro dell'mmproj, per distinguerlo da quello del modello nello stesso avanzamento. */
        const val TAG_MMPROJ = "mmproj"

        /** Tentativi totali (con attesa crescente tra l'uno e l'altro) prima di dichiarare il download fallito. */
        internal const val MAX_ATTEMPTS = 5
    }
}

/** Il modello [requestedId] del catalogo; senza id (o con un id sconosciuto) quello scelto adesso. */
internal fun downloadDefinition(requestedId: String?, selected: LlmModelDefinition): LlmModelDefinition =
    requestedId?.let { id -> LlmModelCatalog.ALL.firstOrNull { it.id == id } } ?: selected

/** Dopo il tentativo numero [runAttemptCount] + 1 (da 0) se ne fa un altro, entro [LlmModelDownloadWorker.MAX_ATTEMPTS]. */
internal fun shouldRetryDownload(runAttemptCount: Int): Boolean = runAttemptCount + 1 < LlmModelDownloadWorker.MAX_ATTEMPTS

/** Disco pieno: ritentare non serve finché l'utente non libera spazio. */
internal fun IOException.isNoSpaceLeft(): Boolean =
    message?.let { it.contains("ENOSPC") || it.contains("No space left", ignoreCase = true) } == true

/**
 * [this] con sha256 e dimensione pubblicati in app-status.json (firmato, verificato da AppStatusClient) per lo
 * stesso file: un modello ricaricato su HuggingFace con lo stesso nome si scarica senza un nuovo rilascio
 * dell'app. Senza una voce per lo stesso id e nome file restano quelli del catalogo dei modelli dell'APK.
 */
internal fun LlmModelDefinition.withPublishedFingerprint(published: List<AiModelManifestEntry>): LlmModelDefinition {
    val entry = published.firstOrNull { it.modelId == id && it.modelVersion == fileName } ?: return this
    return copy(sha256 = entry.sha256.lowercase(), sizeBytes = entry.sizeBytes)
}
