package com.pockettravel.app.navigation

import android.content.Context
import android.net.ConnectivityManager
import androidx.activity.ComponentActivity
import com.pockettravel.core.data.AddressSearchRepository
import com.pockettravel.core.data.CarRouteCalculator
import com.pockettravel.core.data.ExchangeRateTable
import com.pockettravel.core.data.ExchangeRates
import com.pockettravel.core.data.FavoriteStopsRepository
import com.pockettravel.core.data.LastKnownPosition
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.TransitRepository
import com.pockettravel.core.data.WeatherRepository
import com.pockettravel.core.data.WeatherResult
import com.pockettravel.core.data.advice.TravelAdviceUpdate
import com.pockettravel.core.data.advice.TravelAdviceUpdates
import com.pockettravel.core.data.alerts.AlertsRepository
import com.pockettravel.core.data.alerts.RegionAlerts
import com.pockettravel.core.data.di.RepositoryModule
import com.pockettravel.core.data.testing.FakeAddressSearchRepository
import com.pockettravel.core.data.testing.FakeFavoriteStopsRepository
import com.pockettravel.core.data.testing.FakeLastKnownPosition
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.FakeTransitRepository
import com.pockettravel.core.sync.AddressGridClient
import com.pockettravel.core.sync.AlertNotificationSink
import com.pockettravel.core.sync.AlertNotificationsScheduler
import com.pockettravel.core.sync.AlertNotifier
import com.pockettravel.core.sync.ApkInspector
import com.pockettravel.core.sync.AppStatusClient
import com.pockettravel.core.sync.AppUpdateDownloads
import com.pockettravel.core.sync.CatalogPreferences
import com.pockettravel.core.sync.DefaultCatalogPreferences
import com.pockettravel.core.sync.DefaultPublishedAtStore
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.OnlineModelsClient
import com.pockettravel.core.sync.PackageManagerApkInspector
import com.pockettravel.core.sync.PublishedAtStore
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.WorkManagerAlertNotificationsScheduler
import com.pockettravel.core.sync.di.SyncBindingsModule
import com.pockettravel.core.sync.di.SyncModule
import com.pockettravel.core.sync.testing.FakeAddressGridClient
import com.pockettravel.core.sync.testing.FakeAppStatusClient
import com.pockettravel.core.sync.testing.FakeAppUpdateDownloads
import com.pockettravel.core.sync.testing.FakeManifestClient
import com.pockettravel.core.sync.testing.FakeRegionSyncScheduler
import com.pockettravel.core.sync.testing.FakeTransitClient
import com.pockettravel.feature.ai.AiAvailability
import com.pockettravel.feature.ai.AiModelUpdates
import com.pockettravel.feature.ai.BiometricHistoryAvailability
import com.pockettravel.feature.ai.HistoryAvailability
import com.pockettravel.feature.ai.LlmModelManager
import com.pockettravel.feature.ai.OnDeviceLlmEngine
import com.pockettravel.feature.ai.OnDeviceVoiceInput
import com.pockettravel.feature.ai.VoiceInput
import com.pockettravel.feature.ai.di.AiBindingsModule
import com.pockettravel.feature.ai.testing.FakeAiAvailability
import com.pockettravel.feature.ai.testing.FakeAiModelUpdates
import com.pockettravel.feature.ai.testing.FakeLlmModelManager
import com.pockettravel.feature.ai.testing.FakeOnDeviceLlmEngine
import com.pockettravel.feature.map.AndroidConnectivityChecker
import com.pockettravel.feature.map.BRouterCarRouteCalculator
import com.pockettravel.feature.map.ConnectivityChecker
import com.pockettravel.feature.map.ConnectivityObserver
import com.pockettravel.feature.map.DepartureReminderScheduler
import com.pockettravel.feature.map.GpsLocationSource
import com.pockettravel.feature.map.GuidanceServiceStarter
import com.pockettravel.feature.map.OfflineTileSource
import com.pockettravel.feature.map.RouteEngineFactory
import com.pockettravel.feature.map.di.RouteEngineModule
import com.pockettravel.feature.map.testing.FakeDepartureReminderScheduler
import com.pockettravel.feature.map.testing.FakeGpsLocationSource
import com.pockettravel.feature.map.testing.FakeGuidanceServiceStarter
import com.pockettravel.feature.map.testing.FakeOfflineTileSource
import com.pockettravel.feature.map.testing.ScriptedRouteEngineFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Singleton

/** Activity di Hilt per i test di navigazione: sta solo nei test, il manifest dell'app non la conosce (la registra il test). */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()

/** Richieste HTTP che un test ha tentato: devono restare vuote, nei test di navigazione non esce niente dalla JVM. */
object NetworkGuard {
    val attempted = CopyOnWriteArrayList<String>()
}

