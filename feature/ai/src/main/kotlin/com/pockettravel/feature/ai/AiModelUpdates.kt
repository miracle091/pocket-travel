package com.pockettravel.feature.ai

import androidx.work.WorkInfo
import com.pockettravel.core.sync.AiModelManifestEntry
import kotlinx.coroutines.flow.Flow

/**
 * Aggiornamento del modello IA sul dispositivo, per la schermata Aggiornamenti: stesso confronto del controllo periodico
 * ([LlmModelUpdateCheckWorker]) e stesso download di "Scarica" ([LlmModelDownloadScheduler]).
 */
interface AiModelUpdates {
    /** La versione pubblicata del modello scelto se è scaricato e ha un'impronta diversa, altrimenti null. */
    suspend fun availableUpdate(published: List<AiModelManifestEntry>): AiModelManifestEntry?

    /** Mette in coda il download della versione pubblicata: false se lo spazio libero non basta. */
    fun download(update: AiModelManifestEntry): Boolean

    fun observeDownload(): Flow<WorkInfo?>
}
