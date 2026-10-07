package com.pockettravel.feature.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pockettravel.core.sync.AiModelManifestEntry
import com.pockettravel.core.sync.AppStatusClient
import com.pockettravel.core.sync.UpdateAvailableNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Controllo periodico (qualunque rete incluse i dati cellulari, vedi
 * [LlmModelUpdateCheckScheduler]): legge app-status.json (asset della release GitHub
 * "app-status", pubblicato da publish-apk.yml ad ogni rilascio app — non legato
 * all'aggiornamento dei pacchetti regionali, vedi AppStatus.kt in core:sync), una entry per
 * modello del catalogo (LlmModelCatalog.ALL). Se il modello IA on-device installato ha uno
 * sha256 diverso da quello pubblicato per lo stesso modelId, notifica — non scarica mai
 * automaticamente. Notifica solo se l'utente ha gia' scaricato un modello: non ha senso
 * segnalare un aggiornamento a chi non usa il motore on-device.
 */
@HiltWorker
class LlmModelUpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val appStatusClient: AppStatusClient,
    private val modelManager: LlmModelManager,
    private val aiSettingsStore: AiSettingsStore,
    private val notifier: UpdateAvailableNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val definition = aiSettingsStore.selectedModelDefinition()
            if (modelManager.isDownloaded(definition)) {
                val remote = appStatusClient.fetchAppStatus().aiModels.firstOrNull { it.modelId == definition.id }
                // Lo sha del file su disco, non quello del catalogo dell'APK: chi ha scaricato la versione
                // precedente con lo stesso id (cambia solo lo sha256) altrimenti non verrebbe mai avvisato.
                // Calcolato (una volta sola) solo se serve, cioe' se c'e' una entry remota da confrontare.
                val installedSha256 = remote?.let { modelManager.installedSha256(definition) }
                if (remote != null && installedSha256 != null && isAiModelUpdateAvailable(installedSha256, remote)) {
                    notifier.notifyAiModelUpdateAvailable(remote)
                }
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}

internal fun isAiModelUpdateAvailable(installedSha256: String, remote: AiModelManifestEntry): Boolean =
    !remote.sha256.equals(installedSha256, ignoreCase = true)
