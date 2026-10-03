package com.pockettravel.core.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.CitySectionEntity
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.guideCategoryOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Importa il cities.db scaricato di una regione (guide delle citta', generato da
 * tools/data-pipeline) in region.db, sostituendo le sezioni della stessa regione gia' presenti —
 * stesso pattern di PoiImporter. Le categorie sconosciute vengono scartate invece di far fallire
 * l'intero import, come GuidesImporter. cities.db viene cancellato dopo un import riuscito.
 */
class CityImporter @Inject constructor(
    private val cityDao: CityDao,
    private val database: RegionDatabase,
) {
    /**
     * Solo per i test (PackageImporterDeviceTest): legge, sostituisce e cancella [citiesDbFile]. L'installazione
     * vera usa [readSections] fuori dalla transazione e [replace] dentro RegionRepository.inInstallTransaction.
     */
    suspend fun import(regionId: String, citiesDbFile: File) = withContext(Dispatchers.IO) {
        val sections = readSections(regionId, citiesDbFile)
        replace(regionId, sections)
        citiesDbFile.delete()
    }

    /**
     * Legge e fa il parsing del file senza toccare region.db: va chiamata fuori da
     * RegionRepository.inInstallTransaction, per non tenere occupato il lock di scrittura del database durante l'IO sul file.
     */
    suspend fun readSections(regionId: String, citiesDbFile: File): List<CitySectionEntity> =
        withContext(Dispatchers.IO) {
            SQLiteDatabase.openDatabase(citiesDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                readSections(regionId, db)
            }
        }

    /** Sostituisce le guide delle citta' della regione: va chiamata dentro RegionRepository.inInstallTransaction. */
    suspend fun replace(regionId: String, sections: List<CitySectionEntity>) {
        database.withTransaction {
            cityDao.deleteForRegion(regionId)
            cityDao.insertAll(sections)
            cityDao.optimizeFts()
        }
    }

    private fun readSections(regionId: String, db: SQLiteDatabase): List<CitySectionEntity> {
        val sections = mutableListOf<CitySectionEntity>()
        // population e capital ci sono solo nei cities.db generati dopo il 2026-10-03
        val hasPopulation = db.rawQuery("PRAGMA table_info(city_sections)", null).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.any { it == "population" }
        }
        db.rawQuery(if (hasPopulation) CITY_SECTIONS_WITH_POPULATION_QUERY else CITY_SECTIONS_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                val category = guideCategoryOrNull(cursor.getString(1)) ?: continue
                sections += CitySectionEntity(
                    regionId = regionId,
                    city = cursor.getString(0),
                    category = category,
                    title = cursor.getString(2),
                    body = cursor.getString(3),
                    sourceUrl = cursor.getString(4),
                    population = if (hasPopulation && !cursor.isNull(5)) cursor.getLong(5) else null,
                    capital = hasPopulation && cursor.getInt(6) == 1,
                )
            }
        }
        return sections
    }

    companion object {
        // Costante (invece che inline) cosi' un test JVM puro puo' eseguirla via JDBC contro un
        // file prodotto dalla pipeline dati, senza android.database.sqlite — vedi PackageImporterSchemaTest.
        internal const val CITY_SECTIONS_QUERY = "SELECT city, category, title, body, sourceUrl FROM city_sections"
        internal const val CITY_SECTIONS_WITH_POPULATION_QUERY = "SELECT city, category, title, body, sourceUrl, population, capital FROM city_sections"
    }
}
