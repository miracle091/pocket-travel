package com.pockettravel.core.data.alerts

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Indirizzi dei centri tsunami del NOAA e dei servizi nazionali; come [AlertUrls], mai la posizione dell'utente. */
object NationalAlertUrls {
    private const val NWS_ALERTS = "https://api.weather.gov/alerts/active"
    private val NWS_AREA = Regex("[A-Z]{2}")
    private const val TSUNAMI_EVENTS = "https://www.tsunami.gov/events/xml/"
    private const val ECCC_ALERTS = "https://api.weather.gc.ca/collections/weather-alerts/items"
    private const val ECCC_MAX_ALERTS = "500"
    private const val GEONET_VAL = "https://api.geonet.org.nz/volcano/val"
    private const val JMA_WARNINGS = "https://www.jma.go.jp/bosai/warning/data/warning/map.json"

    /** Allerte NWS in corso di uno stato o territorio degli Stati Uniti (codice da [nwsArea]). */
    fun nws(area: String): String {
        require(NWS_AREA.matches(area)) { "Area NWS non valida: $area" }
        return NWS_ALERTS.toHttpUrl().newBuilder().addQueryParameter("area", area).build().toString()
    }

    /** Ultimo messaggio CAP del Pacific Tsunami Warning Center (Pacifico e Caraibi). */
    fun tsunamiPacific(): String = TSUNAMI_EVENTS + "PHEBCAP.xml"

    /** Ultimo messaggio CAP del National Tsunami Warning Center (Alaska, Canada, coste degli Stati Uniti). */
    fun tsunamiNational(): String = TSUNAMI_EVENTS + "PAAQCAP.xml"

    /** Allerte meteo del Canada nel rettangolo della regione (ECCC, MSC GeoMet). */
    fun eccc(bounds: AlertBounds): String = ECCC_ALERTS.toHttpUrl().newBuilder()
        .addQueryParameter("f", "json")
        .addQueryParameter("bbox", "${bounds.minLon},${bounds.minLat},${bounds.maxLon},${bounds.maxLat}")
        .addQueryParameter("limit", ECCC_MAX_ALERTS)
        .build().toString()

    /** Livelli di allerta vulcanica della Nuova Zelanda (GeoNet, pochi kB). */
    fun geonetVolcanoes(): String = GEONET_VAL

    /** Allerte in corso di tutto il Giappone (JMA, un file di circa 150 kB). */
    fun jmaWarnings(): String = JMA_WARNINGS
}
