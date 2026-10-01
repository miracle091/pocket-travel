package com.pockettravel.core.data

import android.os.storage.StorageManager
import com.pockettravel.core.data.RegionStorage.Companion.ADDRESSES_CELLS_FILE
import com.pockettravel.core.data.RegionStorage.Companion.ADDRESSES_FILE
import com.pockettravel.core.data.RegionStorage.Companion.MAP_FILE
import com.pockettravel.core.data.RegionStorage.Companion.PREVIEW_FILE
import com.pockettravel.core.data.RegionStorage.Companion.ROUTING_DIR
import com.pockettravel.core.data.RegionStorage.Companion.TRANSIT_DIR
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

/** Owns the on-disk layout for regional packages (`map.pmtiles`, one or more `.rd5` routing segments, `addresses.pmtiles`; `poi.db` only until imported). */
class RegionStorage @Inject constructor(
    @param:RegionsDir private val regionsDir: File,
    @param:RegionsStagingDir private val stagingDir: File,
    // null nei test JVM: lo spazio disponibile e' solo quello libero.
    private val storageManager: StorageManager? = null,
) {
    fun directoryFor(regionId: String): File = safeChild(regionsDir, regionId, "regionId")
    fun stagingDirectoryFor(regionId: String, version: String): File = safeChild(safeChild(stagingDir, regionId, "regionId"), version, "version")

    /**
     * Sostituisce un solo pacchetto della regione ([MAP_FILE], [ROUTING_DIR], [ADDRESSES_FILE],
     * [PREVIEW_FILE] o [TRANSIT_DIR]) con quello in staging, lasciando intatti gli altri. Il precedente resta come backup fino a commit/rollback;
     * accanto resta anche [version], la versione in attivazione, per [recoverInterruptedActivations].
     */
    fun activatePackage(regionId: String, packageName: String, staged: File, version: String): Activation {
        require(packageName in PACKAGE_NAMES) { "Pacchetto sconosciuto: $packageName" }
        require(staged.exists()) { "Staging mancante per $regionId/$packageName" }
        val regionDir = directoryFor(regionId)
        regionDir.mkdirs()
        val live = File(regionDir, packageName)
        val backup = File(regionDir, ".$packageName.backup")
        val pending = File(regionDir, ".$packageName.pending")
        backup.deleteRecursively()
        val hadPrevious = live.exists()
        if (hadPrevious) {
            // Solo se ci sara' un backup: la versione in attivazione, scritta prima di toccare il pacchetto attivo.
            pending.writeText(version)
            check(live.renameTo(backup)) { "Impossibile preparare l'aggiornamento $regionId/$packageName" }
        }
        try { check(staged.renameTo(live)) { "Impossibile installare $regionId/$packageName" } }
        catch (error: Exception) { if (hadPrevious) { backup.renameTo(live); pending.delete() }; throw error }
        return Activation(live, backup, pending, hadPrevious)
    }

    /**
     * Chiude le attivazioni di [regionId] interrotte da un crash tra [activatePackage] e
     * commit/rollback. [installedVersion] e' la versione registrata nel database per il pacchetto
     * (fonte di verita'): se e' quella che si stava attivando manca solo il commit e il backup si
     * elimina, altrimenti il pacchetto attivo torna quello del backup.
     */
    fun recoverInterruptedActivations(regionId: String, installedVersion: (packageName: String) -> String?) = synchronized(RECOVERY_LOCK) {
        val regionDir = directoryFor(regionId)
        for (packageName in PACKAGE_NAMES) {
            val live = File(regionDir, packageName)
            val backup = File(regionDir, ".$packageName.backup")
            val pending = File(regionDir, ".$packageName.pending")
            val activating = pending.takeIf { it.exists() }?.readText()
            if (activating != null && activating == installedVersion(packageName) && live.exists()) {
                // Il database registra gia' la versione nuova: mancava solo commit().
                backup.deleteRecursively()
            } else if (backup.exists()) {
                // Database fermo alla versione precedente, oppure backup senza versione in attivazione
                // (lasciato da una versione precedente dell'app): il backup e' il pacchetto registrato.
                live.deleteRecursively()
                check(backup.renameTo(live)) { "Impossibile ripristinare $regionId/$packageName" }
            }
            // Senza backup il pacchetto attivo e' ancora il precedente: l'attivazione si e' fermata
            // prima di spostarlo, o il rollback l'aveva gia' rimesso al suo posto.
            pending.delete()
        }
    }

    /** Elimina un solo pacchetto ([MAP_FILE], [ROUTING_DIR], [ADDRESSES_FILE] o [PREVIEW_FILE]) della regione. */
    fun deletePackage(regionId: String, packageName: String): Boolean {
        require(packageName in PACKAGE_NAMES) { "Pacchetto sconosciuto: $packageName" }
        val target = File(directoryFor(regionId), packageName)
        versionedLinks(target).forEach { it.delete() }
        return !target.exists() || (target.deleteRecursively() && !target.exists())
    }

    /**
     * File da dare a MapLibre per un archivio PMTiles della regione ([MAP_FILE], [ADDRESSES_FILE] o
     * [PREVIEW_FILE]), null se non installato. MapLibre tiene in memoria header e directory di ogni archivio per
     * percorso, per tutta la vita del processo, e non accetta parametri nell'URL di un file: se
     * l'archivio viene sostituito allo stesso percorso (aggiornamento o nuovo download con l'app
     * aperta) legge il file nuovo con gli offset del vecchio e va in abort nativo ("incorrect header
     * check"). Qui il percorso passa per una cartella vuota con la data di modifica nel nome
     * (`.v-map-<data>/../map.pmtiles`): il kernel lo risolve nello stesso file, ma per MapLibre e' un
     * percorso nuovo a ogni versione (hard e soft link non si possono usare: SELinux nega `link` alle
     * app). Le cartelle delle versioni precedenti si cancellano; se non si riesce a creare la
     * cartella si torna al percorso normale.
     */
    fun versionedPmtiles(regionId: String, packageName: String): File? {
        require(packageName == MAP_FILE || packageName == ADDRESSES_FILE || packageName == PREVIEW_FILE) { "Non e' un archivio PMTiles: $packageName" }
        val file = File(directoryFor(regionId), packageName)
        if (!file.isFile) return null
        val marker = File(file.parentFile, ".v-${file.nameWithoutExtension}-${file.lastModified()}")
        versionedLinks(file).filter { it != marker }.forEach { it.delete() }
        if (!marker.isDirectory && !marker.mkdir()) return file
        return File(marker, "../${file.name}")
    }

    private fun versionedLinks(packageFile: File): List<File> =
        packageFile.parentFile?.listFiles().orEmpty().filter { it.name.startsWith(".v-${packageFile.nameWithoutExtension}-") }

    /** Byte occupati sul disco da un pacchetto della regione, 0 se assente. */
    fun packageBytes(regionId: String, packageName: String): Long =
        File(directoryFor(regionId), packageName).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Celle dei civici a griglia attive nell'ultimo [ADDRESSES_CELLS_FILE] installato (id di cella ->
     * version), vuoto se la regione non li usa (percorso di oggi, o
     * civici non installati).
     */
    fun installedAddressCells(regionId: String): Map<String, String> {
        val file = File(directoryFor(regionId), ADDRESSES_CELLS_FILE)
        return if (file.isFile) decodeAddressCells(file.readText()) else emptyMap()
    }

    /** Database di ricerca per cella (sorgente dell'aggiornamento incrementale, vedi [ADDRESSES_SEARCH_DIR]); vuoto se non ce ne sono. */
    fun addressSearchFiles(regionId: String): List<File> =
        File(directoryFor(regionId), ADDRESSES_SEARCH_DIR).listFiles { file -> file.isFile && file.extension == "db" && file.name != ADDRESSES_SEARCH_DB }.orEmpty().sortedBy { it.name }

    /** Database di ricerca unico della regione ([ADDRESSES_SEARCH_DB]), null se la regione non ha la ricerca. */
    fun addressSearchDb(regionId: String): File? =
        File(directoryFor(regionId), "$ADDRESSES_SEARCH_DIR/$ADDRESSES_SEARCH_DB").takeIf { it.isFile }

    fun cleanupStagingExcept(regionId: String, version: String) {
        val regionStaging = safeChild(stagingDir, regionId, "regionId")
        regionStaging.listFiles().orEmpty().filter { it.name != version }.forEach { it.deleteRecursively() }
    }

    /** Elimina tutto lo staging della regione (download falliti o interrotti, regione rimossa). */
    fun deleteStaging(regionId: String) {
        safeChild(stagingDir, regionId, "regionId").deleteRecursively()
    }

    fun delete(regionId: String): Boolean {
        val directory = directoryFor(regionId)
        return !directory.exists() || (directory.deleteRecursively() && !directory.exists())
    }

    fun availableBytes(): Long = regionsDir.allocatableBytes(storageManager)

    /** Libera la cache di sistema per [bytes] prima di un download (staging e regioni stanno sullo stesso volume). */
    fun reserveSpace(bytes: Long) = regionsDir.reserveSpace(storageManager, bytes)

    /** Regioni con una cartella su disco (pacchetti installati o attivazioni interrotte). */
    fun regionIdsOnDisk(): List<String> = regionsDir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }

    /** Cartelle di staging presenti, una per regione (o per le guide, vedi GuidesInstaller). */
    fun stagingIds(): List<String> = stagingDir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }

    // Il file .pending si elimina per ultimo: finche' esiste, recoverInterruptedActivations sa cosa si stava attivando.
    class Activation(private val live: File, private val backup: File, private val pending: File, private val hadPrevious: Boolean) {
        fun commit() { backup.deleteRecursively(); pending.delete() }
        fun rollback() { live.deleteRecursively(); if (hadPrevious) backup.renameTo(live); pending.delete() }
    }

    companion object {
        // Devono combaciare con OfflineTileSource e RouteEngineModule (feature/map) e con
        // RegionRoutingGraphInstaller.ROUTING_DIR_NAME (core/sync).
        const val MAP_FILE = "map.pmtiles"
        const val ROUTING_DIR = "routing"
        // Civici sovrapposti alla mappa (OfflineTileSource).
        const val ADDRESSES_FILE = "addresses.pmtiles"
        // Anteprima offline (pochi zoom): si installa da sola con ogni download della regione, vedi
        // RegionPackageInstaller; sopravvive all'eliminazione della sola mappa.
        const val PREVIEW_FILE = "preview.pmtiles"
        // Civici a griglia: elenco delle celle in ADDRESSES_FILE (id -> version),
        // scritto e attivato accanto ad esso, atomicamente con lo stesso meccanismo di activatePackage.
        // Assente per le regioni installate col percorso di oggi (una sola voce "addresses").
        const val ADDRESSES_CELLS_FILE = "addresses-cells.json"
        // Ricerca degli indirizzi: una addresses-search.db per cella (<z>-<x>-<y>.db, vedi addressSearchFileName),
        // tenute solo per aggiornare la regione scaricando le celle cambiate, piu' ADDRESSES_SEARCH_DB, l'unione di
        // tutte le celle che AddressSearchRepository apre in sola lettura. Cartella attivata insieme a
        // ADDRESSES_FILE (stessa versione): le celle sparite dal manifest spariscono con la sostituzione.
        // Assente per le regioni senza ricerca (manifest vecchi).
        const val ADDRESSES_SEARCH_DIR = "addresses-search"
        const val ADDRESSES_SEARCH_DB = "addresses-search.db"
        // Orari dei mezzi pubblici: una transit.db per rete (<feedId>.db) piu' TRANSIT_FEEDS_FILE, attivati
        // insieme come cartella, cosi' le reti sparite dal manifest spariscono con la sostituzione.
        const val TRANSIT_DIR = "transit"
        // Nome, attribuzione e licenza di ogni rete di TRANSIT_DIR (vedi TransitFeedInfo), per la scheda delle partenze.
        const val TRANSIT_FEEDS_FILE = "feeds.json"
        private val PACKAGE_NAMES = setOf(MAP_FILE, ROUTING_DIR, ADDRESSES_FILE, PREVIEW_FILE, ADDRESSES_CELLS_FILE, ADDRESSES_SEARCH_DIR, TRANSIT_DIR)
        // Istanze di RegionStorage non condivise: due recuperi della stessa regione non si sovrappongono.
        private val RECOVERY_LOCK = Any()

        /** Nome del file di ricerca di una cella in [ADDRESSES_SEARCH_DIR]: l'id "z/x/y" senza barre. */
        fun addressSearchFileName(cellId: String): String = cellId.replace('/', '-') + ".db"

        /**
         * Formato di [ADDRESSES_CELLS_FILE]: un oggetto json id -> version. Id e version sono gia'
         * limitati a caratteri sicuri prima di arrivare qui (un id di cella e' sempre "z/x/y",
         * RegionManifest.isSafeVersion per le version): non serve una libreria json per un formato
         * cosi' semplice e interamente sotto il nostro controllo.
         */
        fun encodeAddressCells(cells: Map<String, String>): String =
            cells.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}", separator = ",") { (id, version) -> "\"$id\":\"$version\"" }

        fun decodeAddressCells(text: String): Map<String, String> {
            val body = text.trim().removeSurrounding("{", "}")
            if (body.isBlank()) return emptyMap()
            return body.split(",").associate { entry ->
                val (key, value) = entry.split(":", limit = 2)
                key.trim().trim('"') to value.trim().trim('"')
            }
        }
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
