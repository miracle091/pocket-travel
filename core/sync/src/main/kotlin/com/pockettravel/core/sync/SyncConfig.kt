package com.pockettravel.core.sync

import java.net.URI

// Hosting statico open (GitHub Pages), mai un backend dell'app: manifest.json pubblicato da
// .github/workflows/publish-regions.yml.
object SyncConfig {
    private const val PUBLISHED_MANIFEST_URL = "https://miracle091.github.io/pocket-travel/manifest.json"

    // Chiave pubblica ECDSA P-256 (X.509 SubjectPublicKeyInfo, base64) del catalogo pubblicato: verifica le firme
    // `<url>.sig` di manifest.json, transit.json, address-grid.json e, sempre, di app-status.json (vedi
    // ManifestSignatureVerifier).
    const val PUBLISHED_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEQvTHdjgRhQviaUGMXeJgQ698ZGiIGjbK6iMPmjvend3tqHsvICoVlPHl+FnnjMwjKEOVai3BWWbmOuCSDkz1cw=="

    // brouter.de: tenuto in whitelist solo per retrocompatibilita' con voci di manifest
    // pubblicate prima che i segmenti .rd5 venissero ri-ospitati (vedi build-region.sh) — quei
    // segmenti cambiano dimensione/hash nel tempo (rigenerazione periodica lato BRouter),
    // rompendo permanentemente ogni download che li referenzia ancora in diretta
    // (RegionPackageDownloader.downloadAndVerify rigetta un file la cui dimensione non combacia
    // piu' col manifest). build.protomaps.com: mapSource.sourceUrl (MapExtractionSource) punta
    // alla build giornaliera whole-planet da cui il device estrae solo le tile del bounding box
    // della regione — vedi PmtilesExtractor. github.com: guides.db, poi.db e i segmenti .rd5 ri-ospitati
    // vivono sugli asset della release "region-data" (github.com/OWNER/REPO/releases/download/...,
    // limite di 1GB di GitHub Pages sforato con la copertura mondiale di regions.sh — vedi
    // assemble-site.sh); solo l'host conta qui, il redirect verso il vero storage degli asset
    // avviene dopo, seguito automaticamente da OkHttp. In piu' c'e' sempre l'host del manifest.
    private val FIXED_HOSTS = setOf("brouter.de", "build.protomaps.com", "github.com")

    /** Indirizzo, chiave e host ammessi del catalogo in uso, calcolati una volta per ogni cambio di catalogo. */
    private class Active(val manifestUrl: String, val publicKey: String) {
        // Host di un manifest alternativo servito in chiaro (http, server locale in debug): l'unico
        // per cui RegionManifest accetta URL non https. null col manifest pubblicato.
        val cleartextHost: String? = URI(manifestUrl).takeIf { it.scheme == "http" }?.host
        val allowedHosts: Set<String> = FIXED_HOSTS + URI(manifestUrl).host
    }

    // BuildConfig.MANIFEST_URL_OVERRIDE e' vuoto nelle build di release (vedi build.gradle.kts) e, in debug,
    // vale piu' del catalogo scelto nelle Impostazioni.
    private fun active(custom: CustomCatalog?) = Active(
        manifestUrl = BuildConfig.MANIFEST_URL_OVERRIDE.ifEmpty { custom?.manifestUrl ?: PUBLISHED_MANIFEST_URL },
        publicKey = custom?.publicKey ?: PUBLISHED_PUBLIC_KEY,
    )

    // Catalogo scelto nelle Impostazioni, passato una volta all'avvio del processo da CatalogSettings.applySaved:
    // cambiarlo riavvia l'app, cosi' nessun componente resta con il catalogo precedente.
    @Volatile
    private var current: Active = active(null)

    internal fun useCatalog(catalog: CustomCatalog?) {
        current = active(catalog)
    }

    val MANIFEST_URL: String get() = current.manifestUrl

    // Chiave delle firme di manifest.json, transit.json e address-grid.json (non di app-status.json).
    val MANIFEST_PUBLIC_KEY: String get() = current.publicKey

    val CLEARTEXT_MANIFEST_HOST: String? get() = current.cleartextHost

    val ALLOWED_MANIFEST_HOSTS: Set<String> get() = current.allowedHosts

    const val PERIODIC_SYNC_WORK_NAME = "region-manifest-sync"
    const val DOWNLOAD_WORK_NAME_PREFIX = "region-download-"

    // Tag dei download delle regioni; con "$DOWNLOAD_TAG:<regionId>" l'elenco sa quali sono in corso.
    const val DOWNLOAD_TAG = "region-download"
    const val GUIDES_SYNC_WORK_NAME = "guides-sync"

    // Asset di una release GitHub fissa ("app-status", sovrascritta ad ogni pubblicazione APK -
    // vedi publish-apk.yml), non un file su GitHub Pages: appVersion/aiModel cambiano solo quando
    // l'app viene rilasciata, mai insieme all'aggiornamento settimanale dei pacchetti regionali
    // (manifest.json sopra) — tenerli nello stesso file avrebbe legato due cicli di pubblicazione
    // indipendenti tra loro.
    const val APP_STATUS_URL = "https://github.com/miracle091/pocket-travel/releases/download/app-status/app-status.json"
    const val APP_UPDATE_CHECK_WORK_NAME = "app-update-check"
}
