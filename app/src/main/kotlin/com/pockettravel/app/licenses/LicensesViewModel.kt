package com.pockettravel.app.licenses

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withContext
import com.pockettravel.app.runCatchingCancellable
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.sync.AddressGridAttribution
import com.pockettravel.core.sync.AddressGridClient
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.TransitFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Componenti di `res/raw/aboutlibraries.json` (vedi [licenseLabels]), sempre disponibili senza rete, più le fonti
 * dei civici a griglia (`attributions` di address-grid.json) e le reti dei mezzi pubblici (transit.json): per queste
 * due un errore (rete, `manifest.json` senza addressGrid) lascia semplicemente la lista vuota. La pagina di un
 * componente usa lo stesso ViewModel dell'elenco.
 */
@HiltViewModel
class LicensesViewModel @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
    private val manifestClient: ManifestClient,
    private val addressGridClient: AddressGridClient,
    private val transitClient: TransitClient,
) : ViewModel() {
    private val _libraries = MutableStateFlow<List<Library>>(emptyList())
    val libraries: StateFlow<List<Library>> = _libraries.asStateFlow()

    private val _addressAttributions = MutableStateFlow<List<AddressGridAttribution>>(emptyList())
    val addressAttributions: StateFlow<List<AddressGridAttribution>> = _addressAttributions.asStateFlow()

    private val _transitFeeds = MutableStateFlow<List<TransitFeed>>(emptyList())
    val transitFeeds: StateFlow<List<TransitFeed>> = _transitFeeds.asStateFlow()

    init {
        viewModelScope.launch {
            _libraries.value = withContext(ioDispatcher) { Libs.Builder().withContext(context).build().libraries }
        }
        viewModelScope.launch {
            // Un solo `manifest.json` per entrambe le liste.
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
