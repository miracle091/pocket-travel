package com.pockettravel.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.db.PoiEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Dispatcher principale dei test dei ViewModel: `viewModelScope` e `runTest` condividono lo stesso orologio virtuale. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Tiene acceso per tutto il test uno stato `WhileSubscribed`: senza chi lo raccoglie il suo `value` non si aggiorna. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.keepCollecting(state: Flow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { } }
}

/** Il valore di uno stato dopo che le coroutine pronte hanno finito: gli stati combinati arrivano con qualche passaggio di ritardo. */
fun <T> TestScope.settled(state: StateFlow<T>): T {
    testScheduler.runCurrent()
    return state.value
}

/** Un ViewModel dentro uno `ViewModelStore`: [clear] lo chiude come fa il sistema (`onCleared`, `viewModelScope` annullato). */
class ViewModelHost<T : ViewModel>(private val modelClass: Class<T>, private val create: () -> T) {
    private val store = ViewModelStore()
    val viewModel: T = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
        },
    )[modelClass]

    fun clear() = store.clear()
}

fun installedRegion(
    id: String,
    name: String = id,
    routing: Boolean = true,
    map: Boolean = true,
    countryCode: String? = null,
) = InstalledRegion(
    regionId = id, displayName = name, countryCode = countryCode,
    mapVersion = if (map) "m1" else null, routingVersion = if (routing) "r1" else null,
    poiVersion = "p1", poiExtraVersion = null, addressesVersion = null,
    poiSizeBytes = 1L, poiExtraSizeBytes = null, sizeBytes = 1L,
)

fun poiRow(
    id: Long,
    name: String,
    regionId: String = "r1",
    category: String = "restaurant",
    osmTag: String = "amenity=restaurant",
    lat: Double = 45.5,
    lon: Double = 9.5,
    extra: Boolean = false,
    nameIt: String? = null,
    nameEn: String? = null,
) = PoiEntity(
    id = id, regionId = regionId, name = name, category = category, lat = lat, lon = lon, osmTag = osmTag,
    nameIt = nameIt, nameEn = nameEn, extra = extra,
)
