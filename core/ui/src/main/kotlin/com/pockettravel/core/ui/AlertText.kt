package com.pockettravel.core.ui

import androidx.compose.runtime.Composable
import android.content.res.Resources
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
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
import com.pockettravel.core.data.alerts.nationalEvent
import com.pockettravel.core.data.alerts.NationalEventType
import com.pockettravel.core.data.alerts.NationalHazard
import com.pockettravel.core.data.alerts.quakePlace
import com.pockettravel.core.data.alerts.weatherWarning
import java.text.NumberFormat
import java.util.Locale

/** Titolo e righe sotto il titolo di un'allerta, nella lingua dell'app. */
class AlertText(val title: String, val lines: List<String>)

/** Come sotto, con le risorse e la lingua dell'interfaccia; si aggiorna quando cambia la lingua. */
@Composable
internal fun alertText(alert: Alert): AlertText {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    return remember(alert, resources, locale) { alertText(alert, resources, locale) }
}

/**
 * Riscrive un'allerta nella lingua di [resources] dai pezzi letti nel testo inglese della fonte (tipo, colore, magnitudo,
 * distanza, nazioni); i nomi propri restano come sono. Dove il testo non segue lo schema noto resta l'originale. Senza
 * Compose, per le notifiche delle allerte.
 */
fun alertText(alert: Alert, resources: Resources, locale: Locale): AlertText {
    val original = AlertText(alert.title, listOfNotNull(alert.areaName, alert.details).distinct())
    val texts = Texts(resources, locale)
    return when (alert.source) {
        AlertSource.METEOALARM -> texts.meteoalarmText(alert)
        AlertSource.USGS -> texts.usgsText(alert)
        AlertSource.GDACS -> texts.gdacsText(alert)
        AlertSource.NWS, AlertSource.TSUNAMI, AlertSource.ECCC, AlertSource.JMA -> texts.nationalText(alert)
        AlertSource.GEONET -> texts.volcanoText(alert)
    } ?: original
}

private class Texts(val resources: Resources, val locale: Locale) {
    fun string(id: Int, vararg args: Any): String = resources.getString(id, *args)
}

private val NATIONAL_TYPES = mapOf(
    NationalEventType.EMERGENCY_WARNING to R.string.alerts_type_emergency_warning,
    NationalEventType.URGENT_WARNING to R.string.alerts_type_urgent_warning,
    NationalEventType.WARNING to R.string.alerts_type_warning,
    NationalEventType.WATCH to R.string.alerts_type_watch,
    NationalEventType.ADVISORY to R.string.alerts_type_advisory,
    NationalEventType.STATEMENT to R.string.alerts_type_statement,
    NationalEventType.OUTLOOK to R.string.alerts_type_outlook,
    NationalEventType.THREAT to R.string.alerts_type_threat,
)

private val NATIONAL_HAZARDS = mapOf(
    NationalHazard.WINTER_STORM to R.string.alerts_national_winter_storm,
    NationalHazard.WINTER_WEATHER to R.string.alerts_national_winter_weather,
    NationalHazard.BLIZZARD to R.string.alerts_national_blizzard,
    NationalHazard.ICE_STORM to R.string.alerts_national_ice_storm,
    NationalHazard.LAKE_EFFECT_SNOW to R.string.alerts_national_lake_effect_snow,
    NationalHazard.SNOW_SQUALL to R.string.alerts_national_snow_squall,
    NationalHazard.HEAVY_SNOW to R.string.alerts_national_heavy_snow,
    NationalHazard.BLOWING_SNOW to R.string.alerts_national_blowing_snow,
    NationalHazard.FREEZING_RAIN to R.string.alerts_national_freezing_rain,
    NationalHazard.FREEZING_DRIZZLE to R.string.alerts_national_freezing_drizzle,
    NationalHazard.FLASH_FREEZE to R.string.alerts_national_flash_freeze,
    NationalHazard.HEAVY_RAIN to R.string.alerts_national_heavy_rain,
    NationalHazard.FLOOD to R.string.alerts_national_flood,
    NationalHazard.FLASH_FLOOD to R.string.alerts_national_flash_flood,
    NationalHazard.COASTAL_FLOOD to R.string.alerts_national_coastal_flood,
    NationalHazard.LAKESHORE_FLOOD to R.string.alerts_national_lakeshore_flood,
    NationalHazard.HYDROLOGIC to R.string.alerts_national_hydrologic,
    NationalHazard.LANDSLIDE to R.string.alerts_national_landslide,
    NationalHazard.TORNADO to R.string.alerts_national_tornado,
    NationalHazard.SEVERE_THUNDERSTORM to R.string.alerts_national_severe_thunderstorm,
    NationalHazard.SEVERE_WEATHER to R.string.alerts_national_severe_weather,
    NationalHazard.SPECIAL_WEATHER to R.string.alerts_national_special_weather,
    NationalHazard.WIND to R.string.alerts_national_wind,
    NationalHazard.HIGH_WIND to R.string.alerts_national_high_wind,
    NationalHazard.EXTREME_WIND to R.string.alerts_national_extreme_wind,
    NationalHazard.SQUALL to R.string.alerts_national_squall,
    NationalHazard.HEAT to R.string.alerts_national_heat,
    NationalHazard.EXTREME_HEAT to R.string.alerts_national_extreme_heat,
    NationalHazard.COLD to R.string.alerts_national_cold,
    NationalHazard.EXTREME_COLD to R.string.alerts_national_extreme_cold,
    NationalHazard.ARCTIC_OUTFLOW to R.string.alerts_national_arctic_outflow,
    NationalHazard.FREEZE to R.string.alerts_national_freeze,
    NationalHazard.FROST to R.string.alerts_national_frost,
    NationalHazard.FOG to R.string.alerts_national_fog,
    NationalHazard.FREEZING_FOG to R.string.alerts_national_freezing_fog,
    NationalHazard.SMOKE to R.string.alerts_national_smoke,
    NationalHazard.AIR_QUALITY to R.string.alerts_national_air_quality,
    NationalHazard.FIRE_DANGER to R.string.alerts_national_fire_danger,
    NationalHazard.EXTREME_FIRE_DANGER to R.string.alerts_national_extreme_fire_danger,
    NationalHazard.HURRICANE to R.string.alerts_national_hurricane,
    NationalHazard.TROPICAL_STORM to R.string.alerts_national_tropical_storm,
    NationalHazard.TYPHOON to R.string.alerts_national_typhoon,
    NationalHazard.STORM to R.string.alerts_national_storm,
    NationalHazard.STORM_SURGE to R.string.alerts_national_storm_surge,
    NationalHazard.HIGH_WAVES to R.string.alerts_national_high_waves,
    NationalHazard.RIP_CURRENT to R.string.alerts_national_rip_current,
    NationalHazard.BEACH_HAZARDS to R.string.alerts_national_beach_hazards,
    NationalHazard.TSUNAMI to R.string.alerts_national_tsunami,
    NationalHazard.AVALANCHE to R.string.alerts_national_avalanche,
    NationalHazard.DUST_STORM to R.string.alerts_national_dust_storm,
    NationalHazard.VOLCANO to R.string.alerts_national_volcano,
    NationalHazard.ASHFALL to R.string.alerts_national_ashfall,
    NationalHazard.EARTHQUAKE to R.string.alerts_national_earthquake,
)

