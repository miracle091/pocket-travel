package com.pockettravel.app.widget

import com.pockettravel.app.regions.RecentRegionStore
import com.pockettravel.app.regions.localizedInstalledName
import com.pockettravel.core.data.DiplomaticMissionRepository
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.mergeEmbassies
import com.pockettravel.feature.ai.AiAvailability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Legge dai dati locali (mai dalla rete) quello che i due widget mostrano. */
@Singleton
class WidgetStateLoader @Inject constructor(
    private val recentRegionStore: RecentRegionStore,
    private val regionRepository: RegionRepository,
    private val emergencyNumbersRepository: EmergencyNumbersRepository,
    private val nationalityPreferences: NationalityPreferences,
    private val poiRepository: PoiRepository,
    private val diplomaticMissionRepository: DiplomaticMissionRepository,
    private val aiAvailability: AiAvailability,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * Cambia quando cambia quello che i widget mostrano: l'ultima regione aperta, la nazionalita', le regioni installate o
     * aggiornate, le guide (con i numeri di emergenza) e la disponibilita' di un motore per l'Assistente.
     */
    val changes: Flow<Unit> = combine(
        recentRegionStore.lastRegion,
        nationalityPreferences.nationality,
        regionRepository.observeInstalled(),
        regionRepository.observeInstalledGuides(),
        aiAvailability.available,
    ) { _, _, _, _, _ -> }

    suspend fun emergency(locale: Locale): EmergencyWidgetState = withContext(ioDispatcher) {
        val region = recentRegionStore.lastRegionId()?.let { regionRepository.installed(it) }
            ?: return@withContext EmergencyWidgetState.NoRegion
        val numbers = emergencyNumbersRepository.forRegion(region.regionId)
        // Come la Guida: la nazionalita' uguale al paese della regione non ha rappresentanze da mostrare.
        val country = nationalityPreferences.nationality.value?.takeIf { !it.equals(region.countryCode, ignoreCase = true) }
        val embassy = country?.let {
            val missions = region.countryCode?.let { host -> diplomaticMissionRepository.missions(it, host) }.orEmpty()
            mergeEmbassies(poiRepository.embassiesOf(region.regionId, it), missions, emptyList(), locale.language).widgetEmbassy()
        }
        EmergencyWidgetState.Region(
            regionId = region.regionId,
            name = localizedInstalledName(region.displayName, region.countryCode, locale),
            lines = numbers?.widgetLines().orEmpty(),
            embassy = embassy,
            // Come la Guida: "nessun numero unico" solo se la tabella lo dice, non quando mancano i dati.
            noCentralNumber = numbers == null && emergencyNumbersRepository.hasNoCentralNumber(region.regionId),
        )
    }

    suspend fun shortcuts(): ShortcutsWidgetState = withContext(ioDispatcher) {
        // Un'ultima regione eliminata non conta: l'Assistente ne ha bisogno.
        val regionId = recentRegionStore.lastRegionId()?.takeIf { regionRepository.installed(it) != null }
        ShortcutsWidgetState(regionId, shortcutsFor(regionId, aiAvailability.available.value))
    }
}
