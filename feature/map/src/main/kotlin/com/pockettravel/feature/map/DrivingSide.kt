package com.pockettravel.feature.map

import java.util.Locale

/** Lato della strada su cui si guida in un paese. */
enum class DrivingSide { LEFT, RIGHT }

/**
 * Paesi e territori (ISO 3166-1 alpha-2) dove si guida a sinistra: Regno Unito, Irlanda, Giappone, India,
 * Australia, Nuova Zelanda, buona parte dell'Africa australe e orientale, del Sud-est asiatico e dei Caraibi
 * anglofoni. Tutti gli altri guidano a destra.
 */
private val LEFT_HAND_TRAFFIC = setOf(
    "AG", "AI", "AU", "BB", "BD", "BM", "BN", "BS", "BT", "BW", "CK", "CY", "DM", "FJ", "FK", "GB", "GD", "GG",
    "GY", "HK", "ID", "IE", "IM", "IN", "JE", "JM", "JP", "KE", "KI", "KN", "KY", "LC", "LK", "LS", "MO", "MS",
    "MT", "MU", "MV", "MW", "MY", "MZ", "NA", "NF", "NP", "NR", "NU", "NZ", "PG", "PK", "PN", "SB", "SC", "SG",
    "SH", "SR", "SZ", "TC", "TH", "TK", "TL", "TO", "TT", "TV", "TZ", "UG", "VC", "VG", "VI", "WS", "ZA", "ZM", "ZW",
)

internal fun drivingSide(countryCode: String): DrivingSide =
    if (countryCode.uppercase(Locale.ROOT) in LEFT_HAND_TRAFFIC) DrivingSide.LEFT else DrivingSide.RIGHT

/**
 * Il lato da ricordare a chi guida nei paesi [routeCountries], se diverso da quello di casa ([homeCountry],
 * la nazionalita'); null se e' lo stesso. Senza nazionalita' si assume casa a destra, come per la maggior
 * parte del mondo: l'avviso compare dove si guida a sinistra.
 */
internal fun drivingSideWarning(routeCountries: Collection<String>, homeCountry: String?): DrivingSide? {
    val home = homeCountry?.let(::drivingSide) ?: DrivingSide.RIGHT
    return routeCountries.map(::drivingSide).firstOrNull { it != home }
}
