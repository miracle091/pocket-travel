package com.pockettravel.core.data

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Singleton

/**
 * Il mutex del DatabaseCompactor e' un campo dell'istanza: esclude le compattazioni solo se tutti
 * (worker, RegionStartupRecovery, ViewModel) ricevono la stessa istanza dal grafo Hilt.
 */
class RoomRegionRepositoryScopeTest {
    @Test
    fun `RoomRegionRepository e' singleton`() {
        assertTrue(RoomRegionRepository::class.java.isAnnotationPresent(Singleton::class.java))
    }
}
