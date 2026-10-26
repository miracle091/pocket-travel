package com.pockettravel.core.data.alerts

import java.util.Locale

/*
 * NWS, ECCC, i centri tsunami e i titoli inglesi di JmaHazard nominano un'allerta come "<pericolo> <tipo>"
 * ("Winter Storm Warning", "Rainfall warning", "Heavy rain emergency warning"): qui si leggono i due pezzi, che l'app
 * riscrive nella sua lingua. Null quando il nome non e' nelle tabelle: allora resta il testo originale.
 */

/** Tipo di un'allerta nazionale, con la parola finale del nome inglese (le piu' lunghe prima). */
enum class NationalEventType(internal val sourceName: String) {
    EMERGENCY_WARNING("emergency warning"),
    URGENT_WARNING("urgent warning"),
    WARNING("warning"),
    WATCH("watch"),
    ADVISORY("advisory"),
    STATEMENT("statement"),
    OUTLOOK("outlook"),
    THREAT("threat"),
}

/** Pericolo di un'allerta nazionale, con i nomi inglesi che hanno le fonti. */
enum class NationalHazard(internal vararg val sourceNames: String) {
    WINTER_STORM("winter storm"),
    WINTER_WEATHER("winter weather"),
    BLIZZARD("blizzard", "snowstorm"),
    ICE_STORM("ice storm"),
    LAKE_EFFECT_SNOW("lake effect snow"),
    SNOW_SQUALL("snow squall"),
    HEAVY_SNOW("snowfall", "heavy snow"),
    BLOWING_SNOW("blowing snow"),
    FREEZING_RAIN("freezing rain"),
    FREEZING_DRIZZLE("freezing drizzle"),
    FLASH_FREEZE("flash freeze"),
    HEAVY_RAIN("rainfall", "heavy rain"),
    FLOOD("flood"),
    FLASH_FLOOD("flash flood"),
    COASTAL_FLOOD("coastal flood"),
    LAKESHORE_FLOOD("lakeshore flood"),
    HYDROLOGIC("hydrologic"),
    LANDSLIDE("landslide"),
    TORNADO("tornado"),
    SEVERE_THUNDERSTORM("severe thunderstorm"),
    SEVERE_WEATHER("severe weather", "hazardous weather", "weather"),
    SPECIAL_WEATHER("special weather"),
    WIND("wind"),
    HIGH_WIND("high wind"),
    EXTREME_WIND("extreme wind"),
    SQUALL("squall"),
    HEAT("heat"),
    EXTREME_HEAT("extreme heat"),
    COLD("cold weather"),
    EXTREME_COLD("extreme cold"),
    ARCTIC_OUTFLOW("arctic outflow"),
    FREEZE("freeze"),
    FROST("frost"),
    FOG("fog", "dense fog"),
    FREEZING_FOG("freezing fog"),
    SMOKE("dense smoke"),
    AIR_QUALITY("air quality"),
    FIRE_DANGER("red flag", "fire weather"),
    EXTREME_FIRE_DANGER("extreme fire danger"),
    HURRICANE("hurricane"),
    TROPICAL_STORM("tropical storm"),
    TYPHOON("typhoon"),
    STORM("storm"),
    STORM_SURGE("storm surge"),
    HIGH_WAVES("high surf", "high waves"),
    RIP_CURRENT("rip current"),
    BEACH_HAZARDS("beach hazards"),
    TSUNAMI("tsunami"),
    AVALANCHE("avalanche"),
    DUST_STORM("dust storm", "blowing dust"),
    VOLCANO("volcano"),
    ASHFALL("ashfall"),
    EARTHQUAKE("earthquake"),
}

/** Pericolo e tipo di un'allerta nazionale. */
data class NationalEvent(val hazard: NationalHazard, val type: NationalEventType)

private val HAZARDS: Map<String, NationalHazard> by lazy {
    NationalHazard.entries.flatMap { hazard -> hazard.sourceNames.map { it to hazard } }.toMap()
}

/** Pericolo e tipo dal nome inglese dell'allerta ("Winter Storm Warning"); null se uno dei due non e' nelle tabelle. */
fun nationalEvent(name: String): NationalEvent? {
    val words = name.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
    val type = NationalEventType.entries.firstOrNull { words.endsWith(" " + it.sourceName) } ?: return null
    return HAZARDS[words.removeSuffix(" " + type.sourceName)]?.let { NationalEvent(it, type) }
}
