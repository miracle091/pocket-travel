package com.pockettravel.core.data.alerts

/**
 * Rettangolo in gradi, come i limiti delle regioni del catalogo. Con [minLon] maggiore di [maxLon] attraversa
 * l'antimeridiano (180 gradi). I bordi fanno parte del rettangolo.
 */
data class AlertBounds(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    /** Vero se il punto e' dentro il rettangolo, bordi compresi. */
    fun contains(lat: Double, lon: Double): Boolean = intersects(AlertBounds(lon, lat, lon, lat))

    /** Vero se i due rettangoli hanno almeno un punto in comune. */
    fun intersects(other: AlertBounds): Boolean =
        minLat <= other.maxLat && other.minLat <= maxLat &&
            lonRanges().any { mine -> other.lonRanges().any { theirs -> mine.overlaps(theirs) } }

    // Un rettangolo che attraversa l'antimeridiano e' l'unione di due intervalli di longitudine.
    private fun lonRanges(): List<ClosedFloatingPointRange<Double>> =
        if (minLon <= maxLon) listOf(minLon..maxLon) else listOf(minLon..MAX_LON, MIN_LON..maxLon)

    private fun ClosedFloatingPointRange<Double>.overlaps(other: ClosedFloatingPointRange<Double>): Boolean =
        start <= other.endInclusive && other.start <= endInclusive

    private companion object {
        const val MIN_LON = -180.0
        const val MAX_LON = 180.0
    }
}

/**
 * Le allerte che toccano [bounds]. Quelle senza coordinate (Meteoalarm, che da' solo il nome dell'area) restano:
 * la fonte e' gia' limitata al paese, e un'allerta non si scarta per un dato che manca.
 */
fun List<Alert>.inside(bounds: AlertBounds): List<Alert> = filter { alert -> alert.area?.let(bounds::intersects) != false }
