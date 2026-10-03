package com.pockettravel.core.sync

/**
 * La regione con i percorsi "solo auto" al posto di quelli completi, se [carOnly] e il manifest li offre: stessi nomi
 * dei segmenti, quindi installazione, unione fra regioni e zona funzionano uguali. Va applicata prima di restrictedTo.
 */
fun RegionManifestEntry.withRoutingVariant(carOnly: Boolean): RegionManifestEntry =
    if (carOnly && routingCar != null) copy(routing = routingCar) else this

/**
 * I percorsi della voce sono la variante "solo auto": i segmenti hanno lo sha256 di quelli di [RegionManifestEntry.routingCar]
 * (vale anche dopo restrictedTo, che toglie tile ma non cambia variante).
 */
val RegionManifestEntry.hasCarOnlyRouting: Boolean
    get() = routingCar != null && routing.files.isNotEmpty() &&
        routing.files.all { file -> routingCar.files.any { it.sha256 == file.sha256 } }
