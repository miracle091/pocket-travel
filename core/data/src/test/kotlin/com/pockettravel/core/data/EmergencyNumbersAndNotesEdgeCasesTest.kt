package com.pockettravel.core.data

import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.EmergencyNumbersEntity
import com.pockettravel.core.data.db.NoCentralEmergencyNumberEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEmergencyNumbersDao : EmergencyNumbersDao {
    val numbers = mutableMapOf<String, EmergencyNumbersEntity>()
    val noCentral = mutableSetOf<String>()

    override suspend fun insertAll(numbers: List<EmergencyNumbersEntity>) {
        numbers.forEach { this.numbers[it.regionId] = it }
    }

    override suspend fun forRegion(regionId: String): EmergencyNumbersEntity? = numbers[regionId]

    override suspend fun deleteAll() = numbers.clear()

    override suspend fun insertNoCentralNumber(regions: List<NoCentralEmergencyNumberEntity>) {
        noCentral += regions.map { it.regionId }
    }

    override suspend fun hasNoCentralNumber(regionId: String): Boolean = regionId in noCentral

    override suspend fun deleteAllNoCentralNumber() = noCentral.clear()
}

/** Mappatura di EmergencyNumbersRepository e ordinamento puro delle note (rankNotesByQuery) ai suoi limiti. */
class EmergencyNumbersAndNotesEdgeCasesTest {

    // EmergencyNumbersRepository

    @Test
    fun `i numeri di una regione portano generale, polizia, ambulanza e pompieri`() = runBlocking {
        val dao = FakeEmergencyNumbersDao()
        dao.insertAll(listOf(EmergencyNumbersEntity("italia", "112", "113", "118", "115")))

        val numbers = EmergencyNumbersRepository(dao).forRegion("italia")

        assertEquals(EmergencyNumbers(general = "112", police = "113", ambulance = "118", fire = "115"), numbers)
    }

    @Test
    fun `il numero generale puo' mancare e gli altri restano`() = runBlocking {
        val dao = FakeEmergencyNumbersDao()
        dao.insertAll(listOf(EmergencyNumbersEntity("usa-like", null, "911", "911", "911")))

        val numbers = EmergencyNumbersRepository(dao).forRegion("usa-like")

        assertNull(numbers?.general)
        assertEquals("911", numbers?.police)
    }

    @Test
    fun `una regione senza numeri da' null`() = runBlocking {
        assertNull(EmergencyNumbersRepository(FakeEmergencyNumbersDao()).forRegion("italia"))
    }

    @Test
    fun `nessun numero centralizzato vale per la regione indicata e non per le altre`() = runBlocking {
        val dao = FakeEmergencyNumbersDao()
        dao.insertNoCentralNumber(listOf(NoCentralEmergencyNumberEntity("palestina")))
        val repository = EmergencyNumbersRepository(dao)

        assertTrue(repository.hasNoCentralNumber("palestina"))
        assertFalse(repository.hasNoCentralNumber("italia"))
    }

    // rankNotesByQuery

    private fun note(id: Long, title: String, body: String = "") = Note(id = id, title = title, body = body)

    @Test
    fun `un limite zero non da' note`() {
        assertEquals(emptyList<Note>(), rankNotesByQuery(listOf(note(1, "Volo Roma")), "volo roma", limit = 0))
    }

    @Test
    fun `un limite negativo non da' note`() {
        assertEquals(emptyList<Note>(), rankNotesByQuery(listOf(note(1, "Volo Roma")), "volo roma", limit = -1))
    }

    @Test
    fun `gli accenti non contano, ne' nella domanda ne' nella nota`() {
        val withAccent = note(1, "Perché il treno", "partenza")
        val withoutAccent = note(2, "Perche il bus")

        assertEquals(listOf(withAccent, withoutAccent), rankNotesByQuery(listOf(withAccent, withoutAccent), "perche", limit = 5))
        assertEquals(listOf(withAccent, withoutAccent), rankNotesByQuery(listOf(withAccent, withoutAccent), "PERCHÉ", limit = 5))
    }

    @Test
    fun `senza note o con una domanda vuota il risultato e' vuoto`() {
        assertEquals(emptyList<Note>(), rankNotesByQuery(emptyList(), "volo", limit = 5))
        assertEquals(emptyList<Note>(), rankNotesByQuery(listOf(note(1, "Volo Roma")), "", limit = 5))
        assertEquals(emptyList<Note>(), rankNotesByQuery(listOf(note(1, "Volo Roma")), "   \n\t", limit = 5))
    }

    @Test
    fun `le parole di una o due lettere non contano`() {
        val notes = listOf(note(1, "Hotel a Roma", "il treno di la"))

        assertEquals(emptyList<Note>(), rankNotesByQuery(notes, "a il di la", limit = 5))
        assertEquals(notes, rankNotesByQuery(notes, "a il di la roma", limit = 5))
    }

    @Test
    fun `maiuscole e punteggiatura non contano`() {
        val notes = listOf(note(1, "PRENOTAZIONE", "Hotel Roma, camera 12!"))

        assertEquals(notes, rankNotesByQuery(notes, "prenotazione?", limit = 5))
        assertEquals(notes, rankNotesByQuery(notes, "(ROMA)", limit = 5))
        assertEquals(notes, rankNotesByQuery(notes, "camera-12", limit = 5))
    }

    @Test
    fun `una parola ripetuta nella domanda o nella nota conta una volta sola`() {
        val repeated = note(1, "volo volo volo", "volo")
        val twoWords = note(2, "volo ritorno")

        val result = rankNotesByQuery(listOf(repeated, twoWords), "volo volo ritorno", limit = 5)

        assertEquals(listOf(twoWords, repeated), result)
    }

    @Test
    fun `a parita' di parole in comune restano nell'ordine di arrivo`() {
        val notes = listOf(note(3, "hotel"), note(1, "hotel"), note(2, "hotel"))

        assertEquals(listOf(3L, 1L, 2L), rankNotesByQuery(notes, "hotel", limit = 5).map { it.id })
    }

    @Test
    fun `il limite taglia dopo l'ordinamento e tiene le note migliori`() {
        val best = note(1, "volo ritorno roma")
        val middle = note(2, "volo ritorno")
        val worst = note(3, "volo")

        val result = rankNotesByQuery(listOf(worst, middle, best), "volo ritorno roma", limit = 2)

        assertEquals(listOf(best, middle), result)
    }

    @Test
    fun `lettere accentate e cifre sono parole`() {
        val notes = listOf(note(1, "Perché partire presto", "Sveglia alle 0530"))

        assertEquals(notes, rankNotesByQuery(notes, "perché", limit = 5))
        assertEquals(notes, rankNotesByQuery(notes, "0530", limit = 5))
        assertEquals(emptyList<Note>(), rankNotesByQuery(notes, "053", limit = 5))
    }

    @Test
    fun `alfabeti non latini si cercano con le maiuscole indifferenti`() {
        val notes = listOf(note(1, "Москва", "Гостиница Центральная"))

        assertEquals(notes, rankNotesByQuery(notes, "МОСКВА", limit = 5))
        assertEquals(notes, rankNotesByQuery(notes, "центральная", limit = 5))
    }
}
