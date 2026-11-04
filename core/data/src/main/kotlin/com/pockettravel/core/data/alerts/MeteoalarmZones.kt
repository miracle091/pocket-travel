package com.pockettravel.core.data.alerts

private val AZORES = Regex("""^(A[cç]ores|Azores)\b""", RegexOption.IGNORE_CASE)
private val MADEIRA = Regex("""^Madeira\b""", RegexOption.IGNORE_CASE)

// Zone delle Canarie nel feed spagnolo (codice EMMA_ID): Gran Canaria, Lanzarote, Fuerteventura, La Palma, La Gomera,
// El Hierro e Tenerife. Con il solo NUTS3 le Canarie sono ES70x.
private val CANARY_ZONES = (120..132).map { "ES$it" }.toSet()
private const val CANARY_NUTS = "ES70"

// Lunghezza del codice della regione francese dentro il NUTS3 del dipartimento ("FR81" in "FR812").
private const val FRANCE_REGION_CODE = 4

// Il feed francese da' il dipartimento con il NUTS3 del 2010 (FR812 Gard): le prime quattro cifre sono la regione di prima
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
 * una parte del paese: Azzorre e Madeira tengono solo le loro zone e le altre regioni del Portogallo le perdono, la regione
 * Spagna perde le Canarie, e ogni regione della Francia tiene solo i suoi dipartimenti. Un'allerta con una zona che non si
 * riconosce resta, come quelle delle regioni che coprono tutto il paese.
 */
fun List<Alert>.inMeteoalarmRegion(regionId: String, countryCode: String?): List<Alert> = filter { alert ->
    val name = alert.areaName.orEmpty()
    val zone = alert.zoneCode.orEmpty()
    when {
        regionId == "portogallo-azzorre" -> AZORES.containsMatchIn(name)
        regionId == "portogallo-madeira" -> MADEIRA.containsMatchIn(name)
        countryCode.equals("pt", ignoreCase = true) -> !AZORES.containsMatchIn(name) && !MADEIRA.containsMatchIn(name)
        countryCode.equals("es", ignoreCase = true) -> zone !in CANARY_ZONES && !zone.startsWith(CANARY_NUTS)
        countryCode.equals("fr", ignoreCase = true) -> FRANCE_REGIONS[zone.take(FRANCE_REGION_CODE)]?.let { it == regionId } ?: true
        else -> true
    }
}
