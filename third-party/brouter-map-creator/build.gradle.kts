// Sorgente di BRouter (https://github.com/abrensch/brouter, MIT License — vedi
// third-party/brouter-core/LICENSE-BROUTER.txt, stesso tag v1.7.10, stessa licenza) vendorizzata
// qui per lo stesso motivo di :third-party:brouter-core (README Fase 10): org.btools:* e'
// pubblicato solo su GitHub Packages (richiede autenticazione) e JitPack non riesce a buildare
// il repo.
//
// Vendorizzato solo brouter-map-creator (btools.mapcreator, git tag v1.7.10) — il modulo che
// GENERA i file .rd5 da un .osm.pbf (i tre stadi OsmFastCutter -> PosUnifier -> WayLinker, vedi
// README Fase 12). Dipende solo da classi gia' vendorizzate in :third-party:brouter-core
// (btools.codec/util/expressions — verificato leggendo gli import di tutti i 35 file sorgente,
// nessun uso di btools.mapaccess/router) piu' org.openstreetmap.osmosis:osmosis-osm-binary, gia'
// alla stessa versione usata da :tools:data-pipeline:maptiles per il proprio encoder OSM-PBF —
// nessuna dipendenza nuova per il progetto.
plugins {
    id("java-library")
}

java {
    // Stesso target del build ufficiale di BRouter, come :third-party:brouter-core (Fase 10/11).
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api(project(":third-party:brouter-core"))
    implementation(libs.osmosis.osm.binary)
}
