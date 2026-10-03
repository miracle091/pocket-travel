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
import com.pockettravel.core.data.db.VaccMetaEntity
import com.pockettravel.core.data.db.VaccPolioEntryEntity
import com.pockettravel.core.data.db.VaccPolioStatusEntity
import com.pockettravel.core.data.db.VaccRecommendedEntity
import com.pockettravel.core.data.db.VaccSpecialEntity
import com.pockettravel.core.data.db.VaccYfEntryEntity
import com.pockettravel.core.data.db.VaccYfRiskEntity
import com.pockettravel.core.data.db.VaccinationDao
import com.pockettravel.core.data.guideCategoryOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Importa guides.db (guide Wikivoyage, numeri di emergenza e ambasciate/consolati Wikidata di tutte le
 * regioni, generato da tools/data-pipeline) in region.db: sostituisce tutte le righe di guide_sections,
 * emergency_numbers, emergency_numbers_none, diplomatic_missions e vacc_* (dati vaccinali) e registra la versione installata, in
 * una sola transazione. guides.db viene
 * cancellato dopo l'import: il contenuto e' gia' in region.db.
 */
class GuidesImporter @Inject constructor(
    private val guideDao: GuideDao,
    private val emergencyNumbersDao: EmergencyNumbersDao,
    private val diplomaticMissionDao: DiplomaticMissionDao,
    private val vaccinationDao: VaccinationDao,
    private val regionRepository: RegionRepository,
    private val database: RegionDatabase,
) {
    suspend fun import(guidesDbFile: File, version: String) = withContext(Dispatchers.IO) {
        SQLiteDatabase.openDatabase(guidesDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val sections = readGuideSections(db)
            val emergencyNumbers = readEmergencyNumbers(db)
            val noCentralNumber = readNoCentralEmergencyNumber(db)
            val missions = readDiplomaticMissions(db)
            val vaccinations = readVaccinations(db)
            database.withTransaction {
                guideDao.deleteAll()
                emergencyNumbersDao.deleteAll()
                emergencyNumbersDao.deleteAllNoCentralNumber()
                diplomaticMissionDao.deleteAll()
                vaccinationDao.deleteAll()
                guideDao.insertAll(sections)
                guideDao.optimizeFts()
                emergencyNumbersDao.insertAll(emergencyNumbers)
                emergencyNumbersDao.insertNoCentralNumber(noCentralNumber)
                diplomaticMissionDao.insertAll(missions)
                vaccinations.insertInto(vaccinationDao)
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

    // I guides.db pubblicati prima dei dati vaccinali non hanno le tabelle vacc_*: nessuna riga, nessuna scheda.
    private fun readVaccinations(db: SQLiteDatabase): VaccinationRows {
        fun <T> read(table: String, query: String, map: (Cursor) -> T): List<T> {
            val hasTable = db.rawQuery(tableQuery(table), null).use { it.moveToFirst() }
            if (!hasTable) return emptyList()
            val rows = mutableListOf<T>()
            db.rawQuery(query, null).use { cursor ->
                while (cursor.moveToNext()) rows += map(cursor)
            }
            return rows
        }
        return VaccinationRows(
            yfRisk = read("vacc_yf_risk", VACC_YF_RISK_QUERY) {
                VaccYfRiskEntity(
                    iso2 = it.getString(0), scope = it.getString(1), areasIt = it.getString(2), areasEn = it.getString(3),
                    sources = it.getString(4), verified = it.getString(5),
                )
            },
            yfEntry = read("vacc_yf_entry", VACC_YF_ENTRY_QUERY) {
                VaccYfEntryEntity(
                    iso2 = it.getString(0), rule = it.getString(1), minAgeMonths = it.intOrNull(2), transit = it.getString(3),
                    fromList = it.getString(4), exitRequired = it.getInt(5) != 0, noteIt = it.getString(6), noteEn = it.getString(7),
                    sources = it.getString(8), verified = it.getString(9),
                )
            },
            polioStatus = read("vacc_polio_status", VACC_POLIO_STATUS_QUERY) {
                VaccPolioStatusEntity(
                    iso2 = it.getString(0), category = it.getString(1), statement = it.getString(2), sources = it.getString(3),
                    verified = it.getString(4),
                )
            },
            polioEntry = read("vacc_polio_entry", VACC_POLIO_ENTRY_QUERY) {
                VaccPolioEntryEntity(
                    iso2 = it.getString(0), origin = it.getString(1), vaccine = it.getString(2), timeWindow = it.getString(3),
                    applies = it.getString(4), noteIt = it.getString(5), noteEn = it.getString(6), sources = it.getString(7),
                    verified = it.getString(8),
                )
            },
            special = read("vacc_special", VACC_SPECIAL_QUERY) {
                VaccSpecialEntity(
                    iso2 = it.getString(0), purpose = it.getString(1), vaccine = it.getString(2), minAgeMonths = it.intOrNull(3),
                    minDaysBefore = it.intOrNull(4), validityYears = it.intOrNull(5), noteIt = it.getString(6),
                    noteEn = it.getString(7), sources = it.getString(8), verified = it.getString(9),
                )
            },
            recommended = read("vacc_recommended", VACC_RECOMMENDED_QUERY) {
                VaccRecommendedEntity(
                    iso2 = it.getString(0), vaccine = it.getString(1), level = it.getString(2), conditionIt = it.getString(3),
                    conditionEn = it.getString(4), sources = it.getString(5), verified = it.getString(6),
                )
            },
            meta = read("vacc_meta", VACC_META_QUERY) { VaccMetaEntity(key = it.getString(0), value = it.getString(1)) },
        )
    }

    private class VaccinationRows(
        val yfRisk: List<VaccYfRiskEntity>,
        val yfEntry: List<VaccYfEntryEntity>,
        val polioStatus: List<VaccPolioStatusEntity>,
        val polioEntry: List<VaccPolioEntryEntity>,
        val special: List<VaccSpecialEntity>,
        val recommended: List<VaccRecommendedEntity>,
        val meta: List<VaccMetaEntity>,
    ) {
        suspend fun insertInto(dao: VaccinationDao) {
            dao.insertYfRisk(yfRisk)
            dao.insertYfEntry(yfEntry)
            dao.insertPolioStatus(polioStatus)
            dao.insertPolioEntry(polioEntry)
            dao.insertSpecial(special)
            dao.insertRecommended(recommended)
            dao.insertMeta(meta)
        }
    }

    private fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

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
        internal fun tableQuery(table: String) = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'"
        internal const val VACC_YF_RISK_QUERY = "SELECT iso2, scope, areasIt, areasEn, sources, verified FROM vacc_yf_risk"
        internal const val VACC_YF_ENTRY_QUERY =
            "SELECT iso2, rule, minAgeMonths, transit, fromList, exitRequired, noteIt, noteEn, sources, verified FROM vacc_yf_entry"
        internal const val VACC_POLIO_STATUS_QUERY = "SELECT iso2, category, statement, sources, verified FROM vacc_polio_status"
        internal const val VACC_POLIO_ENTRY_QUERY =
            "SELECT iso2, origin, vaccine, timeWindow, applies, noteIt, noteEn, sources, verified FROM vacc_polio_entry"
        internal const val VACC_SPECIAL_QUERY =
            "SELECT iso2, purpose, vaccine, minAgeMonths, minDaysBefore, validityYears, noteIt, noteEn, sources, verified FROM vacc_special"
        internal const val VACC_RECOMMENDED_QUERY =
            "SELECT iso2, vaccine, level, conditionIt, conditionEn, sources, verified FROM vacc_recommended"
        internal const val VACC_META_QUERY = "SELECT key, value FROM vacc_meta"
        internal const val DIPLOMATIC_MISSIONS_QUERY =
            "SELECT wikidata, sending, host, kind, name, name_en, city, address, phone, website, email, lat, lon FROM diplomatic_missions"
    }
}
