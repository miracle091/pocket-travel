package com.pockettravel.core.data

/**
 * I pacchetti di una regione, ciascuno con versione propria e scaricabile/aggiornabile da solo.
 * Le guide non sono qui: un solo pacchetto per tutte le regioni (vedi installed_guides).
 */
// POI_EXTRA: fontanelle, tavoli da picnic, parchi giochi... (vedi core:poi), solo su richiesta.
enum class PackageKind { MAP, ROUTING, POI, POI_EXTRA, ADDRESSES }
