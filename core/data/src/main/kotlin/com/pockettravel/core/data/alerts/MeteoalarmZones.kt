package com.pockettravel.core.data.alerts

// I codici EMMA_ID del Portogallo non dicono l'isola: Azzorre e Madeira si riconoscono dal nome dell'area ("Açores - Grupo
// Central", "Madeira - Costa Sul").
private val AZORES = Regex("""\b(A[cç]ores|Azores)\b""", RegexOption.IGNORE_CASE)
private val MADEIRA = Regex("""\bMadeira\b""", RegexOption.IGNORE_CASE)

// Aree delle Canarie nel feed spagnolo (EMMA_ID, elenco dei geocodici di MeteoAlarm): ES120-ES132 a terra (Gran Canaria,
// Lanzarote, Fuerteventura, La Palma, La Gomera, El Hierro e Tenerife), ES879-ES889 sulla costa. Con il solo NUTS3 ES70x.
private val CANARY_ZONES = ((120..132) + (879..889)).map { "ES$it" }.toSet()
private const val CANARY_NUTS = "ES70"

// Ceuta e Melilla nel feed spagnolo (EMMA_ID a terra e sulla costa, NUTS3): sono in Africa, fuori dalla regione Spagna.
private val CEUTA_ZONES = setOf("ES250", "ES871", "ES630")
private val MELILLA_ZONES = setOf("ES251", "ES872", "ES640")
private val CEUTA_MELILLA_ZONES = CEUTA_ZONES + MELILLA_ZONES

// Il feed norvegese non da' geocodici, solo poligoni: Svalbard (Bjørnøya compresa) sta a nord di 74°N, Jan Mayen a ovest di
// Greenwich, la Norvegia continentale sotto i 72°N e a est di 4°E.
private const val SVALBARD_MIN_LAT = 74.0
private const val JAN_MAYEN_MAX_LON = 0.0

// Lunghezza del codice della regione francese dentro il NUTS3 del dipartimento ("FR81" in "FR812").
private const val FRANCE_REGION_CODE = 4

// Il feed francese da' il dipartimento con il NUTS3 del 2010 (FR812 Gard): i primi quattro caratteri sono la regione di prima
// del 2016, contenuta per intero in una delle regioni del catalogo.
private val FRANCE_REGIONS = mapOf(
    "FR10" to "francia-ile-de-france",
    "FR21" to "francia-grand-est", "FR41" to "francia-grand-est", "FR42" to "francia-grand-est",
    "FR22" to "francia-alta-francia", "FR30" to "francia-alta-francia",
    "FR23" to "francia-normandia", "FR25" to "francia-normandia",
    "FR24" to "francia-centro-valle-della-loira",
    "FR26" to "francia-borgogna-franca-contea", "FR43" to "francia-borgogna-franca-contea",
    "FR51" to "francia-paesi-della-loira",
    "FR52" to "francia-bretagna",
    "FR53" to "francia-nuova-aquitania", "FR61" to "francia-nuova-aquitania", "FR63" to "francia-nuova-aquitania",
    "FR62" to "francia-occitania", "FR81" to "francia-occitania",
    "FR71" to "francia-alvernia-rodano-alpi", "FR72" to "francia-alvernia-rodano-alpi",
    "FR82" to "francia-provenza-alpi-costa-azzurra",
    "FR83" to "francia-corsica",
)

/**
 * Le allerte Meteoalarm che riguardano la regione [regionId]. Il feed e' per paese, ma alcune regioni del catalogo sono
 * una parte del paese: Azzorre, Madeira, Canarie, Ceuta, Melilla e Svalbard tengono solo le loro aree, che le altre
 * regioni del Portogallo e le regioni Spagna e Norvegia perdono, e ogni regione della Francia tiene solo i suoi
 * dipartimenti. Nelle regioni grandi un'allerta con un'area che non si riconosce resta, come in quelle che coprono tutto
 * il paese.
 */
fun List<Alert>.inMeteoalarmRegion(regionId: String, countryCode: String?): List<Alert> = filter { alert ->
    val zone = alert.zoneCode.orEmpty()
    SMALL_REGIONS[regionId]?.invoke(alert) ?: when {
        countryCode.equals("pt", ignoreCase = true) -> !alert.isAzores() && !alert.isMadeira()
        countryCode.equals("es", ignoreCase = true) -> !alert.isCanary() && zone !in CEUTA_MELILLA_ZONES
        countryCode.equals("no", ignoreCase = true) -> !alert.isSvalbard()
        countryCode.equals("fr", ignoreCase = true) -> FRANCE_REGIONS[zone.take(FRANCE_REGION_CODE)]?.let { it == regionId } ?: true
        else -> true
    }
}

// Regioni che sono una piccola parte del paese del feed: tengono solo le allerte delle loro aree.
private val SMALL_REGIONS: Map<String, (Alert) -> Boolean> = mapOf(
    "portogallo-azzorre" to { it.isAzores() },
    "portogallo-madeira" to { it.isMadeira() },
    "isole-canarie" to { it.isCanary() },
    "ceuta" to { it.zoneCode in CEUTA_ZONES },
    "melilla" to { it.zoneCode in MELILLA_ZONES },
    "svalbard-jan-mayen" to { it.isSvalbard() },
)

private fun Alert.isAzores(): Boolean = AZORES.containsMatchIn(areaName.orEmpty())

private fun Alert.isMadeira(): Boolean = MADEIRA.containsMatchIn(areaName.orEmpty())

private fun Alert.isSvalbard(): Boolean =
    zoneBounds?.let { it.minLat >= SVALBARD_MIN_LAT || it.maxLon < JAN_MAYEN_MAX_LON } == true

private fun Alert.isCanary(): Boolean = zoneCode in CANARY_ZONES || zoneCode.orEmpty().startsWith(CANARY_NUTS)
