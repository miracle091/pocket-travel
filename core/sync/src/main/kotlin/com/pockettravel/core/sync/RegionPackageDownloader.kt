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
 * Downloads every file listed in a region's manifest entry (content.db, map.pmtiles, plus
 * one or more .rd5 routing segments) with HTTP range resume, verifies each
 * against its manifest SHA-256, then swaps the whole package into place in one rename — see
 * "Download verificato" nella specifica tecnica.
 */
class RegionPackageDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val regionStorage: RegionStorage,
) {
    suspend fun download(entry: RegionManifestEntry, onProgress: suspend (filesDone: Int, totalFiles: Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            entry.validate()
            regionStorage.cleanupStagingExcept(entry.regionId, entry.version)
            val staging = regionStorage.stagingDirectoryFor(entry.regionId, entry.version)
            staging.mkdirs()
            entry.files.forEachIndexed { index, file ->
                downloadAndVerify(file, File(staging, file.name))
                onProgress(index + 1, entry.files.size)
            }
            staging
        }
    // internal (non private) cosi' un test puo' esercitare direttamente il download/verifica
    // byte-per-byte senza dover soddisfare anche il vincolo HTTPS+host-allowlist di
    // RegionManifestEntry.validate() (gia' coperto a parte da RegionManifestTest).
    internal fun downloadAndVerify(file: RegionManifestFile, target: File) {
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
            FileOutputStream(partFile, append).use { output -> body.byteStream().copyTo(output) }
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

    companion object {
    }
}
