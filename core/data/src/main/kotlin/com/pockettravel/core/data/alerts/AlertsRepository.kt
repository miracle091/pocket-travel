package com.pockettravel.core.data.alerts

import android.content.Context
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.WEATHER_MIN_REFRESH_MILLIS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Allerte in corso di una regione. [fresh] e' vero se almeno una fonte ha risposto adesso, e allora [updatedAtMillis] e'
 * l'ora di quel download; se nessuna ha risposto (niente rete) e' l'ora del download salvato piu' recente e l'interfaccia
 * lo dice ("Ultime allerte scaricate alle"). Una fonte che non risponde (GDACS a volte va in timeout) lascia le sue allerte
 * salvate senza far sembrare il telefono offline.
 */
data class RegionAlerts(
    val alerts: List<Alert>,
    val updatedAtMillis: Long,
    val fresh: Boolean,
    // Servizio meteorologico nazionale delle allerte Meteoalarm ("Fonte: %1$s tramite EUMETNET – MeteoAlarm"), se noto.
    val meteoalarmSender: String? = null,
)

/** Allerte delle fonti aperte per una regione installata. Implementazione: [OnlineAlertsRepository]. */
interface AlertsRepository {
    /** Null se nessuna fonte ha mai risposto (mai scaricate e niente rete). */
    suspend fun alerts(regionId: String): RegionAlerts?
}

/**
 * GDACS, USGS, Meteoalarm, tsunami.gov e i servizi nazionali (NWS, ECCC, GeoNet, JMA) letti dal telefono, senza server del
 * progetto e senza la posizione dell'utente: le richieste sono per tutto il mondo, per nazione, per stato o per il
 * rettangolo della regione ([AlertUrls]), e il rettangolo si applica sul telefono ([selectAlerts]). L'ultima risposta
 * di ogni fonte resta in un file per l'uso senza rete; una fonte non si richiede piu' spesso di WEATHER_MIN_REFRESH_MILLIS,
 * lo stesso limite del meteo.
 */
