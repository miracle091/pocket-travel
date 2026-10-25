package com.pockettravel.core.sync

import java.io.IOException
import javax.inject.Inject

/** online-models.json: i modelli proposti per i servizi online. */
interface OnlineModelsClient {
    /** Scarica online-models.json, lo valida e ne verifica firma e `publishedAt`; restituisce i byte del JSON. */
    suspend fun fetchOnlineModels(): ByteArray
}

class HttpOnlineModelsClient @Inject constructor(
    private val signedFileCache: SignedFileCache,
) : OnlineModelsClient {
    // Validato prima della firma: la verifica salva publishedAt, e un file firmato ma non valido come elenco (es. un
    // altro file del progetto servito a questo indirizzo) bloccherebbe gli elenchi successivi.
    override suspend fun fetchOnlineModels(): ByteArray = signedFileCache.fetch(
        SyncConfig.ONLINE_MODELS_URL,
        httpError = { throw IOException("Online models fetch failed: HTTP $it") },
        emptyBody = { throw IOException("Empty online models response") },
    ) { bytes -> bytes.also(::parseOnlineModels) }
}
