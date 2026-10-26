package com.pockettravel.core.data.alerts

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate

/**
 * Indirizzi delle fonti. Nessuno contiene la posizione dell'utente: GDACS e USGS rispondono per tutto il mondo
 * (il parametro `bbox` di GDACS e' ignorato dal servizio) e il rettangolo della regione si applica dopo con [inside].
 */
object AlertUrls {
    private const val GDACS_SEARCH = "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH"
    private const val USGS_FEED = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_week.geojson"
    private const val METEOALARM_FEED = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-"
    private val COUNTRY_SLUG = Regex("[a-z]+(-[a-z]+)*")

    /** Eventi GDACS tra [from] e [to] (estremi compresi) con livello arancione o rosso. */
    fun gdacs(from: LocalDate, to: LocalDate): String = GDACS_SEARCH.toHttpUrl().newBuilder()
        .addQueryParameter("eventlist", "EQ;TC;FL;VO;WF;DR")
        .addQueryParameter("fromdate", from.toString())
        .addQueryParameter("todate", to.toString())
        .addQueryParameter("alertlevel", "Orange;Red")
        .build().toString()

    /** Feed USGS dei terremoti di magnitudo 2,5 o piu' dell'ultima settimana. */
    fun usgs(): String = USGS_FEED

    /** Feed Atom di un paese di Meteoalarm; [countrySlug] e' il nome inglese in minuscolo con i trattini ("united-kingdom"). */
    fun meteoalarm(countrySlug: String): String {
        require(COUNTRY_SLUG.matches(countrySlug)) { "Paese Meteoalarm non valido: $countrySlug" }
        return METEOALARM_FEED + countrySlug
    }
}
