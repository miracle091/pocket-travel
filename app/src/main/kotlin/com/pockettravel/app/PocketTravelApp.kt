package com.pockettravel.app

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pockettravel.app.settings.AppLanguage
import com.pockettravel.core.data.AppInitializer
import com.pockettravel.core.sync.CatalogSettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PocketTravelApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    // Lavoro di avvio dei moduli (controlli periodici, pulizie, recupero dei pacchetti): ognuno
    // registra il suo AppInitializer nel proprio modulo Hilt.
    @Inject lateinit var initializers: Set<@JvmSuppressWildcards AppInitializer>

    // Come in MainActivity: testi delle notifiche nella lingua scelta anche fino ad Android 12.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    // Catalogo scelto nelle Impostazioni, se diverso da quello del progetto.
    @Inject lateinit var catalogSettings: CatalogSettings

    override fun onCreate() {
        super.onCreate()
        // Prima di ogni worker e di ogni AppInitializer, che leggono il catalogo da SyncConfig.
        catalogSettings.applySaved()
        initializers.forEach { it.onAppCreate() }
    }
}
