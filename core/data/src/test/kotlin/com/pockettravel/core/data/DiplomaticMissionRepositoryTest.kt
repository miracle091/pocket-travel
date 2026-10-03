package com.pockettravel.core.data

import com.pockettravel.core.data.db.DiplomaticMissionDao
import com.pockettravel.core.data.db.DiplomaticMissionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DiplomaticMissionDao e' un'interfaccia Room senza logica propria: un fake in-memory basta. */
private class FakeDiplomaticMissionDao(private val rows: List<DiplomaticMissionEntity>) : DiplomaticMissionDao {
    val queries = mutableListOf<Pair<String, String>>()

    override suspend fun insertAll(missions: List<DiplomaticMissionEntity>) = Unit

    override suspend fun missions(sending: String, host: String): List<DiplomaticMissionEntity> {
        queries += sending to host
        return rows.filter { it.sending == sending && it.host == host }
    }

    override suspend fun deleteAll() = Unit
}

class DiplomaticMissionRepositoryTest {

    private fun entity(id: String, kind: String, sending: String = "it", host: String = "es") = DiplomaticMissionEntity(
        wikidata = id, sending = sending, host = host, kind = kind, name = "Nome $id", nameEn = null, city = null,
        address = null, phone = null, website = null, email = null, lat = null, lon = null,
    )

    @Test
    fun `i paesi si cercano in minuscolo qualunque sia il maiuscolo passato`() = runBlocking {
        val dao = FakeDiplomaticMissionDao(listOf(entity("Q1", "embassy")))
        val result = DiplomaticMissionRepository(dao).missions("IT", "Es")
        assertEquals(listOf("it" to "es"), dao.queries)
        assertEquals(listOf("Q1"), result.map { it.wikidata })
    }

    @Test
    fun `i tipi noti si mappano e uno sconosciuto vale come consolato`() = runBlocking {
        val dao = FakeDiplomaticMissionDao(
            listOf(
                entity("Q1", "embassy"),
                entity("Q2", "consulate_general"),
                entity("Q3", "consulate"),
                entity("Q4", "honorary_consulate"),
                entity("Q5", "EMBASSY"),
                entity("Q6", ""),
            ),
        )
        val kinds = DiplomaticMissionRepository(dao).missions("it", "es").associate { it.wikidata to it.kind }
        assertEquals(MissionKind.EMBASSY, kinds["Q1"])
        assertEquals(MissionKind.CONSULATE_GENERAL, kinds["Q2"])
        assertEquals(MissionKind.CONSULATE, kinds["Q3"])
        // Un tipo che questa build non conosce (pipeline piu' recente) e' il piu' generico, anche se cambia solo il maiuscolo.
        assertEquals(MissionKind.CONSULATE, kinds["Q4"])
        assertEquals(MissionKind.CONSULATE, kinds["Q5"])
        assertEquals(MissionKind.CONSULATE, kinds["Q6"])
    }

    @Test
    fun `senza righe per la coppia di paesi il risultato e' vuoto`() = runBlocking {
        val dao = FakeDiplomaticMissionDao(listOf(entity("Q1", "embassy", sending = "fr")))
        assertEquals(emptyList<DiplomaticMission>(), DiplomaticMissionRepository(dao).missions("it", "es"))
    }

    @Test
    fun `i campi facoltativi nulli restano nulli e la posizione si conserva`() = runBlocking {
        val row = entity("Q1", "embassy").copy(lat = 40.4, lon = -3.7, phone = "+34 91 000 00 00")
        val mission = DiplomaticMissionRepository(FakeDiplomaticMissionDao(listOf(row))).missions("it", "es").single()
        assertEquals(40.4, mission.latitude!!, 0.0)
        assertEquals(-3.7, mission.longitude!!, 0.0)
        assertEquals("+34 91 000 00 00", mission.phone)
        assertNull(mission.email)
        assertNull(mission.nameEn)
    }
}
