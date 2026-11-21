package com.pockettravel.core.translate

import java.io.File
import java.io.IOException

/**
 * Decompressore zstd (BSD-3-Clause) compilato in `libbergamot-jni.so`: i modelli di Firefox Translations arrivano
 * dalla CDN come file `.zst`. Legge e scrive a blocchi, senza tenere un file intero in memoria.
 */
object Zstd {
    /**
     * Decomprime [source] in [target], che si sovrascrive. Lancia [IOException] con il motivo se il file non e' zstd
     * valido o e' troncato, o se la scrittura fallisce (disco pieno): [target] resta scritto a meta' e va cancellato.
     */
    @Throws(IOException::class)
    fun decompress(source: File, target: File) {
        val error = BergamotNative.zstdDecompress(
            source.absolutePath.toByteArray(Charsets.UTF_8),
            target.absolutePath.toByteArray(Charsets.UTF_8),
        )
        if (error != null) throw IOException("Decompressione di ${source.name} fallita: $error")
    }
}
