package com.pockettravel.core.data

import com.pockettravel.core.data.db.EmergencyNumbersDao
import javax.inject.Inject

class EmergencyNumbersRepository @Inject constructor(
    private val emergencyNumbersDao: EmergencyNumbersDao,
) {
    suspend fun forRegion(regionId: String): EmergencyNumbers? = emergencyNumbersDao.forRegion(regionId)?.let {
        EmergencyNumbers(general = it.general, police = it.police, ambulance = it.ambulance, fire = it.fire)
    }
}

data class EmergencyNumbers(
    val general: String?,
    val police: String,
    val ambulance: String,
    val fire: String,
)
