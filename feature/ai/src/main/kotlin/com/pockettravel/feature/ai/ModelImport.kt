package com.pockettravel.feature.ai

/** File scelto in "Importa da file" che non corrisponde a nessun modello del catalogo. */
class ModelUnknownFileException(message: String) : Exception(message)

/** Spazio libero insufficiente per copiare il file da importare. */
class ModelNoSpaceException(message: String) : Exception(message)

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
