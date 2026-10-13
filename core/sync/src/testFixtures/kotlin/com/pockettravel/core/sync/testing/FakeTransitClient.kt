package com.pockettravel.core.sync.testing

import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.TransitIndex
import com.pockettravel.core.sync.TransitManifestEntry

/** [TransitClient] in memoria: restituisce [index] (da impostare prima) o lancia [failure]; le voci richieste restano in [fetched]. */
class FakeTransitClient(var index: TransitIndex? = null) : TransitClient {
    var failure: Throwable? = null
    val fetched = mutableListOf<TransitManifestEntry>()

    override suspend fun fetchIndex(entry: TransitManifestEntry): TransitIndex {
        fetched += entry
        failure?.let { throw it }
        return checkNotNull(index) { "FakeTransitClient.index non impostato" }
    }
}
