package com.pockettravel.core.ui

import java.net.URI

// Gli indirizzi web arrivano da dati modificabili da chiunque (Wikidata, OSM) o dai database
// scaricati: prima di aprirli nel browser o in un'altra app si lasciano passare solo http/https
// con un host, scartando intent:, market:, content:, file:, javascript: e simili.
fun isSafeWebUrl(url: String): Boolean {
    val uri = try {
        URI(url.trim())
    } catch (_: Exception) {
        return false
    }
    return (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        !uri.host.isNullOrEmpty()
}

// Indirizzo pronto da aprire, oppure null. Senza schema (in OSM e Wikidata il sito e' spesso
// "www.esempio.it") si assume https; qualunque altro schema (o un ':' prima del percorso) si scarta.
fun safeWebUrl(website: String): String? {
    val trimmed = website.trim()
    val candidate = if ("://" in trimmed || ':' in trimmed) trimmed else "https://$trimmed"
    return candidate.takeIf(::isSafeWebUrl)
}
