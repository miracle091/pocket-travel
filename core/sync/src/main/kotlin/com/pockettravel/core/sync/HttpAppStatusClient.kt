package com.pockettravel.core.sync

import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

class HttpAppStatusClient @Inject constructor(
    private val json: Json,
    private val signedFileCache: SignedFileCache,
) : AppStatusClient {
    override suspend fun fetchAppStatus(): AppStatus = signedFileCache.fetch(
        SyncConfig.APP_STATUS_URL,
        httpError = { code ->
            // Errori temporanei del server: IOException, come per `manifest.json`, cosi' i worker riprovano.
            if (isRetryableHttpStatus(code)) throw IOException("App status fetch failed: HTTP $code")
            error("App status fetch failed: HTTP $code")
        },
        emptyBody = { error("Empty app status response") },
    ) { bytes ->
        json.decodeFromString(AppStatus.serializer(), bytes.decodeToString()).also { status ->
            status.appVersion?.validate()
            status.aiModels.forEach { it.validate() }
        }
    }
}
