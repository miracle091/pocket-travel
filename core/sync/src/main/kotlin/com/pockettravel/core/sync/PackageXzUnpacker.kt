package com.pockettravel.core.sync

import kotlinx.coroutines.CoroutineDispatcher
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
 * Gira su [ioDispatcher] e si ferma tra un blocco e l'altro se la coroutine viene annullata (un lavoro sostituito
 * con REPLACE non deve continuare a scrivere). Il file temporaneo ha un nome unico: due esecuzioni
 * sulla stessa cartella di staging non scrivono mai lo stesso file. La decompressione si interrompe
 * appena il risultato supera la dimensione attesa (un xz piccolo puo' espandersi a dismisura).
 */
internal suspend fun unpackXz(
    staging: File,
    file: RegionManifestFile,
    fileXz: RegionManifestFile?,
    ioDispatcher: CoroutineDispatcher,
) {
    val xz = fileXz ?: return
    withContext(ioDispatcher) {
        val target = File(staging, file.name)
        val part = File(staging, "${file.name}.${UUID.randomUUID()}.unpack")
        try {
            try {
                decompressXz(File(staging, xz.name), part, file) { ensureActive() }
            } catch (e: PermanentRegionPackageException) {
                File(staging, xz.name).delete()
                throw e
            }
            // Un tentativo precedente interrotto dopo la decompressione puo' aver lasciato il file finale.
            target.delete()
            check(part.renameTo(target)) { "Impossibile finalizzare ${file.name}" }
            // Verificato e decompresso: l'.xz non serve piu' e liberare il suo spazio abbassa il picco dell'installazione.
            // Se un passo successivo fallisce, il nuovo tentativo lo riscarica (come le celle dei civici, vedi mergeInto).
            File(staging, xz.name).delete()
        } finally {
            // Dopo il rename non esiste piu'; su errore o annullamento non resta niente di parziale.
            part.delete()
        }
    }
}

/**
 * Decomprime [source] (xz) in [target], che deve essere [expected]: si ferma appena supera la sua dimensione e alla fine ne
 * controlla dimensione e sha256, calcolato mentre scrive. [onChunk] gira dopo ogni blocco letto (per fermarsi su annullamento).
 * Bloccante.
 */
internal fun decompressXz(source: File, target: File, expected: RegionManifestFile, onChunk: () -> Unit = {}) {
    val digest = MessageDigest.getInstance("SHA-256")
    var written = 0L
    XZInputStream(source.inputStream().buffered()).use { input ->
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                onChunk()
                written += read
                if (written > expected.sizeBytes) throw PermanentRegionPackageException("${expected.name} decompresso supera la dimensione del catalogo")
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
    }
    val sha256 = digest.digest().toHexString()
    if (written != expected.sizeBytes || !sha256.equals(expected.sha256, ignoreCase = true)) {
        throw PermanentRegionPackageException("${expected.name} decompresso non corrisponde al catalogo")
    }
}
