package com.pockettravel.core.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

/** SHA-256 in esadecimale minuscolo, la forma dei manifest e dei nomi derivati (`grid-…`, `transit-…`). */
fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHexString()

/** SHA-256 del file come [ByteArray.sha256Hex], letto a blocchi; si ferma se la coroutine viene annullata. */
suspend fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        // File da centinaia di MB: blocchi piu' grandi degli 8 KiB di DEFAULT_BUFFER_SIZE, meno chiamate di sistema.
        val buffer = ByteArray(FILE_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}

private const val FILE_BUFFER_SIZE = 64 * 1024
