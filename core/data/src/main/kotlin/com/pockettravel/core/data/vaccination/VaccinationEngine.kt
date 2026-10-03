package com.pockettravel.core.data.vaccination

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Un paese da cui il viaggiatore "proviene", con le ore di scalo se ci e' solo passato. */
private data class Origin(val country: String, val transitHours: Int?)

/**
 * Calcola, offline e senza Android, cosa serve per [trip] secondo [data]. Mai un'eccezione per dati
 * mancanti: senza regola che scatti il risultato ha [VaccinationResult.noCertificateFound] a true.
 *
 * Ipotesi:
 * - "Provenienza" = partenza, soggiorni recenti, scali con uscita dall'aeroporto e scali che superano
 *   la soglia di ore della destinazione (durata ignota = conta). Il paese di destinazione non conta come
 *   provenienza, e se partenza e destinazione coincidono non scatta nessuna regola d'ingresso o d'uscita
 *   (resta l'obbligo legato allo scopo, come il MenACWY dell'Hajj, che vale anche per chi vive nel paese).
 * - Per la polio d'ingresso la provenienza esclude gli scali senza uscita (le fonti non danno soglie di ore).
 * - Il motore non conosce la residenza ne' il visto: i requisiti polio "VISA" e "RESIDENTS" si mostrano
 *   come richiesti (la nota della riga spiega a chi si applicano), per non dire "non serve" per errore.
 */
