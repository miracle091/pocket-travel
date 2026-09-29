package com.pockettravel.core.sync

import com.pockettravel.core.data.WorldMapStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

class ManifestClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val worldMapStore: WorldMapStore,
    private val appCompatibility: AppCompatibility,
) {
    suspend fun fetchManifest(): RegionManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(SyncConfig.MANIFEST_URL).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Errori temporanei del server: IOException, cosi' i worker riprovano.
                if (response.code >= 500 || response.code == 408 || response.code == 429) {
                    throw IOException("Manifest fetch failed: HTTP ${response.code}")
                }
                error("Manifest fetch failed: HTTP ${response.code}")
            }
            val body = response.body.string().ifEmpty { error("Empty manifest response") }
            // Una regione non valida si scarta: senza, una sola voce rotta fermerebbe elenco e
            // aggiornamenti di tutte le altre.
            val parsed = json.decodeFromString(RegionManifest.serializer(), body)
            parsed.copy(regions = parsed.regions.filter { entry -> runCatching { entry.validate() }.isSuccess }).also { manifest ->
                manifest.guides.validate()
                manifest.guidesEn?.validate()
                manifest.worldMap?.validate()
                manifest.addressGrid?.validate()
                manifest.transit?.validate()
                // Ad ogni sync riuscita: feature/map legge solo WorldMapStore, non dipende da core/sync.
                worldMapStore.save(manifest.worldMap?.url, manifest.worldMap?.maxZoom ?: worldMapStore.worldMapMaxZoom())
                appCompatibility.update(manifest.minAppVersionCode)
            }.forLanguage()
        }
    }
}
