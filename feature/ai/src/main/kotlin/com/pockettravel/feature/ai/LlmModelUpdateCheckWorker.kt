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
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Controllo periodico (qualunque rete incluse i dati cellulari, vedi
 * [LlmModelUpdateCheckScheduler]): legge app-status.json (asset della release GitHub
 * "app-status", pubblicato da publish-apk.yml ad ogni rilascio app — non legato
 * all'aggiornamento dei pacchetti regionali, vedi AppStatus.kt in core:sync). Se il modello IA
 * on-device installato ha uno sha256 diverso da quello pubblicato, notifica — non scarica mai
 * automaticamente. Notifica solo se l'utente ha gia' scaricato un modello: nessun senso di
 * segnalare un aggiornamento a chi non usa la modalita' on-device.
 */
@HiltWorker
class LlmModelUpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val appStatusClient: AppStatusClient,
    private val modelManager: LlmModelManager,
    private val notifier: UpdateAvailableNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (modelManager.isDownloaded()) {
                val aiModel = appStatusClient.fetchAppStatus().aiModel
                if (aiModel != null && isAiModelUpdateAvailable(AiModelConfig.MODEL_SHA256, aiModel)) {
                    notifier.notifyAiModelUpdateAvailable(aiModel)
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
