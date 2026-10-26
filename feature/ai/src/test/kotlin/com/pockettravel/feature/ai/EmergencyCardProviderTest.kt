package com.pockettravel.feature.ai

import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.EmergencyNumbersEntity
import com.pockettravel.core.data.db.NoCentralEmergencyNumberEntity
import com.pockettravel.core.data.alerts.AlertsRepository
import com.pockettravel.core.data.alerts.RegionAlerts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEmergencyNumbersDao(
    private val rows: Map<String, EmergencyNumbersEntity> = emptyMap(),
    private val failing: Boolean = false,
) : EmergencyNumbersDao {
    var lookups = 0

    override suspend fun insertAll(numbers: List<EmergencyNumbersEntity>) = Unit

    override suspend fun forRegion(regionId: String): EmergencyNumbersEntity? {
        lookups++
        if (failing) error("database non leggibile")
        return rows[regionId]
    }

    override suspend fun deleteAll() = Unit

    override suspend fun insertNoCentralNumber(regions: List<NoCentralEmergencyNumberEntity>) = Unit

    override suspend fun hasNoCentralNumber(regionId: String): Boolean = false

    override suspend fun deleteAllNoCentralNumber() = Unit
}

/** Allerte finte: [result] per ogni regione, [requests] conta le richieste. */
private class FakeAlertsRepository(private val result: RegionAlerts? = RegionAlerts(emptyList(), 0L, fresh = true)) : AlertsRepository {
    var requests = 0

    override suspend fun alerts(regionId: String): RegionAlerts? {
        requests++
        return result
    }
}

class EmergencyCardProviderTest {
    private val italy = EmergencyNumbersEntity("italia", "112", "113", "118", "115")
    private val japan = EmergencyNumbersEntity("giappone", null, "110", "119", "119")
    private val usa = EmergencyNumbersEntity("usa", "911", "911", "911", "911")

    private val alerts = FakeAlertsRepository()

    private fun provider(dao: FakeEmergencyNumbersDao, alertsRepository: AlertsRepository = alerts) =
        EmergencyCardProvider(EmergencyNumbersRepository(dao), alertsRepository)

    @Test
    fun `una domanda ordinaria non mostra il riquadro e non legge i numeri`() = runTest {
        val dao = FakeEmergencyNumbersDao(mapOf("italia" to italy))

        assertNull(provider(dao).forQuestion("italia", "Serve il visto per San Marino?"))
        assertEquals(0, dao.lookups)
        assertEquals(0, alerts.requests)
    }

    @Test
    fun `un pericolo per la vita in italiano mostra i numeri del paese della regione`() = runTest {
        val card = provider(FakeEmergencyNumbersDao(mapOf("italia" to italy, "giappone" to japan)))
            .forQuestion("italia", "Mio padre non respira")

        assertEquals(
            listOf(
                EmergencyCallEntry(listOf(EmergencyLabel.GENERAL), "112"),
                EmergencyCallEntry(listOf(EmergencyLabel.POLICE), "113"),
                EmergencyCallEntry(listOf(EmergencyLabel.AMBULANCE), "118"),
                EmergencyCallEntry(listOf(EmergencyLabel.FIRE), "115"),
            ),
            card?.entries,
        )
    }

    @Test
    fun `un pericolo per la vita in inglese usa la regione della conversazione`() = runTest {
        val card = provider(FakeEmergencyNumbersDao(mapOf("italia" to italy, "giappone" to japan)))
            .forQuestion("giappone", "my friend is unconscious")

        assertEquals(
            listOf(
                EmergencyCallEntry(listOf(EmergencyLabel.POLICE), "110"),
                EmergencyCallEntry(listOf(EmergencyLabel.AMBULANCE, EmergencyLabel.FIRE), "119"),
            ),
            card?.entries,
        )
    }

    @Test
    fun `un numero unico uguale per tutti e' una riga sola`() = runTest {
        val card = provider(FakeEmergencyNumbersDao(mapOf("usa" to usa))).forQuestion("usa", "someone is choking")

        assertEquals(listOf(EmergencyCallEntry(listOf(EmergencyLabel.SINGLE), "911")), card?.entries)
    }

    @Test
    fun `una regione senza numeri mostra comunque il riquadro, vuoto`() = runTest {
        val card = provider(FakeEmergencyNumbersDao()).forQuestion("sconosciuta", "ha un infarto")

        assertTrue(card != null && card.entries?.isEmpty() == true)
    }

    @Test
    fun `un errore nella lettura dei numeri non toglie il riquadro`() = runTest {
        val card = provider(FakeEmergencyNumbersDao(failing = true)).forQuestion("italia", "emorragia")

        assertTrue(card != null && card.entries?.isEmpty() == true)
    }

    @Test
    fun `una catastrofe mostra le allerte senza i numeri`() = runTest {
        val dao = FakeEmergencyNumbersDao(mapOf("italia" to italy))
        val card = provider(dao).forQuestion("italia", "C'e' un'allerta per l'alluvione?")

        assertNull(card?.entries)
        assertTrue(card?.alerts != null)
        assertEquals(0, dao.lookups)
    }

    @Test
    fun `una catastrofe con un pericolo per la vita mostra numeri e allerte`() = runTest {
        val card = provider(FakeEmergencyNumbersDao(mapOf("italia" to italy))).forQuestion("italia", "Earthquake, my friend is unconscious")

        assertEquals(4, card?.entries?.size)
        assertTrue(card?.alerts != null)
    }

    @Test
    fun `senza allerte disponibili una catastrofe non mostra il riquadro`() = runTest {
        assertNull(provider(FakeEmergencyNumbersDao(), FakeAlertsRepository(result = null)).forQuestion("italia", "terremoto oggi?"))
    }

    @Test
    fun `le righe non dipendono dal generale quando manca`() {
        val entries = EmergencyNumbers(general = null, police = "110", ambulance = "110", fire = "110").callEntries()

        assertEquals(
            listOf(EmergencyCallEntry(listOf(EmergencyLabel.POLICE, EmergencyLabel.AMBULANCE, EmergencyLabel.FIRE), "110")),
            entries,
        )
    }
}
