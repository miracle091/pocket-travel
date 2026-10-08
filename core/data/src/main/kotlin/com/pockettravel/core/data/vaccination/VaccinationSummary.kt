package com.pockettravel.core.data.vaccination

import java.util.Locale

/**
 * Riassunto in testo semplice di [VaccinationResult] per [trip], da mettere nel contesto dell'assistente
 * IA: obblighi, incoraggiate, raccomandate, fonti e data di verifica, e sempre la riga che rimanda ad
 * ambasciata e centro di medicina dei viaggi. [language] e' "it" o "en" come in PromptTemplates; ogni
 * altro valore da' l'italiano. Quando nessun certificato risulta richiesto lo dice "nei nostri dati",
 * mai "non serve nulla".
 */
fun VaccinationResult.toSummaryText(trip: Trip, language: String = "it"): String {
    val en = language == "en"
    val locale = if (en) Locale.ENGLISH else Locale.ITALIAN
    fun country(iso: String) = countryName(iso, locale)
    fun pick(it: String, enText: String) = if (en) enText else it

    val route = buildString {
        append(country(trip.departure))
        trip.transits.forEach { append(" -> ").append(country(it.country)) }
        append(" -> ").append(country(trip.destination))
    }
    val lines = mutableListOf<String>()
    lines += if (en) {
        "Vaccinations for the trip $route" + (checkedOn?.let { " (data checked on $it)" } ?: "") + ":"
    } else {
        "Vaccinazioni per il viaggio $route" + (checkedOn?.let { " (dati verificati il $it)" } ?: "") + ":"
    }

    val certificates = items.filter { it.level == VaccinationLevel.REQUIRED || it.level == VaccinationLevel.REQUIRED_EXIT }
    if (certificates.isEmpty()) {
        lines += pick(
            "Nei nostri dati nessun certificato risulta richiesto per questo percorso (non è una garanzia: verifica i requisiti).",
            "In our data no certificate is listed as required for this route (not a guarantee: check the requirements).",
        )
    } else {
        lines += pick("Certificati richiesti:", "Required certificates:")
        certificates.forEach { lines += "- " + itemLine(it, en, ::country) }
    }

    val encouraged = items.filter { it.level == VaccinationLevel.ENCOURAGED }
    if (encouraged.isNotEmpty()) {
        lines += pick("Incoraggiate (non obbligatorie):", "Encouraged (not mandatory):")
        encouraged.forEach { lines += "- " + itemLine(it, en, ::country) }
    }

    val belowAge = items.filter { it.ageNote == AgeNote.BELOW_AGE }
    if (belowAge.isNotEmpty()) {
        lines += pick("Non richiesti per l'età indicata:", "Not required at the age given:")
        belowAge.forEach { lines += "- " + itemLine(it, en, ::country) }
    }

    val recommendedNames = items.filter { it.level == VaccinationLevel.RECOMMENDED && it.vaccine != Vaccine.ROUTINE }
    if (recommendedNames.isNotEmpty()) {
        lines += pick("Raccomandate per la destinazione: ", "Recommended for the destination: ") +
            recommendedNames.joinToString(", ") { vaccineName(it.vaccine, en) + (if (it.partialArea) pick(" (solo in alcune aree)", " (some areas only)") else "") }
    }
    items.filter { it.reason == VaccinationReason.POLIO_ENTRY_UNLISTED }.forEach { lines += itemLine(it, en, ::country) + "." }
    val considerNames = items.filter {
        it.level == VaccinationLevel.CONSIDER && it.ageNote != AgeNote.BELOW_AGE && it.reason != VaccinationReason.POLIO_ENTRY_UNLISTED
    }
    if (considerNames.isNotEmpty()) {
        lines += pick("Da valutare con il medico secondo il viaggio: ", "To discuss with a doctor depending on the trip: ") +
            considerNames.joinToString(", ") { vaccineName(it.vaccine, en) }
    }
    if (items.any { it.vaccine == Vaccine.ROUTINE }) {
        lines += pick(
            "Vaccinazioni di routine: verifica di essere in regola con il calendario del tuo paese.",
            "Routine vaccinations: check you are up to date with your country's schedule.",
        )
    }

    if (polioDataStale) {
        lines += pick(
            "Attenzione: i dati sulla polio potrebbero non essere aggiornati" + (polioStatement?.let { " ($it)" } ?: "") + ".",
            "Warning: the polio data may be out of date" + (polioStatement?.let { " ($it)" } ?: "") + ".",
        )
    }

    val sources = items.flatMap { it.sources }.distinct().map(::vaccinationSourceName)
    if (sources.isNotEmpty()) lines += pick("Fonti: ", "Sources: ") + sources.joinToString(", ")
    lines += pick(
        "Verifica sempre i requisiti con l'ambasciata del paese di destinazione e con un centro di medicina dei viaggi, 4-6 settimane prima di partire.",
        "Always check the requirements with the destination country's embassy and a travel health clinic, 4-6 weeks before you leave.",
    )
    return lines.joinToString("\n")
}

