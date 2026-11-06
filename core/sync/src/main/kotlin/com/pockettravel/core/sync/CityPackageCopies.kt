package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionStorage
import java.io.File
import javax.inject.Inject

/**
 * Copie dei pacchetti delle citta' gia' scaricati, come arrivano dal catalogo (compressi), in ogni lingua: dopo un cambio
 * di lingua, e al ritorno a quella di prima, la ricarica le reinstalla senza rete. Stanno nella cartella della regione
 * ([RegionStorage.CITIES_COPIES_DIR]), contano nella dimensione delle citta' e si cancellano con loro; oltre [MAX_BYTES]
 * in tutto si tolgono quelle usate meno di recente.
 */
class CityPackageCopies @Inject constructor(private val regionStorage: RegionStorage) {
    /** Il posto della copia di [file]: [RegionPackageDownloader] la riusa solo se dimensione e SHA-256 coincidono. */
    fun fileFor(regionId: String, file: RegionManifestFile): File = File(File(regionStorage.directoryFor(regionId), RegionStorage.CITIES_COPIES_DIR), file.sha256.lowercase())

    fun has(regionId: String, file: RegionManifestFile): Boolean = fileFor(regionId, file).let { it.isFile && it.length() == file.sizeBytes }

    /** Tiene una copia di [downloaded], gia' verificato, e la segna come appena usata; poi rientra nel tetto. */
    fun keep(regionId: String, file: RegionManifestFile, downloaded: File) {
        val copy = fileFor(regionId, file)
        if (!has(regionId, file)) {
            copy.parentFile?.mkdirs()
            downloaded.copyTo(copy, overwrite = true)
        }
        copy.setLastModified(System.currentTimeMillis())
        val newestFirst = regionStorage.regionIdsOnDisk()
            .flatMap { File(regionStorage.directoryFor(it), RegionStorage.CITIES_COPIES_DIR).listFiles().orEmpty().asList() }
            .sortedByDescending { it.lastModified() }
        var total = 0L
        newestFirst.forEach { total += it.length(); if (total > MAX_BYTES) it.delete() }
    }

    companion object {
        internal const val MAX_BYTES = 20L * 1024 * 1024
    }
}