class OnlineAlertsRepository(
    private val cacheDir: File,
    private val regionStorage: RegionStorage,
    private val regionRepository: RegionRepository,
    okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) : AlertsRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        regionStorage: RegionStorage,
        regionRepository: RegionRepository,
        okHttpClient: OkHttpClient,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        clock: Clock,
    ) : this(File(context.filesDir, "alerts"), regionStorage, regionRepository, okHttpClient, ioDispatcher, clock)

    // Tetto per chiamata, come il meteo: con una rete lenta si mostrano subito le allerte salvate.
    private val client = okHttpClient.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    override suspend fun alerts(regionId: String): RegionAlerts? = withContext(ioDispatcher) {
        val countryCode = regionRepository.installed(regionId)?.countryCode
        val bounds = regionStorage.mapBounds(regionId)?.let { AlertBounds(it.minLon, it.minLat, it.maxLon, it.maxLat) }
        val loaded = (sources(countryCode) + nationalSources(regionId, countryCode, bounds)).mapNotNull(::load)
        if (loaded.isEmpty()) return@withContext null
        RegionAlerts(
            alerts = selectAlerts(loaded.flatMap { it.alerts }, bounds, countryCode, clock.instant()),
            updatedAtMillis = (loaded.filter { it.fresh }.ifEmpty { loaded }).maxOf { it.updatedAtMillis },
            fresh = loaded.any { it.fresh },
            meteoalarmSender = meteoalarmCountry(countryCode)?.let { senderFile(it).takeIf(File::isFile)?.readText() },
        )
    }

    private fun sources(countryCode: String?): List<Source> {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val from = today.minusDays(GDACS_DAYS)
        return listOfNotNull(
            Source("gdacs-severe", AlertUrls.gdacsSevere(from, today), JsonAlertParsers::parseGdacs),
            Source("gdacs-green", AlertUrls.gdacsGreen(from, today), JsonAlertParsers::parseGdacs),
            Source("usgs-day", AlertUrls.usgsDay(), JsonAlertParsers::parseUsgs),
            Source("usgs-significant", AlertUrls.usgsSignificantWeek(), JsonAlertParsers::parseUsgs),
            meteoalarmCountry(countryCode)?.let { country ->
                Source("meteoalarm-$country", AlertUrls.meteoalarm(country), MeteoalarmParser::parse, afterDownload = { body -> saveSender(country, body) })
            },
        )
    }

    // Servizi nazionali: allerte meteo di NWS (per stato), ECCC (per rettangolo), GeoNet e JMA (per nazione); i due centri
    // tsunami del NOAA per tutte le regioni, perche' un'onda attraversa l'oceano.
    private fun nationalSources(regionId: String, countryCode: String?, bounds: AlertBounds?): List<Source> {
        val country = countryCode?.lowercase(Locale.ROOT)
        return listOfNotNull(
            Source("tsunami-pacific", NationalAlertUrls.tsunamiPacific(), TsunamiParser::parse),
            Source("tsunami-national", NationalAlertUrls.tsunamiNational(), TsunamiParser::parse),
            nwsArea(regionId, countryCode)?.let { Source("nws-$it", NationalAlertUrls.nws(it), NwsParser::parse, headers = IDENTIFIED) },
            bounds?.takeIf { country == "ca" }?.let {
                Source("eccc-$regionId", NationalAlertUrls.eccc(it), EcccParser::parse, headers = IDENTIFIED)
            },
            Source("geonet-val", NationalAlertUrls.geonetVolcanoes(), GeoNetParser::parse, headers = GEONET).takeIf { country == "nz" },
            Source("jma", NationalAlertUrls.jmaWarnings(), JmaParser::parse).takeIf { country == "jp" },
        )
    }

    // La risposta salvata se ha meno del limite; altrimenti una nuova, salvata solo se si legge. Null se non ce n'e' nessuna.
    private fun load(source: Source): Loaded? {
        val file = File(cacheDir, "${source.key}.txt")
        val recent = file.isFile && clock.millis() - file.lastModified() < WEATHER_MIN_REFRESH_MILLIS
        val fresh = recent || try {
            val body = get(source.url, source.headers)
            source.parse(body)
            save(file, body)
            source.afterDownload(body)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            false
        } catch (_: AlertParseException) {
            false
        }
        val alerts = runCatching { source.parse(file.readText()) }.getOrNull() ?: return null
        return Loaded(alerts, file.lastModified(), fresh)
    }

    // Prima in un file a parte, poi al suo posto: una scrittura interrotta non lascia una risposta troncata.
    private fun save(file: File, body: String) {
        cacheDir.mkdirs()
        val partial = File.createTempFile("alerts", ".part", cacheDir)
        try {
            partial.writeText(body)
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            // L'ora del download e' quella dell'orologio dell'app, la stessa del confronto con il limite.
            file.setLastModified(clock.millis())
        } finally {
            partial.delete()
        }
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Allerte: HTTP ${response.code} da $url")
            response.body.string()
        }
    }

    // Meteoalarm chiede il nome del servizio nazionale accanto alle allerte di un paese: il feed Atom non lo ha, il messaggio
    // CAP di una sua voce si'. Uno solo per download; se non si legge resta il nome salvato prima.
    private fun saveSender(country: String, feed: String) {
        val name = try {
            MeteoalarmParser.firstCapUrl(feed)?.let(::get)?.let(MeteoalarmParser::senderName)
        } catch (_: IOException) {
            null
        } ?: return
        senderFile(country).writeText(name)
    }

    private fun senderFile(country: String) = File(cacheDir, "meteoalarm-$country.sender")

    private class Source(
        val key: String,
        val url: String,
        val parse: (String) -> List<Alert>,
        val afterDownload: (String) -> Unit = {},
        val headers: Map<String, String> = emptyMap(),
    )

    private class Loaded(val alerts: List<Alert>, val updatedAtMillis: Long, val fresh: Boolean)

    private companion object {
        // Eventi GDACS cominciati negli ultimi 14 giorni: gli eventi lunghi (siccita') restano finche' GDACS li aggiorna.
        const val GDACS_DAYS = 14L
        const val CALL_TIMEOUT_SECONDS = 10L

        // NWS e MSC GeoMet chiedono un User-Agent che identifichi l'app con un contatto.
        val IDENTIFIED = mapOf("User-Agent" to "PocketTravel (https://github.com/miracle091/pocket-travel)")

        // GeoNet risponde solo a chi chiede la versione 2 del GeoJSON.
        val GEONET = mapOf("Accept" to "application/vnd.geo+json;version=2")
    }
}
