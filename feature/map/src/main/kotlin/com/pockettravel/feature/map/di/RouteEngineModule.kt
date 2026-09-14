package com.pockettravel.feature.map.di

import android.content.Context
import com.pockettravel.core.data.RegionsDir
import com.pockettravel.feature.map.BRouterRouteEngine
import com.pockettravel.feature.map.OfflineTileSource
import com.pockettravel.feature.map.PmtilesTileSource
import com.pockettravel.feature.map.RouteEngineFactory
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
    private const val PROFILE_NAME = "trekking"

    @Provides
    fun provideRouteEngineFactory(
        @RegionsDir regionsDir: File,
        @ApplicationContext context: Context,
    ): RouteEngineFactory {
        val profileDir = File(context.filesDir, PROFILE_ASSET_DIR)
        copyProfileAssetsIfMissing(context, profileDir)

        return RouteEngineFactory { regionId ->
            BRouterRouteEngine(
                segmentDir = File(regionsDir, "$regionId/$ROUTING_DIR_NAME"),
                profileDir = profileDir,
                profileName = PROFILE_NAME,
            )
        }
    }

    @Provides
    fun provideOfflineTileSource(@RegionsDir regionsDir: File): OfflineTileSource =
        PmtilesTileSource(regionsDir)

    // Il profilo .brf + lookups.dat sono logica dell'app (lo stesso profilo per tutte le
    // regioni), non dati per-regione — bundlati come asset e copiati una volta su un file reale:
    // BRouter legge da file system, non da uno stream di asset compresso.
    private fun copyProfileAssetsIfMissing(context: Context, profileDir: File) {
        if (profileDir.exists()) return
        profileDir.mkdirs()
        for (assetName in listOf("$PROFILE_NAME.brf", "lookups.dat")) {
            context.assets.open("$PROFILE_ASSET_DIR/$assetName").use { input ->
                File(profileDir, assetName).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
