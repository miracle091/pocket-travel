package com.pockettravel.core.sync

import okhttp3.ResponseBody
import java.io.IOException

/** Tetto dei file JSON firmati (manifest.json, app-status.json, online-models.json). */
internal const val MAX_SIGNED_JSON_BYTES = 16L * 1024 * 1024

/** Tetto di un file .sig. */
internal const val MAX_SIGNATURE_BYTES = 1024L

/**
 * I byte del corpo, ma al massimo [maxBytes]: oltre si lancia un [IOException] senza leggere il resto, cosi' una risposta
 * enorme non riempie la memoria prima della verifica della firma.
 */
internal fun ResponseBody.readBodyAtMost(maxBytes: Long): ByteArray {
    if (contentLength() > maxBytes) throw IOException("Risposta troppo grande: oltre $maxBytes byte")
    val source = source()
    source.request(maxBytes + 1)
    if (source.buffer.size > maxBytes) throw IOException("Risposta troppo grande: oltre $maxBytes byte")
    return source.readByteArray()
}
