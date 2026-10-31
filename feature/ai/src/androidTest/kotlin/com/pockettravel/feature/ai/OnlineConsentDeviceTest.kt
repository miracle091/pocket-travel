package com.pockettravel.feature.ai

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.CarRouteCalculator
import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.LastOpenedCityGuide
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.Note
import com.pockettravel.core.data.NoteRepository
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.WeatherRepository
import com.pockettravel.core.data.WeatherResult
import com.pockettravel.core.data.alerts.AlertsRepository
import com.pockettravel.core.data.alerts.RegionAlerts
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.PoiRow
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.db.VaccRecommendedEntity
import com.pockettravel.core.data.testing.FakeLastKnownPosition
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.FakeTransitRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.data.vaccination.VaccinationPreferences
import com.pockettravel.core.data.vaccination.VaccinationRepository
import com.pockettravel.core.sync.OnlineModelEntry
import com.pockettravel.core.sync.OnlineModelList
import com.pockettravel.core.sync.OnlineModelsClient
import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.util.Locale

/**
 * L'interruttore "Invia dati personali al servizio": la richiesta al servizio online contiene nota, POI vicini,
 * vaccinazioni e sezioni per nazionalita' solo con il consenso. Usa database Room in memoria con i dati di una
 * regione, la chiave del Keystore e un OkHttp che registra il corpo della richiesta senza uscire dal dispositivo.
 */
