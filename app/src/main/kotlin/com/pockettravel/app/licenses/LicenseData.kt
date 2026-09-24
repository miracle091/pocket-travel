package com.pockettravel.app.licenses

// Registro delle licenze di terze parti: solo componenti realmente in uso nell'app, tenuto
// sincronizzato a mano con gradle/libs.versions.toml e i moduli che li dichiarano. Nessuna nota
// di verifica/sviluppo qui — quella resta nel log di sviluppo interno, non in un file distribuito
// con l'app.
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
    LicenseEntry("AndroidX Browser (Custom Tabs)", "Apache-2.0"),
    LicenseEntry("pmtiles-reader (lettura PMTiles via HTTP range, core:sync)", "BSD-3-Clause"),
    LicenseEntry("Basemap Protomaps (software/schema)", "BSD-3-Clause"),
    LicenseEntry(
        component = "Natural Earth (confini dei paesi, mappa del mondo)",
        license = "Pubblico dominio",
        note = "Dati 1:50m, ridotti a confini, codice ISO e nome del paese (feature:map/src/main/assets/world).",
    ),
    LicenseEntry(
        component = "Bandiere nazionali (westnordost/flags-vector-drawables-android, core:ui/src/main/res/drawable/ic_flag_*)",
        license = "Licenza di ciascuna bandiera su Wikipedia/Wikimedia Commons, in gran parte pubblico dominio",
        note = "Vettoriali esportati dalle bandiere di Wikipedia (Timeline of national flags).",
    ),
    LicenseEntry(
        component = "Klokantech Noto Sans (glifi etichette mappa, feature:map/src/main/assets/fonts)",
        license = "SIL OFL 1.1",
        note = "Font Noto Sans (Google) ripacchettato in SDF da openmaptiles/fonts; solo il range 0-255 (ASCII + Latin-1) e' incluso nell'app.",
    ),
    LicenseEntry(
        component = "Dati OpenStreetMap",
        license = "ODbL 1.0",
        note = "Richiede attribuzione e condivisione delle modifiche al database.",
    ),
    LicenseEntry(
        component = "Wikivoyage",
        license = "CC BY-SA 4.0",
        note = "Attribuzione visibile obbligatoria; i contenuti derivati restano CC BY-SA.",
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
)
