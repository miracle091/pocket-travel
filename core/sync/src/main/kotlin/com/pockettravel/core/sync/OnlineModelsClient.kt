package com.pockettravel.core.sync

import com.pockettravel.core.data.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/** online-models.json: i modelli proposti per i servizi online. */
interface OnlineModelsClient {
    /** Scarica online-models.json, lo valida e ne verifica firma e `publishedAt`; restituisce i byte del JSON. */
    suspend fun fetchOnlineModels(): ByteArray
}

class HttpOnlineModelsClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val signatureVerifier: ManifestSignatureVerifier,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : OnlineModelsClient {
    override suspend fun fetchOnlineModels(): ByteArray {
        val bytes = withContext(ioDispatcher) {
            okHttpClient.newCall(Request.Builder().url(SyncConfig.ONLINE_MODELS_URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Online models fetch failed: HTTP ${response.code}")
                response.body.bytes()
            }
        }
        if (bytes.isEmpty()) throw IOException("Empty online models response")
        // Prima della firma: la verifica salva publishedAt, e un file firmato ma non valido come elenco (es. un
        // altro file del progetto servito a questo indirizzo) bloccherebbe gli elenchi successivi.
        parseOnlineModels(bytes)
        signatureVerifier.verify(SyncConfig.ONLINE_MODELS_URL, bytes)
        return bytes
    }
}
