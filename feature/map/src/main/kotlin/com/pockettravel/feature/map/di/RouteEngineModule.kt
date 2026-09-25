package com.pockettravel.feature.map.di

import android.content.Context
import com.pockettravel.core.data.RegionsDir
import com.pockettravel.feature.map.BRouterRouteEngine
import com.pockettravel.feature.map.OfflineTileSource
import com.pockettravel.feature.map.PmtilesTileSource
import com.pockettravel.feature.map.RouteEngineFactory
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File

@Module
@InstallIn(SingletonComponent::class)
object RouteEngineModule {

    // "routing" deve combaciare con RegionRoutingGraphInstaller.ROUTING_DIR_NAME (core/sync): la
    // cartella in cui il pacchetto regionale scaricato mette i file .rd5.
    private const val ROUTING_DIR_NAME = "routing"
    private const val PROFILE_ASSET_DIR = "brouter-profile"

    @Provides
    fun provideRouteEngineFactory(
        @RegionsDir regionsDir: File,
        @ApplicationContext context: Context,
        usageModePreferences: UsageModePreferences,
    ): RouteEngineFactory {
        val profileDir = File(context.filesDir, PROFILE_ASSET_DIR)
        copyProfileAssetsIfMissing(context, profileDir)

        return RouteEngineFactory { regionId ->
            BRouterRouteEngine(
                segmentDir = File(regionsDir, "$regionId/$ROUTING_DIR_NAME"),
                profileDir = profileDir,
                // Il profilo della modalita' d'uso scelta, letto a ogni motore creato.
                profileName = usageModePreferences.mode.value?.routingProfile ?: UsageMode.DEFAULT_ROUTING_PROFILE,
            )
        }
    }

    @Provides
    fun provideOfflineTileSource(@RegionsDir regionsDir: File): OfflineTileSource =
        PmtilesTileSource(regionsDir)

    // I profili .brf + lookups.dat sono logica dell'app (gli stessi per tutte le regioni), non dati
    // per-regione — bundlati come asset e copiati su file reali: BRouter legge da file system, non da
    // uno stream di asset compresso. File per file, cosi' un'app gia' installata riceve anche i
    // profili aggiunti dopo (prima c'era solo trekking.brf).
    private fun copyProfileAssetsIfMissing(context: Context, profileDir: File) {
        profileDir.mkdirs()
        for (assetName in UsageMode.ROUTING_PROFILES.map { "$it.brf" } + "lookups.dat") {
            val target = File(profileDir, assetName)
            if (target.exists()) continue
            context.assets.open("$PROFILE_ASSET_DIR/$assetName").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