private val VOLCANO_LEVEL = Regex("""VAL(d)""")

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

private fun Texts.meteoalarmText(alert: Alert): AlertText? {
    val warning = weatherWarning(alert.title) ?: return null
    val title = string(
        R.string.alerts_weather_warning,
        string(COLOR_NAMES.getValue(warning.color)),
        string(HAZARD_NAMES.getValue(warning.hazard)),
    )
    return AlertText(title, listOfNotNull(alert.areaName))
}

private fun Texts.usgsText(alert: Alert): AlertText? {
    val magnitude = alert.magnitude ?: return null
    val title = string(R.string.alerts_quake_magnitude, decimal(magnitude, locale))
    val place = alert.details?.let(::quakePlace)
    val line = if (place == null) {
        alert.details
    } else {
        val direction = resources.getStringArray(R.array.alerts_compass)[place.direction]
        string(R.string.alerts_quake_place, place.km, direction, localizedCountries(place.place, locale))
    }
    return AlertText(title, listOfNotNull(line))
}

private fun Texts.gdacsText(alert: Alert): AlertText? {
    val countries = gdacsCountries(alert.areaName).joinToString(", ") { name -> countryCode(name)?.let { countryName(it, locale) } ?: name }
    val withName = WITH_NAME[alert.kind]
    val inCountries = IN_COUNTRIES[alert.kind]
    val name = gdacsEventName(alert.kind, alert.title)
    val title = when {
        withName != null && name != null -> string(withName, name)
        inCountries != null && countries.isNotEmpty() -> string(inCountries, countries)
        else -> null
    } ?: return null
    val countryLine = countries.takeIf { withName != null && it.isNotEmpty() }
    val detail = alert.details?.let { text -> gdacsDetail(text)?.let { gdacsDetailText(it) } ?: text.trim().ifEmpty { null } }
    return AlertText(title, listOfNotNull(countryLine, detail))
}

private fun Texts.gdacsDetailText(detail: GdacsDetail): String? = when (detail) {
    is GdacsDetail.Quake -> string(
        R.string.alerts_quake_depth,
        decimal(detail.magnitude, locale),
        NumberFormat.getIntegerInstance(locale).format(Math.round(detail.depthKm)),
    )
    is GdacsDetail.Cyclone -> {
        val category = when (detail.category) {
            CycloneCategory.DEPRESSION -> string(R.string.alerts_cyclone_depression)
            CycloneCategory.STORM -> string(R.string.alerts_cyclone_storm)
            CycloneCategory.HURRICANE -> string(R.string.alerts_cyclone_hurricane)
            null -> null
        }
        if (category != null) {
            string(R.string.alerts_cyclone_wind, category, detail.maxWindKmh)
        } else {
            string(R.string.alerts_wind_only, detail.maxWindKmh)
        }
    }
    is GdacsDetail.Area -> string(
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

private fun Texts.nationalText(alert: Alert): AlertText? {
    val event = nationalEvent(alert.title) ?: return null
    val hazard = string(NATIONAL_HAZARDS.getValue(event.hazard))
    val title = string(NATIONAL_TYPES.getValue(event.type), hazard)
        .replaceFirstChar { it.titlecase(locale) }
    // Il testo della fonte resta sotto il titolo: ECCC chiede l'originale accanto a ogni riscrittura.
    return AlertText(title, listOfNotNull(alert.areaName, alert.details).distinct())
}

private fun Texts.volcanoText(alert: Alert): AlertText? {
    val level = alert.eventCode?.let { VOLCANO_LEVEL.matchEntire(it) }?.groupValues?.get(1)?.toInt() ?: return null
    return AlertText(string(R.string.alerts_volcano_level, alert.title, level), listOfNotNull(alert.details))
}
