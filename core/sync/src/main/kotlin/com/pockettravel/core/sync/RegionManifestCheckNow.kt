package com.pockettravel.core.sync

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pockettravel.core.data.PreferencesFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Un controllo del manifest subito, appena c'e' una rete, invece di aspettare quello periodico ([RegionSyncScheduler]): la
 * notifica di un aggiornamento gia' noto (i punti di interesse tolti dalla migrazione del database) arriva in pochi secondi.
 * Una sola richiesta alla volta, e al massimo una al giorno: chiamato a ogni avvio, la notifica non torna a ogni apertura
 * dell'app finche' l'utente non riscarica. Come quello periodico, non scarica nulla.
 */
class RegionManifestCheckNow internal constructor(
    preferences: PreferencesFactory,
    private val enqueueWork: () -> Unit,
) {
    @Inject constructor(@ApplicationContext context: Context, preferences: PreferencesFactory) : this(
        preferences,
        enqueueWork = { enqueueManifestCheck(context) },
    )

    private val prefs = preferences.open(PREFS)

    fun enqueue(now: Long = System.currentTimeMillis()) {
        if (now - prefs.getLong(KEY_LAST, 0L) < MIN_INTERVAL_MILLIS) return
        prefs.edit { putLong(KEY_LAST, now) }
        enqueueWork()
    }

    private companion object {
        const val PREFS = "region_manifest_check_now"
        const val KEY_LAST = "last_enqueued_at"
        val MIN_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1)
    }
}

private fun enqueueManifestCheck(context: Context) {
    val request = OneTimeWorkRequestBuilder<RegionManifestSyncWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(SyncConfig.ONE_TIME_SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, request)
}
