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

    /**
     * Sostituisce un solo pacchetto della regione ([MAP_FILE] o [ROUTING_DIR]) con quello in
     * staging, lasciando intatti gli altri. Il precedente resta come backup fino a commit/rollback.
     */
    fun activatePackage(regionId: String, packageName: String, staged: File): Activation {
        require(packageName == MAP_FILE || packageName == ROUTING_DIR) { "Pacchetto sconosciuto: $packageName" }
        require(staged.exists()) { "Staging mancante per $regionId/$packageName" }
        val regionDir = directoryFor(regionId)
        regionDir.mkdirs()
        val live = File(regionDir, packageName)
        val backup = File(regionDir, ".$packageName.backup")
        backup.deleteRecursively()
        val hadPrevious = live.exists()
        if (hadPrevious) check(live.renameTo(backup)) { "Impossibile preparare l'aggiornamento $regionId/$packageName" }
        try { check(staged.renameTo(live)) { "Impossibile installare $regionId/$packageName" } }
        catch (error: Exception) { if (hadPrevious) backup.renameTo(live); throw error }
        return Activation(live, backup, hadPrevious)
    }

    /** Elimina un solo pacchetto ([MAP_FILE] o [ROUTING_DIR]) della regione. */
    fun deletePackage(regionId: String, packageName: String): Boolean {
        require(packageName == MAP_FILE || packageName == ROUTING_DIR) { "Pacchetto sconosciuto: $packageName" }
        val target = File(directoryFor(regionId), packageName)
        return !target.exists() || (target.deleteRecursively() && !target.exists())
    }

    /** Byte occupati sul disco da un pacchetto della regione, 0 se assente. */
    fun packageBytes(regionId: String, packageName: String): Long =
        File(directoryFor(regionId), packageName).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

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

    companion object {
        // Devono combaciare con OfflineTileSource e RouteEngineModule (feature/map) e con
        // RegionRoutingGraphInstaller.ROUTING_DIR_NAME (core/sync).
        const val MAP_FILE = "map.pmtiles"
        const val ROUTING_DIR = "routing"
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
