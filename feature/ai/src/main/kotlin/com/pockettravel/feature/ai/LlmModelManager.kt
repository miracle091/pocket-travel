package com.pockettravel.feature.ai

import android.os.storage.StorageManager
import com.pockettravel.core.data.allocatableBytes
import com.pockettravel.core.data.reserveSpace
import com.pockettravel.feature.ai.di.AiModelsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

class ModelIntegrityException(message: String) : Exception(message)

/** Errore non recuperabile con un retry (es. HTTP 404): a differenza di un errore di rete
 *  transitorio, ritentare la stessa richiesta darebbe sempre lo stesso esito. */
class ModelDownloadFailedException(message: String) : Exception(message)

/** definition.sha256 == null: modello non ancora disponibile per il download (vedi LlmModelDefinition). */
class ModelNotAvailableException(message: String) : Exception(message)

/**
 * Modello IA on-device: scaricato solo su richiesta, liberabile dall'utente in un tocco
 * (quantizzato, consigliato sotto 1,5 GB).
 * Generalizzato a un catalogo di modelli (LlmModelCatalog): ogni metodo prende il
 * [LlmModelDefinition] su cui operare invece di leggere un unico modello fisso, cosi' il
 * chiamante (risolto da AiSettingsStore.selectedModelId) resta l'unica fonte di verita' su
 * quale modello e' "quello attivo" — nessuno stato duplicato qui dentro.
 */
