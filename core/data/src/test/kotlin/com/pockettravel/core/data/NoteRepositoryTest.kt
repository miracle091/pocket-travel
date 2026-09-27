package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rankNotesByQuery e' pura (nessuna cifratura): testabile senza Android Keystore, a differenza del
 * resto di NoteRepository (cifra/decifra con KeystoreCipher, verificato solo via androidTest, come
 * AiSettingsStoreDeviceTest).
 */
class NoteRepositoryTest {

    private fun note(id: Long, title: String, body: String) = Note(id = id, title = title, body = body, updatedAt = 0)

    @Test
    fun `ordina per numero di parole condivise con la domanda`() {
        val voloNote = note(1, "Volo di ritorno", "Decolla martedi alle 18 da Fiumicino")
        val hotelNote = note(2, "Hotel Roma centro", "Check-in dalle 14, indirizzo via Nazionale 10")

        val result = rankNotesByQuery(listOf(voloNote, hotelNote), "a che ora parte il volo di ritorno?", limit = 5)

        assertEquals(listOf(voloNote), result)
    }

    @Test
    fun `scarta le note senza nessuna parola in comune`() {
        val note = note(1, "Hotel Roma centro", "Check-in dalle 14")

        val result = rankNotesByQuery(listOf(note), "che tempo fa domani", limit = 5)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `rispetta il limite anche con piu' note pertinenti`() {
        val notes = listOf(
            note(1, "Volo andata", "Partenza mattina volo diretto"),
            note(2, "Volo ritorno", "Partenza sera volo diretto"),
            note(3, "Hotel", "Nessuna parola del volo qui dentro"),
        )

        val result = rankNotesByQuery(notes, "a che ora e' il volo diretto?", limit = 1)

        assertEquals(1, result.size)
    }

    @Test
    fun `una domanda senza parole utili non fa scattare nessuna nota`() {
        val result = rankNotesByQuery(listOf(note(1, "Volo", "diretto")), "e a e", limit = 5)

        assertTrue(result.isEmpty())
    }
}
