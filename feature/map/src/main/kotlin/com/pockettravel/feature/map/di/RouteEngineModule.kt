package com.pockettravel.feature.map.di

import android.content.Context
import android.net.ConnectivityManager
import com.pockettravel.core.data.Rd5Merger
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.RegionsDir
import com.pockettravel.core.data.WorldMapStore
import com.pockettravel.feature.map.AndroidConnectivityChecker
import com.pockettravel.feature.map.AndroidGpsLocationSource
import com.pockettravel.feature.map.GpsLocationSource
import com.pockettravel.feature.map.BRouterRouteEngine
import com.pockettravel.feature.map.ConnectivityChecker
import com.pockettravel.feature.map.ConnectivityObserver
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Module
@InstallIn(SingletonComponent::class)
object RouteEngineModule {

    // "routing" deve combaciare con RegionRoutingGraphInstaller.ROUTING_DIR_NAME (core/sync): la
    // cartella in cui il pacchetto regionale scaricato mette i file .rd5.
    private const val ROUTING_DIR_NAME = "routing"
    // Cache dei segmenti uniti di piu' regioni (Rd5Merger): si rifa' quando serve, il sistema puo' svuotarla.
    private const val MERGED_DIR_NAME = "routing-merged"
    private const val PROFILE_ASSET_DIR = "brouter-profile"

    // Tempo massimo di un calcolo: Milano-Roma in auto (575 km) ha richiesto 45-48 s sull'emulatore,
    // un telefono e' piu' lento. Oltre, RouteResult.TimedOut ("destinazione troppo lontana").
    private const val ROUTE_TIMEOUT_MILLIS = 180_000L

    // Singleton: i profili si sincronizzano una volta sola, non a ogni ViewModel che riceve la factory.
    @Provides
    @Singleton
    fun provideRouteEngineFactory(
        @RegionsDir regionsDir: File,
        @ApplicationContext context: Context,
        usageModePreferences: UsageModePreferences,
    ): RouteEngineFactory {
        val profileDir = File(context.filesDir, PROFILE_ASSET_DIR)
        // Copia dei profili al primo calcolo, su IO (legge e scrive file): se fallisce si riprova al successivo.
        val profilesSynced = lazy { syncProfileAssets(context, profileDir) }

        val mergedDir = File(context.cacheDir, MERGED_DIR_NAME)

        return RouteEngineFactory { regionIds ->
            withContext(Dispatchers.IO) { profilesSynced.value }
            BRouterRouteEngine(
                segmentDir = segmentDirFor(regionIds, regionsDir, mergedDir),
                profileDir = profileDir,
                // Il profilo della modalita' d'uso scelta, letto a ogni motore creato.
                profileName = usageModePreferences.mode.value?.routingProfile ?: UsageMode.DEFAULT_ROUTING_PROFILE,
                maxRunningTimeMillis = ROUTE_TIMEOUT_MILLIS,
            )
        }
    }

    // Una regione: la sua cartella, nessun costo in piu'. Piu' regioni: i segmenti uniti; se non
    // si riesce a unirli (file rovinato, disco pieno) si naviga con la sola prima regione.
    private suspend fun segmentDirFor(regionIds: List<String>, regionsDir: File, mergedDir: File): File {
        val dirs = regionIds.map { File(regionsDir, "$it/$ROUTING_DIR_NAME") }
        if (dirs.size == 1) return dirs.single()
        return withContext(Dispatchers.IO) {
            try {
                Rd5Merger.mergedDirectory(mergedDir, dirs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // IOException, ma anche errori di un file rovinato (dimensioni assurde nell'indice).
                dirs.first()
            }
        }
    }

    @Provides
    fun provideOfflineTileSource(
        regionStorage: RegionStorage,
        worldMapStore: WorldMapStore,
        connectivityChecker: ConnectivityChecker,
    ): OfflineTileSource = PmtilesTileSource(regionStorage, worldMapStore, connectivityChecker)

    @Provides
    fun provideGpsLocationSource(source: AndroidGpsLocationSource): GpsLocationSource = source

    @Provides
    fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    @Provides
    fun provideConnectivityChecker(connectivityManager: ConnectivityManager): ConnectivityChecker =
        AndroidConnectivityChecker(connectivityManager)

    @Provides
    fun provideConnectivityObserver(connectivityManager: ConnectivityManager): ConnectivityObserver =
        ConnectivityObserver(connectivityManager)

    // I profili .brf + lookups.dat sono logica dell'app (gli stessi per tutte le regioni), non dati
    // per-regione — bundlati come asset e copiati su file reali: BRouter legge da file system, non da
    // uno stream di asset compresso. File per file, confrontando i byte: un'app gia' installata riceve
    // i profili aggiunti o modificati da un aggiornamento. La scrittura passa da un file temporaneo
    // rinominato sul definitivo, cosi' una copia interrotta non lascia un file troncato.
    private fun syncProfileAssets(context: Context, profileDir: File) {
        profileDir.mkdirs()
        for (assetName in UsageMode.ROUTING_PROFILES.map { "$it.brf" } + "lookups.dat") {
            val target = File(profileDir, assetName)
            val bytes = context.assets.open("$PROFILE_ASSET_DIR/$assetName").use { it.readBytes() }
            if (target.exists() && target.readBytes().contentEquals(bytes)) continue
            val temp = File(profileDir, "$assetName.tmp")
            temp.writeBytes(bytes)
            check(temp.renameTo(target)) { "impossibile sostituire ${target.path}" }
        }
    }
}
