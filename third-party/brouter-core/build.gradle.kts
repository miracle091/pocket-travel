// Sorgente di BRouter (https://github.com/abrensch/brouter, MIT License — vedi LICENSE-BROUTER.txt
// in questa cartella) vendorizzata da qui invece che come dipendenza Maven: org.btools:brouter-core
// e' pubblicato solo su GitHub Packages (richiede autenticazione anche in sola lettura) e JitPack
// non riesce a buildare il repo (verificato: il suo ambiente di default usa JDK 8, il Gradle
// wrapper di BRouter richiede 17+ — vedi README Fase 10).
//
// Vendorizzati solo i moduli lato "router" (brouter-core/mapaccess/util/expressions/codec, git
// tag v1.7.10) usati per CARICARE un .rd5 e calcolare un percorso — non brouter-mapcreator, che
// serve solo a GENERARE i .rd5 e porta con se' osmosis-osm-binary/protobuf (verificato leggendo
// brouter-core/build.gradle upstream: nessuna di queste 5 dipendenze le richiede).
// Dallo stesso tag anche KinematicModel/KinematicPath/KinematicPrePath (2026-09-25): il profilo
// car-vario.brf li carica per nome ("---model:btools.router.KinematicModel", Class.forName).
plugins {
    id("java-library")
}

java {
    // Stesso target del build ufficiale di BRouter (buildSrc/.../brouter.java-conventions.gradle:
    // "options.release = 11") — mantenuto identico per restare fedeli al bytecode gia' verificato
    // funzionante su Android ART nel prototipo (Fase 10), non un valore scelto a caso.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
