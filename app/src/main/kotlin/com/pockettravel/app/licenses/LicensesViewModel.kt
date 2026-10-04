package com.pockettravel.app.licenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.app.runCatchingCancellable
import com.pockettravel.core.sync.AddressGridAttribution
import com.pockettravel.core.sync.AddressGridClient
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.TransitFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fonti dei civici a griglia (`attributions` di address-grid.json) e reti dei mezzi pubblici (transit.json):
 * un errore (rete, manifest senza addressGrid) lascia semplicemente la lista vuota, la schermata
 * mostra comunque le licenze statiche di [thirdPartyLicenses].
 */
@HiltViewModel
class LicensesViewModel @Inject constructor(
    private val manifestClient: ManifestClient,
    private val addressGridClient: AddressGridClient,
    private val transitClient: TransitClient,
) : ViewModel() {
    private val _addressAttributions = MutableStateFlow<List<AddressGridAttribution>>(emptyList())
    val addressAttributions: StateFlow<List<AddressGridAttribution>> = _addressAttributions.asStateFlow()

    private val _transitFeeds = MutableStateFlow<List<TransitFeed>>(emptyList())
    val transitFeeds: StateFlow<List<TransitFeed>> = _transitFeeds.asStateFlow()

    init {
        viewModelScope.launch {
            // Un solo manifest per entrambe le liste.
            val manifest = runCatchingCancellable { manifestClient.recentManifest() }.getOrNull() ?: return@launch
            launch {
                val attributions = runCatchingCancellable {
                    manifest.addressGrid?.let { addressGridClient.fetchIndex(it).attributions }
                }.getOrNull()
                if (attributions != null) _addressAttributions.value = attributions
            }
            launch {
                val feeds = runCatchingCancellable {
                    manifest.transit?.let { transitClient.fetchIndex(it).feeds }
                }.getOrNull()
                if (feeds != null) _transitFeeds.value = feeds
            }
        }
    }
}
