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
        component = "LiteRT-LM",
        license = "Apache-2.0",
        note = "Sostituisce MediaPipe LLM Inference API (Google), ora in maintenance-only mode.",
    ),
    LicenseEntry("AndroidX Browser (Custom Tabs)", "Apache-2.0"),
    LicenseEntry("pmtiles-reader (lettura PMTiles via HTTP range, core:sync)", "BSD-3-Clause"),
    LicenseEntry("Basemap Protomaps (software/schema)", "BSD-3-Clause"),
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
)
