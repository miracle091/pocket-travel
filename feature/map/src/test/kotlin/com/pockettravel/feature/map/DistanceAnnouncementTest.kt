package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistanceAnnouncementTest {
    @Test
    fun `a piedi ogni 100 m, poi ogni 10 m dai 50`() {
        assertEquals(300, distanceAnnouncement(287.0, 1.4))
        assertEquals(100, distanceAnnouncement(51.0, 1.4))
        assertEquals(50, distanceAnnouncement(50.0, 1.4))
        assertEquals(30, distanceAnnouncement(24.0, 1.4))
        assertEquals(10, distanceAnnouncement(10.0, 1.4))
        assertNull(distanceAnnouncement(9.0, 1.4))
    }

    @Test
    fun `piu' veloci, soglie piu' larghe`() {
        // In auto in citta' (12 m/s): ogni 500 m, poi 250-200-150-100-50.
        assertEquals(1000, distanceAnnouncement(760.0, 12.0))
        assertEquals(250, distanceAnnouncement(240.0, 12.0))
        assertEquals(50, distanceAnnouncement(50.0, 12.0))
        assertNull(distanceAnnouncement(40.0, 12.0))
        // In bici (4,5 m/s): 100, 80, 60, 40, 20.
        assertEquals(80, distanceAnnouncement(75.0, 4.5))
    }

    @Test
    fun `la velocita' viene dalla distanza che cala, smussata`() {
        val estimate = SpeedEstimate()
        estimate.update(1000.0, 0)
        estimate.update(990.0, 1_000_000_000) // 10 m in 1 s
        assertEquals(10.0, estimate.speed!!, 0.001)
        estimate.update(985.0, 2_000_000_000) // 5 m in 1 s: 0,7*10 + 0,3*5
        assertEquals(8.5, estimate.speed!!, 0.001)
        // Un ricalcolo che allunga la strada non e' una velocita' negativa.
        estimate.update(1200.0, 3_000_000_000)
        assertEquals(8.5, estimate.speed!!, 0.001)
    }
}
