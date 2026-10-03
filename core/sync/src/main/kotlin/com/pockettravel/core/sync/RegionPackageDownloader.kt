package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class PermanentRegionPackageException(message: String) : Exception(message)

/**
 * Scarica i file del manifest indicati (poi.db, segmenti .rd5 dei percorsi, guides.db) in una
 * cartella di staging, con ripresa tramite HTTP range, e verifica lo SHA-256 di ciascuno rispetto al
 * manifest; il chiamante convalida prima la voce del manifest e poi sposta i file al loro posto.
 */
class RegionPackageDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val regionStorage: RegionStorage,
) {
    /**
     * Sezione critica per id di staging: chiamate concorrenti sullo stesso id (piu' ViewModel che chiedono
     * lo stesso indice, due lavori sulla stessa regione) si accodano: altrimenti scriverebbero lo stesso
     * .part e cleanupStagingExcept cancellerebbe lo staging dell'altra. Chi scarica, decomprime e installa
     * la tiene per tutto il percorso (download + unpackXz + pulizia), non solo per il download. Il Mutex
     * non e' rientrante: dentro il blocco si usa [downloadLocked], mai [download].
     */
    suspend fun <T> withStagingLock(stagingId: String, block: suspend () -> T): T =
        locks.getOrPut(stagingId) { Mutex() }.withLock { block() }

    suspend fun download(
        regionId: String,
        stagingVersion: String,
        files: List<RegionManifestFile>,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withStagingLock(regionId) { downloadLocked(regionId, stagingVersion, files, onProgress) }

    /** Come [download], ma senza prendere il lock: il chiamante e' gia' dentro [withStagingLock] per [regionId]. */
    suspend fun downloadLocked(
        regionId: String,
        stagingVersion: String,
        files: List<RegionManifestFile>,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File =
        withContext(Dispatchers.IO) {
            // Una seconda richiesta accodata dopo la prima trova i file gia' completi e li riusa.
            regionStorage.cleanupStagingExcept(regionId, stagingVersion)
            val staging = regionStorage.stagingDirectoryFor(regionId, stagingVersion)
            staging.mkdirs()
            val installedRouting = File(regionStorage.directoryFor(regionId), RegionStorage.ROUTING_DIR)
            val totalBytes = files.sumOf { it.sizeBytes }
            regionStorage.reserveSpace(totalBytes)
            var bytesBeforeCurrentFile = 0L
            files.forEach { file ->
                val baseBytes = bytesBeforeCurrentFile
                val target = File(staging, file.name)
                if (reuseLocalCopy(file, target, File(installedRouting, file.name))) {
                    onProgress(baseBytes + file.sizeBytes, totalBytes)
                } else {
                    downloadAndVerify(file, target) { fileBytesDownloaded ->
                        onProgress(baseBytes + fileBytesDownloaded, totalBytes)
                    }
                }
                bytesBeforeCurrentFile += file.sizeBytes
            }
            staging
        }

    /**
     * Evita di riscaricare un file che c'e' gia': un segmento .rd5 installato con lo stesso nome,
     * dimensione e SHA-256 (tile non cambiata tra due versioni del routing, la pipeline ricarica
     * solo quelle cambiate), o un file gia' completo in staging da un tentativo precedente. Il
     * segmento installato si copia, non si sposta: serve ancora per il rollback
     * (RegionStorage.activatePackage).
     */
    private fun reuseLocalCopy(file: RegionManifestFile, target: File, installed: File): Boolean {
        if (!target.exists() && installed.isFile && installed.length() == file.sizeBytes) {
            installed.copyTo(target)
        }
        if (!target.exists()) return false
        if (target.length() == file.sizeBytes && sha256Of(target).equals(file.sha256, ignoreCase = true)) return true
        target.delete()
        return false
    }
    // internal (non private) cosi' un test puo' esercitare direttamente il download/verifica
    // byte-per-byte senza dover soddisfare anche il vincolo HTTPS+host-allowlist di
    // RegionManifestEntry.validate() (gia' coperto a parte da RegionManifestTest).
    internal suspend fun downloadAndVerify(
        file: RegionManifestFile,
        target: File,
        onProgress: suspend (bytesDownloaded: Long) -> Unit = {},
    ) {
        val partFile = File(target.parentFile, "${target.name}.part")
        if (partFile.length() > file.sizeBytes) partFile.delete()
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        // .part gia' completo (processo chiuso tra l'ultimo byte e il renameTo): niente richiesta, si
        // verifica e basta. Chiedere "bytes=<dimensione>-" darebbe 416 e il download fallirebbe per sempre.
        if (existingBytes < file.sizeBytes) okHttpClient.newCall(
            Request.Builder().url(file.url).apply {
                if (existingBytes > 0) header("Range", "bytes=$existingBytes-")
            }.build(),
        ).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 416) {
                    // .part non coerente con il file sul server (es. cambiato a parita' di nome): si riparte da zero
                    partFile.delete()
                    throw IOException("Range non valido per ${file.name}: riscarico da capo")
                }
                if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                    throw PermanentRegionPackageException("Download fallito per ${file.name}: HTTP ${response.code}")
                }
                throw IOException("Download fallito per ${file.name}: HTTP ${response.code}")
            }
            val body = response.body
            val append = response.code == 206 && existingBytes > 0
            if (existingBytes > 0 && response.code == 206) {
                require(response.header("Content-Range")?.startsWith("bytes $existingBytes-", ignoreCase = true) == true) { "Risposta range non valida per ${file.name}" }
            }
            var downloaded = if (append) existingBytes else 0L
            var lastReported = downloaded
            try {
                FileOutputStream(partFile, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            // Annullare il download (es. regione eliminata) lo ferma subito, non al prossimo onProgress.
                            currentCoroutineContext().ensureActive()
                            downloaded += read
                            // Un server che manda piu' byte del manifest (anche col resume: conta il totale del
                            // file) non riempie il disco: ci si ferma subito, senza aspettare l'EOF.
                            if (downloaded > file.sizeBytes) {
                                throw PermanentRegionPackageException("Dimensione non valida per ${file.name}: superiore ai ${file.sizeBytes} byte attesi")
                            }
                            output.write(buffer, 0, read)
                            if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = downloaded
                                onProgress(downloaded)
                            }
                        }
                    }
                }
            } catch (e: PermanentRegionPackageException) {
                partFile.delete()
                throw e
            }
            onProgress(downloaded)
        }

        if (partFile.length() != file.sizeBytes) {
            val actual = partFile.length()
            partFile.delete()
            throw PermanentRegionPackageException("Dimensione non valida per ${file.name}: attesa ${file.sizeBytes}, ottenuta $actual")
        }
        val actualSha256 = sha256Of(partFile)
        if (!actualSha256.equals(file.sha256, ignoreCase = true)) {
            partFile.delete()
            throw PermanentRegionPackageException(
                "Checksum non valido per ${file.name}: atteso ${file.sha256}, ottenuto $actualSha256",
            )
        }
        check(partFile.renameTo(target)) { "Impossibile finalizzare ${file.name}" }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (stream.read(buffer) != -1) {
                // DigestInputStream.read aggiorna il digest a ogni lettura
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        // Un lock per id di staging, condiviso tra le istanze (il downloader non e' un singleton).
        val locks = ConcurrentHashMap<String, Mutex>()
        const val PROGRESS_STEP_BYTES = 1_000_000L
    }
}
