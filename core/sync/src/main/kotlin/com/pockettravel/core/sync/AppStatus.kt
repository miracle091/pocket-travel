package com.pockettravel.core.sync

import kotlinx.serialization.Serializable

/**
 * Schema di app-status.json (asset della release GitHub "app-status", vedi
 * SyncConfig.APP_STATUS_URL), separato di proposito da RegionManifest/manifest.json: appVersion
 * cambia solo quando l'app viene rilasciata (publish-apk.yml), aiModels anche quando un modello viene
 * ricaricato su HuggingFace (upload_hf.py --update-app-status), mai insieme ai pacchetti regionali.
 */
@Serializable
data class AppStatus(val appVersion: AppVersionEntry? = null, val aiModels: List<AiModelManifestEntry> = emptyList())

@Serializable
data class AppVersionEntry(val versionName: String, val versionCode: Int)

// Una entry per modello del catalogo (LlmModelCatalog.ALL): modelId
// e' il discriminante per sapere a quale modello installato si riferisce l'entry (vedi
// LlmModelUpdateCheckWorker, che cerca quella con modelId == modello selezionato). sha256 resta
// il discriminante di versione all'interno di uno stesso modelId; modelVersion e' il nome del file
// (fileName nel catalogo). Niente campo url: l'app scarica sempre dall'url del suo catalogo.
// sha256 e sizeBytes sono anche le impronte con cui LlmModelDownloadWorker verifica il download,
// al posto di quelle del catalogo dell'APK, quando nome file e modelId coincidono.
@Serializable
data class AiModelManifestEntry(val modelId: String, val modelVersion: String, val sha256: String, val sizeBytes: Long)

fun AppVersionEntry.validate() {
    require(versionName.matches(Regex("[A-Za-z0-9._-]+"))) { "versionName non valido" }
    require(versionCode > 0) { "versionCode non valido" }
}

fun AiModelManifestEntry.validate() {
    require(modelId.matches(Regex("[A-Za-z0-9._-]+"))) { "modelId non valido" }
    require(modelVersion.matches(Regex("[A-Za-z0-9._-]+"))) { "modelVersion non valido" }
    require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "sha256 non valido" }
    require(sizeBytes >= 0) { "sizeBytes non valido" }
}
