package com.pockettravel.core.data

/**
 * I pacchetti di una regione, ciascuno con versione propria e scaricabile/aggiornabile da solo.
 * Le guide non sono qui: un solo pacchetto per tutte le regioni (vedi installed_guides).
 */
// POI_EXTRA: fontanelle, tavoli da picnic, parchi giochi... (vedi core:poi), solo su richiesta.
// CITIES: guide delle citta' della regione (city_sections), scaricate col resto nel download completo.
// TRANSIT: orari dei mezzi pubblici (una transit.db per rete, GTFS), solo su richiesta come i POI extra.
enum class PackageKind { MAP, ROUTING, POI, POI_EXTRA, ADDRESSES, CITIES, TRANSIT }
