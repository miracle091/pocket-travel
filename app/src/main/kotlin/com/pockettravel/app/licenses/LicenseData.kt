package com.pockettravel.app.licenses

import androidx.compose.ui.graphics.Color

// Registro delle licenze di terze parti: solo componenti realmente in uso nell'app, tenuto
// sincronizzato a mano con gradle/libs.versions.toml e i moduli che li dichiarano. Nessuna nota
// di verifica/sviluppo qui — quella resta nel log di sviluppo interno, non in un file distribuito
// con l'app.
enum class LicenseRisk(val label: String, val color: Color) {
    OPEN(label = "Riuso libero", color = Color(0xFF2E7D32)),
    CAUTION(label = "Attenzione", color = Color(0xFF9A7B00)),
}

data class LicenseEntry(
    val component: String,
    val license: String,
    val risk: LicenseRisk,
    val note: String? = null,
)

val thirdPartyLicenses: List<LicenseEntry> = listOf(
    LicenseEntry("MapLibre GL (Android SDK)", "BSD-2-Clause", LicenseRisk.OPEN),
    LicenseEntry("BRouter Core (vendorizzato, third-party/brouter-core)", "MIT", LicenseRisk.OPEN),
    LicenseEntry("Planetiler", "Apache-2.0", LicenseRisk.OPEN),
    LicenseEntry("Jetpack Compose, Room, WorkManager, Hilt", "Apache-2.0", LicenseRisk.OPEN),
    LicenseEntry("kotlinx.serialization", "Apache-2.0", LicenseRisk.OPEN),
    LicenseEntry(
        component = "LiteRT-LM",
        license = "Apache-2.0",
        risk = LicenseRisk.OPEN,
        note = "Sostituisce MediaPipe LLM Inference API (Google), ora in maintenance-only mode.",
    ),
    LicenseEntry("AndroidX Browser (Custom Tabs)", "Apache-2.0", LicenseRisk.OPEN),
    LicenseEntry("pmtiles-reader (lettura PMTiles via HTTP range, core:sync)", "BSD-3-Clause", LicenseRisk.OPEN),
    LicenseEntry("Basemap Protomaps (software/schema)", "BSD-3-Clause", LicenseRisk.OPEN),
    LicenseEntry(
        component = "Dati OpenStreetMap",
        license = "ODbL 1.0",
        risk = LicenseRisk.OPEN,
        note = "Richiede attribuzione e condivisione delle modifiche al database.",
    ),
    LicenseEntry(
        component = "Wikivoyage",
        license = "CC BY-SA 4.0",
        risk = LicenseRisk.CAUTION,
        note = "Attribuzione visibile obbligatoria; i contenuti derivati restano CC BY-SA.",
    ),
    LicenseEntry(
        component = "Modello on-device Gemma3-1B-IT (LiteRT Community)",
        license = "Licenza custom Google (Gemma)",
        risk = LicenseRisk.CAUTION,
        note = "Non OSI-approved; richiede accettazione della licenza su Hugging Face.",
    ),
)
