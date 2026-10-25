package com.pockettravel.app.crash

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Rapporto in attesa e timestamp dell'ultima chiusura già vista, in `filesDir/crash/`. Un solo rapporto alla volta:
 * il nuovo sovrascrive quello vecchio. Le scritture sono sincrone, perché il gestore delle eccezioni ha pochi
 * istanti prima che il processo muoia.
 */
class CrashStore(private val dir: File) {
    private val pendingFile get() = File(dir, PENDING)
    private val lastSeenFile get() = File(dir, LAST_SEEN)

    fun writePending(report: CrashReport) {
        dir.mkdirs()
        // Scrittura su un file temporaneo e rinomina: un processo ucciso a metà non lascia un rapporto troncato.
        val temp = File(dir, "$PENDING.tmp")
        temp.writeText(report.toJson())
        Files.move(temp.toPath(), pendingFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun readPending(): CrashReport? = try {
        pendingFile.takeIf { it.isFile }?.readText()?.let(CrashReport::fromJson)
    } catch (_: IOException) {
        null
    }

    fun clearPending() {
        pendingFile.delete()
    }

    fun lastSeen(): Long? = try {
        lastSeenFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    } catch (_: IOException) {
        null
    }

    fun saveLastSeen(timestamp: Long) {
        dir.mkdirs()
        lastSeenFile.writeText(timestamp.toString())
    }

    companion object {
        private const val PENDING = "pending.json"
        private const val LAST_SEEN = "last_seen.txt"

        /** Cartella condivisa con la traccia del modello caricato (`LoadedModelBreadcrumb`). */
        const val DIR_NAME = "crash"
    }
}
