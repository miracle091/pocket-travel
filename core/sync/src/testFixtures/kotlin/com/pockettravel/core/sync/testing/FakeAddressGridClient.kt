package com.pockettravel.core.sync.testing

import com.pockettravel.core.sync.AddressGridClient
import com.pockettravel.core.sync.AddressGridIndex
import com.pockettravel.core.sync.AddressGridManifestEntry

/** [AddressGridClient] in memoria: restituisce [index] (da impostare prima) o lancia [failure]; le voci richieste restano in [fetched]. */
class FakeAddressGridClient(var index: AddressGridIndex? = null) : AddressGridClient {
    var failure: Throwable? = null
    val fetched = mutableListOf<AddressGridManifestEntry>()

    override suspend fun fetchIndex(entry: AddressGridManifestEntry): AddressGridIndex {
        fetched += entry
        failure?.let { throw it }
        return checkNotNull(index) { "FakeAddressGridClient.index non impostato" }
    }
}