@Singleton
class LlmModelManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @AiModelsDir private val modelsDir: File,
    private val coordinator: AiModelCoordinator,
    // null nei test: lo spazio disponibile e' solo quello libero.
    private val storageManager: StorageManager? = null,
) {
    fun modelFile(definition: LlmModelDefinition): File = File(modelsDir, definition.fileName)
    private fun partFile(definition: LlmModelDefinition): File = File(modelsDir, "${definition.fileName}.part")

    // sha256 verificato al momento dell'installazione: isDownloaded guarda solo l'esistenza del file, e
    // senza questo non si saprebbe se il modello su disco e' la versione pubblicata oggi (vedi installedSha256).
    private fun shaFile(definition: LlmModelDefinition): File = File(modelsDir, "${definition.fileName}.sha256")

    fun isDownloaded(definition: LlmModelDefinition): Boolean = modelFile(definition).exists()

    // Id dei modelli del catalogo presenti su disco, aggiornato a ogni installazione/eliminazione
    // (per questo la classe e' un singleton: una sola copia dello stato).
    private val _downloadedModelIds = MutableStateFlow(scanDownloadedModelIds())
    val downloadedModelIds: StateFlow<Set<String>> = _downloadedModelIds.asStateFlow()

    private fun scanDownloadedModelIds(): Set<String> =
        LlmModelCatalog.ALL.filter { isDownloaded(it) }.map { it.id }.toSet()

    fun sizeOnDisk(definition: LlmModelDefinition): Long =
        modelFile(definition).let { if (it.exists()) it.length() else 0L }

    fun availableStorageBytes(): Long = modelsDir.allocatableBytes(storageManager)

    suspend fun download(
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        // Fallisce prima di aprire la connessione (non dopo centinaia di MB scaricati e
        // scartati): un sha256 nullo qui indica un modello non ancora pubblicato, non un
        // problema di rete o di integrita' del file.
        if (definition.sha256 == null) {
            throw ModelNotAvailableException("Il modello «${definition.displayName}» non è ancora disponibile per il download.")
        }
        // Rete e verifica senza il lock del modello: durano minuti (1-2,5 GB) e lavorano solo sul .part, che
        // nessun altro tocca; intanto l'assistente puo' rispondere col modello installato. Il lock serve
        // solo al passaggio finale, per non sostituire un file che il motore sta caricando o liberando.
        val verified = withContext(Dispatchers.IO) {
            // Il .part di un modello abbandonato (scelto, scaricato in parte, poi cambiato) occuperebbe fino a 2,5 GB per sempre.
            deletePartFilesExcept(definition)
            downloadAndVerify(definition, onProgress)
        }
        coordinator.withModelLock {
            withContext(Dispatchers.IO) {
                // Prima del nuovo file: uno sha vecchio accanto a un modello nuovo farebbe credere installata la versione sbagliata.
                shaFile(definition).delete()
                check(verified.renameTo(modelFile(definition))) { "Impossibile installare il modello" }
                shaFile(definition).writeText(requireNotNull(definition.sha256).lowercase())
            }
            _downloadedModelIds.value = scanDownloadedModelIds()
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

    // Scarica e verifica il .part e lo restituisce; lo installa download(). internal (non private) cosi'
    // un test puo' esercitare il resume Range/la classificazione errori — stesso approccio di
    // RegionPackageDownloader.downloadAndVerify.
    internal suspend fun downloadAndVerify(
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ): File {
        val partFile = partFile(definition)
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        // .part gia' completo (processo chiuso fra l'ultimo byte e la verifica): niente richiesta, il server
        // risponderebbe 416 e si riscaricherebbe tutto da zero; basta verificare lo sha256.
        if (definition.sizeBytes > 0 && existingBytes == definition.sizeBytes) {
            onProgress(existingBytes, existingBytes)
        } else {
            fetchPart(definition, partFile, existingBytes, onProgress)
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
        return partFile
    }

    // Scarica (o riprende con Range) il .part fino alla fine, senza verificarlo.
    private suspend fun fetchPart(
        definition: LlmModelDefinition,
        partFile: File,
        existingBytes: Long,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        modelsDir.reserveSpace(storageManager, definition.sizeBytes - existingBytes)
        val requestBuilder = Request.Builder().url(definition.url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val request = requestBuilder.build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw failedResponse(response.code, partFile, existingBytes)
            val body = response.body

            val resumed = response.code == 206 && existingBytes > 0
            if (resumed) {
                require(response.header("Content-Range")?.startsWith("bytes $existingBytes-", ignoreCase = true) == true) {
                    "Risposta range non valida per il modello"
                }
            }
            val baseBytes = if (resumed) existingBytes else 0L
            val total = baseBytes + body.contentLength().coerceAtLeast(0L)
            val downloaded = body.byteStream().use { input -> writePart(input, partFile, resumed, baseBytes, total, definition, onProgress) }
            onProgress(downloaded, total)
        }
    }

    /**
     * L'errore per una risposta non riuscita. .part gia' completo (processo chiuso fra l'ultimo byte e la verifica):
     * il server risponde 416 al Range, e senza cancellarlo ogni nuovo tentativo fallirebbe uguale. Stessa
     * classificazione di RegionPackageDownloader: un 4xx (tranne 408/429, tipicamente transitori) non cambierebbe
     * ritentando la stessa richiesta; un errore di rete o un 5xx invece si', il Worker chiamante puo' ritentare.
     */
    private fun failedResponse(code: Int, partFile: File, existingBytes: Long): Exception = when {
        code == HTTP_RANGE_NOT_SATISFIABLE && existingBytes > 0 -> {
            partFile.delete()
            IOException("Download modello da ricominciare: HTTP $code")
        }
        code in HTTP_CLIENT_ERRORS && code != HTTP_REQUEST_TIMEOUT && code != HTTP_TOO_MANY_REQUESTS ->
            ModelDownloadFailedException("Download modello fallito: HTTP $code")
        else -> IOException("Download modello fallito: HTTP $code")
    }

    /** Scrive [input] in [partFile] (in coda se [resumed]) e restituisce i byte del file a fine download. */
    private suspend fun writePart(
        input: InputStream,
        partFile: File,
        resumed: Boolean,
        baseBytes: Long,
        total: Long,
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ): Long {
        var downloaded = baseBytes
        var lastReported = downloaded
        FileOutputStream(partFile, resumed).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                downloaded += read
                // downloaded include gia' i byte del .part ripreso: sizeBytes e' la dimensione
                // del file intero. Oltre quella il server sta mandando altro (file cambiato,
                // risposta sbagliata): si interrompe subito invece di riempire il disco fino a EOF.
                if (definition.sizeBytes > 0 && downloaded > definition.sizeBytes) {
                    output.close()
                    partFile.delete()
                    throw ModelDownloadFailedException("Download modello interrotto: oltre i ${definition.sizeBytes} byte attesi")
                }
                if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                    lastReported = downloaded
                    onProgress(downloaded, total)
                }
            }
        }
        return downloaded
    }

    /**
     * sha256 del file installato di [definition], null se non e' scaricato. Quello salvato da [download];
     * per le installazioni precedenti, senza sha salvato, si calcola una volta e si salva. Da una coroutine
     * (hash di 1-2,5 GB su IO, senza il lock del modello: se il file cambia nel frattempo, null).
     */
    suspend fun installedSha256(definition: LlmModelDefinition): String? = withContext(Dispatchers.IO) {
        val model = modelFile(definition)
        if (!model.exists()) return@withContext null
        val shaFile = shaFile(definition)
        val saved = if (shaFile.exists()) shaFile.readText().trim() else ""
        if (saved.length == SHA256_HEX_LENGTH) return@withContext saved
        val stamp = model.lastModified() to model.length()
        val computed = sha256Of(model)
        if (!model.exists() || stamp != (model.lastModified() to model.length())) return@withContext null
        shaFile.writeText(computed)
        computed
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

    internal fun deleteWithoutLock(definition: LlmModelDefinition): Boolean =
        modelFile(definition).delete().also {
            shaFile(definition).delete()
            _downloadedModelIds.value = scanDownloadedModelIds()
        }

    /**
     * Elimina da [modelsDir] ogni file che non e' un modello del catalogo attuale (col suo sha256 salvato)
     * ne' il `.part` del modello [selected]: es. i `.litertlm` scaricati prima del passaggio a llama.cpp,
     * o il parziale di un modello poi abbandonato, che nessun altro codice referenzia piu' e che
     * occuperebbero fino a 2,5 GB per sempre. Senza lock: i modelli del catalogo e il download in corso
     * (quello del modello scelto) non vengono mai toccati, quindi non c'e' corsa con download().
     */
    fun deleteOrphanedFiles(selected: LlmModelDefinition) {
        val keep = LlmModelCatalog.ALL.flatMap { listOf(it.fileName, "${it.fileName}.sha256") }.toSet() +
            partFile(selected).name
        modelsDir.listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { it.delete() }
        _downloadedModelIds.value = scanDownloadedModelIds()
    }

    private fun deletePartFilesExcept(keep: LlmModelDefinition) {
        LlmModelCatalog.ALL.filter { it.id != keep.id }.forEach { partFile(it).delete() }
    }

    private companion object {
        const val PROGRESS_STEP_BYTES = 1_000_000L
        const val SHA256_HEX_LENGTH = 64
    }
}

private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_CLIENT_ERRORS = 400..499
