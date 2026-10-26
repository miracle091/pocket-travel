package com.pockettravel.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import com.pockettravel.core.data.alerts.Alert
import com.pockettravel.core.data.alerts.AlertKind
import com.pockettravel.core.data.alerts.AlertSource
import com.pockettravel.core.data.alerts.CycloneCategory
import com.pockettravel.core.data.alerts.GdacsDetail
import com.pockettravel.core.data.alerts.WarningColor
import com.pockettravel.core.data.alerts.WeatherHazard
import com.pockettravel.core.data.alerts.countryCode
import com.pockettravel.core.data.alerts.gdacsCountries
import com.pockettravel.core.data.alerts.gdacsDetail
import com.pockettravel.core.data.alerts.gdacsEventName
import com.pockettravel.core.data.alerts.quakePlace
import com.pockettravel.core.data.alerts.weatherWarning
import java.text.NumberFormat
import java.util.Locale

/** Titolo e righe sotto il titolo di un'allerta, nella lingua dell'app. */
internal class AlertText(val title: String, val lines: List<String>)

/**
 * Riscrive un'allerta nella lingua dell'app dai pezzi letti nel testo inglese della fonte (tipo, colore, magnitudo,
 * distanza, nazioni); i nomi propri restano come sono. Dove il testo non segue lo schema noto resta l'originale.
 */
@Composable
internal fun alertText(alert: Alert): AlertText {
    val original = AlertText(alert.title, listOfNotNull(alert.areaName, alert.details).distinct())
    return when (alert.source) {
        AlertSource.METEOALARM -> meteoalarmText(alert)
        AlertSource.USGS -> usgsText(alert)
        AlertSource.GDACS -> gdacsText(alert)
    } ?: original
}

private val COLOR_NAMES = mapOf(
    WarningColor.YELLOW to R.string.alerts_color_yellow,
    WarningColor.ORANGE to R.string.alerts_color_orange,
    WarningColor.RED to R.string.alerts_color_red,
)

private val HAZARD_NAMES = mapOf(
    WeatherHazard.WIND to R.string.alerts_hazard_wind,
    WeatherHazard.SNOW_ICE to R.string.alerts_hazard_snow_ice,
    WeatherHazard.THUNDERSTORM to R.string.alerts_hazard_thunderstorm,
    WeatherHazard.FOG to R.string.alerts_hazard_fog,
    WeatherHazard.HIGH_TEMPERATURE to R.string.alerts_hazard_high_temperature,
    WeatherHazard.LOW_TEMPERATURE to R.string.alerts_hazard_low_temperature,
    WeatherHazard.COASTAL_EVENT to R.string.alerts_hazard_coastal_event,
    WeatherHazard.FOREST_FIRE to R.string.alerts_hazard_forest_fire,
    WeatherHazard.AVALANCHES to R.string.alerts_hazard_avalanches,
    WeatherHazard.RAIN to R.string.alerts_hazard_rain,
    WeatherHazard.FLOODING to R.string.alerts_hazard_flooding,
    WeatherHazard.RAIN_FLOOD to R.string.alerts_hazard_rain_flood,
)

// Titolo "<tipo> in <nazioni>" degli eventi GDACS senza nome proprio.
private val IN_COUNTRIES = mapOf(
    AlertKind.EARTHQUAKE to R.string.alerts_earthquake_in,
    AlertKind.FLOOD to R.string.alerts_flood_in,
    AlertKind.FIRE to R.string.alerts_fire_in,
    AlertKind.DROUGHT to R.string.alerts_drought_in,
)

// Titolo con il nome proprio dell'evento; le nazioni vanno su una riga a parte.
private val WITH_NAME = mapOf(
    AlertKind.CYCLONE to R.string.alerts_cyclone,
    AlertKind.VOLCANO to R.string.alerts_eruption,
)

@Composable
private fun meteoalarmText(alert: Alert): AlertText? {
    val warning = weatherWarning(alert.title) ?: return null
    val title = stringResource(
        R.string.alerts_weather_warning,
        stringResource(COLOR_NAMES.getValue(warning.color)),
        stringResource(HAZARD_NAMES.getValue(warning.hazard)),
    )
    return AlertText(title, listOfNotNull(alert.areaName))
}

@Composable
private fun usgsText(alert: Alert): AlertText? {
    val magnitude = alert.magnitude ?: return null
    val locale = LocalLocale.current.platformLocale
    val title = stringResource(R.string.alerts_quake_magnitude, decimal(magnitude, locale))
    val place = alert.details?.let(::quakePlace)
    val line = if (place == null) {
        alert.details
    } else {
        val direction = stringArrayResource(R.array.alerts_compass)[place.direction]
        stringResource(R.string.alerts_quake_place, place.km, direction, localizedCountries(place.place, locale))
    }
    return AlertText(title, listOfNotNull(line))
}

@Composable
private fun gdacsText(alert: Alert): AlertText? {
    val locale = LocalLocale.current.platformLocale
    val countries = gdacsCountries(alert.areaName).joinToString(", ") { name -> countryCode(name)?.let { countryName(it, locale) } ?: name }
    val withName = WITH_NAME[alert.kind]
    val inCountries = IN_COUNTRIES[alert.kind]
    val name = gdacsEventName(alert.kind, alert.title)
    val title = when {
        withName != null && name != null -> stringResource(withName, name)
        inCountries != null && countries.isNotEmpty() -> stringResource(inCountries, countries)
        else -> null
    } ?: return null
    val countryLine = countries.takeIf { withName != null && it.isNotEmpty() }
    val detail = alert.details?.let { text -> gdacsDetail(text)?.let { gdacsDetailText(it, locale) } ?: text.trim().ifEmpty { null } }
    return AlertText(title, listOfNotNull(countryLine, detail))
}

@Composable
private fun gdacsDetailText(detail: GdacsDetail, locale: Locale): String? = when (detail) {
    is GdacsDetail.Quake -> stringResource(
        R.string.alerts_quake_depth,
        decimal(detail.magnitude, locale),
        NumberFormat.getIntegerInstance(locale).format(Math.round(detail.depthKm)),
    )
    is GdacsDetail.Cyclone -> {
        val category = when (detail.category) {
            CycloneCategory.DEPRESSION -> stringResource(R.string.alerts_cyclone_depression)
            CycloneCategory.STORM -> stringResource(R.string.alerts_cyclone_storm)
            CycloneCategory.HURRICANE -> stringResource(R.string.alerts_cyclone_hurricane)
            null -> null
        }
        if (category != null) {
            stringResource(R.string.alerts_cyclone_wind, category, detail.maxWindKmh)
        } else {
            stringResource(R.string.alerts_wind_only, detail.maxWindKmh)
        }
    }
    is GdacsDetail.Area -> stringResource(
        if (detail.hectares) R.string.alerts_area_hectares else R.string.alerts_area_km2,
        NumberFormat.getIntegerInstance(locale).format(detail.value),
    )
    GdacsDetail.Empty -> null
}

// "Malfa, Italy" -> "Malfa, Italia": si traduce solo l'ultima parte, se e' una nazione.
private fun localizedCountries(place: String, locale: Locale): String {
    val head = place.substringBeforeLast(", ", missingDelimiterValue = "")
    val last = place.substringAfterLast(", ")
    val country = countryCode(last)?.let { countryName(it, locale) } ?: return place
    return if (head.isEmpty()) country else "$head, $country"
}

private fun decimal(value: Double, locale: Locale): String = String.format(locale, "%.1f", value)
