package com.pockettravel.core.data.vaccination

import com.pockettravel.core.data.db.VaccMetaEntity
import com.pockettravel.core.data.db.VaccPolioEntryEntity
import com.pockettravel.core.data.db.VaccPolioStatusEntity
import com.pockettravel.core.data.db.VaccRecommendedEntity
import com.pockettravel.core.data.db.VaccSpecialEntity
import com.pockettravel.core.data.db.VaccYfEntryEntity
import com.pockettravel.core.data.db.VaccYfRiskEntity
import com.pockettravel.core.data.db.VaccinationDao
import javax.inject.Inject

class VaccinationRepository @Inject constructor(private val vaccinationDao: VaccinationDao) {
    /** I dati vaccinali di region.db, o null se il pacchetto guide installato non li contiene (o non c'e' ancora). */
    suspend fun load(): VaccinationData? = vaccinationDataFrom(
        yfRisk = vaccinationDao.yfRisk(),
        yfEntry = vaccinationDao.yfEntry(),
        polioStatus = vaccinationDao.polioStatus(),
        polioEntry = vaccinationDao.polioEntry(),
        special = vaccinationDao.special(),
        recommended = vaccinationDao.recommended(),
        meta = vaccinationDao.meta(),
    ).takeUnless { it.isEmpty }

    /** Esito per [trip], o null se mancano i dati (allora la UI non mostra la scheda). */
    suspend fun evaluate(trip: Trip): VaccinationResult? = load()?.let { evaluateVaccinations(trip, it) }
}

/**
 * Converte le righe di Room nel modello del motore. I valori che questa versione dell'app non conosce
 * (codici nuovi di una pipeline piu' recente) fanno saltare la singola riga, non l'intero caricamento.
 */
internal fun vaccinationDataFrom(
    yfRisk: List<VaccYfRiskEntity>,
    yfEntry: List<VaccYfEntryEntity>,
    polioStatus: List<VaccPolioStatusEntity>,
    polioEntry: List<VaccPolioEntryEntity>,
    special: List<VaccSpecialEntity>,
    recommended: List<VaccRecommendedEntity>,
    meta: List<VaccMetaEntity>,
): VaccinationData {
    val metaByKey = meta.associate { it.key to it.value }
    return VaccinationData(
        yfRisk = yfRisk.mapNotNull { r ->
            when (r.scope) {
                "WHOLE", "PARTIAL" -> YfRiskRow(r.iso2, r.scope == "PARTIAL", r.areasIt, r.areasEn, r.sources.codes(), r.verified)
                else -> null
            }
        },
        yfEntry = yfEntry.mapNotNull { r ->
            YfEntryRow(
                iso2 = r.iso2,
                rule = enumOrNull<YfRule>(r.rule) ?: return@mapNotNull null,
                minAgeMonths = r.minAgeMonths,
                transit = enumOrNull<StopoverRule>(r.transit) ?: return@mapNotNull null,
                fromList = r.fromList.countries(),
                exitRequired = r.exitRequired,
                noteIt = r.noteIt,
                noteEn = r.noteEn,
                sources = r.sources.codes(),
                verifiedAt = r.verified,
            )
        },
        polioStatus = polioStatus.mapNotNull { r ->
            enumOrNull<PolioCategory>(r.category)?.let { PolioStatusRow(r.iso2, it, r.statement, r.sources.codes(), r.verified) }
        },
        polioEntry = polioEntry.mapNotNull { r ->
            val category = if (r.origin.startsWith(CATEGORY_PREFIX)) {
                enumOrNull<PolioCategory>(r.origin.removePrefix(CATEGORY_PREFIX)) ?: return@mapNotNull null
            } else {
                null
            }
            PolioEntryRow(
                iso2 = r.iso2,
                originCategory = category,
                originCountries = if (category == null) r.origin.countries() else emptySet(),
                vaccine = enumOrNull<PolioVaccine>(r.vaccine) ?: return@mapNotNull null,
                window = when (r.timeWindow) {
                    "4W_12M" -> PolioWindow.W4_12M
                    "ANY" -> PolioWindow.ANY
                    else -> return@mapNotNull null
                },
                applies = enumOrNull<PolioApplies>(r.applies) ?: return@mapNotNull null,
                noteIt = r.noteIt,
                noteEn = r.noteEn,
                sources = r.sources.codes(),
                verifiedAt = r.verified,
            )
        },
        special = special.mapNotNull { r ->
            SpecialEntryRow(
                iso2 = r.iso2,
                purpose = enumOrNull<TripPurpose>(r.purpose) ?: return@mapNotNull null,
                vaccine = vaccineOrNull(r.vaccine) ?: return@mapNotNull null,
                minAgeMonths = r.minAgeMonths,
                minDaysBefore = r.minDaysBefore,
                validityYears = r.validityYears,
                noteIt = r.noteIt,
                noteEn = r.noteEn,
                sources = r.sources.codes(),
                verifiedAt = r.verified,
            )
        },
        recommended = recommended.mapNotNull { r ->
            RecommendedRow(
                iso2 = r.iso2,
                vaccine = vaccineOrNull(r.vaccine) ?: return@mapNotNull null,
                level = enumOrNull<RecommendedLevel>(r.level) ?: return@mapNotNull null,
                conditionIt = r.conditionIt,
                conditionEn = r.conditionEn,
                sources = r.sources.codes(),
                verifiedAt = r.verified,
            )
        },
        meta = VaccinationMeta(
            polioStatement = metaByKey["polio_statement"],
            polioVerifiedAt = metaByKey["polio_verified"],
            lastReview = metaByKey["last_review"],
        ),
    )
}

private const val CATEGORY_PREFIX = "CAT:"

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

// Nei dati la febbre gialla e' "YF"; negli altri casi il codice e' il nome dell'enum.
private fun vaccineOrNull(code: String): Vaccine? = if (code == "YF") Vaccine.YELLOW_FEVER else enumOrNull<Vaccine>(code)

private fun String.codes(): List<String> = split("+").map { it.trim() }.filter { it.isNotEmpty() }

private fun String.countries(): Set<String> = split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
