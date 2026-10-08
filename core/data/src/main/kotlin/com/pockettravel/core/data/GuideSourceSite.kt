package com.pockettravel.core.data

/**
 * Sito da cui viene una sezione di guida, letto dal suo link: decide il nome della fonte nel riquadro in fondo alla
 * guida e nelle citazioni dell'assistente. Wikivoyage per tutto il resto, come prima che ci fossero altre fonti.
 */
enum class GuideSourceSite { WIKIVOYAGE, WIKIPEDIA, TRAVEL_GC_CA, FCDO }

fun guideSourceSiteOf(url: String): GuideSourceSite {
    val host = url.substringAfter("://").substringBefore('/').lowercase()
    return when {
        host.endsWith("wikipedia.org") -> GuideSourceSite.WIKIPEDIA
        host == "travel.gc.ca" || host.endsWith(".travel.gc.ca") -> GuideSourceSite.TRAVEL_GC_CA
        host == "www.gov.uk" || host == "gov.uk" -> GuideSourceSite.FCDO
        else -> GuideSourceSite.WIKIVOYAGE
    }
}
