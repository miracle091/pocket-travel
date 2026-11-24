package com.pockettravel.feature.ai

import android.os.storage.StorageManager
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.allocatableBytes
import com.pockettravel.core.data.reserveSpace
import com.pockettravel.core.data.sha256Hex
import com.pockettravel.core.sync.SegmentedDownload
import com.pockettravel.core.sync.SegmentedDownloadException
import com.pockettravel.feature.ai.di.AiModelsDir
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementazione di [LlmModelManager] su file: i modelli stanno in [modelsDir], scaricati con OkHttp (ripresa
 * con Range e verifica sha256).
 */
@Singleton
class FileLlmModelManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @AiModelsDir private val modelsDir: File,
    private val coordinator: AiModelCoordinator,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    // null nei test: lo spazio disponibile e' solo quello libero.
    private val storageManager: StorageManager? = null,
) : LlmModelManager {
    private val importLock = Mutex()

    // internal (non private): i test la sostituiscono con segmenti piccoli.
    internal var segmentedDownload = SegmentedDownload(okHttpClient)

    // Un download alla volta per file: dopo "Annulla" e "Scarica" il worker vecchio puo' ancora essere nella lettura
    // di rete mentre il nuovo apre lo stesso .part; il nuovo aspetta che il vecchio finisca.
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    override fun modelFile(definition: LlmModelDefinition): File = File(modelsDir, definition.fileName)
    private fun partFile(definition: LlmModelDefinition): File = File(modelsDir, "${definition.fileName}.part")

    // sha256 atteso quando il .part e' stato iniziato: se il file viene ricaricato con lo stesso nome (impronta nuova in
    // app-status.json), il parziale della versione vecchia non va ripreso, darebbe un file misto che fallisce la verifica.
    private fun partShaFile(definition: LlmModelDefinition): File = File(modelsDir, "${definition.fileName}.part.sha256")

    // Mappa dei segmenti finiti del .part scaricato a piu' connessioni (vedi SegmentedDownload).
    private fun partMapFile(definition: LlmModelDefinition): File = SegmentedDownload.mapFile(partFile(definition))

    // sha256 verificato al momento dell'installazione: isDownloaded guarda solo l'esistenza del file, e
    // senza questo non si saprebbe se il modello su disco e' la versione pubblicata oggi (vedi installedSha256).
    private fun shaFile(definition: LlmModelDefinition): File = File(modelsDir, "${definition.fileName}.sha256")

    override fun isDownloaded(definition: LlmModelDefinition): Boolean = modelFile(definition).exists()

    override fun mmprojFile(definition: LlmModelDefinition): File? = definition.mmproj?.let { File(modelsDir, it.fileName) }

    override fun isMmprojDownloaded(definition: LlmModelDefinition): Boolean = mmprojFile(definition)?.exists() == true

    // Id delle voci del catalogo dei modelli presenti su disco, aggiornato a ogni installazione/eliminazione
    // (per questo la classe e' un singleton: una sola copia dello stato).
    private val _downloadedModelIds = MutableStateFlow(scanDownloadedModelIds())
    override val downloadedModelIds: StateFlow<Set<String>> = _downloadedModelIds.asStateFlow()

    private fun scanDownloadedModelIds(): Set<String> =
        LlmModelCatalog.ALL.filter { isDownloaded(it) }.map { it.id }.toSet()

    override fun sizeOnDisk(definition: LlmModelDefinition): Long =
        modelFile(definition).let { if (it.exists()) it.length() else 0L }

    override fun availableStorageBytes(): Long = modelsDir.allocatableBytes(storageManager)

    override suspend fun download(
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        download(definition, replaces = null, onInstalled = {}, onProgress)
    }

    // [replaces]: il modello installato che il nuovo sostituisce, eliminato solo a download verificato.
    private suspend fun download(
        definition: LlmModelDefinition,
        replaces: LlmModelDefinition?,
        onInstalled: () -> Unit,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        // Fallisce prima di aprire la connessione (non dopo centinaia di MB scaricati e
        // scartati): un sha256 nullo qui indica un modello non ancora pubblicato, non un
        // problema di rete o di integrita' del file.
        if (definition.sha256 == null) {
            throw ModelNotAvailableException("Il modello «${definition.displayName}» non è ancora disponibile per il download.")
        }
        // Rete e verifica senza il lock del modello: durano minuti (1-2,5 GB) e lavorano solo sul .part, che
        // nessun altro tocca; intanto il modello gia' installato resta su disco. Il lock serve
        // solo al passaggio finale, per non sostituire un file che il motore sta caricando o liberando.
        val verified = withContext(ioDispatcher) {
            // Il .part di un modello abbandonato (scelto, scaricato in parte, poi cambiato) occuperebbe fino a 2,5 GB per sempre.
            deletePartFilesExcept(definition)
            downloadAndVerify(definition, onProgress)
        }
        install(verified, definition, requireNotNull(definition.sha256).lowercase(), replaces = replaces, onInstalled = onInstalled)
    }

    override suspend fun downloadMmproj(
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        val mmproj = requireNotNull(definition.mmproj) { "Il modello «${definition.displayName}» non legge le foto" }
        // Stesso scarico del modello: Range, .part e verifica sha256; il file ha il suo nome e non entra nel catalogo.
        val file = mmproj.asDownload()
        val verified = withContext(ioDispatcher) { downloadAndVerify(file, onProgress) }
        install(verified, file, mmproj.sha256.lowercase())
    }

    private fun MmprojDefinition.asDownload() = LlmModelDefinition(
        id = fileName, displayName = fileName, url = url, fileName = fileName, sha256 = sha256, sizeBytes = sizeBytes,
        minRamTier = RamTier.CONFORTEVOLE,
    )

    // Passaggio finale comune a download e importazione: sostituisce il file del modello con quello verificato.
    // [onInstalled] gira nel lock, col nuovo file gia' al suo posto e prima di eliminare [replaces]: chi sceglie il
    // modello in uso non vede mai un modello senza file.
    private suspend fun install(
        verified: File,
        definition: LlmModelDefinition,
        sha256: String,
        deleteOthers: Boolean = false,
        replaces: LlmModelDefinition? = null,
        onInstalled: () -> Unit = {},
    ) {
        coordinator.withModelLock {
            if (deleteOthers) {
                LlmModelCatalog.ALL.filter { it.id != definition.id && isDownloaded(it) }.forEach { deleteFiles(it, keepMmprojOf = definition) }
            }
            withContext(ioDispatcher) {
                // Prima del nuovo file: uno sha vecchio accanto a un modello nuovo farebbe credere installata la versione sbagliata.
                shaFile(definition).delete()
                check(verified.renameTo(modelFile(definition))) { "Impossibile installare il modello" }
                shaFile(definition).writeText(sha256)
                partShaFile(definition).delete()
            }
            onInstalled()
            if (replaces != null && replaces.id != definition.id) deleteWithoutLock(replaces)
            _downloadedModelIds.value = scanDownloadedModelIds()
        }
    }

    override suspend fun importModel(
        source: InputStream,
        displayName: String?,
        sizeBytes: Long,
        candidates: List<LlmModelDefinition>,
    ): LlmModelDefinition {
        // Spazio per lo staging controllato prima di copiare, come per il download.
        if (sizeBytes > 0 && availableStorageBytes() < sizeBytes) {
            throw ModelNoSpaceException("Spazio insufficiente per importare il modello")
        }
        // Un'importazione alla volta: due copie nello stesso file di staging si mescolerebbero.
        return importLock.withLock {
            val staging = File(modelsDir, IMPORT_STAGING_NAME)
            var installed = false
            try {
                val maxBytes = candidates.maxOfOrNull { it.sizeBytes } ?: 0L
                val (sha256, copied) = withContext(ioDispatcher) {
                    modelsDir.reserveSpace(storageManager, sizeBytes)
                    copyWithSha256(source, staging, maxBytes)
                }
                val definition = classifyImport(sha256, copied, displayName, candidates).definitionOrThrow(sha256)
                // Un solo modello installato alla volta, come in selectAndDownload.
                install(staging, definition, sha256, deleteOthers = true)
                installed = true
                definition
            } finally {
                // Staging parziale, scartato o non installato: non deve restare su disco.
                if (!installed) withContext(NonCancellable + ioDispatcher) { staging.delete() }
            }
        }
    }

    // Copia [input] in [target] e restituisce sha256 (minuscolo) e byte copiati; l'sha256 si calcola durante la copia.
    // Oltre [maxBytes] (il modello piu' grande del catalogo) si ferma: il file non e' un modello e non deve riempire il disco.
    private fun copyWithSha256(input: InputStream, target: File, maxBytes: Long): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use { stream ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    total += read
                    if (total > maxBytes) throw ModelUnknownFileException("File piu' grande di ogni modello del catalogo")
                }
            }
        }
        return digest.digest().toHexString() to total
    }

    /**
     * Un solo modello sul dispositivo installato alla volta: [currentlyInstalled] resta su disco finche' quello
     * nuovo non e' scaricato e verificato, poi si elimina (nel lock del modello, col motore rilasciato). Durante il
     * download servono quindi i byte di tutti e due: lo spazio libero si controlla prima di accodare il download.
     */
    override suspend fun selectAndDownload(
        newDefinition: LlmModelDefinition,
        currentlyInstalled: LlmModelDefinition?,
        onInstalled: () -> Unit,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        download(newDefinition, currentlyInstalled, onInstalled, onProgress)
    }

    // Scarica e verifica il .part e lo restituisce; lo installa download(). internal (non private) cosi'
    // un test puo' esercitare il resume Range/la classificazione errori — stesso approccio di
    // RegionPackageDownloader.downloadAndVerify.
    internal suspend fun downloadAndVerify(
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ): File = downloadLocks.getOrPut(definition.fileName) { Mutex() }.withLock { downloadAndVerifyLocked(definition, onProgress) }

    private suspend fun downloadAndVerifyLocked(
        definition: LlmModelDefinition,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ): File {
        val partFile = partFile(definition)
        val partShaFile = partShaFile(definition)
        val expectedSha256 = definition.sha256.orEmpty().lowercase()
        val mapFile = partMapFile(definition)
        if (partShaFile.exists() && partShaFile.readText().trim() != expectedSha256) {
            partFile.delete()
            mapFile.delete()
        }
        partShaFile.writeText(expectedSha256)
        // Un .part senza mappa e' di un tentativo a una connessione (anche del codice vecchio): si riprende in linea.
        val segmented = (partFile.length() == 0L || mapFile.exists()) && fetchSegmented(definition, partFile, expectedSha256, onProgress)
        // Mappa e .part preallocato di un server che ora non regge piu' i segmenti: non sono un file parziale in linea.
        if (!segmented && mapFile.exists()) {
            partFile.delete()
            mapFile.delete()
        }
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        // .part gia' completo (processo chiuso fra l'ultimo byte e la verifica): niente richiesta, il server
        // risponderebbe 416 e si riscaricherebbe tutto da zero; basta verificare lo sha256.
        if (!segmented && definition.sizeBytes > 0 && existingBytes == definition.sizeBytes) {
            onProgress(existingBytes, existingBytes)
        } else if (!segmented) {
            fetchPart(definition, partFile, existingBytes, onProgress)
        }

        // Hash calcolato sul .part completo, non solo sui byte scaricati in questa chiamata:
        // dopo una ripresa i byte gia' presenti prima di questa chiamata non sono mai passati
        // per il digest di questa esecuzione.
        val actualSha256 = partFile.sha256Hex()
        if (!actualSha256.equals(definition.sha256, ignoreCase = true)) {
            partFile.delete()
            throw ModelIntegrityException(
                "Checksum del modello non valido: atteso ${definition.sha256}, ottenuto $actualSha256",
            )
        }
        return partFile
    }

    // false: file piccolo o server senza intervalli, si scarica in linea con fetchPart.
    private suspend fun fetchSegmented(
        definition: LlmModelDefinition,
        partFile: File,
        sha256: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        if (definition.sizeBytes <= 0) return false
        // Il .part preallocato (sparso) ha gia' la dimensione piena: lo spazio si controlla solo alla prima volta.
        if (!partFile.exists()) modelsDir.reserveSpace(storageManager, definition.sizeBytes)
        return try {
            segmentedDownload.fetch(definition.url, partFile, definition.sizeBytes, sha256, PROGRESS_STEP_BYTES) {
                onProgress(it, definition.sizeBytes)
            }
        } catch (e: SegmentedDownloadException) {
            throw ModelDownloadFailedException("Download modello fallito: ${e.message}", e)
        }
    }

    // Scarica (o riprende con Range) il .part fino alla fine, senza verificarlo.
    private suspend fun fetchPart(
        definition: LlmModelDefinition,
        partFile: File,
        existingBytes: Long,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        modelsDir.reserveSpace(storageManager, definition.sizeBytes - existingBytes)
        resumableDownload(okHttpClient, definition.url, partFile, definition.sizeBytes, "modello", onProgress)
    }

    /**
     * sha256 del file installato di [definition], null se non e' scaricato. Quello salvato da [download];
     * per le installazioni precedenti, senza sha salvato, si calcola una volta e si salva. Da una coroutine
     * (hash di 1-2,5 GB su IO, senza il lock del modello: se il file cambia nel frattempo, null).
     */
    override suspend fun installedSha256(definition: LlmModelDefinition): String? = withContext(ioDispatcher) {
        val model = modelFile(definition)
        if (!model.exists()) return@withContext null
        val shaFile = shaFile(definition)
        val saved = if (shaFile.exists()) shaFile.readText().trim() else ""
        if (saved.length == SHA256_HEX_LENGTH) return@withContext saved
        val stamp = model.lastModified() to model.length()
        val computed = model.sha256Hex()
        if (!model.exists() || stamp != (model.lastModified() to model.length())) return@withContext null
        shaFile.writeText(computed)
        computed
    }

    override suspend fun delete(definition: LlmModelDefinition): Boolean = coordinator.withModelLock { deleteWithoutLock(definition) }

    override fun deleteWithoutLock(definition: LlmModelDefinition): Boolean = deleteFiles(definition, keepMmprojOf = null)

    // [keepMmprojOf]: il modello in arrivo, che puo' usare lo stesso mmproj di [definition] senza essere ancora su disco.
    private fun deleteFiles(definition: LlmModelDefinition, keepMmprojOf: LlmModelDefinition?): Boolean =
        modelFile(definition).delete().also {
            shaFile(definition).delete()
            definition.mmproj?.let { mmproj ->
                val shared = LlmModelCatalog.ALL.any {
                    it.id != definition.id && it.mmproj?.fileName == mmproj.fileName && (isDownloaded(it) || it.id == keepMmprojOf?.id)
                }
                if (!shared) deleteMmprojFiles(mmproj)
            }
            _downloadedModelIds.value = scanDownloadedModelIds()
        }

    override suspend fun deleteMmproj(definition: LlmModelDefinition) = coordinator.withModelLock {
        definition.mmproj?.let(::deleteMmprojFiles) ?: Unit
    }

    private fun deleteMmprojFiles(mmproj: MmprojDefinition) {
        listOf("", ".sha256", ".part", ".part.sha256", ".part.map").forEach { File(modelsDir, mmproj.fileName + it).delete() }
    }

    /**
     * Elimina da [modelsDir] ogni file che non e' una voce del catalogo dei modelli attuale (col suo sha256 salvato)
     * ne' il `.part` del modello [selected]: es. i `.litertlm` scaricati prima del passaggio a llama.cpp,
     * o il parziale di un modello poi abbandonato, che nessun altro codice referenzia piu' e che
     * occuperebbero fino a 2,5 GB per sempre. Senza lock: le voci del catalogo dei modelli e il download in corso
     * (quello del modello scelto) non vengono mai toccati, quindi non c'e' corsa con download().
     */
    override fun deleteOrphanedFiles(selected: LlmModelDefinition) {
        // Gli mmproj restano su disco anche se il modello e' un altro: costano centinaia di MB da riscaricare.
        val keep = LlmModelCatalog.ALL.flatMap { listOf(it.fileName, "${it.fileName}.sha256") }.toSet() +
            LlmModelCatalog.ALL.mapNotNull { it.mmproj }.flatMap { listOf(it.fileName, "${it.fileName}.sha256") } +
            partFile(selected).name + partShaFile(selected).name + partMapFile(selected).name +
            listOfNotNull(selected.mmproj).flatMap { listOf("${it.fileName}.part", "${it.fileName}.part.sha256", "${it.fileName}.part.map") }
        modelsDir.listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { it.delete() }
        _downloadedModelIds.value = scanDownloadedModelIds()
    }

    private fun deletePartFilesExcept(keep: LlmModelDefinition) {
        LlmModelCatalog.ALL.filter { it.id != keep.id }.forEach {
            partFile(it).delete()
            partShaFile(it).delete()
            partMapFile(it).delete()
        }
    }

    private companion object {
        const val PROGRESS_STEP_BYTES = 1_000_000L
        const val SHA256_HEX_LENGTH = 64
        const val IMPORT_STAGING_NAME = "import.part"
    }
}

// Il modello del catalogo per un file importato, o l'errore da mostrare: danneggiato o fuori dal catalogo.
private fun ImportVerdict.definitionOrThrow(sha256: String): LlmModelDefinition = when (this) {
    is ImportVerdict.Match -> definition
    ImportVerdict.Damaged -> throw ModelIntegrityException("File del modello danneggiato: sha256 $sha256")
    ImportVerdict.Unknown -> throw ModelUnknownFileException("File fuori dal catalogo dei modelli: sha256 $sha256")
}
