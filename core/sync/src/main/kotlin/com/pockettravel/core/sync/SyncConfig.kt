package com.pockettravel.core.sync

import java.net.URI

// Hosting statico open (GitHub Pages), mai un backend dell'app — vedi sezione "Dati &
// sincronizzazione" della specifica tecnica. MANIFEST_URL resta un segnaposto finche' il repo
// non e' davvero pubblicato su GitHub Pages (nessun remote configurato al momento in cui
// questo valore e' stato scritto) — va corretto con l'host reale prima del rilascio.
object SyncConfig {
    private const val PUBLISHED_MANIFEST_URL = "https://miracle091.github.io/pocket-travel/manifest.json"

    // BuildConfig.MANIFEST_URL_OVERRIDE e' vuoto nelle build di release (vedi build.gradle.kts).
    val MANIFEST_URL: String = BuildConfig.MANIFEST_URL_OVERRIDE.ifEmpty { PUBLISHED_MANIFEST_URL }

    // Host di un manifest alternativo servito in chiaro (http, server locale in debug): l'unico
    // per cui RegionManifest accetta URL non https. null col manifest pubblicato.
    val CLEARTEXT_MANIFEST_HOST: String? = URI(MANIFEST_URL).takeIf { it.scheme == "http" }?.host

    const val PERIODIC_SYNC_WORK_NAME = "region-manifest-sync"
    const val DOWNLOAD_WORK_NAME_PREFIX = "region-download-"
    const val GUIDES_SYNC_WORK_NAME = "guides-sync"

    // Asset di una release GitHub fissa ("app-status", sovrascritta ad ogni pubblicazione APK -
    // vedi publish-apk.yml), non un file su GitHub Pages: appVersion/aiModel cambiano solo quando
    // l'app viene rilasciata, mai insieme all'aggiornamento settimanale dei pacchetti regionali
    // (manifest.json sopra) — tenerli nello stesso file avrebbe legato due cicli di pubblicazione
    // indipendenti tra loro.
    const val APP_STATUS_URL = "https://github.com/miracle091/pocket-travel/releases/download/app-status/app-status.json"
    const val APP_UPDATE_CHECK_WORK_NAME = "app-update-check"

    // brouter.de: tenuto in whitelist solo per retrocompatibilita' con voci di manifest
    // pubblicate prima che i segmenti .rd5 venissero ri-ospitati (vedi build-region.sh) — quei
    // segmenti cambiano dimensione/hash nel tempo (rigenerazione periodica lato BRouter),
    // rompendo permanentemente ogni download che li referenzia ancora in diretta
    // (RegionPackageDownloader.downloadAndVerify rigetta un file la cui dimensione non combacia
    // piu' col manifest). build.protomaps.com: mapSource.sourceUrl (MapExtractionSource) punta
    // alla build giornaliera whole-planet da cui il device estrae solo le tile del bounding box
    // della regione — vedi PmtilesExtractor. github.com: guides.db, poi.db e i segmenti .rd5 ri-ospitati
    // vivono sugli asset della release "region-data" (github.com/OWNER/REPO/releases/download/...,
    // limite di 1GB di GitHub Pages sforato con la copertura mondiale di pilot-regions.sh — vedi
    // assemble-site.sh); solo l'host conta qui, il redirect verso il vero storage degli asset
    // avviene dopo, seguito automaticamente da OkHttp.
    val ALLOWED_MANIFEST_HOSTS: Set<String> = setOf(
        URI(MANIFEST_URL).host,
        "brouter.de",
        "build.protomaps.com",
        "github.com",
    )
}
