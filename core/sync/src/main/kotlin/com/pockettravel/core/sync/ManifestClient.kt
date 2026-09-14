package com.pockettravel.core.sync

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class ManifestClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchManifest(): RegionManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(SyncConfig.MANIFEST_URL).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Manifest fetch failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: error("Empty manifest response")
            json.decodeFromString(RegionManifest.serializer(), body).also { it.regions.forEach(RegionManifestEntry::validate) }
        }
    }
}
