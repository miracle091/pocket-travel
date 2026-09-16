package com.pockettravel.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pockettravel.core.sync.AppUpdateCheckScheduler
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.ai.LlmModelUpdateCheckScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PocketTravelApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var regionSyncScheduler: RegionSyncScheduler
    @Inject lateinit var appUpdateCheckScheduler: AppUpdateCheckScheduler
    @Inject lateinit var llmModelUpdateCheckScheduler: LlmModelUpdateCheckScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        regionSyncScheduler.schedulePeriodicManifestCheck()
        appUpdateCheckScheduler.schedulePeriodicCheck()
        llmModelUpdateCheckScheduler.schedulePeriodicCheck()
    }
}
