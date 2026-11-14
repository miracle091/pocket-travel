package com.pockettravel.feature.map

/**
 * L'attribuzione del catalogo delle reti finisce in italiano ("; modificato", e per una rete ", orari delle fermate
 * intermedie stimati"): nella lingua dell'app.
 */
internal fun localizedAttribution(attribution: String, modified: String, estimated: String): String =
    attribution.replace("; modificato, orari delle fermate intermedie stimati", "; $modified, $estimated")
        .replace(Regex("; modificato$"), "; $modified")
