package com.pockettravel.core.sync

import android.os.SystemClock
import android.util.Log
import com.pockettravel.core.data.WorldMapStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val signatureVerifier: ManifestSignatureVerifier,
) {
    private val recent = RecentFetch<RegionManifest>(SystemClock::elapsedRealtime)

    /** Il manifest scaricato adesso, sempre: per i controlli di aggiornamento e prima di scaricare un pacchetto. */
    suspend fun fetchManifest(): RegionManifest = recent.get(maxAgeMillis = 0) { download() }.forLanguage()

    /**
     * Il manifest scaricato negli ultimi [RECENT_MANIFEST_MILLIS] se c'e' (lo stesso tempo di Cache-Control del sito), altrimenti
     * uno nuovo: per chi lo legge solo per mostrarlo (elenco, hub, licenze). Pesa 2,5 MB e va scaricato, verificato e letto per
     * intero: all'avvio lo chiedevano insieme l'elenco delle regioni e l'hub, poi ogni apertura di un hub.
     */
    suspend fun recentManifest(): RegionManifest = recent.get(RECENT_MANIFEST_MILLIS) { download() }.forLanguage()

    private suspend fun download(): RegionManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(SyncConfig.MANIFEST_URL).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Errori temporanei del server: IOException, cosi' i worker riprovano.
                if (response.code >= 500 || response.code == 408 || response.code == 429) {
                    throw IOException("Manifest fetch failed: HTTP ${response.code}")
                }
                error("Manifest fetch failed: HTTP ${response.code}")
            }
            val bodyBytes = response.body.bytes()
            if (bodyBytes.isEmpty()) error("Empty manifest response")
            // Prima di interpretarlo: un manifest con la firma sbagliata e' un fetch fallito (resta la cache).
            signatureVerifier.verify(SyncConfig.MANIFEST_URL, bodyBytes)
            val body = bodyBytes.decodeToString()
            val parsed = json.decodeFromString(RegionManifest.serializer(), body)
            sanitizeManifest(parsed) { what, error -> Log.w(TAG, "Voce del manifest scartata: $what", error) }.also { manifest ->
                // Ad ogni sync riuscita: feature/map legge solo WorldMapStore, non dipende da core/sync.
                worldMapStore.save(manifest.worldMap?.url, manifest.worldMap?.maxZoom ?: worldMapStore.worldMapMaxZoom())
                appCompatibility.update(manifest.minAppVersionCode)
            }
        }
    }

    private companion object {
        private val TAG = ManifestClient::class.java.simpleName
        const val RECENT_MANIFEST_MILLIS = 10 * 60 * 1_000L
    }
}

/**
 * L'ultimo valore letto da [get] e quando ([now], in millisecondi): una richiesta lo riusa se ha meno di `maxAgeMillis`, altrimenti lo
 * rilegge con `load`. Le richieste sono una alla volta: chi arriva mentre un'altra sta scaricando aspetta e trova il valore nuovo.
 * Un errore di `load` non cambia il valore.
 */
internal class RecentFetch<T : Any>(private val now: () -> Long) {
    private val lock = Mutex()
    private var value: T? = null
    private var readAt = 0L

    suspend fun get(maxAgeMillis: Long, load: suspend () -> T): T = lock.withLock {
        value?.takeIf { now() - readAt < maxAgeMillis }?.let { return it }
        load().also {
            value = it
            readAt = now()
        }
    }
}

/**
 * Scarta le voci non valide invece di far fallire tutto il manifest: senza, una sola voce rotta fermerebbe
 * elenco e aggiornamenti di tutte le altre. Una regione non valida si toglie dall'elenco; guidesEn, worldMap,
 * addressGrid e transit (facoltativi) diventano null. Ogni scarto e' segnalato a [onDropped] (voce, motivo).
 * Le guide italiane sono obbligatorie: se non valide il manifest fallisce, e resta la cache.
 */
internal fun sanitizeManifest(manifest: RegionManifest, onDropped: (String, Throwable) -> Unit): RegionManifest {
    manifest.guides.validate()
    fun <T> optional(name: String, entry: T?, validate: (T) -> Unit): T? {
        if (entry == null) return null
        val failure = runCatching { validate(entry) }.exceptionOrNull() ?: return entry
        onDropped(name, failure)
        return null
    }
    return manifest.copy(
        regions = manifest.regions.filter { entry ->
            val failure = runCatching { entry.validate() }.exceptionOrNull()
            failure?.let { onDropped("regione ${entry.regionId}", it) }
            failure == null
        },
        guidesEn = optional("guidesEn", manifest.guidesEn) { it.validate() },
        worldMap = optional("worldMap", manifest.worldMap) { it.validate() },
        addressGrid = optional("addressGrid", manifest.addressGrid) { it.validate() },
        transit = optional("transit", manifest.transit) { it.validate() },
    )
}
