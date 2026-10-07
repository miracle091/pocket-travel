package com.pockettravel.app.licenses

// Registro delle licenze di terze parti: solo componenti realmente in uso nell'app, tenuto
// sincronizzato a mano con gradle/libs.versions.toml e i moduli che li dichiarano. Contiene solo
// i dati mostrati nell'app, senza note di verifica.
data class LicenseEntry(
    val component: String,
    val license: String,
    val note: String? = null,
)

val thirdPartyLicenses: List<LicenseEntry> = listOf(
    LicenseEntry("MapLibre GL (Android SDK)", "BSD-2-Clause"),
    LicenseEntry("BRouter Core (vendorizzato, third-party/brouter-core)", "MIT"),
    LicenseEntry("Planetiler", "Apache-2.0"),
    LicenseEntry("Jetpack Compose, Room, WorkManager, Hilt", "Apache-2.0"),
    LicenseEntry("kotlinx.serialization", "Apache-2.0"),
    LicenseEntry(
        component = "llama.cpp (vendorizzato, third-party/llama-cpp)",
        license = "MIT",
        note = "Sostituisce LiteRT-LM (Google), che non pubblica piu' release da agosto 2026; nessun AAR Maven ufficiale esiste, compilato da sorgente via NDK/CMake.",
    ),
    LicenseEntry("nlohmann/json (usato da llama.cpp, third-party/llama-cpp/vendor/nlohmann)", "MIT"),
    LicenseEntry("sheredom/subprocess.h (usato da llama.cpp, third-party/llama-cpp/vendor/sheredom)", "Unlicense"),
    LicenseEntry("yhirose/cpp-httplib (usato da llama.cpp, third-party/llama-cpp/vendor/cpp-httplib)", "MIT"),
    LicenseEntry("pmtiles-reader (lettura PMTiles via HTTP range, core:sync)", "BSD-3-Clause"),
    LicenseEntry("XZ for Java (decompressione dei punti di interesse, core:sync)", "0BSD"),
    LicenseEntry("Basemap Protomaps (software/schema)", "BSD-3-Clause"),
    LicenseEntry(
        component = "Natural Earth (confini dei paesi, mappa delle nazioni)",
        license = "Pubblico dominio",
        note = "Dati 1:50m, ridotti a confini, codice ISO e nome del paese (feature:map/src/main/assets/world).",
    ),
    LicenseEntry(
        component = "OurAirports (aeroporti) e Wikidata (compagnie aeree, nomi delle città)",
        license = "Pubblico dominio (OurAirports), CC0 (Wikidata)",
        note = "Suggerimenti di aeroporti e compagnie nei biglietti dei documenti (feature:vault/src/main/assets). " +
            "I dati di OurAirports sono forniti senza garanzie.",
    ),
    LicenseEntry(
        component = "Bandiere nazionali (westnordost/flags-vector-drawables-android, core:ui/src/main/res/drawable/ic_flag_*)",
        license = "Licenza di ciascuna bandiera su Wikipedia/Wikimedia Commons, in gran parte pubblico dominio",
        note = "Vettoriali esportati dalle bandiere di Wikipedia (Timeline of national flags).",
    ),
    LicenseEntry(
        component = "Icone Material Symbols (Google, google/material-design-icons, core:ui/src/main/res/drawable/ms_*)",
        license = "Apache-2.0",
        note = "Icone dell'interfaccia, delle categorie dei punti di interesse e delle frecce di navigazione.",
    ),
    LicenseEntry(
        component = "Klokantech Noto Sans (glifi etichette mappa, feature:map/src/main/assets/fonts)",
        license = "SIL OFL 1.1",
        note = "Font Noto Sans (Google) ripacchettato in SDF da openmaptiles/fonts; inclusi i range di latino, greco, cirillico, armeno, ebraico, arabo, thai, georgiano e punteggiatura.",
    ),
    LicenseEntry(
        component = "Dati OpenStreetMap",
        license = "ODbL 1.0",
        note = "Richiede attribuzione e condivisione delle modifiche al database.",
    ),
    LicenseEntry(
        component = "Wikivoyage",
        license = "CC BY-SA 4.0",
        note = "Attribuzione visibile obbligatoria; i contenuti derivati restano CC BY-SA. " +
            "Alcune sezioni in italiano sono tradotte automaticamente dall'inglese (opus-mt-tc-big-en-it, Helsinki-NLP, CC BY 4.0) " +
            "e alcune in inglese dall'italiano (opus-mt-tc-big-it-en, CC BY 4.0): sono opere derivate modificate rispetto all'originale.\n" +
            "Some sections in English are machine-translated from Italian (opus-mt-tc-big-it-en, Helsinki-NLP, CC BY 4.0) " +
            "and some in Italian from English (opus-mt-tc-big-en-it, CC BY 4.0): they are derivative works, modified from the original.",
    ),
    LicenseEntry(
        component = "Wikipedia (storia e clima delle città)",
        license = "CC BY-SA 4.0",
        note = "Sezioni Storia (accorciata) e Clima della voce di ogni città, con il link alla voce tra le fonti della guida. " +
            "Se la voce è in un'altra lingua, il testo è tradotto automaticamente (vedi Wikivoyage).",
    ),
    LicenseEntry(
        component = "Wikidata (ambasciate e consolati)",
        license = "CC0 1.0",
        note = "Dominio pubblico: l'attribuzione non è obbligatoria, ma la fonte è Wikidata.",
    ),
    LicenseEntry(
        component = "Meteo: Open-Meteo.com (previsioni) e GeoNames (posizione delle città)",
        license = "CC BY 4.0",
        note = "Weather data by Open-Meteo.com, uso non commerciale. Coordinate delle città dal geocoding di Open-Meteo, basato su GeoNames.",
    ),
    LicenseEntry(
        component = "Numeri di emergenza: Travel.gc.ca (Governo del Canada)",
        license = "Open Government Licence - Canada 2.0",
        note = "Contiene informazioni concesse in licenza ai sensi della Licence du gouvernement ouvert - Canada. Confrontati con Wikipedia (CC BY-SA 4.0) e Wikidata (CC0).",
    ),
    LicenseEntry(
        component = "Numeri di emergenza: gov.uk Foreign travel advice (Governo del Regno Unito)",
        license = "Open Government Licence v3.0",
        note = "Contains public sector information licensed under the Open Government Licence v3.0.",
    ),
    LicenseEntry(
        component = "Vaccinazioni: Travel.gc.ca (Governo del Canada)",
        license = "Open Government Licence - Canada 2.0",
        note = "Contiene informazioni concesse in licenza ai sensi della Licence du gouvernement ouvert - Canada. Fatti riscritti con parole nostre e confrontati con altre fonti.",
    ),
    LicenseEntry(
        component = "Vaccinazioni: TravelHealthPro (UKHSA / NaTHNaC)",
        license = "Open Government Licence v3.0",
        note = "Contains public sector information published by the UK Health Security Agency (UKHSA) and the National Travel Health Network and Centre (NaTHNaC), licensed under the Open Government Licence v3.0. Facts rewritten in our own words.",
    ),
)
