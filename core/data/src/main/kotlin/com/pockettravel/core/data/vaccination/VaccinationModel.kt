package com.pockettravel.core.data.vaccination

import java.time.LocalDate

/*
 * Modello in memoria dei dati vaccinali e del viaggio, senza Android ne' Room: lo usa il motore delle
 * regole (evaluateVaccinations) e lo riempie VaccinationRepository dalle tabelle vacc_* di region.db.
 * Tutti i paesi sono ISO 3166-1 alpha-2 minuscoli, come il flagCode delle regioni.
 */

/** Vaccino a cui si riferisce una voce del risultato. [ROUTINE] e' il calendario vaccinale di routine. */
enum class Vaccine { YELLOW_FEVER, POLIO, MENACWY, HEPA, HEPB, TYPHOID, RABIES, JE, TBE, CHOLERA, DENGUE, CHIK, ROUTINE }

enum class VaccinationLevel {
    /** Certificato richiesto all'ingresso (o dalla compagnia/visto) per questo percorso. */
    REQUIRED,

    /** Certificato richiesto all'uscita dal paese di partenza. */
    REQUIRED_EXIT,

    /** Dose incoraggiata dall'OMS, non obbligatoria. */
    ENCOURAGED,

    /** Raccomandato (destinazione a rischio, "most travellers", routine). */
    RECOMMENDED,

    /** Da valutare con il medico ("some travellers"), oppure obbligo non applicabile per l'eta' indicata. */
    CONSIDER,
}

/** Perche' una voce compare nel risultato; i parametri stanno negli altri campi di [VaccinationItem]. */
enum class VaccinationReason {
    YF_ENTRY_ALL,
    YF_ENTRY_FROM_RISK,
    YF_ENTRY_FROM_LIST,
    YF_EXIT,
    YF_DESTINATION_RISK,
    POLIO_EXIT,
    POLIO_ENCOURAGED,
    POLIO_ENTRY,
    HAJJ_UMRAH,
    DESTINATION_MOST,
    DESTINATION_SOME,
    ROUTINE,
}

/** Rapporto dell'eta' del viaggiatore con la soglia [VaccinationItem.minAgeMonths]. */
enum class AgeNote {
    /** Nessuna soglia, oppure eta' nota e sopra la soglia. */
    NONE,

    /** Soglia presente ma eta' non indicata: "da N mesi in su". */
    FROM_AGE,

    /** Eta' indicata sotto la soglia: obbligo non applicabile (voce a livello [VaccinationLevel.CONSIDER]). */
    BELOW_AGE,
}

enum class TripPurpose { HAJJ_UMRAH }

enum class YfRule { NONE, FROM_RISK, ALL, FROM_LIST }

/** Transiti che contano come "provenienza" per la febbre gialla: [hours] e' la soglia (esclusa), null = nessuna. */
enum class StopoverRule(val hours: Int?) {
    NONE(null),
    ANY(null),
    GT4H(4),
    GT12H(12),
    GT24H(24),
}

enum class PolioCategory { WPV1_CVDPV1_CVDPV3, CVDPV2, PREVIOUSLY_INFECTED }

enum class PolioVaccine { BOPV_OR_IPV, IPV }

/** Finestra della dose: [W4_12M] tra 4 settimane e 12 mesi prima di partire, [ANY] nessuna finestra fissa. */
enum class PolioWindow { W4_12M, ANY }

/** A chi si applica un requisito polio d'ingresso (il motore non conosce la residenza: vedi evaluateVaccinations). */
enum class PolioApplies { ALL, VISA, RESIDENTS, HAJJ_UMRAH }

enum class RecommendedLevel { MOST, SOME }

data class YfRiskRow(
    val iso2: String,
    val partial: Boolean,
    val areasIt: String,
    val areasEn: String,
    val sources: List<String>,
    val verifiedAt: String,
)

data class YfEntryRow(
    val iso2: String,
    val rule: YfRule,
    val minAgeMonths: Int?,
    val transit: StopoverRule,
    val fromList: Set<String>,
    val exitRequired: Boolean,
    val noteIt: String,
    val noteEn: String,
    val sources: List<String>,
    val verifiedAt: String,
)

data class PolioStatusRow(
    val iso2: String,
    val category: PolioCategory,
    val statement: String,
    val sources: List<String>,
    val verifiedAt: String,
)

/** Requisito polio d'ingresso di [iso2]: vale per chi arriva da un paese della [originCategory] o di [originCountries]. */
data class PolioEntryRow(
    val iso2: String,
    val originCategory: PolioCategory?,
    val originCountries: Set<String>,
    val vaccine: PolioVaccine,
    val window: PolioWindow,
    val applies: PolioApplies,
    val noteIt: String,
    val noteEn: String,
    val sources: List<String>,
    val verifiedAt: String,
)

