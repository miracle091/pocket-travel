package com.pockettravel.core.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.db.PoiCodeDao
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiRow
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.db.estimatedStorageBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Importa il poi.db (o, con extra, il poi-extra.db) scaricato di una regione (generato da
 * tools/data-pipeline) in region.db, l'unico database che l'app interroga via PoiDao, sostituendo
 * i POI dello stesso pacchetto gia' presenti per la regione. Le colonne corrispondono a PoiRow meno l'id, autogenerato all'insert:
 * regione e coppia (category, osmTag) diventano interi di poi_region e poi_code (registrati qui se nuovi), le coordinate
 * microgradi interi. poi.db
 * viene cancellato dopo un import riuscito: tenerlo raddoppierebbe lo spazio occupato.
 *
 * Due formati, distinti da PRAGMA user_version (SQLiteDatabase.version): 0 (assente, il vecchio
 * formato) ha "category"/"osmTag" in chiaro e lat/lon REAL su ogni riga di "poi", oltre a una
 * colonna "regionId" costante mai letta (il chiamante la passa gia'); >= 1 e' il formato compatto
 * di GeneratePoi.kt (tools/data-pipeline, POI_DB_FORMAT_VERSION) - "poi" ha solo un intero "code"
 * verso la tabella "poi_code" (category/osmTag) e coordinate come interi in microgradi
 * (latE6/lonE6). Finche' non tutte le regioni pubblicate sono state rigenerate nel nuovo formato
 * (rigenerazione incrementale, non tutte insieme) l'app puo' incontrare entrambi: legge entrambi.
 */
class PoiImporter @Inject constructor(
    private val poiDao: PoiDao,
    private val database: RegionDatabase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * Solo per i test (PackageImporterDeviceTest): sostituisce i POI e cancella [poiDbFile]. L'installazione
     * vera passa da [replaceFromFile] dentro RegionRepository.inInstallTransaction e lascia il file allo staging.
     */
    suspend fun import(regionId: String, poiDbFile: File, extra: Boolean = false) = withContext(ioDispatcher) {
        replaceFromFile(regionId, poiDbFile, extra)
        poiDbFile.delete()
    }

    /**
     * Sostituisce i POI del pacchetto leggendo [poiDbFile] a blocchi di [CHUNK_SIZE] righe invece che tutto in memoria:
     * per le regioni grandi (centinaia di migliaia di POI, ognuno con una dozzina di stringhe) la lista
     * intera rischia l'OutOfMemoryError. Il file si legge dentro la transazione (piu' lunga, ma e' un'installazione).
     * Ritorna i byte che i POI importati occupano in region.db (stima di [estimatedStorageBytes], non la dimensione del file).
     */
    suspend fun replaceFromFile(regionId: String, poiDbFile: File, extra: Boolean = false): Long =
        database.withTransaction {
            poiDao.deleteForRegion(regionId, extra)
            val poiCodeDao = database.poiCodeDao()
            poiCodeDao.ensureRegion(regionId)
            val regionRef = poiCodeDao.regionRef(regionId)
            var storageBytes = 0L
            SQLiteDatabase.openDatabase(poiDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                forEachPoiChunk(regionRef, db, extra, poiCodeDao) { chunk ->
                    poiDao.insertAll(chunk)
                    storageBytes += chunk.sumOf { it.estimatedStorageBytes() }
                }
            }
            storageBytes
        }

    // Le colonne facoltative dipendono da quando il file e' stato generato: i content.db v1 (vedi
    // MergeManifests.convertV1Region, tools/data-pipeline) pubblicati prima di "phone" non la hanno, i
    // poi.db pubblicati prima di "wheelchair" nemmeno questa. Selezionare una colonna assente farebbe
    // fallire l'intero download.
    private suspend fun forEachPoiChunk(
        regionRef: Long, db: SQLiteDatabase, extra: Boolean, poiCodeDao: PoiCodeDao, onChunk: suspend (List<PoiRow>) -> Unit,
    ) {
        // db.version legge PRAGMA user_version: assente (0) sui file nel vecchio formato, vedi la
        // nota di formato sopra la classe.
        val columns = poiColumns(db)
        val query = if (db.version >= 1) compactPoiQuery(columns) else poiQuery(columns)
        var pois = ArrayList<PoiRow>(CHUNK_SIZE)
        // I codici di poi_code, uno per coppia (category, osmTag): poche centinaia, si chiedono al database la prima volta.
        val codes = HashMap<Pair<String, String>, Long>()
        db.rawQuery(query, null).use { cursor ->
            // Indici calcolati una volta sola, non a ogni riga (-1: colonna assente dal file).
            val phone = cursor.getColumnIndex("phone")
            val wheelchair = cursor.getColumnIndex("wheelchair")
            val openingHours = cursor.getColumnIndex("openingHours")
            val address = cursor.getColumnIndex("address")
            val website = cursor.getColumnIndex("website")
            val email = cursor.getColumnIndex("email")
            val country = cursor.getColumnIndex("country")
            val nameEn = cursor.getColumnIndex("nameEn")
            val nameIt = cursor.getColumnIndex("nameIt")
            val toiletsWheelchair = cursor.getColumnIndex("toiletsWheelchair")
            val capacityDisabled = cursor.getColumnIndex("capacityDisabled")
            fun optional(index: Int): String? = if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            while (cursor.moveToNext()) {
                val category = cursor.getString(1)
                val osmTag = cursor.getString(4)
                val code = codes.getOrPut(category to osmTag) {
                    poiCodeDao.ensureCode(category, osmTag)
                    poiCodeDao.codeOf(category, osmTag)
                }
                pois += PoiRow(
                    regionRef = regionRef,
                    code = code,
                    // Il formato compatto ha gia' i microgradi (la query li divide per 1e6, qui si riportano allo stesso intero).
                    latE6 = Math.round(cursor.getDouble(2) * MICRODEGREES),
                    lonE6 = Math.round(cursor.getDouble(3) * MICRODEGREES),
                    name = cursor.getString(0),
                    phone = optional(phone),
                    wheelchair = optional(wheelchair),
                    openingHours = optional(openingHours),
                    address = optional(address),
                    website = optional(website),
                    email = optional(email),
                    country = optional(country),
                    nameEn = optional(nameEn),
                    nameIt = optional(nameIt),
                    toiletsWheelchair = optional(toiletsWheelchair),
                    capacityDisabled = optional(capacityDisabled)?.toIntOrNull(),
                    extra = extra,
                )
                if (pois.size == CHUNK_SIZE) {
                    onChunk(pois)
                    pois = ArrayList(CHUNK_SIZE)
                }
            }
        }
        if (pois.isNotEmpty()) onChunk(pois)
    }

    private fun poiColumns(db: SQLiteDatabase): Set<String> {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(poi)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        return columns
    }

    companion object {
        private const val CHUNK_SIZE = 5_000
        private const val MICRODEGREES = 1_000_000.0

        // Vedi GuidesImporter: eseguita via JDBC da un test JVM contro lo schema della pipeline.
        internal fun poiQuery(columns: Set<String>): String =
            "SELECT name, category, lat, lon, osmTag" + OPTIONAL_COLUMNS.filter { it in columns }.joinToString("") { ", $it" } + " FROM poi"

        // Formato compatto (GeneratePoi.kt, POI_DB_FORMAT_VERSION): stesso ordine di colonne di
        // poiQuery (name, category, lat, lon, osmTag, poi le facoltative) cosi' forEachPoiChunk legge le
        // prime cinque per posizione in entrambi i casi. Anche qui le facoltative dipendono dal file:
        // i poi.db pubblicati prima di orari e indirizzo non le hanno.
        internal fun compactPoiQuery(columns: Set<String>): String =
            "SELECT poi.name, poi_code.category, poi.latE6 / 1000000.0 AS lat, poi.lonE6 / 1000000.0 AS lon, poi_code.osmTag" +
                OPTIONAL_COLUMNS.filter { it in columns }.joinToString("") { ", poi.$it" } +
                " FROM poi JOIN poi_code ON poi.code = poi_code.code"

        private val OPTIONAL_COLUMNS = listOf("phone", "wheelchair", "openingHours", "address", "website", "email", "country", "nameEn", "nameIt", "toiletsWheelchair", "capacityDisabled")
    }
}
