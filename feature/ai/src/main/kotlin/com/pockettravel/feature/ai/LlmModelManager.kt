package com.pockettravel.feature.ai

import kotlinx.coroutines.flow.StateFlow
import java.io.File

class ModelIntegrityException(message: String) : Exception(message)

/** Errore non recuperabile con un retry (es. HTTP 404): a differenza di un errore di rete
 *  transitorio, ritentare la stessa richiesta darebbe sempre lo stesso esito. */
class ModelDownloadFailedException(message: String) : Exception(message)

/** definition.sha256 == null: modello non ancora disponibile per il download (vedi LlmModelDefinition). */
class ModelNotAvailableException(message: String) : Exception(message)

/**
 * Modello IA sul dispositivo: scaricato solo su richiesta, liberabile dall'utente in un tocco
 * (quantizzato, consigliato sotto 1,5 GB).
 * Generalizzato al catalogo dei modelli (LlmModelCatalog): ogni metodo prende il
 * [LlmModelDefinition] su cui operare invece di leggere un unico modello fisso, cosi' il
 * chiamante (risolto da AiSettingsStore.selectedModelId) resta l'unica fonte di verita' su
 * quale modello e' "quello attivo" — nessuno stato duplicato qui dentro.
 */
interface LlmModelManager {
    fun modelFile(definition: LlmModelDefinition): File

    fun isDownloaded(definition: LlmModelDefinition): Boolean

    /** Id delle voci del catalogo dei modelli presenti su disco, aggiornato a ogni installazione ed eliminazione. */
    val downloadedModelIds: StateFlow<Set<String>>

    fun sizeOnDisk(definition: LlmModelDefinition): Long

    fun availableStorageBytes(): Long

    suspend fun download(
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    )

    /** Un solo modello sul dispositivo installato alla volta: [currentlyInstalled], se diverso, si elimina prima del download. */
    suspend fun selectAndDownload(
        newDefinition: LlmModelDefinition,
        currentlyInstalled: LlmModelDefinition?,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    )

    /** sha256 del file installato di [definition], null se non e' scaricato. */
    suspend fun installedSha256(definition: LlmModelDefinition): String?

    suspend fun delete(definition: LlmModelDefinition): Boolean

    /** Come [delete], da chiamare solo con il lock del modello gia' preso. */
    fun deleteWithoutLock(definition: LlmModelDefinition): Boolean

    /** Elimina da disco ogni file che non e' una voce del catalogo dei modelli ne' il parziale del modello [selected]. */
    fun deleteOrphanedFiles(selected: LlmModelDefinition)
}
