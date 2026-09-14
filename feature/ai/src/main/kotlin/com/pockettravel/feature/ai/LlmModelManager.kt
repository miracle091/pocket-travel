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

class ModelAuthException(message: String) : Exception(message)

/** Errore non recuperabile con un retry (es. HTTP 404): a differenza di un errore di rete
 *  transitorio, ritentare la stessa richiesta darebbe sempre lo stesso esito. */
class ModelDownloadFailedException(message: String) : Exception(message)

/**
 * Modello IA on-device: scaricato solo su richiesta, liberabile dall'utente in un tocco —
 * vedi "Vincoli tecnici" nella specifica tecnica (quantizzato, consigliato sotto 1,5 GB).
 */
class LlmModelManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @AiModelsDir private val modelsDir: File,
    private val coordinator: AiModelCoordinator,
) {
    val modelFile: File get() = File(modelsDir, AiModelConfig.MODEL_FILE_NAME)
    private val partFile: File get() = File(modelsDir, "${AiModelConfig.MODEL_FILE_NAME}.part")

    fun isDownloaded(): Boolean = modelFile.exists()

    fun sizeOnDisk(): Long = if (modelFile.exists()) modelFile.length() else 0L

    fun availableStorageBytes(): Long = modelsDir.usableSpace

    // modelUrl/expectedSha256 hanno un default di produzione ma restano parametri (non
    // AiModelConfig letto direttamente nel corpo) cosi' un test puo' verificare il download e
    // la verifica del checksum contro un server HTTP locale, senza scaricare davvero 584 MB da
    // un repo HuggingFace con licenza gated.
    suspend fun download(
        modelUrl: String = AiModelConfig.MODEL_URL,
        expectedSha256: String = AiModelConfig.MODEL_SHA256,
        hfToken: String? = null,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        coordinator.withModelLock {
            withContext(Dispatchers.IO) {
                downloadAndVerify(modelUrl, expectedSha256, hfToken, onProgress)
            }
        }
    }

    // internal (non private) cosi' un test puo' esercitare il resume Range/la classificazione
    // errori senza dover passare per il Mutex di AiModelCoordinator — stesso approccio di
    // RegionPackageDownloader.downloadAndVerify.
    internal suspend fun downloadAndVerify(
        modelUrl: String,
        expectedSha256: String,
        hfToken: String?,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        val requestBuilder = Request.Builder().url(modelUrl)
        if (!hfToken.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $hfToken")
        }
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val request = requestBuilder.build()

        okHttpClient.newCall(request).execute().use { response ->
            // Repo HuggingFace gated (vedi AiModelConfig): 401/403 significano quasi
            // sempre token mancante/errato o licenza Gemma non accettata, non un guasto
            // generico — messaggio dedicato cosi' la UI puo' guidare l'utente a sistemare
            // il token invece di un "riprova più tardi" fuorviante.
            if (response.code == 401 || response.code == 403) {
                throw ModelAuthException(
                    "Accesso negato (HTTP ${response.code}): verifica il token HuggingFace e di aver accettato la licenza del modello su huggingface.co.",
                )
            }
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
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            partFile.delete()
            throw ModelIntegrityException(
                "Checksum del modello non valido: atteso $expectedSha256, ottenuto $actualSha256",
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

    suspend fun delete(): Boolean = coordinator.withModelLock { deleteWithoutLock() }

    internal fun deleteWithoutLock(): Boolean = modelFile.delete()

    private companion object {
        const val PROGRESS_STEP_BYTES = 1_000_000L
    }
}
