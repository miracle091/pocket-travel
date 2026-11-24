package com.pockettravel.core.sync

import android.content.Context
import android.util.Log
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.data.RoutingVariantPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

/**
 * Come e' finito il download di una voce: installata, da ritentare (errore di rete) o fallita per sempre. Le due
 * ultime sono fallimenti con la causa nota: il Risparmio dati di Android, o un blocco sospetto della rete dell'app.
 */
internal enum class DownloadOutcome { SUCCESS, RETRY, FAILURE, DATA_SAVER_BLOCKED, NETWORK_BLOCKED }

/** Quello che il download fa sapere a chi lo esegue: la notifica in primo piano e l'avanzamento. */
internal interface DownloadCallbacks {
    /** Prima di scaricare, con i pacchetti davvero da scaricare: false se la notifica in primo piano non si e' potuta mostrare. */
    suspend fun start(displayName: String, kinds: Set<PackageKind>): Boolean

    suspend fun onFiles(bytesDownloaded: Long, totalBytes: Long)

    /** Dal thread bloccante dell'estrazione, solo quando cambia la percentuale. */
    fun onMap(bytesDone: Long, bytesTotal: Long)

    fun onInstalling()
}

/** Il download dei pacchetti di una regione; il worker della coda lo riceve da qui, i test ne passano uno finto. */
internal interface RegionDownload {
    /**
     * Scarica e installa [requestedKinds] della voce [entryJson]. [attempt] conta i tentativi gia' fatti (0 al primo);
     * [cancelledByUser] dice, a download interrotto, se lo staging va tolto subito.
     */
    suspend fun run(
        entryJson: String,
        requestedKinds: Set<PackageKind>,
        attempt: Int,
        callbacks: DownloadCallbacks,
        cancelledByUser: () -> Boolean,
    ): DownloadOutcome
}

/**
 * Il download dei pacchetti di una regione, senza il lavoro di WorkManager che lo esegue: zona e rete stradale scelte,
 * installazione, pulizia dello staging e decisione sui nuovi tentativi.
 */
