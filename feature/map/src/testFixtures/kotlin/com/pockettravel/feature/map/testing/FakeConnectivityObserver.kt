package com.pockettravel.feature.map.testing

import com.pockettravel.feature.map.ConnectivityObserver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [ConnectivityObserver] pilotabile: ogni cambio di [online] e' una notifica di rete. */
class FakeConnectivityObserver : ConnectivityObserver {
    val online = MutableStateFlow(true)

    override fun changes(): Flow<Boolean> = online
}
