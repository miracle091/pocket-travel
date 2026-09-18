package com.pockettravel.core.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.EmergencyNumbersEntity
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.RegionDatabase
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Importa il content.db scaricato (file SQLite semplice generato dalla pipeline dati in
 * tools/data-pipeline, non una replica dello schema Room — tabelle "guide_sections" e "poi"
 * insieme nello stesso file, accorpate da due file separati guide.db/poi.db per evitare di
 * scaricare/verificare/aprire due file quando finiscono comunque nello stesso posto)
 * dentro region.db, l'unico database che l'app interroga via GuideDao/PoiDao.
 * Le colonne corrispondono 1:1 a GuideSectionEntity/PoiEntity meno l'id, autogenerato qui
 * all'insert. content.db viene cancellato dopo un import riuscito: il contenuto e' gia' in
 * region.db, tenerlo occuperebbe spazio su disco raddoppiato per ogni regione installata senza
 * alcun beneficio.
 */
class RegionContentImporter @Inject constructor(
    private val guideDao: GuideDao,
    private val poiDao: PoiDao,
    private val emergencyNumbersDao: EmergencyNumbersDao,
    private val database: RegionDatabase,
) {
    suspend fun import(regionId: String, packageDir: File) = withContext(Dispatchers.IO) {
        val contentDbFile = File(packageDir, "content.db")
        SQLiteDatabase.openDatabase(contentDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val sections = readGuideSections(regionId, db)
            val pois = readPois(regionId, db)
            val emergencyNumbers = readEmergencyNumbers(regionId, db)
            database.withTransaction {
                guideDao.deleteForRegion(regionId)
                poiDao.deleteForRegion(regionId)
                emergencyNumbersDao.deleteForRegion(regionId)
                guideDao.insertAll(sections)
                poiDao.insertAll(pois)
                emergencyNumbers?.let { emergencyNumbersDao.insertAll(listOf(it)) }
            }
        }
        contentDbFile.delete()
    }

    private fun readGuideSections(regionId: String, db: SQLiteDatabase): List<GuideSectionEntity> {
        val sections = mutableListOf<GuideSectionEntity>()
        db.rawQuery(GUIDE_SECTIONS_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                sections += GuideSectionEntity(
                    regionId = regionId,
                    category = GuideCategory.valueOf(cursor.getString(0)),
                    title = cursor.getString(1),
                    body = cursor.getString(2),
                    sourceUrl = cursor.getString(3),
                )
            }
        }
        return sections
    }

    // I content.db gia' pubblicati (GitHub Releases) prima dell'introduzione della colonna phone
    // non hanno quella colonna nella tabella poi: POI_QUERY la selezionerebbe comunque e
    // fallirebbe con "no such column: phone" finche' quella regione non viene ripubblicata da
    // tools/data-pipeline. hasPhoneColumn sceglie la query giusta invece di far fallire l'intero
    // import (e quindi l'intero download) per una colonna assente ma innocua.
    private fun readPois(regionId: String, db: SQLiteDatabase): List<PoiEntity> {
        val hasPhone = hasPhoneColumn(db)
        val pois = mutableListOf<PoiEntity>()
        db.rawQuery(if (hasPhone) POI_QUERY else POI_QUERY_LEGACY, null).use { cursor ->
            while (cursor.moveToNext()) {
                pois += PoiEntity(
                    regionId = regionId,
                    name = cursor.getString(0),
                    category = cursor.getString(1),
                    lat = cursor.getDouble(2),
                    lon = cursor.getDouble(3),
                    osmTag = cursor.getString(4),
                    phone = if (hasPhone && !cursor.isNull(5)) cursor.getString(5) else null,
                )
            }
        }
        return pois
    }

    private fun hasPhoneColumn(db: SQLiteDatabase): Boolean {
        db.rawQuery("PRAGMA table_info(poi)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "phone") return true
            }
        }
        return false
    }

    // "emergency_numbers" e' una tabella nuova, non solo una colonna: i content.db gia'
    // pubblicati prima della sua introduzione non la hanno affatto, e selezionarla senza
    // controllo fallirebbe con "no such table" (stesso ragionamento di hasPhoneColumn per poi).
    // Al piu' una riga (la regione stessa) — vedi GenerateEmergencyNumbers.kt.
    private fun readEmergencyNumbers(regionId: String, db: SQLiteDatabase): EmergencyNumbersEntity? {
        if (!hasTable(db, "emergency_numbers")) return null
        db.rawQuery(EMERGENCY_NUMBERS_QUERY, null).use { cursor ->
            if (!cursor.moveToNext()) return null
            return EmergencyNumbersEntity(
                regionId = regionId,
                general = if (cursor.isNull(0)) null else cursor.getString(0),
                police = cursor.getString(1),
                ambulance = cursor.getString(2),
                fire = cursor.getString(3),
            )
        }
    }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
            return cursor.moveToNext()
        }
    }

    companion object {
        // Estratte come costanti (invece che inline) cosi' un test JVM puro puo' eseguirle
        // via JDBC contro un file prodotto dalla pipeline dati, senza dipendere da
        // android.database.sqlite (non disponibile fuori da un device/emulatore reale).
        internal const val GUIDE_SECTIONS_QUERY = "SELECT category, title, body, sourceUrl FROM guide_sections"
        internal const val POI_QUERY = "SELECT name, category, lat, lon, osmTag, phone FROM poi"
        internal const val POI_QUERY_LEGACY = "SELECT name, category, lat, lon, osmTag FROM poi"
        internal const val EMERGENCY_NUMBERS_QUERY = "SELECT general, police, ambulance, fire FROM emergency_numbers"
    }
}
