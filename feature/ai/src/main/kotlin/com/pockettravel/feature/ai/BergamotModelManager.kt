package com.pockettravel.feature.ai

import android.os.storage.StorageManager
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.reserveSpace
import com.pockettravel.core.data.sha256Hex
import com.pockettravel.core.translate.Zstd
import com.pockettravel.feature.ai.di.AiModelsDir
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** La coppia di modelli di traduzione italiano-inglese (Firefox Translations) sul dispositivo. */
interface BergamotModelManager {
    /** true se tutti i file della coppia sono su disco, interi. */
    val installed: StateFlow<Boolean>

    /** Il file [name] della coppia (le costanti di [BergamotModels]), anche se non e' ancora scaricato. */
    fun file(name: String): File

    /** Byte occupati su disco dai file della coppia gia' presenti. */
    fun sizeOnDisk(): Long

    /**
     * Scarica i file mancanti dalla CDN di Firefox (con ripresa dei `.zst` interrotti), li decomprime e li installa
     * dopo aver controllato lo sha256 del file compresso e di quello decompresso. Un file con l'impronta sbagliata si
     * cancella e lancia [ModelIntegrityException]; un errore HTTP che non passerebbe ritentando, [ModelDownloadFailedException];
     * un errore di rete o un disco pieno, [IOException].
     */
    suspend fun download(onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit)

    /** Elimina la coppia e i file parziali. */
    suspend fun delete()
}

/** Decomprime un file `.zst`; lancia [IOException] se non e' valido, e' troncato o la scrittura fallisce. */
fun interface ZstdDecompressor {
    fun decompress(source: File, target: File)

    companion object {
        /** Il decompressore compilato in `core:translate`. */
        val Native = ZstdDecompressor { source, target -> Zstd.decompress(source, target) }
    }
}

/**
 * [BergamotModelManager] su file, in `models/translate` accanto ai modelli dell'assistente (una sottocartella: la
 * pulizia dei file orfani di [FileLlmModelManager] guarda solo i file di `models`). Per ogni file: scarica il `.zst` in
 * `.zst.part` (ripresa con Range), controlla lo sha256, decomprime in `.tmp`, controlla lo sha256 del risultato e lo
 * rinomina al nome definitivo: un file con il suo nome e la dimensione giusta e' sempre completo e verificato.
 */
@Singleton
class FileBergamotModelManager internal constructor(
    private val okHttpClient: OkHttpClient,
    modelsDir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val zstd: ZstdDecompressor,
    // null nei test: lo spazio disponibile e' solo quello libero.
    private val storageManager: StorageManager?,
    private val baseUrl: String,
    private val files: List<BergamotFile>,
) : BergamotModelManager {

    @Inject
    constructor(
        okHttpClient: OkHttpClient,
        @AiModelsDir modelsDir: File,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        zstd: ZstdDecompressor,
        storageManager: StorageManager?,
    ) : this(okHttpClient, modelsDir, ioDispatcher, zstd, storageManager, BergamotModels.BASE_URL, BergamotModels.FILES)

    private val directory = File(modelsDir, "translate")

    // Un download o un'eliminazione alla volta: i file parziali e quelli finiti non si toccano da due parti.
    private val lock = Mutex()

    private val _installed = MutableStateFlow(scanInstalled())
    override val installed: StateFlow<Boolean> = _installed.asStateFlow()

    override fun file(name: String): File = File(directory, name)

    override fun sizeOnDisk(): Long = files.filter { isComplete(it) }.sumOf { it.bytes }

    private fun isComplete(file: BergamotFile): Boolean = file(file.fileName).let { it.isFile && it.length() == file.bytes }

    private fun scanInstalled(): Boolean = files.all { isComplete(it) }

    override suspend fun download(onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit) {
        lock.withLock {
            withContext(ioDispatcher) {
                directory.mkdirs()
                val pending = files.filterNot { isComplete(it) }
                val total = pending.sumOf { it.compressedBytes }
                var done = 0L
                for (file in pending) {
                    val before = done
                    fetchAndInstall(file) { bytes -> onProgress(before + bytes, total) }
                    done += file.compressedBytes
                }
                onProgress(total, total)
            }
            _installed.value = scanInstalled()
        }
    }

    private suspend fun fetchAndInstall(file: BergamotFile, onProgress: suspend (bytesOfThisFile: Long) -> Unit) {
        val compressed = File(directory, "${file.fileName}.zst.part")
        val decompressed = File(directory, "${file.fileName}.tmp")
        try {
            directory.reserveSpace(storageManager, file.compressedBytes + file.bytes)
            fetch(file, compressed, onProgress)
            verify(compressed, file.compressedBytes, file.compressedSha256, discard = compressed)
            zstd.decompress(compressed, decompressed)
            verify(decompressed, file.bytes, file.sha256, discard = compressed)
            // Un file vecchio con lo stesso nome (dimensione sbagliata) non deve restare se il rename fallisce.
            file(file.fileName).delete()
            check(decompressed.renameTo(file(file.fileName))) { "Impossibile installare ${file.fileName}" }
            compressed.delete()
        } finally {
            decompressed.delete()
        }
    }

    // Dimensione e sha256 di [file]; se non corrispondono cancella [discard] (un file sbagliato non si riprende: il
    // prossimo tentativo riparte da zero) e lancia ModelIntegrityException.
    private suspend fun verify(file: File, bytes: Long, sha256: String, discard: File) {
        if (file.length() != bytes || file.sha256Hex() != sha256) {
            discard.delete()
            throw ModelIntegrityException("Checksum di ${file.name} non valido")
        }
    }

    // Scarica (o riprende con Range) il .zst fino alla fine, senza verificarlo.
    private suspend fun fetch(file: BergamotFile, part: File, onProgress: suspend (Long) -> Unit) {
        if (part.length() > file.compressedBytes) part.delete()
        val existing = part.length()
        // .part gia' completo (processo chiuso fra l'ultimo byte e la verifica): il server risponderebbe 416.
        if (existing == file.compressedBytes) {
            onProgress(existing)
            return
        }
        resumableDownload(okHttpClient, baseUrl + file.attachment, part, file.compressedBytes, file.fileName) { downloaded, _ ->
            onProgress(downloaded)
        }
    }

    override suspend fun delete() {
        lock.withLock {
            withContext(ioDispatcher) { directory.listFiles()?.forEach { it.delete() } }
            _installed.value = scanInstalled()
        }
    }
}
