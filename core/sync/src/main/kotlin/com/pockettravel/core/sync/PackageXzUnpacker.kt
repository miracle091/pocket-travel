package com.pockettravel.core.sync

import org.tukaani.xz.XZInputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Decomprime un file scaricato compresso con xz ([fileXz], gia' verificato con il suo sha256) in
 * [file], controllando dimensione e sha256 del risultato decompresso. Senza [fileXz] non fa nulla:
 * il file scaricato e' gia' [file]. Condivisa da RegionPackageInstaller (poi.db, poi-extra.db,
 * addresses.pmtiles, preview.pmtiles) e GuidesInstaller (guides.db).
 */
internal fun unpackXz(staging: File, file: RegionManifestFile, fileXz: RegionManifestFile?) {
    val xz = fileXz ?: return
    val target = File(staging, file.name)
    val part = File(staging, "${file.name}.unpack")
    val digest = MessageDigest.getInstance("SHA-256")
    XZInputStream(File(staging, xz.name).inputStream().buffered()).use { input ->
        DigestOutputStream(part.outputStream().buffered(), digest).use { input.copyTo(it) }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    if (part.length() != file.sizeBytes || !sha256.equals(file.sha256, ignoreCase = true)) {
        part.delete()
        File(staging, xz.name).delete()
        throw PermanentRegionPackageException("${file.name} decompresso non corrisponde al manifest")
    }
    check(part.renameTo(target)) { "Impossibile finalizzare ${file.name}" }
}
