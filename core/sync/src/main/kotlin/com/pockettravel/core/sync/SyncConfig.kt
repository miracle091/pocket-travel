package com.pockettravel.core.sync

import java.net.URI

// Hosting statico open (GitHub Pages), mai un backend dell'app — vedi sezione "Dati &
// sincronizzazione" della specifica tecnica. MANIFEST_URL resta un segnaposto finche' il repo
// non e' davvero pubblicato su GitHub Pages (nessun remote configurato al momento in cui
// questo valore e' stato scritto) — va corretto con l'host reale prima del rilascio.
object SyncConfig {
    const val MANIFEST_URL = "https://miracle091.github.io/pocket-travel/manifest.json"
    const val PERIODIC_SYNC_WORK_NAME = "region-manifest-sync"
    const val DOWNLOAD_WORK_NAME_PREFIX = "region-download-"

    // brouter.de: segmenti di routing .rd5 referenziati direttamente, mai ri-ospitati (build
    // settimanale gia' pronta pubblicata dal progetto BRouter). build.protomaps.com:
    // mapSource.sourceUrl (MapExtractionSource) punta alla build giornaliera whole-planet da
    // cui il device estrae solo le tile del bounding box della regione — vedi PmtilesExtractor.
    val ALLOWED_MANIFEST_HOSTS: Set<String> = setOf(
        URI(MANIFEST_URL).host,
        "brouter.de",
        "build.protomaps.com",
    )
}
