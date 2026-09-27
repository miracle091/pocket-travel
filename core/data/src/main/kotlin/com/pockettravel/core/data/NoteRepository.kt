package com.pockettravel.core.data

import com.pockettravel.core.data.crypto.KeystoreCipher
import com.pockettravel.core.data.db.NoteDao
import com.pockettravel.core.data.db.NoteEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Note personali dell'utente (prenotazioni, indirizzi, itinerario): title e body sono cifrati con
 * AES-256-GCM e una chiave Android Keystore non esportabile (KeystoreCipher, come la chiave API di
 * AiSettingsStore), senza autenticazione biometrica — TravelAssistant le legge senza chiedere
 * l'impronta. Una nota che non decifra piu' (chiave persa/invalidata) viene saltata invece di far
 * fallire l'intera lista.
 */
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
) {
    private val cipher = KeystoreCipher(keyAlias = KEY_ALIAS)

    fun observeAll(): Flow<List<Note>> =
        noteDao.observeAll().map { entities -> entities.mapNotNull { it.toDomainOrNull() } }

    /** [note].id 0 = nuova nota; un id gia' esistente la sostituisce. Ritorna l'id salvato. */
    suspend fun save(note: Note): Long = noteDao.upsert(
        NoteEntity(
            id = note.id,
            title = cipher.encrypt(note.title),
            body = cipher.encrypt(note.body),
            updatedAt = System.currentTimeMillis(),
        ),
    )

    suspend fun delete(id: Long) = noteDao.delete(id)

    /** Decifra in memoria e ordina per parole in comune con [query] (tokenizzazione come buildFtsQuery: minuscolo, non alfanumerico scartato). */
    suspend fun search(query: String, limit: Int): List<Note> =
        rankNotesByQuery(noteDao.all().mapNotNull { it.toDomainOrNull() }, query, limit)

    private fun NoteEntity.toDomainOrNull(): Note? {
        val decryptedTitle = cipher.decrypt(title) ?: return null
        val decryptedBody = cipher.decrypt(body) ?: return null
        return Note(id = id, title = decryptedTitle, body = decryptedBody, updatedAt = updatedAt)
    }

    private companion object {
        const val KEY_ALIAS = "pocket_travel_notes_v1"
    }
}

data class Note(
    val id: Long = 0,
    val title: String,
    val body: String,
    val updatedAt: Long = 0,
)

/**
 * Ordina [notes] per numero di parole in comune (titolo + corpo) con [query], scartando quelle
 * senza nessuna parola condivisa. Separata da NoteRepository.search perche' e' pura (nessuna
 * cifratura): testabile senza Android Keystore, a differenza del resto della classe.
 */
internal fun rankNotesByQuery(notes: List<Note>, query: String, limit: Int): List<Note> {
    val queryWords = tokenize(query)
    if (queryWords.isEmpty()) return emptyList()
    return notes
        .map { note -> note to tokenize("${note.title} ${note.body}").intersect(queryWords).size }
        .filter { (_, shared) -> shared > 0 }
        .sortedByDescending { (_, shared) -> shared }
        .take(limit)
        .map { (note, _) -> note }
}

private fun tokenize(text: String): Set<String> =
    text.split(Regex("[^\\p{L}\\p{N}]+"))
        .map { it.lowercase() }
        .filter { it.length >= 3 }
        .toSet()