// Catalogo, mezzi pubblici, civici, stato dell'app, modelli online e tassi di cambio: fake in memoria. Il client HTTP condiviso rifiuta ogni
// richiesta e la annota in NetworkGuard, cosi' un percorso non previsto fa fallire il test invece di uscire in rete.
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SyncModule::class, SyncBindingsModule::class])
object TestSyncModule {
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            NetworkGuard.attempted += chain.request().url.toString()
            throw IOException("Rete non ammessa nei test di navigazione: ${chain.request().url}")
        }
        .build()

    @Provides
    @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun fakeManifestClient(): FakeManifestClient = FakeManifestClient().apply { failure = IOException("offline") }

    @Provides
    fun manifestClient(fake: FakeManifestClient): ManifestClient = fake

    @Provides
    @Singleton
    fun fakeRegionSyncScheduler(): FakeRegionSyncScheduler = FakeRegionSyncScheduler()

    @Provides
    fun regionSyncScheduler(fake: FakeRegionSyncScheduler): RegionSyncScheduler = fake

    @Provides
    fun transitClient(): TransitClient = FakeTransitClient().apply { failure = IOException("offline") }

    @Provides
    fun addressGridClient(): AddressGridClient = FakeAddressGridClient().apply { failure = IOException("offline") }

    @Provides
    fun appStatusClient(): AppStatusClient = FakeAppStatusClient().apply { failure = IOException("offline") }

    @Provides
    fun onlineModelsClient(): OnlineModelsClient = object : OnlineModelsClient {
        override suspend fun fetchOnlineModels(): ByteArray = throw IOException("offline")
    }

    @Provides
    fun exchangeRates(): ExchangeRates = object : ExchangeRates {
        override suspend fun latest(): ExchangeRateTable? = null
    }

    @Provides
    @Singleton
    fun appUpdateDownloads(): AppUpdateDownloads = FakeAppUpdateDownloads()

    @Provides
    fun catalogPreferences(impl: DefaultCatalogPreferences): CatalogPreferences = impl

    @Provides
    fun apkInspector(impl: PackageManagerApkInspector): ApkInspector = impl

    @Provides
    fun publishedAtStore(store: DefaultPublishedAtStore): PublishedAtStore = store

    @Provides
    fun alertNotificationSink(notifier: AlertNotifier): AlertNotificationSink = notifier

    @Provides
    fun alertNotificationsScheduler(scheduler: WorkManagerAlertNotificationsScheduler): AlertNotificationsScheduler = scheduler
}

// Regioni installate, mezzi pubblici e civici in memoria; meteo, allerte e avvisi di viaggio senza risposta, come offline.
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RepositoryModule::class])
object TestRepositoryModule {
    @Provides
    @Singleton
    fun fakeRegionRepository(): FakeRegionRepository = FakeRegionRepository()

    @Provides
    fun regionRepository(fake: FakeRegionRepository): RegionRepository = fake

    @Provides
    fun transitRepository(): TransitRepository = FakeTransitRepository()

    @Provides
    fun favoriteStopsRepository(): FavoriteStopsRepository = FakeFavoriteStopsRepository()

    @Provides
    fun addressSearchRepository(): AddressSearchRepository = FakeAddressSearchRepository()

    @Provides
    fun lastKnownPosition(): LastKnownPosition = FakeLastKnownPosition()

    @Provides
    fun weatherRepository(): WeatherRepository = object : WeatherRepository {
        override suspend fun weather(cacheKey: String, coordinates: suspend () -> Pair<Double, Double>?): WeatherResult? = null
        override suspend fun coordinatesOf(city: String, countryCode: String): Pair<Double, Double>? = null
    }

    @Provides
    fun alertsRepository(): AlertsRepository = object : AlertsRepository {
        override suspend fun alerts(regionId: String): RegionAlerts? = null
    }

    @Provides
    fun travelAdviceUpdates(): TravelAdviceUpdates = object : TravelAdviceUpdates {
        override suspend fun latest(slug: String): TravelAdviceUpdate? = null
    }
}

// Assistente IA: nessun modello e nessun motore, niente controlli di aggiornamento in rete.
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AiBindingsModule::class])
object TestAiModule {
    @Provides
    fun engine(): OnDeviceLlmEngine = FakeOnDeviceLlmEngine()

    @Provides
    fun availability(): AiAvailability = FakeAiAvailability()

    @Provides
    @Singleton
    fun modelManager(): LlmModelManager = FakeLlmModelManager()

    @Provides
    fun modelUpdates(): AiModelUpdates = FakeAiModelUpdates()

    @Provides
    fun voiceInput(impl: OnDeviceVoiceInput): VoiceInput = impl

    @Provides
    fun historyAvailability(impl: BiometricHistoryAvailability): HistoryAvailability = impl
}

// Motore di percorsi, GPS, servizi e mappa senza file ne' sistema. Il rilevatore della connessione non notifica mai: la
// scheda Mappa non conosce la sua sorgente e non disegna MapLibre, che e' nativo e su JVM non si carica.
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RouteEngineModule::class])
object TestMapModule {
    @Provides
    @Singleton
    fun routeEngineFactory(): RouteEngineFactory = ScriptedRouteEngineFactory()

    @Provides
    fun carRouteCalculator(factory: RouteEngineFactory): CarRouteCalculator = BRouterCarRouteCalculator(factory)

    @Provides
    fun offlineTileSource(): OfflineTileSource = FakeOfflineTileSource()

    @Provides
    @Singleton
    fun gpsLocationSource(): GpsLocationSource = FakeGpsLocationSource(permission = false)

    @Provides
    fun guidanceServiceStarter(): GuidanceServiceStarter = FakeGuidanceServiceStarter()

    @Provides
    fun departureReminderScheduler(): DepartureReminderScheduler = FakeDepartureReminderScheduler()

    @Provides
    fun connectivityManager(@ApplicationContext context: Context): ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    @Provides
    fun connectivityChecker(connectivityManager: ConnectivityManager): ConnectivityChecker =
        AndroidConnectivityChecker(connectivityManager)

    @Provides
    fun connectivityObserver(): ConnectivityObserver = object : ConnectivityObserver {
        override fun changes(): Flow<Boolean> = emptyFlow()
    }
}