internal class RegionPackageDownload @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: RegionPackageInstaller,
    private val regionZonePreferences: RegionZonePreferences,
    private val routingVariantPreferences: RoutingVariantPreferences,
    private val json: Json,
) : RegionDownload {
    override suspend fun run(
        entryJson: String,
        requestedKinds: Set<PackageKind>,
        attempt: Int,
        callbacks: DownloadCallbacks,
        cancelledByUser: () -> Boolean,
    ): DownloadOutcome {
        val staging = Staging()

        @Suppress("TooGenericExceptionCaught") // Qualunque altro errore fa fallire solo questo download, non l'app.
        val outcome = try {
            install(entryJson, requestedKinds, attempt, callbacks, staging)
        } catch (error: CancellationException) {
            // "Annulla": il download non riparte e lo staging si ripulisce subito (senza il motivo, prima di Android 12, al
            // prossimo avvio: RegionStartupRecovery). Altre cause (vincoli persi, processo fermato dal sistema) lasciano lo
            // staging per la ripresa.
            if (cancelledByUser()) cleanUp(staging)
            throw error
        } catch (error: OutOfMemoryError) {
            // Non si ingoia, ma lo staging non resta: il lavoro non viene ritentato.
            cleanUp(staging)
            throw error
        } catch (_: PermanentRegionPackageException) {
            DownloadOutcome.FAILURE
        } catch (error: IOException) {
            // Errore di rete: si riprova, ma non all'infinito.
            outcomeForNetworkError(context.networkBlockFor(error), attempt)
        } catch (error: Exception) {
            Log.w(TAG, "Download pacchetto regione fallito", error)
            DownloadOutcome.FAILURE
        }
        // Installazione finita (i file sono gia' stati spostati, restava una cartella vuota) o fallimento definitivo: lo
        // staging (file .part compresi) non serve piu'. Solo un nuovo tentativo lo riusa.
        if (outcome != DownloadOutcome.RETRY) cleanUp(staging)
        return outcome
    }

    // La cartella di staging della richiesta, nota solo dopo l'inizio dell'installazione.
    private class Staging {
        var regionId: String? = null
        var version: String? = null
    }

    private suspend fun install(
        entryJson: String,
        requestedKinds: Set<PackageKind>,
        attempt: Int,
        callbacks: DownloadCallbacks,
        staging: Staging,
    ): DownloadOutcome {
        val manifestEntry = json.decodeFromString(RegionManifestEntry.serializer(), entryJson)
        manifestEntry.validate()
        staging.regionId = manifestEntry.regionId
        // Qui e non da chi accoda: qualunque schermata avvii il download, mappa, rete stradale e civici restano nella zona
        // scelta. Una zona senza celle dei civici toglie i civici dai pacchetti da scaricare.
        // Rete stradale "solo auto" al posto di quella completa, se scelti e offerti dal catalogo.
        val carOnly = routingVariantPreferences.choices.value.isCarOnly(manifestEntry.regionId) && manifestEntry.routingCar != null
        val entry = manifestEntry.withRoutingVariant(carOnly).restrictedTo(regionZonePreferences.zone(manifestEntry.regionId))
        val kinds = requestedKinds.filterTo(mutableSetOf()) { it in entry.availableKinds }
        return when {
            kinds.isEmpty() -> DownloadOutcome.SUCCESS
            !callbacks.start(entry.displayName, kinds) && givesUpWithoutForeground(attempt, kinds) -> DownloadOutcome.FAILURE
            else -> {
                installKinds(entry, kinds, callbacks, staging)
                DownloadOutcome.SUCCESS
            }
        }
    }

    private suspend fun installKinds(entry: RegionManifestEntry, kinds: Set<PackageKind>, callbacks: DownloadCallbacks, staging: Staging) {
        var lastMapPercent = -1L
        installer.install(
            entry,
            kinds,
            onProgress = callbacks::onFiles,
            onMapProgress = { bytesDone, bytesTotal ->
                val percent = if (bytesTotal > 0) bytesDone * 100 / bytesTotal else 100
                if (percent != lastMapPercent) {
                    lastMapPercent = percent
                    callbacks.onMap(bytesDone, bytesTotal)
                }
            },
            onInstalling = callbacks::onInstalling,
            onStagingVersion = { staging.version = it },
        )
        // Il Navigatore lo legge per avvisare che a piedi o in bici questa rete stradale non basta.
        if (PackageKind.ROUTING in kinds) routingVariantPreferences.setInstalledCarOnly(entry.regionId, entry.hasCarOnlyRouting)
    }

    private fun cleanUp(staging: Staging) {
        // Solo la cartella di questa richiesta, e mai sotto i piedi di un altro download della stessa regione.
        val regionId = staging.regionId ?: return
        staging.version?.let { installer.deleteStagingIfIdle(regionId, it) }
    }

    companion object {
        // Tentativi ripetuti dopo il primo per errori di rete, poi il download si arrende.
        private const val MAX_RETRIES = 5

        // Tentativi (dopo il primo) consentiti senza la notifica in primo piano.
        private const val MAX_RETRIES_WITHOUT_FOREGROUND = 2

        private val TAG = RegionPackageDownload::class.java.simpleName

        // Da Android 12 un nuovo tentativo partito da sfondo non puo' avviare il servizio: si scarica comunque, senza notifica.
        // Senza notifica pero' il lavoro ha il limite di 10 minuti e l'estrazione della mappa riparte da zero a ogni stop, che
        // non conta in MAX_RETRIES: dopo qualche tentativo ci si arrende. Senza mappa tra i pacchetti non c'e' motivo di arrendersi.
        // Ritentare non serve finche' l'utente non toglie il Risparmio dati per l'app. Un errore DNS passeggero non e' un
        // blocco: lo si dice solo quando i tentativi sono finiti.
        internal fun outcomeForNetworkError(block: NetworkBlock, attempt: Int): DownloadOutcome = when {
            block == NetworkBlock.DATA_SAVER -> DownloadOutcome.DATA_SAVER_BLOCKED
            attempt < MAX_RETRIES -> DownloadOutcome.RETRY
            block == NetworkBlock.SUSPECTED -> DownloadOutcome.NETWORK_BLOCKED
            else -> DownloadOutcome.FAILURE
        }

        internal fun givesUpWithoutForeground(attempt: Int, kinds: Set<PackageKind>) =
            PackageKind.MAP in kinds && attempt >= MAX_RETRIES_WITHOUT_FOREGROUND
    }
}
