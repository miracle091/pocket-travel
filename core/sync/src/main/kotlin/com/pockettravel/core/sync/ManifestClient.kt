package com.pockettravel.core.sync

/** Il `manifest.json` del catalogo. Implementazione: [HttpManifestClient]. */
interface ManifestClient {
    /** Il `manifest.json` scaricato adesso, sempre: per i controlli di aggiornamento e prima di scaricare un pacchetto. */
    suspend fun fetchManifest(): RegionManifest

    /**
     * Il `manifest.json` scaricato negli ultimi dieci minuti se c'e' (lo stesso tempo di Cache-Control del sito), altrimenti
     * uno nuovo: per chi lo legge solo per mostrarlo (elenco, hub, licenze). Pesa 2,5 MB e va scaricato, verificato e letto per
     * intero: all'avvio lo chiedono insieme l'elenco delle regioni e l'hub, poi ogni apertura di un hub.
     */
    suspend fun recentManifest(): RegionManifest
}