data class SpecialEntryRow(
    val iso2: String,
    val purpose: TripPurpose,
    val vaccine: Vaccine,
    val minAgeMonths: Int?,
    val minDaysBefore: Int?,
    val validityYears: Int?,
    val noteIt: String,
    val noteEn: String,
    val sources: List<String>,
    val verifiedAt: String,
)

data class RecommendedRow(
    val iso2: String,
    val vaccine: Vaccine,
    val level: RecommendedLevel,
    val conditionIt: String,
    val conditionEn: String,
    val sources: List<String>,
    val verifiedAt: String,
)

/** polioVerifiedAt: data dell'ultimo statement polio recepito; lastReview: verifica piu' recente di tutti i file. */
data class VaccinationMeta(
    val polioStatement: String? = null,
    val polioVerifiedAt: String? = null,
    val lastReview: String? = null,
)

/** Le tabelle vaccinali per intero, come le pubblica la pipeline. Tutte vuote = pacchetto guide senza dati vaccinali. */
data class VaccinationData(
    val yfRisk: List<YfRiskRow> = emptyList(),
    val yfEntry: List<YfEntryRow> = emptyList(),
    val polioStatus: List<PolioStatusRow> = emptyList(),
    val polioEntry: List<PolioEntryRow> = emptyList(),
    val special: List<SpecialEntryRow> = emptyList(),
    val recommended: List<RecommendedRow> = emptyList(),
    val meta: VaccinationMeta = VaccinationMeta(),
) {
    val isEmpty: Boolean
        get() = yfRisk.isEmpty() && yfEntry.isEmpty() && polioStatus.isEmpty() && polioEntry.isEmpty() &&
            special.isEmpty() && recommended.isEmpty()
}

/**
 * Una tappa intermedia: [transitHours] null = durata non indicata (le soglie di ore la contano comunque,
 * per prudenza); [leftAirport] = il viaggiatore esce dall'aeroporto, e allora conta come soggiorno.
 */
data class TripLeg(val country: String, val transitHours: Int?, val leftAirport: Boolean)

data class Trip(
    val departure: String,
    /** Soggiorni recenti prima della partenza (facoltativo). */
    val recentCountries: Set<String> = emptySet(),
    val transits: List<TripLeg> = emptyList(),
    val destination: String,
    /** null = non chiesta: le soglie di eta' si mostrano senza decidere. */
    val travellerAgeMonths: Int? = null,
    /** Residente nel paese di partenza o li' per oltre 4 settimane (soglia dell'obbligo polio in uscita). */
    val stayOverFourWeeksInDeparture: Boolean = false,
    val purpose: TripPurpose? = null,
)

/** Dettagli della dose per le voci polio (POLIO_EXIT, POLIO_ENCOURAGED, POLIO_ENTRY). */
data class PolioDose(val vaccine: PolioVaccine, val window: PolioWindow, val applies: PolioApplies? = null)

/**
 * Una voce del risultato. [country] e' il paese che fa scattare la regola (provenienza, partenza o
 * destinazione secondo [reason]); [transitHours] solo se a farla scattare e' stato uno scalo senza uscita
 * dall'aeroporto ([stopover] e' true anche quando la durata e' ignota e [transitHours] resta null). [noteIt] e [noteEn] sono le note della riga di dati (vuote se non ce ne sono).
 */
data class VaccinationItem(
    val vaccine: Vaccine,
    val level: VaccinationLevel,
    val reason: VaccinationReason,
    val country: String? = null,
    val transitHours: Int? = null,
    val stopover: Boolean = false,
    val minAgeMonths: Int? = null,
    val ageNote: AgeNote = AgeNote.NONE,
    val minDaysBefore: Int? = null,
    val validityYears: Int? = null,
    val polioDose: PolioDose? = null,
    val partialArea: Boolean = false,
    val noteIt: String = "",
    val noteEn: String = "",
    val sources: List<String> = emptyList(),
    val verifiedAt: String? = null,
)

/**
 * Esito per un viaggio. [noCertificateFound] e' true quando nessuna voce e' REQUIRED o REQUIRED_EXIT: la
 * UI deve dire "nei nostri dati, verificati il [checkedOn], nessun certificato risulta richiesto", mai
 * "non serve nulla". [polioDataStale]: i dati polio hanno piu' di [POLIO_STALE_DAYS] giorni.
 */
data class VaccinationResult(
    val items: List<VaccinationItem>,
    val noCertificateFound: Boolean,
    val checkedOn: String?,
    val polioDataStale: Boolean,
    val polioStatement: String?,
)

const val POLIO_STALE_DAYS = 120L

/** Nome leggibile delle sigle delle fonti usate nei dati (stesse sigle dei file della pipeline). */
fun vaccinationSourceName(code: String): String = when (code) {
    "F3" -> "OMS (Polio IHR Emergency Committee)"
    "F6" -> "Travel.gc.ca"
    "F7" -> "TravelHealthPro"
    else -> code
}

internal fun parseIsoDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
