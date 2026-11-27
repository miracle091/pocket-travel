package com.pockettravel.feature.ai.llamacpp

import java.io.IOException

/** Perche' il caricamento di un modello e' fallito, ricavato dalle righe di errore del log di llama.cpp. */
enum class ModelLoadFailure {
    /** File mancante, illeggibile, corrotto o troncato. */
    FILE,

    /** Architettura sconosciuta a questa versione di llama.cpp: la gestisce [UnsupportedArchitectureException]. */
    ARCHITECTURE,

    /** Memoria insufficiente per i pesi o per i buffer del contesto. */
    MEMORY,

    /** Nessuna causa riconosciuta. */
    OTHER,
}

/** Caricamento non riuscito per una causa diversa dall'architettura: [reason] dice quale, [message] riporta il log nativo. */
class ModelLoadException(val reason: ModelLoadFailure, message: String) : IOException(message)

// Frasi del log di llama.cpp e ggml (src/llama-model-loader.cpp, llama-model.cpp, ggml-alloc.c, llama-mmap.cpp), in
// minuscolo. L'ordine conta: un file troncato puo' dare anche un errore di memoria, ma l'architettura sconosciuta e la
// memoria sono le cause che l'utente puo' correggere.
private val ARCHITECTURE_MARKERS = listOf("unknown model architecture")
private val MEMORY_MARKERS = listOf("failed to allocate", "unable to allocate", "cannot allocate", "out of memory", "bad_alloc")
private val FILE_MARKERS = listOf(
    "failed to open",
    "no such file",
    "permission denied",
    "invalid magic",
    "not within the file bounds",
    "failed to read",
    "unexpectedly reached end",
)

private const val MAX_MESSAGE_CHARS = 400

/** Classifica le righe di errore native [nativeError] (vuote se llama.cpp non ne ha scritte). */
internal fun classifyModelLoadFailure(nativeError: String): ModelLoadFailure {
    val text = nativeError.lowercase()
    return when {
        ARCHITECTURE_MARKERS.any { it in text } -> ModelLoadFailure.ARCHITECTURE
        MEMORY_MARKERS.any { it in text } -> ModelLoadFailure.MEMORY
        FILE_MARKERS.any { it in text } -> ModelLoadFailure.FILE
        else -> ModelLoadFailure.OTHER
    }
}

/**
 * L'eccezione di un caricamento fallito alla fase [stage] ("load" o "prepare") con le righe di errore native
 * [nativeError]: [UnsupportedArchitectureException] per un'architettura sconosciuta, [ModelLoadException] per il resto.
 * Il messaggio tiene le ultime righe, ripulite, al massimo [MAX_MESSAGE_CHARS] caratteri.
 */
internal fun modelLoadException(stage: String, code: Int, nativeError: String): Exception {
    val lines = nativeError.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
    val detail = lines.takeLast(MAX_MESSAGE_CHARS)
    val message = "Model $stage failed (code $code)" + if (detail.isEmpty()) "" else ": $detail"
    return when (val reason = classifyModelLoadFailure(nativeError)) {
        ModelLoadFailure.ARCHITECTURE -> UnsupportedArchitectureException(message)
        ModelLoadFailure.FILE, ModelLoadFailure.MEMORY, ModelLoadFailure.OTHER -> ModelLoadException(reason, message)
    }
}
