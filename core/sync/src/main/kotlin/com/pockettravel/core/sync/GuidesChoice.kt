package com.pockettravel.core.sync

import com.pockettravel.core.data.currentGuidesLanguage

/**
 * Pacchetto guide da installare e versione con cui registrarlo in installed_guides. Le guide inglesi
 * (quando l'app e' in inglese e il manifest le offre) si registrano come "en-<versione>": escono nella
 * stessa run di quelle italiane, con la stessa versione, e senza il prefisso cambiare lingua non
 * farebbe riscaricare nulla.
 */
data class GuidesChoice(val entry: GuidesManifestEntry, val installedVersion: String)

fun RegionManifest.guidesChoice(language: String = currentGuidesLanguage()): GuidesChoice {
    val english = guidesEn?.takeIf { language == "en" }
    return if (english != null) GuidesChoice(english, "${ENGLISH_PREFIX}${english.version}") else GuidesChoice(guides, guides.version)
}

/** true se le guide installate (versione registrata) sono in inglese. */
fun isEnglishGuidesVersion(installedVersion: String?): Boolean = installedVersion?.startsWith(ENGLISH_PREFIX) == true

/**
 * Il manifest per la lingua dell'interfaccia: in inglese le citta' di ogni regione sono quelle di
 * Wikivoyage EN (citiesEn, se c'e'), con la versione preceduta da "en-" come per le guide. Cosi' tutto
 * il resto (installazione, versioni installate, pacchetti da aggiornare) resta com'e', e dopo un cambio
 * di lingua le citta' installate risultano da aggiornare.
 */
fun RegionManifest.forLanguage(language: String = currentGuidesLanguage()): RegionManifest =
    if (language != "en") {
        this
    } else {
        copy(
            regions = regions.map { region ->
                region.citiesEn?.let { english -> region.copy(cities = english.copy(version = "${ENGLISH_PREFIX}${english.version}")) } ?: region
            },
        )
    }

// "-" e non "/": le versioni dei pacchetti di regione finiscono nei percorsi di staging (isSafeVersion).
private const val ENGLISH_PREFIX = "en-"
