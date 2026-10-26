package com.pockettravel.core.data.alerts

/**
 * Codice "area" dell'API di NWS (`alerts/active?area=XX`) di una regione degli Stati Uniti. Le regioni statunitensi sono
 * una per stato; i territori hanno una regione propria o, se la regione non e' tra queste, si riconoscono dal paese.
 */
private val NWS_REGION_AREAS = mapOf(
    "stati-uniti-alabama" to "AL",
    "stati-uniti-alaska" to "AK",
    "stati-uniti-arizona" to "AZ",
    "stati-uniti-arkansas" to "AR",
    "stati-uniti-california" to "CA",
    "stati-uniti-carolina-del-nord" to "NC",
    "stati-uniti-carolina-del-sud" to "SC",
    "stati-uniti-colorado" to "CO",
    "stati-uniti-connecticut" to "CT",
    "stati-uniti-dakota-del-nord" to "ND",
    "stati-uniti-dakota-del-sud" to "SD",
    "stati-uniti-delaware" to "DE",
    "stati-uniti-distretto-di-columbia" to "DC",
    "stati-uniti-florida" to "FL",
    "stati-uniti-georgia" to "GA",
    "stati-uniti-hawaii" to "HI",
    "stati-uniti-idaho" to "ID",
    "stati-uniti-illinois" to "IL",
    "stati-uniti-indiana" to "IN",
    "stati-uniti-iowa" to "IA",
    "stati-uniti-kansas" to "KS",
    "stati-uniti-kentucky" to "KY",
    "stati-uniti-louisiana" to "LA",
    "stati-uniti-maine" to "ME",
    "stati-uniti-maryland" to "MD",
    "stati-uniti-massachusetts" to "MA",
    "stati-uniti-michigan" to "MI",
    "stati-uniti-minnesota" to "MN",
    "stati-uniti-mississippi" to "MS",
    "stati-uniti-missouri" to "MO",
    "stati-uniti-montana" to "MT",
    "stati-uniti-nebraska" to "NE",
    "stati-uniti-nevada" to "NV",
    "stati-uniti-new-hampshire" to "NH",
    "stati-uniti-new-jersey" to "NJ",
    "stati-uniti-new-york" to "NY",
    "stati-uniti-nuovo-messico" to "NM",
    "stati-uniti-ohio" to "OH",
    "stati-uniti-oklahoma" to "OK",
    "stati-uniti-oregon" to "OR",
    "stati-uniti-pennsylvania" to "PA",
    "stati-uniti-rhode-island" to "RI",
    "stati-uniti-tennessee" to "TN",
    "stati-uniti-texas" to "TX",
    "stati-uniti-utah" to "UT",
    "stati-uniti-vermont" to "VT",
    "stati-uniti-virginia" to "VA",
    "stati-uniti-virginia-occidentale" to "WV",
    "stati-uniti-washington" to "WA",
    "stati-uniti-wisconsin" to "WI",
    "stati-uniti-wyoming" to "WY",
    "portorico" to "PR",
    "guam" to "GU",
    "isole-vergini-americane" to "VI",
    "samoa-americane" to "AS",
    "isole-marianne-settentrionali" to "MP",
)

// Territori degli Stati Uniti per codice ISO 3166-1 alpha-2 del paese.
private val NWS_TERRITORY_AREAS = mapOf(
    "pr" to "PR",
    "gu" to "GU",
    "vi" to "VI",
    "as" to "AS",
    "mp" to "MP",
)

/**
 * Codice area di NWS per la regione [regionId] o, se la regione non e' elencata, per il territorio del paese [countryCode]
 * (ISO 3166-1 alpha-2, maiuscolo o minuscolo); nullo fuori dagli Stati Uniti e dai loro territori.
 */
fun nwsArea(regionId: String, countryCode: String?): String? =
    NWS_REGION_AREAS[regionId] ?: countryCode?.lowercase()?.let(NWS_TERRITORY_AREAS::get)
