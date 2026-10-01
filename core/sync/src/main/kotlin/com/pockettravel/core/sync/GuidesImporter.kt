package com.pockettravel.core.sync

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.db.DiplomaticMissionDao
import com.pockettravel.core.data.db.DiplomaticMissionEntity
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.EmergencyNumbersEntity
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.NoCentralEmergencyNumberEntity
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.guideCategoryOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Importa guides.db (guide Wikivoyage, numeri di emergenza e ambasciate/consolati Wikidata di tutte le
 * regioni, generato da tools/data-pipeline) in region.db: sostituisce tutte le righe di guide_sections,
 * emergency_numbers, emergency_numbers_none e diplomatic_missions e registra la versione installata, in
 * una sola transazione. guides.db viene
 * cancellato dopo l'import: il contenuto e' gia' in region.db.
 */
class GuidesImporter @Inject constructor(
    private val guideDao: GuideDao,
    private val emergencyNumbersDao: EmergencyNumbersDao,
    private val diplomaticMissionDao: DiplomaticMissionDao,
    private val regionRepository: RegionRepository,
    private val database: RegionDatabase,
) {
    suspend fun import(guidesDbFile: File, version: String) = withContext(Dispatchers.IO) {
        SQLiteDatabase.openDatabase(guidesDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val sections = readGuideSections(db)
            val emergencyNumbers = readEmergencyNumbers(db)
            val noCentralNumber = readNoCentralEmergencyNumber(db)
            val missions = readDiplomaticMissions(db)
            database.withTransaction {
                guideDao.deleteAll()
                emergencyNumbersDao.deleteAll()
                emergencyNumbersDao.deleteAllNoCentralNumber()
                diplomaticMissionDao.deleteAll()
                guideDao.insertAll(sections)
                guideDao.optimizeFts()
                emergencyNumbersDao.insertAll(emergencyNumbers)
                emergencyNumbersDao.insertNoCentralNumber(noCentralNumber)
                diplomaticMissionDao.insertAll(missions)
                regionRepository.markGuidesInstalled(version, guidesDbFile.length())
            }
        }
        guidesDbFile.delete()
    }

    // Le categorie sconosciute (pubblicate da una pipeline piu' recente di questa build dell'app)
    // vengono saltate invece di far fallire l'intero import.
    private fun readGuideSections(db: SQLiteDatabase): List<GuideSectionEntity> {
        val sections = mutableListOf<GuideSectionEntity>()
        db.rawQuery(GUIDE_SECTIONS_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                val category = guideCategoryOrNull(cursor.getString(1)) ?: continue
                sections += GuideSectionEntity(
                    regionId = cursor.getString(0),
                    category = category,
                    title = cursor.getString(2),
                    body = cursor.getString(3),
                    sourceUrl = cursor.getString(4),
                )
            }
        }
        return sections
    }

    private fun readEmergencyNumbers(db: SQLiteDatabase): List<EmergencyNumbersEntity> {
        val numbers = mutableListOf<EmergencyNumbersEntity>()
        db.rawQuery(EMERGENCY_NUMBERS_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                numbers += EmergencyNumbersEntity(
                    regionId = cursor.getString(0),
                    general = if (cursor.isNull(1)) null else cursor.getString(1),
                    police = cursor.getString(2),
                    ambulance = cursor.getString(3),
                    fire = cursor.getString(4),
                )
            }
        }
        return numbers
    }

    // I guides.db pubblicati prima di emergency_numbers_none non hanno la tabella.
    private fun readNoCentralEmergencyNumber(db: SQLiteDatabase): List<NoCentralEmergencyNumberEntity> {
        val hasTable = db.rawQuery(NO_CENTRAL_NUMBER_TABLE_QUERY, null).use { it.moveToFirst() }
        if (!hasTable) return emptyList()
        val regions = mutableListOf<NoCentralEmergencyNumberEntity>()
        db.rawQuery(NO_CENTRAL_NUMBER_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                regions += NoCentralEmergencyNumberEntity(regionId = cursor.getString(0))
            }
        }
        return regions
    }

    // I guides.db pubblicati prima di diplomatic_missions non hanno la tabella.
    private fun readDiplomaticMissions(db: SQLiteDatabase): List<DiplomaticMissionEntity> {
        val hasTable = db.rawQuery(DIPLOMATIC_MISSIONS_TABLE_QUERY, null).use { it.moveToFirst() }
        if (!hasTable) return emptyList()
        val missions = mutableListOf<DiplomaticMissionEntity>()
        db.rawQuery(DIPLOMATIC_MISSIONS_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                missions += DiplomaticMissionEntity(
                    wikidata = cursor.getString(0),
                    sending = cursor.getString(1),
                    host = cursor.getString(2),
                    kind = cursor.getString(3),
                    name = cursor.getString(4),
                    nameEn = cursor.stringOrNull(5),
                    city = cursor.stringOrNull(6),
                    address = cursor.stringOrNull(7),
                    phone = cursor.stringOrNull(8),
                    website = cursor.stringOrNull(9),
                    email = cursor.stringOrNull(10),
                    lat = if (cursor.isNull(11)) null else cursor.getDouble(11),
                    lon = if (cursor.isNull(12)) null else cursor.getDouble(12),
                )
            }
        }
        return missions
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    companion object {
        // Costanti (invece che inline) cosi' un test JVM puro puo' eseguirle via JDBC contro un
        // file prodotto dalla pipeline dati, senza android.database.sqlite.
        internal const val GUIDE_SECTIONS_QUERY = "SELECT regionId, category, title, body, sourceUrl FROM guide_sections"
        internal const val EMERGENCY_NUMBERS_QUERY = "SELECT regionId, general, police, ambulance, fire FROM emergency_numbers"
        internal const val NO_CENTRAL_NUMBER_TABLE_QUERY =
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'emergency_numbers_none'"
        internal const val NO_CENTRAL_NUMBER_QUERY = "SELECT regionId FROM emergency_numbers_none"
        internal const val DIPLOMATIC_MISSIONS_TABLE_QUERY =
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'diplomatic_missions'"
        internal const val DIPLOMATIC_MISSIONS_QUERY =
            "SELECT wikidata, sending, host, kind, name, name_en, city, address, phone, website, email, lat, lon FROM diplomatic_missions"
    }
}
