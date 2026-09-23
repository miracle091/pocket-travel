package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionStorage
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

class PermanentRegionPackageException(message: String) : Exception(message)

/**
 * Downloads the given manifest files (poi.db, .rd5 routing segments, guides.db) into a staging
 * directory with HTTP range resume and verifies each against its manifest SHA-256; the caller
 * validates the manifest entry first and then moves the staged files into place.
 */
class RegionPackageDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val regionStorage: RegionStorage,
) {
    suspend fun download(
        regionId: String,
        stagingVersion: String,
        files: List<RegionManifestFile>,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File =
        withContext(Dispatchers.IO) {
            regionStorage.cleanupStagingExcept(regionId, stagingVersion)
            val staging = regionStorage.stagingDirectoryFor(regionId, stagingVersion)
            staging.mkdirs()
            val totalBytes = files.sumOf { it.sizeBytes }
            var bytesBeforeCurrentFile = 0L
            files.forEach { file ->
                val baseBytes = bytesBeforeCurrentFile
                downloadAndVerify(file, File(staging, file.name)) { fileBytesDownloaded ->
                    onProgress(baseBytes + fileBytesDownloaded, totalBytes)
                }
                bytesBeforeCurrentFile += file.sizeBytes
            }
            staging
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
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val request = Request.Builder().url(file.url).apply {
            if (existingBytes > 0) header("Range", "bytes=$existingBytes-")
        }.build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                    throw PermanentRegionPackageException("Download fallito per ${file.name}: HTTP ${response.code}")
                }
                throw IOException("Download fallito per ${file.name}: HTTP ${response.code}")
            }
            val body = checkNotNull(response.body) { "Corpo vuoto per ${file.name}" }
            val append = response.code == 206 && existingBytes > 0
            if (existingBytes > 0 && response.code == 206) {
                require(response.header("Content-Range")?.startsWith("bytes $existingBytes-", ignoreCase = true) == true) { "Risposta range non valida per ${file.name}" }
            }
            var downloaded = if (append) existingBytes else 0L
            var lastReported = downloaded
            FileOutputStream(partFile, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = downloaded
                            onProgress(downloaded)
                        }
                    }
                }
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
                // digest updates as a side effect of DigestInputStream.read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        const val PROGRESS_STEP_BYTES = 1_000_000L
    }
}
