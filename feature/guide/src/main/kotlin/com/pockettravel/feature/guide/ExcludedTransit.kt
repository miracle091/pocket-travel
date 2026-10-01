package com.pockettravel.feature.guide

/**
 * Reti di mezzi pubblici lasciate fuori dagli orari perche' la licenza non ne consente la
 * ridistribuzione (verificata il 2026-10-01), dichiarate nella guida delle regioni che le
 * proporrebbero: chi va a Parigi sa che gli orari della metro mancano per scelta, non per errore.
 * TMB: licenza valida solo per l'app che la chiede, revocabile. Île-de-France Mobilites e TCL Lione:
 * Licence Mobilites, che vuole registrato chi riusa i dati (un file pubblico non puo' garantirlo).
 */
internal fun excludedTransitNotes(regionId: String): List<Int> = when (regionId) {
    "spagna" -> listOf(R.string.transit_excluded_tmb)
    "francia-ile-de-france" -> listOf(R.string.transit_excluded_idfm)
    "francia-alvernia-rodano-alpi" -> listOf(R.string.transit_excluded_tcl)
    "francia" -> listOf(R.string.transit_excluded_idfm, R.string.transit_excluded_tcl)
    else -> emptyList()
}
