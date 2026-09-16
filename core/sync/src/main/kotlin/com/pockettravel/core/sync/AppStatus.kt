package com.pockettravel.core.sync

import kotlinx.serialization.Serializable

/**
 * Schema di app-status.json (asset della release GitHub "app-status", vedi
 * SyncConfig.APP_STATUS_URL), separato di proposito da RegionManifest/manifest.json: appVersion
 * e aiModel cambiano solo quando l'app viene rilasciata (publish-apk.yml), non insieme
 * all'aggiornamento settimanale/manuale dei pacchetti regionali.
 */
@Serializable
data class AppStatus(val appVersion: AppVersionEntry? = null, val aiModel: AiModelManifestEntry? = null)

@Serializable
data class AppVersionEntry(val versionName: String, val versionCode: Int)

// sha256 e' il discriminante di versione (confrontato con AiModelConfig.MODEL_SHA256, la build
// pinnata nell'app corrente), non modelVersion: quest'ultima resta solo un'etichetta leggibile,
// stesso ruolo di displayName per una regione. Niente campo url: questo controllo si limita a
// rilevare/notificare un aggiornamento, non a riscaricare il modello (fuori scope).
@Serializable
data class AiModelManifestEntry(val modelVersion: String, val sha256: String, val sizeBytes: Long)

fun AppVersionEntry.validate() {
    require(versionName.matches(Regex("[A-Za-z0-9._-]+"))) { "versionName non valido" }
    require(versionCode > 0) { "versionCode non valido" }
}

fun AiModelManifestEntry.validate() {
    require(modelVersion.matches(Regex("[A-Za-z0-9._-]+"))) { "modelVersion non valido" }
    require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "sha256 non valido" }
    require(sizeBytes >= 0) { "sizeBytes non valido" }
}
