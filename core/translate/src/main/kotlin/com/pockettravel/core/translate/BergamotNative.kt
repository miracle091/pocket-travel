package com.pockettravel.core.translate

/**
 * Funzioni JNI di `libbergamot-jni.so` (`translate_jni.cpp`). Testi e percorsi passano come byte UTF-8, non come
 * String: le funzioni *UTF* di JNI usano il Modified UTF-8 e rompono gli emoji. Si usano solo da
 * [BergamotTranslator], che garantisce che [translate] e [release] non girino insieme sullo stesso handle.
 */
internal object BergamotNative {
    init {
        System.loadLibrary("bergamot-jni")
    }

    /** Handle del modello caricato, 0 se il caricamento fallisce (file mancanti o non validi). [shortlist] puo' essere null. */
    external fun load(model: ByteArray, vocabulary: ByteArray, shortlist: ByteArray?): Long

    /** La traduzione in UTF-8, null se fallisce. */
    external fun translate(handle: Long, text: ByteArray): ByteArray?

    external fun release(handle: Long)

    /** Decomprime il file zstd [source] in [target] (zstd_jni.cpp): null se riesce, altrimenti il motivo. */
    external fun zstdDecompress(source: ByteArray, target: ByteArray): String?
}
