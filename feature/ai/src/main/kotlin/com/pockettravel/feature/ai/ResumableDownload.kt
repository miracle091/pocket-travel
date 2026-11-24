package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

private const val PROGRESS_STEP = 1_000_000L
private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_CLIENT_ERRORS = 400..499

/**
 * Scarica [url] in [part] fino alla fine, riprendendo con Range dai byte gia' presenti, senza verificarlo: il
 * chiamante controlla prima se il `.part` e' gia' completo (il server risponderebbe 416) e dopo lo sha256.
 *
 * - 416 con un `.part` esistente: lo cancella e lancia [IOException] (ritentando riparte da zero).
 * - Altro 4xx, tranne 408 e 429: [ModelDownloadFailedException], ritentare non cambierebbe. Gli altri errori: [IOException].
 * - Un 200 al posto del 206: il server ignora Range, si riparte da zero.
 * - Se arrivano piu' di [expectedBytes] byte (0 = dimensione ignota, nessun limite) cancella il `.part` e lancia
 *   [ModelDownloadFailedException].
 *
 * [what] entra nei messaggi d'errore. [onProgress] riceve i byte del file e il totale stimato (ogni 1 MB e alla fine).
 */
internal suspend fun resumableDownload(
    client: OkHttpClient,
    url: String,
    part: File,
    expectedBytes: Long,
    what: String,
    onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
) {
    val existing = if (part.exists()) part.length() else 0L
    val request = Request.Builder().url(url)
        .apply { if (existing > 0) header("Range", "bytes=$existing-") }
        .build()
    val call = client.newCall(request)
    // execute() e le letture del corpo bloccano il thread: annullando la coroutine si annulla la chiamata, che le
    // fa fallire subito. Unconfined: il gestore parte nel thread che annulla, anche se quello del download e' bloccato.
    coroutineScope {
        val canceller = launch(Dispatchers.Unconfined) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw failedResponse(response.code, part, existing, what)
                val resumed = response.code == HTTP_PARTIAL_CONTENT && existing > 0
                if (resumed) {
                    require(response.header("Content-Range")?.startsWith("bytes $existing-", ignoreCase = true) == true) {
                        "Risposta range non valida per $what"
                    }
                }
                val base = if (resumed) existing else 0L
                val total = base + response.body.contentLength().coerceAtLeast(0L)
                val downloaded = response.body.byteStream().use { input ->
                    writePart(input, part, resumed, base, total, expectedBytes, what, onProgress)
                }
                onProgress(downloaded, total)
            }
        } catch (error: IOException) {
            // La chiamata annullata fallisce con "Socket closed": e' l'annullamento, non un errore da riportare.
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            canceller.cancel()
        }
    }
}

// 416 con .part esistente: il server non ha quei byte, senza cancellarlo ogni nuovo tentativo fallirebbe uguale. Un 4xx
// (tranne 408/429, tipicamente transitori) non cambierebbe ritentando; un errore di rete o un 5xx invece si.
private fun failedResponse(code: Int, part: File, existing: Long, what: String): Exception = when {
    code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0 -> {
        part.delete()
        IOException("Download $what da ricominciare: HTTP $code")
    }
    code in HTTP_CLIENT_ERRORS && code != HTTP_REQUEST_TIMEOUT && code != HTTP_TOO_MANY_REQUESTS ->
        ModelDownloadFailedException("Download $what fallito: HTTP $code")
    else -> IOException("Download $what fallito: HTTP $code")
}

// Scrive [input] in [part] (in coda se [resumed]) e restituisce i byte del file a fine download.
private suspend fun writePart(
    input: InputStream,
    part: File,
    resumed: Boolean,
    base: Long,
    total: Long,
    expectedBytes: Long,
    what: String,
    onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
): Long {
    var downloaded = base
    var lastReported = base
    FileOutputStream(part, resumed).use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            currentCoroutineContext().ensureActive()
            output.write(buffer, 0, read)
            downloaded += read
            // downloaded include i byte del .part ripreso: expectedBytes e' la dimensione del file intero. Oltre
            // quella il server manda altro (file cambiato, risposta sbagliata): si ferma invece di riempire il disco.
            if (expectedBytes > 0 && downloaded > expectedBytes) {
                output.close()
                part.delete()
                throw ModelDownloadFailedException("Download $what interrotto: oltre i $expectedBytes byte attesi")
            }
            if (downloaded - lastReported >= PROGRESS_STEP) {
                lastReported = downloaded
                onProgress(downloaded, total)
            }
        }
    }
    return downloaded
}
