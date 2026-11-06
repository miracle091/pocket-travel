package com.pockettravel.app.updates

import androidx.annotation.StringRes
import com.pockettravel.app.R
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.core.sync.AppUpdateFailure
import com.pockettravel.core.sync.AppUpdateState
import com.pockettravel.core.sync.AppVersionEntry

/** Le nazioni installate con almeno un pacchetto da aggiornare. */
internal fun outdatedRegions(items: List<RegionUiItem>): List<RegionUiItem> =
    items.filter { it.status == RegionStatus.UPDATE_AVAILABLE }

/** Le nazioni da aggiornare che non hanno già un download in corso o in coda: quelle di "Aggiorna tutto". */
internal fun pendingRegionUpdates(items: List<RegionUiItem>, downloading: Set<String>): List<RegionUiItem> =
    outdatedRegions(items).filter { it.regionId !in downloading }

/** Quanti aggiornamenti ci sono in tutto: il numero sul badge di Altro. Ogni nazione conta uno. */
internal fun updatesCount(appUpdate: Boolean, regionUpdates: Int, guidesUpdate: Boolean, modelUpdate: Boolean): Int =
    (if (appUpdate) 1 else 0) + regionUpdates + (if (guidesUpdate) 1 else 0) + (if (modelUpdate) 1 else 0)

/** Peso di "Aggiorna tutto": [bytes] dei file e se tra i pacchetti c'è una mappa, il cui peso si conosce solo estraendola. */
internal data class UpdateTotal(val bytes: Long, val includesMap: Boolean)

internal fun updateTotal(items: List<RegionUiItem>): UpdateTotal =
    UpdateTotal(bytes = items.sumOf { it.sizeBytes }, includesMap = items.any { it.includesMap })

/** Su una rete a consumo "Aggiorna tutto" chiede conferma; su una senza limiti parte subito. Niente da scaricare, niente conferma. */
internal fun updateAllNeedsConfirmation(metered: Boolean, pending: List<RegionUiItem>): Boolean = metered && pending.isNotEmpty()

/**
 * Il controllo non ha raggiunto la rete: app-status.json, che si scarica sempre, non è arrivato e il dispositivo non ha una rete
 * utilizzabile. Il catalogo non conta: può essere quello tenuto in memoria per 10 minuti. Con la rete presente un errore è del server,
 * e la schermata tiene i valori di prima.
 */
internal fun updateCheckOffline(appStatusReached: Boolean, deviceOnline: Boolean): Boolean = !appStatusReached && !deviceOnline

/** Il download che riguarda [app]: un APK pronto o un errore di un'altra versione (rimasti da prima) non contano. */
internal fun relevantDownload(download: AppUpdateState, app: AppVersionEntry): AppUpdateState = when (download) {
    is AppUpdateState.Ready -> if (download.versionCode != app.versionCode) AppUpdateState.Idle else download
    is AppUpdateState.Failed -> if (download.versionCode != app.versionCode) AppUpdateState.Idle else download
    AppUpdateState.Idle, is AppUpdateState.Downloading -> download
}

/** Avanzamento 0..1 del download; null finché il totale non è noto. */
internal fun AppUpdateState.Downloading.fraction(): Float? =
    if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else null

/** Il testo di un download fallito: la firma e i file non validi rimandano a GitHub, la rete a un nuovo tentativo. */
@StringRes
internal fun AppUpdateFailure.messageRes(): Int = when (this) {
    AppUpdateFailure.NETWORK -> R.string.updates_app_error_download
    AppUpdateFailure.SIGNATURE -> R.string.updates_app_error_signature
    AppUpdateFailure.INVALID_FILE,
    AppUpdateFailure.UNREADABLE,
    AppUpdateFailure.PACKAGE,
    AppUpdateFailure.NOT_NEWER,
    -> R.string.updates_app_error_file
}

/**
 * Vero se un nuovo download può riuscire: rete persa, o file arrivato diverso dall'impronta. Con firma, pacchetto,
 * versione o archivio sbagliati lo stesso APK verrebbe scartato di nuovo, e resta solo "Scarica da GitHub".
 */
internal fun AppUpdateFailure.canRetry(): Boolean = when (this) {
    AppUpdateFailure.NETWORK, AppUpdateFailure.INVALID_FILE -> true
    AppUpdateFailure.UNREADABLE, AppUpdateFailure.PACKAGE, AppUpdateFailure.NOT_NEWER, AppUpdateFailure.SIGNATURE -> false
}
