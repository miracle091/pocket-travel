package com.pockettravel.core.sync

/**
 * La regione con i percorsi "solo auto" al posto di quelli completi, se [carOnly] e il manifest li offre: stessi nomi
 * dei segmenti, quindi installazione, unione fra regioni e zona funzionano uguali. Va applicata prima di restrictedTo.
 */
fun RegionManifestEntry.withRoutingVariant(carOnly: Boolean): RegionManifestEntry =
    if (carOnly && routingCar != null) copy(routing = routingCar) else this
