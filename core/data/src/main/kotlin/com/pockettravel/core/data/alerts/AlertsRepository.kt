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
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Allerte in corso di una regione. [updatedAtMillis] e' il download piu' vecchio tra le fonti usate; [fresh] e' falso se
 * almeno una fonte non si e' aggiornata (niente rete): l'interfaccia lo dice ("Ultime allerte scaricate alle").
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
 * GDACS, USGS e Meteoalarm letti dal telefono, senza server del progetto e senza la posizione dell'utente: le richieste sono
 * per tutto il mondo o per nazione, il rettangolo della regione si applica sul telefono ([selectAlerts]). L'ultima risposta
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
        val loaded = sources(countryCode).mapNotNull(::load)
        if (loaded.isEmpty()) return@withContext null
        val bounds = regionStorage.mapBounds(regionId)?.let { AlertBounds(it.minLon, it.minLat, it.maxLon, it.maxLat) }
        RegionAlerts(
            alerts = selectAlerts(loaded.flatMap { it.alerts }, bounds, countryCode, clock.instant()),
            updatedAtMillis = loaded.minOf { it.updatedAtMillis },
            fresh = loaded.all { it.fresh },
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
                Source("meteoalarm-$country", AlertUrls.meteoalarm(country), MeteoalarmParser::parse) { body -> saveSender(country, body) }
            },
        )
    }

    // La risposta salvata se ha meno del limite; altrimenti una nuova, salvata solo se si legge. Null se non ce n'e' nessuna.
    private fun load(source: Source): Loaded? {
        val file = File(cacheDir, "${source.key}.txt")
        val recent = file.isFile && clock.millis() - file.lastModified() < WEATHER_MIN_REFRESH_MILLIS
        val fresh = recent || try {
            val body = get(source.url)
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

    private fun get(url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Allerte: HTTP ${response.code} da $url")
        response.body.string()
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
    )

    private class Loaded(val alerts: List<Alert>, val updatedAtMillis: Long, val fresh: Boolean)

    private companion object {
        // Eventi GDACS cominciati negli ultimi 14 giorni: gli eventi lunghi (siccita') restano finche' GDACS li aggiorna.
        const val GDACS_DAYS = 14L
        const val CALL_TIMEOUT_SECONDS = 10L
    }
}
