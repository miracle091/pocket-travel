package com.pockettravel.app.licenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.sync.AddressGridAttribution
import com.pockettravel.core.sync.AddressGridClient
import com.pockettravel.core.sync.ManifestClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fonti dei civici a griglia (address-grid-plan.md "App" 6, `attributions` di address-grid.json):
 * un errore (rete, manifest senza addressGrid) lascia semplicemente la lista vuota, la schermata
 * mostra comunque le licenze statiche di [thirdPartyLicenses].
 */
@HiltViewModel
class LicensesViewModel @Inject constructor(
    private val manifestClient: ManifestClient,
    private val addressGridClient: AddressGridClient,
) : ViewModel() {
    private val _addressAttributions = MutableStateFlow<List<AddressGridAttribution>>(emptyList())
    val addressAttributions: StateFlow<List<AddressGridAttribution>> = _addressAttributions.asStateFlow()

    init {
        viewModelScope.launch {
            val attributions = runCatching {
                manifestClient.fetchManifest().addressGrid?.let { addressGridClient.fetchIndex(it).attributions }
            }.getOrNull()
            if (attributions != null) _addressAttributions.value = attributions
        }
    }
}
