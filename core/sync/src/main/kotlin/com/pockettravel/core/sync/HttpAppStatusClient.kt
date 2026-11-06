package com.pockettravel.core.sync

import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

class HttpAppStatusClient @Inject constructor(
    private val json: Json,
    private val signedFileCache: SignedFileCache,
    preferences: PreferencesFactory,
) : AppStatusClient {
    private val prefs = preferences.open(PREFS_NAME)

    override suspend fun fetchAppStatus(): AppStatus = signedFileCache.fetch(
        SyncConfig.appStatusUrl,
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
    }.also { prefs.edit { putLong(KEY_LAST_CHECKED, System.currentTimeMillis()) } }

    override fun lastCheckedAtMillis(): Long? = prefs.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0L }

    private companion object {
        const val PREFS_NAME = "app_status_check"
        const val KEY_LAST_CHECKED = "last_checked_at"
    }
}
