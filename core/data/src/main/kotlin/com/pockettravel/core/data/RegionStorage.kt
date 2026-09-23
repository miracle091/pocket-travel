package com.pockettravel.core.data

import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RegionsDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RegionsStagingDir

/** Owns the on-disk layout for regional packages (`map.pmtiles`, one or more `.rd5` routing segments; `poi.db` only until imported). */
class RegionStorage @Inject constructor(
    @param:RegionsDir private val regionsDir: File,
    @param:RegionsStagingDir private val stagingDir: File,
) {
    fun directoryFor(regionId: String): File = safeChild(regionsDir, regionId, "regionId")
    fun stagingDirectoryFor(regionId: String, version: String): File = safeChild(safeChild(stagingDir, regionId, "regionId"), version, "version")

    fun activate(regionId: String, stagedDirectory: File): Activation {
        require(stagedDirectory.isDirectory) { "Staging mancante per $regionId" }
        val live = directoryFor(regionId)
        val backup = File(live.parentFile, ".${live.name}.backup")
        backup.deleteRecursively()
        val hadPrevious = live.exists()
        if (hadPrevious) check(live.renameTo(backup)) { "Impossibile preparare l'aggiornamento $regionId" }
        try { check(stagedDirectory.renameTo(live)) { "Impossibile installare il pacchetto $regionId" } }
        catch (error: Exception) { if (hadPrevious) backup.renameTo(live); throw error }
        return Activation(live, backup, hadPrevious)
    }

    fun cleanupStagingExcept(regionId: String, version: String) {
        val regionStaging = safeChild(stagingDir, regionId, "regionId")
        regionStaging.listFiles().orEmpty().filter { it.name != version }.forEach { it.deleteRecursively() }
    }

    fun delete(regionId: String): Boolean {
        val directory = directoryFor(regionId)
        return !directory.exists() || (directory.deleteRecursively() && !directory.exists())
    }

    fun availableBytes(): Long = regionsDir.usableSpace

    class Activation(private val live: File, private val backup: File, private val hadPrevious: Boolean) {
        fun commit() { backup.deleteRecursively() }
        fun rollback() { live.deleteRecursively(); if (hadPrevious) backup.renameTo(live) }
    }

    private fun safeChild(root: File, segment: String, field: String): File {
        require(segment.isNotEmpty() && segment != "." && segment != ".." &&
            !segment.contains('/') && !segment.contains('\\')) { "$field non valido" }
        val rootCanonical = root.canonicalFile
        val child = File(rootCanonical, segment).canonicalFile
        if (child.parentFile != rootCanonical) throw IOException("$field fuori dalla root")
        return child
    }
}
