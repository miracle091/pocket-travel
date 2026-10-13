package com.pockettravel.core.data.testing

import com.pockettravel.core.data.AddressResult
import com.pockettravel.core.data.AddressSearchRepository

/**
 * [AddressSearchRepository] in memoria: cerca in [addresses] quelli delle regioni richieste il cui nome contiene il
 * testo (senza distinguere maiuscole); non fa l'analisi della query della ricerca vera. Le ricerche restano in [searches].
 */
class FakeAddressSearchRepository : AddressSearchRepository {
    val addresses = mutableListOf<AddressResult>()
    val searches = mutableListOf<Triple<List<String>, String, Int>>()

    override suspend fun search(regionIds: List<String>, text: String, limit: Int): List<AddressResult> {
        searches += Triple(regionIds, text, limit)
        val needle = text.trim()
        return regionIds.flatMap { regionId ->
            addresses.filter { it.regionId == regionId && it.displayName.contains(needle, ignoreCase = true) }
        }.take(limit)
    }
}
