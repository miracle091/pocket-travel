package com.pockettravel.core.sync

import java.util.Locale

/**
 * Pacchetto guide da installare e versione con cui registrarlo in installed_guides. Le guide inglesi
 * (quando l'app e' in inglese e il manifest le offre) si registrano come "en/<versione>": escono nella
 * stessa run di quelle italiane, con la stessa versione, e senza il prefisso cambiare lingua non
 * farebbe riscaricare nulla.
 */
data class GuidesChoice(val entry: GuidesManifestEntry, val installedVersion: String)

/** "en" se l'interfaccia e' in inglese, "it" altrimenti (le guide esistono solo in queste due lingue). */
fun currentGuidesLanguage(): String = if (Locale.getDefault().language == "en") "en" else "it"

fun RegionManifest.guidesChoice(language: String = currentGuidesLanguage()): GuidesChoice {
    val english = guidesEn?.takeIf { language == "en" }
    return if (english != null) GuidesChoice(english, "${ENGLISH_PREFIX}${english.version}") else GuidesChoice(guides, guides.version)
}

/** true se le guide installate (versione registrata) sono in inglese. */
fun isEnglishGuidesVersion(installedVersion: String?): Boolean = installedVersion?.startsWith(ENGLISH_PREFIX) == true

private const val ENGLISH_PREFIX = "en/"
