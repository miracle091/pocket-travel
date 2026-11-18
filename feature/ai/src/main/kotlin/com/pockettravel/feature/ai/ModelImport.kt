package com.pockettravel.feature.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** File scelto in "Importa da file" che non corrisponde a nessun modello del catalogo. */
class ModelUnknownFileException(message: String) : Exception(message)

/** Spazio libero insufficiente per copiare il file da importare. */
class ModelNoSpaceException(message: String) : Exception(message)

/** Il messaggio di "Importa da file" non riuscito per [error]. */
internal fun importFailureMessage(error: Exception): Int = when (error) {
    is ModelNoSpaceException -> R.string.ai_import_no_space
    is ModelIntegrityException -> R.string.ai_import_damaged
    is ModelUnknownFileException -> R.string.ai_import_unknown
    else -> R.string.ai_import_failed
}

/** Esito del confronto fra un file ricevuto e i modelli del catalogo. */
sealed interface ImportVerdict {
    /** Stesso sha256 di [definition]: il file è quel modello. */
    data class Match(val definition: LlmModelDefinition) : ImportVerdict

    /** Sha256 diverso, ma dimensione o nome di un modello del catalogo: file alterato o incompleto. */
    data object Damaged : ImportVerdict

    /** Nessuna corrispondenza con il catalogo. */
    data object Unknown : ImportVerdict
}

/**
 * Confronta il file ricevuto con [candidates] (il catalogo dell'APK e le impronte pubblicate, quelle con sha256).
 * L'sha256 decide; solo quando non corrisponde, la dimensione o il nome del file di un modello del catalogo
 * distinguono un file danneggiato (stesso modello, byte diversi) da un file estraneo.
 */
fun classifyImport(sha256: String, sizeBytes: Long, displayName: String?, candidates: List<LlmModelDefinition>): ImportVerdict {
    val known = candidates.filter { it.sha256 != null }
    known.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }?.let { return ImportVerdict.Match(it) }
    val looksLikeKnown = known.any { it.sizeBytes == sizeBytes || (displayName != null && it.fileName.equals(displayName, ignoreCase = true)) }
    return if (looksLikeKnown) ImportVerdict.Damaged else ImportVerdict.Unknown
}

/**
 * "Importa da file" ([isImportingModel] gia' acceso): installa il file letto con [open] e, se e' un modello del catalogo,
 * rilascia il motore e chiama [onImported]; altrimenti mostra l'errore. Chiude [AiUiState.isImportingModel].
 */
@Suppress("TooGenericExceptionCaught") // Qualunque errore dell'importazione si mostra come messaggio.
internal suspend fun MutableStateFlow<AiUiState>.importFrom(
    manager: LlmModelManager,
    engine: OnDeviceLlmEngine,
    ioDispatcher: CoroutineDispatcher,
    displayName: String?,
    sizeBytes: Long,
    open: () -> InputStream?,
    onImported: (LlmModelDefinition) -> Unit,
) {
    try {
        // Un provider cloud puo' scaricare il file all'apertura: fuori dal main thread.
        val input = withContext(ioDispatcher) { open() } ?: throw IOException("File non leggibile")
        val definition = manager.importModel(input, displayName, sizeBytes, LlmModelCatalog.ALL + value.availableModels)
        // Solo con un file valido: un file sbagliato non deve scaricare il modello in uso.
        engine.release()
        onImported(definition)
        update { it.copy(isImportingModel = false) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        update { it.copy(isImportingModel = false, importError = importFailureMessage(error)) }
    }
}
