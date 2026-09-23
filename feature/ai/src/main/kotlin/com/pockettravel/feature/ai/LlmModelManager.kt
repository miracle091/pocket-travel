package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.di.AiModelsDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ModelIntegrityException(message: String) : Exception(message)

/** Errore non recuperabile con un retry (es. HTTP 404): a differenza di un errore di rete
 *  transitorio, ritentare la stessa richiesta darebbe sempre lo stesso esito. */
class ModelDownloadFailedException(message: String) : Exception(message)

/** definition.sha256 == null: modello non ancora disponibile per il download (vedi LlmModelDefinition). */
class ModelNotAvailableException(message: String) : Exception(message)

/**
 * Modello IA on-device: scaricato solo su richiesta, liberabile dall'utente in un tocco —
 * vedi "Vincoli tecnici" nella specifica tecnica (quantizzato, consigliato sotto 1,5 GB).
 * Generalizzato a un catalogo di modelli (LlmModelCatalog): ogni metodo prende il
 * [LlmModelDefinition] su cui operare invece di leggere un unico modello fisso, cosi' il
 * chiamante (risolto da AiSettingsStore.selectedModelId) resta l'unica fonte di verita' su
 * quale modello e' "quello attivo" — nessuno stato duplicato qui dentro.
 */
class LlmModelManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @AiModelsDir private val modelsDir: File,
    private val coordinator: AiModelCoordinator,
) {
    fun modelFile(definition: LlmModelDefinition): File = File(modelsDir, definition.fileName)
    private fun partFile(definition: LlmModelDefinition): File = File(modelsDir, "${definition.fileName}.part")

    fun isDownloaded(definition: LlmModelDefinition): Boolean = modelFile(definition).exists()

    fun sizeOnDisk(definition: LlmModelDefinition): Long =
        modelFile(definition).let { if (it.exists()) it.length() else 0L }

    fun availableStorageBytes(): Long = modelsDir.usableSpace

    suspend fun download(
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        // Fallisce prima di aprire la connessione (non dopo centinaia di MB scaricati e
        // scartati): un sha256 nullo qui indica un modello non ancora pubblicato, non un
        // problema di rete o di integrità del file.
        if (definition.sha256 == null) {
            throw ModelNotAvailableException("Il modello «${definition.displayName}» non è ancora disponibile per il download.")
        }
        coordinator.withModelLock {
            withContext(Dispatchers.IO) {
                downloadAndVerify(definition, onProgress)
            }
        }
    }

    /**
     * Un solo modello on-device installato alla volta: se un altro modello e' gia' presente su
     * disco, va eliminato (e il motore rilasciato) prima di scaricare quello nuovo — evita di
     * accumulare piu' file da centinaia di MB/pochi GB ciascuno in parallelo.
     */
    suspend fun selectAndDownload(
        newDefinition: LlmModelDefinition,
        currentlyInstalled: LlmModelDefinition?,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        if (currentlyInstalled != null && currentlyInstalled.id != newDefinition.id) {
            delete(currentlyInstalled)
        }
        download(newDefinition, onProgress)
    }

    // internal (non private) cosi' un test puo' esercitare il resume Range/la classificazione
    // errori senza dover passare per il Mutex di AiModelCoordinator — stesso approccio di
    // RegionPackageDownloader.downloadAndVerify.
    internal suspend fun downloadAndVerify(
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        val partFile = partFile(definition)
        val modelFile = modelFile(definition)
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        val requestBuilder = Request.Builder().url(definition.url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val request = requestBuilder.build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Stessa classificazione di RegionPackageDownloader: un 4xx (tranne 408/429,
                // tipicamente transitori) non cambierebbe ritentando la stessa richiesta; un
                // errore di rete o un 5xx invece sì, il Worker chiamante puo' ritentare.
                if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                    throw ModelDownloadFailedException("Download modello fallito: HTTP ${response.code}")
                }
                throw IOException("Download modello fallito: HTTP ${response.code}")
            }
            val body = checkNotNull(response.body) { "Corpo vuoto" }

            val resumed = response.code == 206 && existingBytes > 0
            if (existingBytes > 0 && response.code == 206) {
                require(response.header("Content-Range")?.startsWith("bytes $existingBytes-", ignoreCase = true) == true) {
                    "Risposta range non valida per il modello"
                }
            }
            val baseBytes = if (resumed) existingBytes else 0L
            val total = baseBytes + body.contentLength().coerceAtLeast(0L)

            var downloaded = baseBytes
            var lastReported = downloaded
            FileOutputStream(partFile, resumed).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            onProgress(downloaded, total)
        }

        // Hash calcolato sul .part completo, non solo sui byte scaricati in questa chiamata:
        // dopo una ripresa i byte gia' presenti prima di questa chiamata non sono mai passati
        // per il digest di questa esecuzione.
        val actualSha256 = sha256Of(partFile)
        if (!actualSha256.equals(definition.sha256, ignoreCase = true)) {
            partFile.delete()
            throw ModelIntegrityException(
                "Checksum del modello non valido: atteso ${definition.sha256}, ottenuto $actualSha256",
            )
        }
        check(partFile.renameTo(modelFile)) { "Impossibile installare il modello" }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (stream.read(buffer) != -1) {
                // il digest si aggiorna come effetto collaterale di DigestInputStream.read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    suspend fun delete(definition: LlmModelDefinition): Boolean = coordinator.withModelLock { deleteWithoutLock(definition) }

    internal fun deleteWithoutLock(definition: LlmModelDefinition): Boolean = modelFile(definition).delete()

    /**
     * Elimina da [modelsDir] ogni file che non e' un modello del catalogo attuale ne' il suo `.part`:
     * es. i `.litertlm` scaricati prima del passaggio a llama.cpp, che nessun altro codice
     * referenzia piu' e che occuperebbero 1,6–3,9 GB per sempre. Senza lock: i file del catalogo
     * (compreso un download in corso) non vengono mai toccati, quindi non c'e' corsa con download().
     */
    fun deleteOrphanedFiles() {
        val keep = LlmModelCatalog.ALL.flatMap { listOf(it.fileName, "${it.fileName}.part") }.toSet()
        modelsDir.listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { it.delete() }
    }

    private companion object {
        const val PROGRESS_STEP_BYTES = 1_000_000L
    }
}
