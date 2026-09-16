package com.pockettravel.core.sync

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class AppStatusClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchAppStatus(): AppStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(SyncConfig.APP_STATUS_URL).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("App status fetch failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: error("Empty app status response")
            json.decodeFromString(AppStatus.serializer(), body).also { status ->
                status.appVersion?.validate()
                status.aiModel?.validate()
            }
        }
    }
}
