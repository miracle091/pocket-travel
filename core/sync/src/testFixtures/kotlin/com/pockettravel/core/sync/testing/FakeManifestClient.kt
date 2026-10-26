package com.pockettravel.core.sync.testing

import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifest
import kotlinx.coroutines.CompletableDeferred

/**
 * [ManifestClient] in memoria: restituisce [manifest] (da impostare prima) o lancia [failure], e conta le richieste in
 * [fetchCount] e [recentCount]. Non filtra per lingua e non tiene cache. [saved] e' la copia senza rete; con [gate] le
 * richieste al sito aspettano che il test lo completi.
 */
class FakeManifestClient(var manifest: RegionManifest? = null) : ManifestClient {
    var failure: Throwable? = null
    var saved: RegionManifest? = null
    var gate: CompletableDeferred<Unit>? = null
    var fetchCount = 0
        private set
    var recentCount = 0
        private set

    override suspend fun fetchManifest(): RegionManifest {
        fetchCount++
        gate?.await()
        return current()
    }

    override suspend fun recentManifest(): RegionManifest {
        recentCount++
        gate?.await()
        return current()
    }

    override suspend fun savedManifest(): RegionManifest? = saved

    private fun current(): RegionManifest {
        failure?.let { throw it }
        return checkNotNull(manifest) { "FakeManifestClient.manifest non impostato" }
    }
}
