package com.pockettravel.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.RegionSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Guide di tutte le nazioni nell'onboarding: download in background all'ultimo passo, senza una scelta. */
@HiltViewModel
class GuidesDownloadViewModel @Inject constructor(
    private val regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
) : ViewModel() {

    val isInstalled: StateFlow<Boolean> = regionRepository.observeInstalledGuides()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Avvia il download (anche con la rete a consumo: meno di un MB) se le guide mancano e non e' gia' in corso. */
    fun startIfMissing() {
        viewModelScope.launch {
            val installed = regionRepository.observeInstalledGuides().first() != null
            val running = regionSyncScheduler.observeGuidesSync().first()?.state == WorkInfo.State.RUNNING
            if (!installed && !running) regionSyncScheduler.enqueueGuidesSync(onlyOnWifi = false)
        }
    }
}
