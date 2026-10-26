package com.pockettravel.core.data.alerts

/**
 * Livello di un'allerta JMA, dal piu' grave. I nomi inglesi sono quelli del sito della JMA
 * (Emergency Warning, Urgent Warning, Warning, Advisory).
 */
enum class JmaLevel(val alertLevel: AlertLevel, val englishName: String, val shown: Boolean) {
    // Allerta speciale: pericolo straordinario.
    EMERGENCY(AlertLevel.EXTREME, "Emergency Warning", shown = true),

    // Allerta di livello 4 ("pericolo"), introdotta nel 2026 per pioggia, mareggiata e frane.
    URGENT(AlertLevel.HIGH, "Urgent Warning", shown = true),
    WARNING(AlertLevel.HIGH, "Warning", shown = true),

    // Le segnalazioni sono decine e quasi sempre in corso (fulmini, nebbia): al viaggiatore servono dalle allerte in su.
    ADVISORY(AlertLevel.MODERATE, "Advisory", shown = false),
}

/**
 * Allerte meteo della JMA con il codice di `bosai/warning/data/warning/map.json`, il livello e il titolo inglese
 * (nomi del sito della JMA). I codici 04, 18 e 27 sono quelli storici del bollettino; gli altri quelli del sistema
 * a livelli 2-5 usato dal sito. Un codice che manca qui (es. 40 e 41, indice di piena dei fiumi) si salta.
 */
enum class JmaHazard(val code: String, val level: JmaLevel, val title: String) {
    // Allerte speciali (livello 5).
    EMERGENCY_SNOWSTORM("32", JmaLevel.EMERGENCY, "Snowstorm emergency warning"),
    EMERGENCY_HEAVY_RAIN("33", JmaLevel.EMERGENCY, "Heavy rain emergency warning"),
    EMERGENCY_STORM("35", JmaLevel.EMERGENCY, "Storm emergency warning"),
    EMERGENCY_HEAVY_SNOW("36", JmaLevel.EMERGENCY, "Heavy snow emergency warning"),
    EMERGENCY_HIGH_WAVES("37", JmaLevel.EMERGENCY, "High waves emergency warning"),
    EMERGENCY_STORM_SURGE("38", JmaLevel.EMERGENCY, "Storm surge emergency warning"),
    EMERGENCY_LANDSLIDE("39", JmaLevel.EMERGENCY, "Landslide emergency warning"),

    // Allerte di pericolo (livello 4).
    URGENT_HEAVY_RAIN("43", JmaLevel.URGENT, "Heavy rain urgent warning"),
    URGENT_STORM_SURGE("48", JmaLevel.URGENT, "Storm surge urgent warning"),
    URGENT_LANDSLIDE("49", JmaLevel.URGENT, "Landslide urgent warning"),

    // Allerte (livello 3).
    WARNING_SNOWSTORM("02", JmaLevel.WARNING, "Snowstorm warning"),
    WARNING_HEAVY_RAIN("03", JmaLevel.WARNING, "Heavy rain warning"),
    WARNING_FLOOD("04", JmaLevel.WARNING, "Flood warning"),
    WARNING_STORM("05", JmaLevel.WARNING, "Storm warning"),
    WARNING_HEAVY_SNOW("06", JmaLevel.WARNING, "Heavy snow warning"),
    WARNING_HIGH_WAVES("07", JmaLevel.WARNING, "High waves warning"),
    WARNING_STORM_SURGE("08", JmaLevel.WARNING, "Storm surge warning"),
    WARNING_LANDSLIDE("09", JmaLevel.WARNING, "Landslide warning"),

    // Segnalazioni (livello 2).
    ADVISORY_HEAVY_RAIN("10", JmaLevel.ADVISORY, "Heavy rain advisory"),
    ADVISORY_HEAVY_SNOW("12", JmaLevel.ADVISORY, "Heavy snow advisory"),
    ADVISORY_GALE_AND_SNOW("13", JmaLevel.ADVISORY, "Gale and snow advisory"),
    ADVISORY_THUNDERSTORM("14", JmaLevel.ADVISORY, "Thunderstorm advisory"),
    ADVISORY_GALE("15", JmaLevel.ADVISORY, "Gale advisory"),
    ADVISORY_HIGH_WAVES("16", JmaLevel.ADVISORY, "High waves advisory"),
    ADVISORY_SNOW_MELTING("17", JmaLevel.ADVISORY, "Snow melting advisory"),
    ADVISORY_FLOOD("18", JmaLevel.ADVISORY, "Flood advisory"),
    ADVISORY_STORM_SURGE("19", JmaLevel.ADVISORY, "Storm surge advisory"),
    ADVISORY_DENSE_FOG("20", JmaLevel.ADVISORY, "Dense fog advisory"),
    ADVISORY_DRY_AIR("21", JmaLevel.ADVISORY, "Dry air advisory"),
    ADVISORY_AVALANCHE("22", JmaLevel.ADVISORY, "Avalanche advisory"),
    ADVISORY_LOW_TEMPERATURE("23", JmaLevel.ADVISORY, "Low temperature advisory"),
    ADVISORY_FROST("24", JmaLevel.ADVISORY, "Frost advisory"),
    ADVISORY_ICE_ACCRETION("25", JmaLevel.ADVISORY, "Ice accretion advisory"),
    ADVISORY_SNOW_ACCRETION("26", JmaLevel.ADVISORY, "Snow accretion advisory"),
    ADVISORY_LANDSLIDE("29", JmaLevel.ADVISORY, "Landslide advisory"),
    ADVISORY_OTHER("27", JmaLevel.ADVISORY, "Other advisory");

    /** Codice stabile `<livello>:<codice JMA>` (es. `WARNING:03`) da riscrivere nella lingua dell'app. */
    val eventCode: String get() = "${level.name}:$code"

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        /** L'allerta con il codice JMA [code], nulla se il codice non e' tra quelli noti. */
        fun fromCode(code: String): JmaHazard? = BY_CODE[code]
    }
}