@RunWith(AndroidJUnit4::class)
class OnlineConsentDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val previousLocale = Locale.getDefault()
    private lateinit var db: RegionDatabase
    private lateinit var settings: AiSettingsStore
    private lateinit var assistant: TravelAssistant
    private val requests = mutableListOf<String>()

    @Before
    fun setUp() = runBlocking<Unit> {
        Locale.setDefault(Locale.ITALIAN)
        db = Room.inMemoryDatabaseBuilder(context, RegionDatabase::class.java).build()
        seed()

        val preferences = InMemoryPreferencesFactory()
        settings = AiSettingsStore(preferences, File(context.filesDir, "models")) { RamTier.CONFORTEVOLE }.apply { setApiKey("chiave-di-prova") }
        val nationality = NationalityPreferences(preferences).apply { setNationality("GB") }
        VaccinationPreferences(preferences).departure = "IT"
        val position = FakeLastKnownPosition(LATITUDE to LONGITUDE)
        val region = InstalledRegion(REGION, "Giappone", "JP", null, null, null, null, null, null, null, 0L)
        val regions = FakeRegionRepository(listOf(region))
        val cities = CityRepository(db.cityDao())
        val pois = PoiRepository(db.poiDao())
        val clock = Clock.systemUTC()
        val weather = object : WeatherRepository {
            override suspend fun weather(cacheKey: String, coordinates: suspend () -> Pair<Double, Double>?): WeatherResult? = null
            override suspend fun coordinatesOf(city: String, countryCode: String): Pair<Double, Double>? = null
        }
        val alerts = object : AlertsRepository {
            override suspend fun alerts(regionId: String): RegionAlerts? = null
        }
        val models = OnlineModelList(mapOf("CHATGPT" to listOf(OnlineModelEntry("modello-di-prova", "auto", true, 0.0, 0.0))))
        assistant = TravelAssistant(
            guideRepository = GuideRepository(db.guideDao()),
            cityRepository = cities,
            noteRepository = NoteRepository(db.noteDao(), Dispatchers.IO),
            engine = UnusedEngine,
            onlineLlmClient = OnlineLlmClient(recordingClient(), Json { ignoreUnknownKeys = true }, Dispatchers.IO),
            onlineModelCatalog = OnlineModelCatalog(models, File(context.cacheDir, "online-models.json"), object : OnlineModelsClient {
                override suspend fun fetchOnlineModels(): ByteArray = ByteArray(0)
            }, Dispatchers.IO),
            aiSettingsStore = settings,
            nationalityPreferences = nationality,
            regionRepository = regions,
            vaccinationRepository = VaccinationRepository(db.vaccinationDao()),
            vaccinationPreferences = VaccinationPreferences(preferences),
            emergencyNumbersRepository = EmergencyNumbersRepository(db.emergencyNumbersDao()),
            poiRepository = pois,
            lastKnownPosition = position,
            transitRepository = FakeTransitRepository(),
            carRouteCalculator = CarRouteCalculator { _, _, _ -> null },
            context = context,
            wordListsOverride = WordListsOverride(preferences, false),
            forecastSections = ForecastSections(ForecastProvider(weather, clock), regions, cities, pois, position, LastOpenedCityGuide(), clock),
            alertsSections = AlertsSections(alerts, context, clock),
            defaultDispatcher = Dispatchers.Default,
        )
    }

    @After
    fun tearDown() {
        db.close()
        Locale.setDefault(previousLocale)
    }

    @Test
    fun senzaConsensoLaRichiestaNonHaDatiPersonali() = runBlocking<Unit> {
        settings.setSharesPersonalData(false)

        val request = askOnline()

        // Le sezioni senza vincoli ci sono: l'assenza dei dati personali non viene da un contesto vuoto.
        assertTrue(request, PUBLIC_SECTION in request)
        assertFalse("nota personale: $request", NOTE_MARKER in request)
        assertFalse("POI vicino: $request", NEARBY_POI in request)
        assertFalse("vaccinazioni: $request", VACCINATION_HEADER in request)
        assertFalse("sezione per nazionalita': $request", NATIONALITY_SECTION in request)
    }

    @Test
    fun conConsensoLaRichiestaHaNotaPoiVaccinazioniESezioniPerNazionalita() = runBlocking<Unit> {
        settings.setSharesPersonalData(true)

        val request = askOnline()

        assertTrue(request, PUBLIC_SECTION in request)
        assertTrue("nota personale: $request", NOTE_MARKER in request)
        assertTrue("POI vicino: $request", NEARBY_POI in request)
        assertTrue("vaccinazioni: $request", VACCINATION_HEADER in request)
        assertTrue("sezione per nazionalita': $request", NATIONALITY_SECTION in request)
    }

    // Una domanda che accende la ricerca nelle guide, la nota, i POI vicini e le vaccinazioni; ritorna il corpo inviato.
    private suspend fun askOnline(): String {
        val answers = assistant.ask(REGION, "Quali vaccini servono e dov'è la farmacia qui vicino?", AiEngineMode.ONLINE).toList()
        assertTrue(answers.last() is AssistantProgress.Done)
        return requests.single()
    }

    private suspend fun seed() {
        db.guideDao().insertAll(
            listOf(
                GuideSectionEntity(
                    regionId = REGION, category = GuideCategory.DA_SAPERE, title = "Farmacie",
                    body = "Farmacia: $PUBLIC_SECTION, aperte di giorno.", sourceUrl = "https://it.wikivoyage.org/wiki/Giappone",
                ),
                GuideSectionEntity(
                    regionId = REGION, category = GuideCategory.DA_SAPERE, title = "Farmacia per i britannici",
                    body = "Farmacia: $NATIONALITY_SECTION.", sourceUrl = "https://www.gov.uk/foreign-travel-advice/japan#for-nationality=GB",
                ),
            ),
        )
        db.poiCodeDao().ensureRegion(REGION)
        db.poiCodeDao().ensureCode("pharmacy", "amenity=pharmacy")
        db.poiDao().insertAll(
            listOf(
                PoiRow(
                    regionRef = db.poiCodeDao().regionRef(REGION), code = db.poiCodeDao().codeOf("pharmacy", "amenity=pharmacy"),
                    latE6 = Math.round((LATITUDE + 0.0005) * 1_000_000), lonE6 = Math.round(LONGITUDE * 1_000_000), name = NEARBY_POI,
                ),
            ),
        )
        db.vaccinationDao().insertRecommended(
            listOf(VaccRecommendedEntity(iso2 = "JP", vaccine = "HEPA", level = "MOST", conditionIt = "", conditionEn = "", sources = "", verified = "2026-01-01")),
        )
        NoteRepository(db.noteDao(), Dispatchers.IO).save(Note(title = "Prenotazione", body = "Farmacia da ricordare: $NOTE_MARKER"))
    }

    // Risponde senza rete e conserva il corpo di ogni richiesta.
    private fun recordingClient() = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val body = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            requests += body
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(HTTP_OK).message("OK")
                .body("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""".toResponseBody("application/json".toMediaType()))
                .build()
        },
    ).build()

    // Online il motore sul dispositivo non si usa: ogni chiamata e' un errore del test.
    private object UnusedEngine : OnDeviceLlmEngine {
        override val stats: StateFlow<LlmRuntimeStats> get() = unused()
        override val isLoadingModel: StateFlow<Boolean> get() = unused()
        override val cpuBackend: StateFlow<CpuBackendInfo?> get() = unused()
        override suspend fun ensureLoaded(): Long = unused()
        override suspend fun generateAll(prompts: List<String>): OnDeviceLlmEngine.GenerationBatch = unused()
        override suspend fun generate(prompt: String): String = unused()
        override fun generateStream(prompt: String): Flow<String> = unused()
        override suspend fun generateWithGrammar(prompt: String, grammar: String, maxTokens: Int): String = unused()
        override suspend fun release() = unused()
        override suspend fun releaseAndDelete(): Boolean = unused()

        private fun unused(): Nothing = error("Il motore sul dispositivo non si usa online")
    }

    private companion object {
        const val REGION = "giappone"
        const val LATITUDE = 35.0
        const val LONGITUDE = 135.0
        const val HTTP_OK = 200
        const val PUBLIC_SECTION = "SEZIONE-PUBBLICA-4417"
        const val NATIONALITY_SECTION = "SEZIONE-SOLO-GB-9023"
        const val NOTE_MARKER = "NOTA-PERSONALE-7731"
        const val NEARBY_POI = "Farmacia Tanuki"
        const val VACCINATION_HEADER = "Vaccinazioni per il viaggio"
    }
}
