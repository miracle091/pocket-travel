package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.PreferencesFactory

/** Una nazione da aggiornare; [redownload]: tra i pacchetti ci sono quelli tolti per mancanza di spazio. */
internal data class RegionUpdateItem(
    val regionId: String,
    val name: String,
    val updatedAt: String,
    val redownload: Boolean,
) {
    companion object {
        fun of(entry: RegionManifestEntry, kinds: Set<PackageKind>, redownloadKinds: Set<PackageKind>): RegionUpdateItem =
            RegionUpdateItem(entry.regionId, entry.displayName, entry.updatedAt, (redownloadKinds intersect kinds).isNotEmpty())
    }
}

/** Gli aggiornamenti disponibili della notifica unica: ogni controllo sostituisce solo la sua parte. */
internal data class PendingUpdates(
    val regions: List<RegionUpdateItem> = emptyList(),
    val appVersion: String? = null,
    val aiModelVersion: String? = null,
) {
    val count: Int get() = regions.size + listOfNotNull(appVersion, aiModelVersion).size

    /** Cambia quando arriva qualcosa di nuovo: una nazione ripubblicata, un'altra versione dell'app o del modello. */
    val keys: Set<String>
        get() = regions.mapTo(mutableSetOf()) { "region:${it.regionId}:${it.updatedAt}:${it.redownload}" } +
            listOfNotNull(appVersion?.let { "app:$it" }, aiModelVersion?.let { "ai:$it" })
}

internal sealed interface UpdatesNotificationAction {
    data object Cancel : UpdatesNotificationAction
    data object Skip : UpdatesNotificationAction
    data class Post(val alert: Boolean) : UpdatesNotificationAction
}

/**
 * Suona solo per un aggiornamento nuovo. Senza novita' la notifica si aggiorna in silenzio se e' ancora visibile, e
 * non ricompare se l'utente l'ha chiusa; senza aggiornamenti si toglie.
 */
internal fun updatesNotificationAction(pending: PendingUpdates, alertedKeys: Set<String>, showing: Boolean): UpdatesNotificationAction =
    when {
        pending.count == 0 -> UpdatesNotificationAction.Cancel
        !alertedKeys.containsAll(pending.keys) -> UpdatesNotificationAction.Post(alert = true)
        showing -> UpdatesNotificationAction.Post(alert = false)
        else -> UpdatesNotificationAction.Skip
    }

/** Salva [PendingUpdates] e le chiavi gia' segnalate, cosi' un controllo del giorno dopo non suona per le stesse cose. */
internal class PendingUpdatesStore(factory: PreferencesFactory) {
    // Aperto al primo uso, dai worker: non al momento della creazione, che puo' avvenire sul thread principale.
    private val preferences by lazy { factory.open(FILE_NAME) }

    fun load(): PendingUpdates = PendingUpdates(
        regions = preferences.getStringSet(REGIONS, emptySet()).orEmpty().mapNotNull(::decodeRegion).sortedBy { it.name },
        appVersion = preferences.getString(APP, null),
        aiModelVersion = preferences.getString(AI_MODEL, null),
    )

    fun save(pending: PendingUpdates) {
        preferences.edit()
            .putStringSet(REGIONS, pending.regions.mapTo(mutableSetOf(), ::encodeRegion))
            .putString(APP, pending.appVersion)
            .putString(AI_MODEL, pending.aiModelVersion)
            .apply()
    }

    var alertedKeys: Set<String>
        get() = preferences.getStringSet(ALERTED, emptySet()).orEmpty()
        set(value) = preferences.edit().putStringSet(ALERTED, value).apply()

    private fun encodeRegion(item: RegionUpdateItem): String =
        listOf(item.regionId, item.name, item.updatedAt, item.redownload).joinToString(SEPARATOR)

    private fun decodeRegion(value: String): RegionUpdateItem? {
        val parts = value.split(SEPARATOR).takeIf { it.size == REGION_FIELDS } ?: return null
        return RegionUpdateItem(parts[0], parts[1], parts[2], parts[REDOWNLOAD_FIELD].toBoolean())
    }

    private companion object {
        const val FILE_NAME = "pending_updates"
        const val REGIONS = "regions"
        const val APP = "app"
        const val AI_MODEL = "ai_model"
        const val ALERTED = "alerted_keys"
        const val SEPARATOR = "\t"
        const val REGION_FIELDS = 4
        const val REDOWNLOAD_FIELD = 3
    }
}
