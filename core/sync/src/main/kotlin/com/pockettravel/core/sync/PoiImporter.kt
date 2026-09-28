package com.pockettravel.core.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.RegionDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Importa il poi.db (o, con extra, il poi-extra.db) scaricato di una regione (generato da
 * tools/data-pipeline) in region.db, l'unico database che l'app interroga via PoiDao, sostituendo
 * i POI dello stesso pacchetto gia' presenti per la regione. Le colonne corrispondono 1:1 a PoiEntity meno l'id, autogenerato all'insert. poi.db
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
) {
    suspend fun import(regionId: String, poiDbFile: File, extra: Boolean = false) = withContext(Dispatchers.IO) {
        val pois = readPois(regionId, poiDbFile, extra)
        replace(regionId, pois, extra)
        poiDbFile.delete()
    }

    /**
     * Legge e fa il parsing del file senza toccare region.db: va chiamata fuori da
     * RegionRepository.inInstallTransaction, cosi' l'IO sul file non tiene occupato il lock di
     * scrittura del database (RegionPackageInstaller).
     */
    suspend fun readPois(regionId: String, poiDbFile: File, extra: Boolean = false): List<PoiEntity> =
        withContext(Dispatchers.IO) {
            SQLiteDatabase.openDatabase(poiDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                readPois(regionId, db, extra)
            }
        }

    /** Sostituisce i POI del pacchetto: va chiamata dentro RegionRepository.inInstallTransaction. */
    suspend fun replace(regionId: String, pois: List<PoiEntity>, extra: Boolean = false) {
        database.withTransaction {
            poiDao.deletePackageForRegion(regionId, extra)
            poiDao.insertAll(pois)
        }
    }

    // Le colonne facoltative dipendono da quando il file e' stato generato: i content.db v1 (vedi
    // MergeManifests.convertV1Region, tools/data-pipeline) pubblicati prima di "phone" non la hanno, i
    // poi.db pubblicati prima di "wheelchair" nemmeno questa. Selezionare una colonna assente farebbe
    // fallire l'intero download.
    private fun readPois(regionId: String, db: SQLiteDatabase, extra: Boolean): List<PoiEntity> {
        // db.version legge PRAGMA user_version: assente (0) sui file nel vecchio formato, vedi la
        // nota di formato sopra la classe.
        val columns = poiColumns(db)
        val query = if (db.version >= 1) compactPoiQuery(columns) else poiQuery(columns)
        val pois = mutableListOf<PoiEntity>()
        db.rawQuery(query, null).use { cursor ->
            fun optional(name: String): String? {
                val index = cursor.getColumnIndex(name)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            }
            while (cursor.moveToNext()) {
                pois += PoiEntity(
                    regionId = regionId,
                    name = cursor.getString(0),
                    category = cursor.getString(1),
                    lat = cursor.getDouble(2),
                    lon = cursor.getDouble(3),
                    osmTag = cursor.getString(4),
                    phone = optional("phone"),
                    wheelchair = optional("wheelchair"),
                    openingHours = optional("openingHours"),
                    address = optional("address"),
                    website = optional("website"),
                    email = optional("email"),
                    country = optional("country"),
                    nameEn = optional("nameEn"),
                    nameIt = optional("nameIt"),
                    extra = extra,
                )
            }
        }
        return pois
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
        // Vedi GuidesImporter: eseguita via JDBC da un test JVM contro lo schema della pipeline.
        internal fun poiQuery(columns: Set<String>): String =
            "SELECT name, category, lat, lon, osmTag" + OPTIONAL_COLUMNS.filter { it in columns }.joinToString("") { ", $it" } + " FROM poi"

        // Formato compatto (GeneratePoi.kt, POI_DB_FORMAT_VERSION): stesso ordine di colonne di
        // poiQuery (name, category, lat, lon, osmTag, poi le facoltative) cosi' readPois legge le
        // prime cinque per posizione in entrambi i casi. Anche qui le facoltative dipendono dal file:
        // i poi.db pubblicati prima di orari e indirizzo non le hanno.
        internal fun compactPoiQuery(columns: Set<String>): String =
            "SELECT poi.name, poi_code.category, poi.latE6 / 1000000.0 AS lat, poi.lonE6 / 1000000.0 AS lon, poi_code.osmTag" +
                OPTIONAL_COLUMNS.filter { it in columns }.joinToString("") { ", poi.$it" } +
                " FROM poi JOIN poi_code ON poi.code = poi_code.code"

        private val OPTIONAL_COLUMNS = listOf("phone", "wheelchair", "openingHours", "address", "website", "email", "country", "nameEn", "nameIt")
    }
}
