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

// apkSha256 e apkSizeBytes: impronta e dimensione di app-release.apk della release v<versionName>, firmate con il
// resto del file da publish-apk.yml. Facoltative: mancano nei file pubblicati fino alla 0.14.0; quando mancano, l'app
// offre solo il link a GitHub.
@Serializable
data class AppVersionEntry(
    val versionName: String,
    val versionCode: Int,
    val apkSha256: String? = null,
    val apkSizeBytes: Long? = null,
)

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
    require(versionName.matches(SAFE_NAME)) { "versionName non valido" }
    require(versionCode > 0) { "versionCode non valido" }
    require((apkSha256 == null) == (apkSizeBytes == null)) { "apkSha256 e apkSizeBytes vanno insieme" }
    require(apkSha256 == null || apkSha256.matches(SHA256_HEX)) { "apkSha256 non valido" }
    require(apkSizeBytes == null || apkSizeBytes > 0) { "apkSizeBytes non valido" }
}

fun AiModelManifestEntry.validate() {
    require(modelId.matches(SAFE_NAME)) { "modelId non valido" }
    require(modelVersion.matches(SAFE_NAME)) { "modelVersion non valido" }
    require(sha256.matches(SHA256_HEX)) { "sha256 non valido" }
    require(sizeBytes >= 0) { "sizeBytes non valido" }
}