private fun itemLine(item: VaccinationItem, en: Boolean, country: (String) -> String): String {
    fun pick(it: String, enText: String) = if (en) enText else it
    val name = vaccineName(item.vaccine, en)
    val where = item.country?.let(country).orEmpty()
    val reason = when (item.reason) {
        VaccinationReason.YF_ENTRY_ALL -> pick("richiesto a tutti i viaggiatori in arrivo", "required of all arriving travellers")
        VaccinationReason.YF_ENTRY_FROM_RISK, VaccinationReason.YF_ENTRY_FROM_LIST -> when {
            item.transitHours != null -> pick("richiesto per lo scalo di ${item.transitHours} ore in $where", "required because of the ${item.transitHours}-hour stopover in $where")
            item.stopover -> pick("richiesto per lo scalo in $where", "required because of the stopover in $where")
            else -> pick("richiesto perché arrivi da $where", "required because you are arriving from $where")
        }
        VaccinationReason.YF_EXIT -> pick("richiesto all'uscita da $where", "required when leaving $where")
        VaccinationReason.YF_DESTINATION_RISK -> pick("$where è a rischio", "$where is a risk area")
        VaccinationReason.POLIO_EXIT -> pick(
            "una dose ${doseText(item.polioDose, false)} prima di partire da $where, registrata sul certificato internazionale",
            "one dose ${doseText(item.polioDose, true)} before leaving $where, recorded on the international certificate",
        )
        VaccinationReason.POLIO_ENCOURAGED -> pick(
            "da $where è incoraggiata una dose ${doseText(item.polioDose, false)} prima di partire",
            "when leaving $where one dose ${doseText(item.polioDose, true)} is encouraged",
        )
        VaccinationReason.POLIO_ENTRY -> pick(
            "richiesto all'ingresso per chi arriva da $where (${doseText(item.polioDose, false)})",
            "required on entry for those arriving from $where (${doseText(item.polioDose, true)})",
        )
        VaccinationReason.POLIO_ENTRY_UNLISTED -> pick(
            "$where può chiedere la prova della vaccinazione a chi arriva da paesi in cui circola la polio; le fonti " +
                "non dicono da quali paesi: verifica sul sito del ministero degli Esteri del tuo paese o con l'ambasciata",
            "$where may ask for proof of vaccination from travellers arriving from countries where polio is circulating; " +
                "the sources do not say which countries: check your foreign ministry's travel advice or the embassy",
        )
        VaccinationReason.HAJJ_UMRAH -> pick("obbligatorio per Hajj e Umrah", "mandatory for Hajj and Umrah")
        VaccinationReason.DESTINATION_MOST, VaccinationReason.DESTINATION_SOME, VaccinationReason.ROUTINE -> ""
    }
    val details = buildList {
        item.minDaysBefore?.let { add(pick("almeno $it giorni prima", "at least $it days before")) }
        item.validityYears?.let { add(pick("validità $it anni", "valid for $it years")) }
        when (item.ageNote) {
            AgeNote.FROM_AGE -> add(pick("da ${item.minAgeMonths} mesi di età", "from age ${item.minAgeMonths} months"))
            AgeNote.BELOW_AGE -> add(pick("non richiesto sotto i ${item.minAgeMonths} mesi", "not required under ${item.minAgeMonths} months"))
            AgeNote.NONE -> if (item.reason == VaccinationReason.HAJJ_UMRAH && item.minAgeMonths != null) {
                add(pick("da ${item.minAgeMonths} mesi di età", "from age ${item.minAgeMonths} months"))
            }
        }
        val note = if (en) item.noteEn else item.noteIt
        if (note.isNotBlank()) add(note.trim())
    }
    val exit = if (item.level == VaccinationLevel.REQUIRED_EXIT) pick(" (in uscita)", " (on exit)") else ""
    return name + exit + ": " + (listOf(reason).filter { it.isNotEmpty() } + details).joinToString("; ")
}

private fun doseText(dose: PolioDose?, en: Boolean): String {
    val vaccine = if (dose?.vaccine == PolioVaccine.IPV) "IPV" else "bOPV/IPV"
    return when (dose?.window) {
        PolioWindow.W4_12M -> vaccine + if (en) " between 4 weeks and 12 months" else " tra 4 settimane e 12 mesi"
        else -> vaccine + if (en) " (certified)" else " (certificata)"
    }
}

private fun vaccineName(vaccine: Vaccine, en: Boolean): String = when (vaccine) {
    Vaccine.YELLOW_FEVER -> if (en) "Yellow fever" else "Febbre gialla"
    Vaccine.POLIO -> if (en) "Polio" else "Poliomielite"
    Vaccine.MENACWY -> if (en) "Meningococcal ACWY" else "Meningococco ACWY"
    Vaccine.HEPA -> if (en) "Hepatitis A" else "Epatite A"
    Vaccine.HEPB -> if (en) "Hepatitis B" else "Epatite B"
    Vaccine.TYPHOID -> if (en) "Typhoid" else "Tifo"
    Vaccine.RABIES -> if (en) "Rabies" else "Rabbia"
    Vaccine.JE -> if (en) "Japanese encephalitis" else "Encefalite giapponese"
    Vaccine.TBE -> if (en) "Tick-borne encephalitis" else "Encefalite da zecche"
    Vaccine.CHOLERA -> if (en) "Cholera" else "Colera"
    Vaccine.DENGUE -> if (en) "Dengue" else "Dengue"
    Vaccine.CHIK -> if (en) "Chikungunya" else "Chikungunya"
    Vaccine.ROUTINE -> if (en) "Routine vaccinations" else "Vaccinazioni di routine"
}

private fun countryName(iso: String, locale: Locale): String {
    val name = runCatching { Locale.Builder().setRegion(iso.trim().uppercase(Locale.ROOT)).build().getDisplayCountry(locale) }.getOrDefault("")
    return name.ifBlank { iso.uppercase(Locale.ROOT) }
}
