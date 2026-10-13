package com.pockettravel.core.ui

import java.net.IDN
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
    val host = uri.host ?: uri.authority?.let(::internationalHost)
    return (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        !host.isNullOrEmpty()
}

// URI non riconosce come host un dominio con lettere non ASCII ("münchen.de"): lo si ricava dall'authority, senza
// credenziali e porta, e lo si valida come dominio internazionale (IDN), come fanno i browser.
private fun internationalHost(authority: String): String? {
    val host = authority.substringAfterLast('@').substringBefore(':')
    return try {
        IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
    } catch (_: IllegalArgumentException) {
        null
    }
}

// Indirizzo pronto da aprire, oppure null. Senza schema (in OSM e Wikidata il sito e' spesso
// "www.esempio.it") si assume https; qualunque altro schema (o un ':' prima del percorso) si scarta.
fun safeWebUrl(website: String): String? {
    val trimmed = website.trim()
    val hasScheme = ':' in trimmed.takeWhile { it != '/' && it != '?' && it != '#' }
    val candidate = if (hasScheme) trimmed else "https://$trimmed"
    return candidate.takeIf(::isSafeWebUrl)
}
