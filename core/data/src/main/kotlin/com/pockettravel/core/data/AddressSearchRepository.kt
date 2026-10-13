package com.pockettravel.core.data

private const val DEFAULT_ADDRESS_LIMIT = 20

/**
 * Ricerca degli indirizzi ("Via Roma 12a", "12 Rue de Rivoli") nel database di ricerca di ogni regione (pacchetto
 * ADDRESSES). Regioni senza database di ricerca (dati installati prima) non danno risultati.
 * Implementazione: [SqliteAddressSearchRepository].
 */
interface AddressSearchRepository {
    /** Al piu' [limit] indirizzi per [text] nelle [regionIds], nell'ordine delle regioni; su IO. */
    suspend fun search(regionIds: List<String>, text: String, limit: Int = DEFAULT_ADDRESS_LIMIT): List<AddressResult>
}
