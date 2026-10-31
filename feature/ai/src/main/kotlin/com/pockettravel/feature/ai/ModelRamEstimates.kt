package com.pockettravel.feature.ai

import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.PreferencesFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La RAM stimata di ogni modello del catalogo ([estimateRamBytes]), dai metadati in testa al GGUF: dal file se il
 * modello e' scaricato, altrimenti dai primi 64 KB chiesti al server con una richiesta Range, prima del download.
 * Le stime si salvano per impronta (sha256), cosi' la richiesta si fa una volta sola per versione del modello.
 */
@Singleton
class ModelRamEstimates @Inject constructor(
    preferencesFactory: PreferencesFactory,
    private val okHttpClient: OkHttpClient,
    private val modelManager: LlmModelManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val prefs = preferencesFactory.open(PREFERENCES_NAME)

    /** La stima in byte, o null senza rete, con un file che non e' un GGUF o con metadati che la stima non conosce. */
    suspend fun estimate(definition: LlmModelDefinition): Long? = withContext(ioDispatcher) {
        val key = "$CACHE_VERSION:${definition.sha256 ?: definition.url}"
        if (prefs.contains(key)) return@withContext prefs.getLong(key, 0)
        // Scaricato: il file e il suo peso vero; altrimenti l'intestazione dal server e il peso del catalogo.
        val file = modelManager.modelFile(definition).takeIf { modelManager.isDownloaded(definition) }
        val meta = try {
            (file?.inputStream() ?: openRemoteHeader(definition)).use { GgufMetadata.read(it) }
        } catch (_: IOException) {
            null
        } ?: return@withContext null
        estimateRamBytes(meta, file?.length() ?: definition.sizeBytes)?.also { prefs.edit().putLong(key, it).apply() }
    }

    private fun openRemoteHeader(definition: LlmModelDefinition): InputStream {
        val request = Request.Builder().url(definition.url).header("Range", "bytes=0-${GgufMetadata.DEFAULT_MAX_BYTES - 1}").build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        return response.body.byteStream()
    }

    private companion object {
        const val PREFERENCES_NAME = "model_ram_estimates"

        // Da cambiare quando cambia la formula della stima: le stime salvate con quella vecchia non si leggono piu'.
        const val CACHE_VERSION = 1
    }
}