fun evaluateVaccinations(trip: Trip, data: VaccinationData, today: LocalDate = LocalDate.now()): VaccinationResult {
    val departure = trip.departure.iso()
    val destination = trip.destination.iso()
    val travelling = departure != destination
    val items = mutableListOf<VaccinationItem>()

    val stays = buildList {
        add(Origin(departure, null))
        trip.recentCountries.forEach { add(Origin(it.iso(), null)) }
        trip.transits.filter { it.leftAirport }.forEach { add(Origin(it.country.iso(), null)) }
    }.filter { it.country != destination }.distinctBy { it.country }

    val riskCountries = data.yfRisk.mapTo(mutableSetOf()) { it.iso2 }
    val yfEntry = data.yfEntry.firstOrNull { it.iso2 == destination }

    if (travelling && yfEntry != null) {
        val trigger: Origin? = when (yfEntry.rule) {
            YfRule.NONE -> null
            YfRule.ALL -> Origin(departure, null)
            YfRule.FROM_RISK -> yfOrigins(trip, stays, yfEntry.transit, destination).firstOrNull { it.country in riskCountries }
            YfRule.FROM_LIST -> yfOrigins(trip, stays, yfEntry.transit, destination).firstOrNull { it.country in yfEntry.fromList }
        }
        if (trigger != null) {
            val (level, ageNote) = applyAge(VaccinationLevel.REQUIRED, yfEntry.minAgeMonths, trip.travellerAgeMonths)
            items += VaccinationItem(
                vaccine = Vaccine.YELLOW_FEVER,
                level = level,
                reason = when (yfEntry.rule) {
                    YfRule.ALL -> VaccinationReason.YF_ENTRY_ALL
                    YfRule.FROM_LIST -> VaccinationReason.YF_ENTRY_FROM_LIST
                    else -> VaccinationReason.YF_ENTRY_FROM_RISK
                },
                country = if (yfEntry.rule == YfRule.ALL) null else trigger.country,
                transitHours = trigger.transitHours,
                minAgeMonths = yfEntry.minAgeMonths,
                ageNote = ageNote,
                noteIt = yfEntry.noteIt,
                noteEn = yfEntry.noteEn,
                sources = yfEntry.sources,
                verifiedAt = yfEntry.verifiedAt,
            )
        }
    }

    val yfExit = data.yfEntry.firstOrNull { it.iso2 == departure }
    if (travelling && yfExit != null && yfExit.exitRequired) {
        val (level, ageNote) = applyAge(VaccinationLevel.REQUIRED_EXIT, yfExit.minAgeMonths, trip.travellerAgeMonths)
        items += VaccinationItem(
            vaccine = Vaccine.YELLOW_FEVER,
            level = level,
            reason = VaccinationReason.YF_EXIT,
            country = departure,
            minAgeMonths = yfExit.minAgeMonths,
            ageNote = ageNote,
            noteIt = yfExit.noteIt,
            noteEn = yfExit.noteEn,
            sources = yfExit.sources,
            verifiedAt = yfExit.verifiedAt,
        )
    }

    // Se il certificato e' gia' richiesto, la raccomandazione per la destinazione a rischio non aggiunge nulla.
    // Sotto i 9 mesi il vaccino di norma non si fa (sotto i 6 e' controindicato): da valutare col medico.
    val yfInfant = trip.travellerAgeMonths?.let { it < YF_RECOMMENDED_MIN_AGE_MONTHS } == true
    data.yfRisk.firstOrNull { it.iso2 == destination }?.takeIf { items.none { it.vaccine == Vaccine.YELLOW_FEVER && it.ageNote != AgeNote.BELOW_AGE } }?.let { risk ->
        items += VaccinationItem(
            vaccine = Vaccine.YELLOW_FEVER,
            level = if (yfInfant) VaccinationLevel.CONSIDER else VaccinationLevel.RECOMMENDED,
            minAgeMonths = if (yfInfant) YF_RECOMMENDED_MIN_AGE_MONTHS else null,
            ageNote = if (yfInfant) AgeNote.BELOW_AGE else AgeNote.NONE,
            reason = VaccinationReason.YF_DESTINATION_RISK,
            country = destination,
            partialArea = risk.partial,
            noteIt = if (risk.partial) risk.areasIt else "",
            noteEn = if (risk.partial) risk.areasEn else "",
            sources = risk.sources,
            verifiedAt = risk.verifiedAt,
        )
    }

    if (travelling) {
        polioExit(trip, data, departure)?.let { items += it }
        items += polioEntry(trip, data, destination, stays)
    }

    if (trip.purpose != null) {
        data.special.filter { it.iso2 == destination && it.purpose == trip.purpose }.forEach { row ->
            val (level, ageNote) = applyAge(VaccinationLevel.REQUIRED, row.minAgeMonths, trip.travellerAgeMonths)
            items += VaccinationItem(
                vaccine = row.vaccine,
                level = level,
                reason = VaccinationReason.HAJJ_UMRAH,
                country = destination,
                minAgeMonths = row.minAgeMonths,
                ageNote = ageNote,
                minDaysBefore = row.minDaysBefore,
                validityYears = row.validityYears,
                noteIt = row.noteIt,
                noteEn = row.noteEn,
                sources = row.sources,
                verifiedAt = row.verifiedAt,
            )
        }
    }

    // Raccomandate per destinazione: MOST = consigliate, SOME = da valutare. Una riga che ripete un vaccino
    // gia' presente (febbre gialla a rischio, MenACWY dell'Hajj) si salta, salvo che la voce sia solo
    // "non richiesto per l'eta'".
    data.recommended.filter { it.iso2 == destination }.forEach { row ->
        if (items.any { it.vaccine == row.vaccine && it.ageNote != AgeNote.BELOW_AGE }) return@forEach
        val most = row.level == RecommendedLevel.MOST
        items += VaccinationItem(
            vaccine = row.vaccine,
            level = if (most) VaccinationLevel.RECOMMENDED else VaccinationLevel.CONSIDER,
            reason = if (most) VaccinationReason.DESTINATION_MOST else VaccinationReason.DESTINATION_SOME,
            country = destination,
            noteIt = row.conditionIt,
            noteEn = row.conditionEn,
            sources = row.sources,
            verifiedAt = row.verifiedAt,
        )
    }

    items += VaccinationItem(
        vaccine = Vaccine.ROUTINE,
        level = VaccinationLevel.RECOMMENDED,
        reason = VaccinationReason.ROUTINE,
    )

    val sorted = items.sortedWith(compareBy({ it.level.ordinal }, { it.vaccine.ordinal }))
    val polioVerified = parseIsoDate(data.meta.polioVerifiedAt ?: data.polioStatus.maxOfOrNull { it.verifiedAt })
    return VaccinationResult(
        items = sorted,
        noCertificateFound = sorted.none { it.level == VaccinationLevel.REQUIRED || it.level == VaccinationLevel.REQUIRED_EXIT },
        checkedOn = data.meta.lastReview ?: allVerifiedDates(data).maxOrNull(),
        polioDataStale = polioVerified != null && ChronoUnit.DAYS.between(polioVerified, today) > POLIO_STALE_DAYS,
        polioStatement = data.meta.polioStatement ?: data.polioStatus.firstOrNull()?.statement,
    )
}

private fun String.iso(): String = trim().lowercase(Locale.ROOT)

