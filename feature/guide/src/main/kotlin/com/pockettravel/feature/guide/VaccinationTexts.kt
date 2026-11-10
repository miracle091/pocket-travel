package com.pockettravel.feature.guide

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.pockettravel.core.data.vaccination.AgeNote
import com.pockettravel.core.data.vaccination.PolioVaccine
import com.pockettravel.core.data.vaccination.PolioWindow
import com.pockettravel.core.data.vaccination.VaccinationItem
import com.pockettravel.core.data.vaccination.VaccinationLevel
import com.pockettravel.core.data.vaccination.VaccinationReason
import com.pockettravel.core.data.vaccination.VaccinationResult
import com.pockettravel.core.data.vaccination.Vaccine
import com.pockettravel.core.ui.countryName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun VaccinationItem.isCertificate() = level == VaccinationLevel.REQUIRED || level == VaccinationLevel.REQUIRED_EXIT

@Composable
internal fun noCertificateText(result: VaccinationResult, locale: Locale): String =
    result.checkedOn?.let { stringResource(R.string.vacc_none_found, formatDate(it, locale)) } ?: stringResource(R.string.vacc_none_found_no_date)

internal fun formatDate(iso: String, locale: Locale): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }.getOrDefault(iso)

internal fun Vaccine.nameRes(): Int = when (this) {
    Vaccine.YELLOW_FEVER -> R.string.vacc_vaccine_yellow_fever
    Vaccine.POLIO -> R.string.vacc_vaccine_polio
    Vaccine.MENACWY -> R.string.vacc_vaccine_menacwy
    Vaccine.HEPA -> R.string.vacc_vaccine_hepa
    Vaccine.HEPB -> R.string.vacc_vaccine_hepb
    Vaccine.TYPHOID -> R.string.vacc_vaccine_typhoid
    Vaccine.RABIES -> R.string.vacc_vaccine_rabies
    Vaccine.JE -> R.string.vacc_vaccine_je
    Vaccine.TBE -> R.string.vacc_vaccine_tbe
    Vaccine.CHOLERA -> R.string.vacc_vaccine_cholera
    Vaccine.DENGUE -> R.string.vacc_vaccine_dengue
    Vaccine.CHIK -> R.string.vacc_vaccine_chik
    Vaccine.ROUTINE -> R.string.vacc_vaccine_routine
}

// "Arrivo da un paese a rischio di febbre gialla (Kenya)", "Scalo di 14 ore (Etiopia)"...: il motivo della voce nella
// lingua dell'interfaccia. Il nome del paese sta tra parentesi: dopo una preposizione l'italiano vorrebbe l'articolo
// ("dal Kenya", "del Nepal"), che il nome da solo non porta.
@Composable
internal fun VaccinationItem.reasonText(): String? {
    val place = country?.let { countryName(it.uppercase()) }.orEmpty()
    val hours = transitHours
    return when (reason) {
        VaccinationReason.YF_ENTRY_ALL -> stringResource(R.string.vacc_reason_yf_all)
        VaccinationReason.YF_ENTRY_FROM_RISK, VaccinationReason.YF_ENTRY_FROM_LIST -> when {
            hours != null -> pluralStringResource(R.plurals.vacc_reason_stopover, hours, hours, place)
            stopover -> stringResource(R.string.vacc_reason_stopover_unknown, place)
            reason == VaccinationReason.YF_ENTRY_FROM_RISK -> stringResource(R.string.vacc_reason_from_risk, place)
            else -> stringResource(R.string.vacc_reason_from_list, place)
        }
        VaccinationReason.YF_EXIT -> stringResource(R.string.vacc_reason_yf_exit, place)
        VaccinationReason.YF_DESTINATION_RISK ->
            stringResource(if (partialArea) R.string.vacc_reason_yf_destination_partial else R.string.vacc_reason_yf_destination, place)
        VaccinationReason.POLIO_EXIT -> stringResource(R.string.vacc_reason_polio_exit, place)
        VaccinationReason.POLIO_ENCOURAGED -> stringResource(R.string.vacc_reason_polio_encouraged, place)
        VaccinationReason.POLIO_ENTRY -> stringResource(R.string.vacc_reason_polio_entry, place)
        VaccinationReason.POLIO_ENTRY_UNLISTED -> stringResource(R.string.vacc_reason_polio_entry_unlisted, place)
        VaccinationReason.HAJJ_UMRAH -> stringResource(R.string.vacc_reason_hajj)
        VaccinationReason.DESTINATION_MOST -> stringResource(R.string.vacc_reason_destination_most, place)
        VaccinationReason.DESTINATION_SOME -> stringResource(R.string.vacc_reason_destination_some, place)
        VaccinationReason.ROUTINE -> stringResource(R.string.vacc_reason_routine)
    }
}

// Dose, tempi, validita' ed eta' della voce, una riga ciascuno.
@Composable
internal fun VaccinationItem.detailTexts(): List<String> = buildList {
    when (reason) {
        VaccinationReason.POLIO_EXIT -> add(stringResource(R.string.vacc_polio_exit))
        VaccinationReason.POLIO_ENCOURAGED -> add(stringResource(R.string.vacc_polio_encouraged))
        VaccinationReason.POLIO_ENTRY -> polioDose?.let { dose ->
            val vaccine = stringResource(if (dose.vaccine == PolioVaccine.IPV) R.string.vacc_polio_vaccine_ipv else R.string.vacc_polio_vaccine_bopv_or_ipv)
            add(stringResource(if (dose.window == PolioWindow.W4_12M) R.string.vacc_polio_dose_window else R.string.vacc_polio_dose_any, vaccine))
        }
        else -> Unit
    }
    minDaysBefore?.let { add(pluralStringResource(R.plurals.vacc_days_before, it, it)) }
    validityYears?.let { add(pluralStringResource(R.plurals.vacc_validity_years, it, it)) }
    val minAge = minAgeMonths
    if (minAge != null) {
        when {
            ageNote == AgeNote.BELOW_AGE -> add(pluralStringResource(R.plurals.vacc_below_age, minAge, minAge))
            ageNote == AgeNote.FROM_AGE || reason == VaccinationReason.HAJJ_UMRAH -> add(pluralStringResource(R.plurals.vacc_min_age, minAge, minAge))
        }
    }
}
