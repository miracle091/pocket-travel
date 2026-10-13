package com.pockettravel.core.sync

import com.pockettravel.core.data.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

class HttpAppStatusClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val signatureVerifier: ManifestSignatureVerifier,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AppStatusClient {
    override suspend fun fetchAppStatus(): AppStatus = withContext(ioDispatcher) {
        val request = Request.Builder().url(SyncConfig.APP_STATUS_URL).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Errori temporanei del server: IOException, come per il manifest, cosi' i worker riprovano.
                if (isRetryableHttpStatus(response.code)) {
                    throw IOException("App status fetch failed: HTTP ${response.code}")
                }
                error("App status fetch failed: HTTP ${response.code}")
            }
            val bodyBytes = response.body.bytes()
            if (bodyBytes.isEmpty()) error("Empty app status response")
            signatureVerifier.verify(SyncConfig.APP_STATUS_URL, bodyBytes)
            val body = bodyBytes.decodeToString()
            json.decodeFromString(AppStatus.serializer(), body).also { status ->
                status.appVersion?.validate()
                status.aiModels.forEach { it.validate() }
            }
        }
    }
}
