package com.pockettravel.core.data.testing

import com.pockettravel.core.data.LastKnownPosition

/** [LastKnownPosition] in memoria: [position] (latitudine, longitudine) con la sua eta' in [ageMillis]; null = nessuna posizione nota. */
class FakeLastKnownPosition(var position: Pair<Double, Double>? = null) : LastKnownPosition {
    var ageMillis: Long = 0L

    override fun get(maxAgeMillis: Long): Pair<Double, Double>? = position?.takeIf { ageMillis <= maxAgeMillis }
}
