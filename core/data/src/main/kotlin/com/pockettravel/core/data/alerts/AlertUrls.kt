package com.pockettravel.core.data.alerts

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate

/**
 * Indirizzi delle fonti. Nessuno contiene la posizione dell'utente: GDACS, USGS, tsunami.gov, GeoNet e JMA rispondono per
 * tutto il mondo o tutta la nazione (il parametro `bbox` di GDACS e' ignorato dal servizio), Meteoalarm per nazione, NWS
 * per stato, ECCC per il rettangolo della regione; il rettangolo si applica comunque dopo con [inside]. Gli indirizzi dei
 * servizi nazionali sono in [NationalAlertUrls].
 */
object AlertUrls {
    private const val GDACS_SEARCH = "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH"
    private const val USGS_FEEDS = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/"
    private const val METEOALARM_FEED = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-"
    private val COUNTRY_SLUG = Regex("[a-z]+(-[a-z]+)*")

    /**
     * Eventi GDACS arancioni e rossi di ogni tipo tra [from] e [to] (estremi compresi). Una risposta ha al massimo 100
     * eventi: i verdi stanno in [gdacsGreen], cosi' non tolgono posto a questi.
     */
    fun gdacsSevere(from: LocalDate, to: LocalDate): String = gdacs("EQ;TC;FL;VO;WF;DR", "Orange;Red", from, to)

    /**
     * Eventi GDACS verdi di cicloni, alluvioni, vulcani e siccita'. Senza terremoti (li copre USGS, scelta dell'utente del
     * 2026-10-26) e senza incendi, che sono centinaia a settimana nel mondo e riempirebbero la risposta.
     */
    fun gdacsGreen(from: LocalDate, to: LocalDate): String = gdacs("TC;FL;VO;DR", "Green", from, to)

    private fun gdacs(events: String, levels: String, from: LocalDate, to: LocalDate): String = GDACS_SEARCH.toHttpUrl().newBuilder()
        .addQueryParameter("eventlist", events)
        .addQueryParameter("fromdate", from.toString())
        .addQueryParameter("todate", to.toString())
        .addQueryParameter("alertlevel", levels)
        .build().toString()

    /** Terremoti USGS di magnitudo 2,5 o piu' delle ultime 24 ore (circa 25 kB). */
    fun usgsDay(): String = USGS_FEEDS + "2.5_day.geojson"

    /** Terremoti "significativi" USGS dell'ultima settimana (magnitudo, segnalazioni e allerta PAGER; pochi kB). */
    fun usgsSignificantWeek(): String = USGS_FEEDS + "significant_week.geojson"

    /** Feed Atom di un paese di Meteoalarm; [countrySlug] e' il nome inglese in minuscolo con i trattini ("united-kingdom"). */
    fun meteoalarm(countrySlug: String): String {
        require(COUNTRY_SLUG.matches(countrySlug)) { "Paese Meteoalarm non valido: $countrySlug" }
        return METEOALARM_FEED + countrySlug
    }
}
