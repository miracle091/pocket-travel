package com.pockettravel.app.updates

import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem

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
