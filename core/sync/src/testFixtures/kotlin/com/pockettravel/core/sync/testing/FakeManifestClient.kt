package com.pockettravel.core.sync.testing

import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifest

/**
 * [ManifestClient] in memoria: restituisce [manifest] (da impostare prima) o lancia [failure], e conta le richieste in
 * [fetchCount] e [recentCount]. Non filtra per lingua e non tiene cache.
 */
class FakeManifestClient(var manifest: RegionManifest? = null) : ManifestClient {
    var failure: Throwable? = null
    var fetchCount = 0
        private set
    var recentCount = 0
        private set

    override suspend fun fetchManifest(): RegionManifest {
        fetchCount++
        return current()
    }

    override suspend fun recentManifest(): RegionManifest {
        recentCount++
        return current()
    }

    private fun current(): RegionManifest {
        failure?.let { throw it }
        return checkNotNull(manifest) { "FakeManifestClient.manifest non impostato" }
    }
}
