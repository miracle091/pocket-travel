package com.pockettravel.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Decomprime un file scaricato compresso con xz ([fileXz], gia' verificato con il suo sha256) in
 * [file], controllando dimensione e sha256 del risultato decompresso. Senza [fileXz] non fa nulla:
 * il file scaricato e' gia' [file]. Condivisa da RegionPackageInstaller (poi.db, poi-extra.db,
 * addresses.pmtiles, preview.pmtiles) e GuidesInstaller (guides.db).
 *
 * Gira su IO e si ferma tra un blocco e l'altro se la coroutine viene annullata (un lavoro sostituito
 * con REPLACE non deve continuare a scrivere). Il file temporaneo ha un nome unico: due esecuzioni
 * sulla stessa cartella di staging non scrivono mai lo stesso file. La decompressione si interrompe
 * appena il risultato supera la dimensione attesa (un xz piccolo puo' espandersi a dismisura).
 */
internal suspend fun unpackXz(staging: File, file: RegionManifestFile, fileXz: RegionManifestFile?) {
    val xz = fileXz ?: return
    withContext(Dispatchers.IO) {
        val target = File(staging, file.name)
        val part = File(staging, "${file.name}.${UUID.randomUUID()}.unpack")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            XZInputStream(File(staging, xz.name).inputStream().buffered()).use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        ensureActive()
                        written += read
                        if (written > file.sizeBytes) {
                            File(staging, xz.name).delete()
                            throw PermanentRegionPackageException("${file.name} decompresso supera la dimensione del manifest")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (written != file.sizeBytes || !sha256.equals(file.sha256, ignoreCase = true)) {
                File(staging, xz.name).delete()
                throw PermanentRegionPackageException("${file.name} decompresso non corrisponde al manifest")
            }
            check(part.renameTo(target)) { "Impossibile finalizzare ${file.name}" }
        } finally {
            // Dopo il rename non esiste piu'; su errore o annullamento non resta niente di parziale.
            part.delete()
        }
    }
}
