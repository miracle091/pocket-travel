package com.pockettravel.feature.map.testing

import com.pockettravel.feature.map.GpsFix
import com.pockettravel.feature.map.GpsLocationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** [GpsLocationSource] pilotabile: il permesso e il GPS si cambiano da fuori, le posizioni si emettono con [emit]. */
class FakeGpsLocationSource(var permission: Boolean = true) : GpsLocationSource {
    val enabled = MutableStateFlow(true)
    private val fixes = MutableSharedFlow<GpsFix>(extraBufferCapacity = 16)

    /** Quanti raccolgono le posizioni in questo momento. */
    val listeners: Int get() = fixes.subscriptionCount.value

    override fun hasPermission(): Boolean = permission
    override fun gpsEnabled(): Flow<Boolean> = enabled
    override fun fixes(): Flow<GpsFix> = fixes

    suspend fun emit(fix: GpsFix) = fixes.emit(fix)
}