/** Soggiorni piu' gli scali senza uscita che superano la soglia di ore della destinazione. */
private fun yfOrigins(trip: Trip, stays: List<Origin>, rule: TransitRule, destination: String): List<Origin> {
    val passing = trip.transits
        .filter { !it.leftAirport && transitCounts(rule, it.transitHours) }
        .map { Origin(it.country.iso(), it.transitHours) }
        .filter { it.country != destination }
    return (stays + passing).distinctBy { it.country }
}

private fun transitCounts(rule: TransitRule, hours: Int?): Boolean = when (rule) {
    TransitRule.NONE -> false
    TransitRule.ANY -> true
    else -> hours == null || hours > rule.hours!!
}

private const val YF_RECOMMENDED_MIN_AGE_MONTHS = 9

/** Sotto la soglia di eta' l'obbligo non si applica: la voce scende a CONSIDER con [AgeNote.BELOW_AGE]. */
private fun applyAge(level: VaccinationLevel, minAgeMonths: Int?, ageMonths: Int?): Pair<VaccinationLevel, AgeNote> = when {
    minAgeMonths == null -> level to AgeNote.NONE
    ageMonths == null -> level to AgeNote.FROM_AGE
    ageMonths < minAgeMonths -> VaccinationLevel.CONSIDER to AgeNote.BELOW_AGE
    else -> level to AgeNote.NONE
}

/** Uscita (RSI): obbligo da paesi WPV1/cVDPV1/cVDPV3 con soggiorno oltre 4 settimane, altrimenti dose incoraggiata per cVDPV2. */
private fun polioExit(trip: Trip, data: VaccinationData, departure: String): VaccinationItem? {
    val statuses = data.polioStatus.filter { it.iso2 == departure }
    val severe = statuses.firstOrNull { it.category == PolioCategory.WPV1_CVDPV1_CVDPV3 }
    if (severe != null && trip.stayOverFourWeeksInDeparture) {
        return polioItem(VaccinationLevel.REQUIRED_EXIT, VaccinationReason.POLIO_EXIT, departure, severe)
    }
    val encouraged = statuses.firstOrNull { it.category == PolioCategory.CVDPV2 }
    return encouraged?.let { polioItem(VaccinationLevel.ENCOURAGED, VaccinationReason.POLIO_ENCOURAGED, departure, it) }
}

private fun polioItem(level: VaccinationLevel, reason: VaccinationReason, country: String, status: PolioStatusRow) = VaccinationItem(
    vaccine = Vaccine.POLIO,
    level = level,
    reason = reason,
    country = country,
    polioDose = PolioDose(PolioVaccine.BOPV_OR_IPV, PolioWindow.W4_12M),
    sources = status.sources,
    verifiedAt = status.verifiedAt,
)

/** Ingresso: righe della destinazione confrontate con la provenienza; le categorie si risolvono con polioStatus. */
private fun polioEntry(trip: Trip, data: VaccinationData, destination: String, stays: List<Origin>): List<VaccinationItem> {
    val byCategory = data.polioStatus.groupBy({ it.category }, { it.iso2 })
    return data.polioEntry
        .filter { it.iso2 == destination }
        .filter { it.applies != PolioApplies.HAJJ_UMRAH || trip.purpose == TripPurpose.HAJJ_UMRAH }
        .mapNotNull { row ->
            val countries = row.originCountries + (row.originCategory?.let { byCategory[it] }.orEmpty())
            stays.firstOrNull { it.country in countries }?.let { row to it }
        }
        .distinctBy { (row, _) -> listOf(row.vaccine, row.window, row.applies, row.noteIt, row.noteEn) }
        .map { (row, origin) ->
            VaccinationItem(
                vaccine = Vaccine.POLIO,
                level = VaccinationLevel.REQUIRED,
                reason = VaccinationReason.POLIO_ENTRY,
                country = origin.country,
                polioDose = PolioDose(row.vaccine, row.window, row.applies),
                noteIt = row.noteIt,
                noteEn = row.noteEn,
                sources = row.sources,
                verifiedAt = row.verifiedAt,
            )
        }
}

private fun allVerifiedDates(data: VaccinationData): List<String> =
    data.yfRisk.map { it.verifiedAt } + data.yfEntry.map { it.verifiedAt } + data.polioStatus.map { it.verifiedAt } +
        data.polioEntry.map { it.verifiedAt } + data.special.map { it.verifiedAt } + data.recommended.map { it.verifiedAt }
