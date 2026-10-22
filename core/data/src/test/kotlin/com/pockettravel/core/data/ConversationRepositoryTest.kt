package com.pockettravel.core.data

import com.pockettravel.core.data.crypto.HistoryKeys
import com.pockettravel.core.data.crypto.HistoryReader
import com.pockettravel.core.data.crypto.HistoryUnreadableException
import com.pockettravel.core.data.testing.FakeConversationDao
import com.pockettravel.core.data.testing.InMemorySharedPreferences
import com.pockettravel.core.data.testing.SoftwareHistoryMasterKey
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ConversationRepositoryTest {
    private val dao = FakeConversationDao()
    private val masterKey = SoftwareHistoryMasterKey()
    private val prefs = InMemorySharedPreferences()
    private val keys = HistoryKeys(prefs, masterKey)

    private val writeErrors = mutableListOf<Throwable>()

    private fun TestScope.repository() =
        ConversationRepository(dao, keys, StandardTestDispatcher(testScheduler)).also { it.onWriteError = writeErrors::add }

    private suspend fun ConversationRepository.unlock(): HistoryReader {
        val unlock = prepareUnlock()
        check(unlock is HistoryKeys.Unlock.Ready) { "atteso Ready, trovato $unlock" }
        return keys.completeUnlock(unlock.cipher)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IllegalStateException) {
            return
        }
        throw AssertionError("atteso un errore")
    }

    private val summary = ConversationSummary(title = "Serve il visto?", regionId = "italia", regionName = "Italia")

    private fun turn(asked: String, answer: String = "Risposta a $asked", at: Long = 1L) =
        ConversationTurn(askedQuestion = asked, question = asked, answer = answer, citations = listOf("Guida › Visti"), modelId = "qwen", askedAt = at)

    @Test
    fun `una conversazione salvata si rilegge solo dopo lo sblocco, con i turni in ordine`() = runTest {
        val repository = repository()
        val handle = repository.start({ summary }, turn("Serve il visto?"), now = 10)
        repository.append(handle, turn("E per il Nepal?"), now = 20)

        val reader = repository.unlock()
        val listed = repository.list(reader).single()
        assertEquals(summary, listed.summary)
        assertEquals(10L, listed.createdAt)
        assertEquals(20L, listed.updatedAt)

        val resumed = checkNotNull(repository.resume(reader, handle.id, now = 30))
        assertEquals(listOf("Serve il visto?", "E per il Nepal?"), resumed.turns.map { it.askedQuestion })
        assertEquals("Risposta a E per il Nepal?", resumed.turns.last().answer)
        assertEquals(listOf("Guida › Visti"), resumed.turns.last().citations)
    }

    @Test
    fun `nel database non c'e' testo in chiaro`() = runTest {
        val repository = repository()
        repository.start({ summary }, turn("Serve il visto per San Marino?", answer = "No, non serve"))
        repository.unlock().let { repository.list(it) }

        val stored = dao.conversations.values.joinToString { it.encryptedSummary + it.wrappedKey } + dao.turns.joinToString { it.encryptedPayload }
        listOf("visto", "San Marino", "Italia", "italia", "non serve", "qwen").forEach { assertFalse("$it in chiaro", it in stored) }
    }

    @Test
    fun `senza conversazioni salvate non c'e' niente da sbloccare`() = runTest {
        assertEquals(HistoryKeys.Unlock.Empty, repository().prepareUnlock())
    }

    @Test
    fun `una ripresa continua la stessa conversazione e si conta`() = runTest {
        val repository = repository()
        val handle = repository.start({ summary }, turn("Serve il visto?"))
        repository.archive(handle)
        val resumed = checkNotNull(repository.resume(repository.unlock(), handle.id, now = 50))
        repository.append(resumed.handle, turn("E il passaporto?"), now = 60)
        repository.archive(resumed.handle)

        val second = checkNotNull(repository.resume(repository.unlock(), handle.id, now = 70))
        assertEquals(2, second.turns.size)
        assertEquals(2, second.resumeCount)
        assertEquals(2, second.archiveCount)
        val entity = dao.conversations.getValue(handle.id)
        assertEquals(2, entity.archiveCount)
        assertEquals(70L, entity.lastResumedAt)
        assertEquals(1, dao.conversations.size)
    }

    @Test
    fun `la cancellazione singola toglie solo quella conversazione con i suoi turni`() = runTest {
        val repository = repository()
        val first = repository.start({ summary }, turn("Prima"))
        val second = repository.start({ summary.copy(title = "Seconda") }, turn("Seconda"))
        repository.append(first, turn("Seguito"))

        repository.delete(first.id)

        val reader = repository.unlock()
        assertEquals(listOf(second.id), repository.list(reader).map { it.id })
        assertTrue(dao.turns.all { it.conversationId == second.id })
        assertNull(repository.resume(reader, first.id))
    }

    @Test
    fun `una cancellazione singola non riuscita lancia e la conversazione resta`() = runTest {
        val repository = repository()
        val handle = repository.start({ summary }, turn("Prima"))
        dao.failDeletes = true

        assertFails { repository.delete(handle.id) }
        assertEquals(setOf(handle.id), dao.conversations.keys)

        dao.failDeletes = false
        repository.delete(handle.id)
        assertTrue(dao.conversations.isEmpty())
    }

    @Test
    fun `una cancellazione globale non riuscita lancia ma le chiavi si dimenticano`() = runTest {
        val repository = repository()
        repository.start({ summary }, turn("Prima"))
        repository.list(repository.unlock())
        val resets = masterKey.deleted
        dao.failDeletes = true

        assertFails { repository.deleteAll() }

        assertEquals(resets + 1, masterKey.deleted)
    }

    @Test
    fun `un turno di una conversazione cancellata non la ricrea`() = runTest {
        val repository = repository()
        val handle = repository.start({ summary }, turn("Prima"))
        repository.delete(handle.id)
        repository.append(handle, turn("Dopo la cancellazione"))
        repository.archive(handle)

        assertTrue(dao.conversations.isEmpty())
        assertTrue(dao.turns.isEmpty())
    }

    @Test
    fun `la cancellazione globale toglie conversazioni e chiavi, e la cronologia riparte vuota`() = runTest {
        val repository = repository()
        repository.start({ summary }, turn("Prima"))
        repository.start({ summary }, turn("Seconda"))
        repository.list(repository.unlock())
        val resets = masterKey.deleted

        repository.deleteAll()

        assertTrue(dao.conversations.isEmpty())
        assertTrue(dao.turns.isEmpty())
        assertEquals(resets + 1, masterKey.deleted)
        assertEquals(HistoryKeys.Unlock.Empty, repository.prepareUnlock())

        val after = repository.start({ summary }, turn("Nuova"))
        assertEquals(listOf(after.id), repository.list(repository.unlock()).map { it.id })
    }

    @Test
    fun `una conversazione avvolta con chiavi di un'altra cronologia si salta`() = runTest {
        val repository = repository()
        repository.start({ summary }, turn("Mia"))
        repository.list(repository.unlock())
        val other = HistoryKeys(InMemorySharedPreferences(), SoftwareHistoryMasterKey())
        dao.insertConversation(
            dao.conversations.values.single().copy(id = "estranea", wrappedKey = other.wrap(other.newConversationKey())),
        )

        val reader = repository.unlock()
        assertEquals(1, repository.list(reader).size)
        assertNull(repository.resume(reader, "estranea"))
    }

    @Test
    fun `con la chiave del Keystore invalidata le conversazioni nuove non si salvano e la cronologia lo dice`() = runTest {
        val repository = repository()
        repository.start({ summary }, turn("Prima"))
        repository.list(repository.unlock())
        masterKey.invalidated = true

        repository.start({ summary }, turn("Dopo le impronte nuove"))

        assertEquals(HistoryKeys.Unlock.Invalidated, repository.prepareUnlock())
        assertEquals(1, dao.conversations.size)
        assertEquals(1, writeErrors.size)
    }

    @Test
    fun `una chiave pubblica rovinata o un segreto di lunghezza sbagliata rendono la cronologia illeggibile`() = runTest {
        val repository = repository()
        repository.start({ summary }, turn("Prima"))
        repository.list(repository.unlock())
        val publicKey = prefs.getString("public_key", null)

        prefs.edit().putString("public_key", "AAAA").commit()
        val corruptPublic = repository.prepareUnlock()
        check(corruptPublic is HistoryKeys.Unlock.Ready)
        assertThrows(HistoryUnreadableException::class.java) { keys.completeUnlock(corruptPublic.cipher) }

        prefs.edit().putString("public_key", publicKey).putString("wrapped_secret", Base64.getEncoder().encodeToString(ByteArray(300))).commit()
        val corruptSecret = repository.prepareUnlock()
        check(corruptSecret is HistoryKeys.Unlock.Ready)
        assertThrows(HistoryUnreadableException::class.java) { keys.completeUnlock(corruptSecret.cipher) }
    }

    @Test
    fun `una chiave pubblica sostituita rende la cronologia illeggibile invece di aprirla`() = runTest {
        val repository = repository()
        repository.start({ summary }, turn("Prima"))
        repository.list(repository.unlock())
        val other = InMemorySharedPreferences()
        HistoryKeys(other, SoftwareHistoryMasterKey()).wrap(ByteArray(32))
        prefs.edit().putString("public_key", other.getString("public_key", null)).commit()

        val unlock = repository.prepareUnlock()
        check(unlock is HistoryKeys.Unlock.Ready)
        assertThrows(HistoryUnreadableException::class.java) { keys.completeUnlock(unlock.cipher) }
    }

    @Test
    fun `una ripresa dopo la chiusura forzata dell'app conta anche l'archiviazione mancata`() = runTest {
        val repository = repository()
        val handle = repository.start({ summary }, turn("Prima"))

        val resumed = checkNotNull(repository.resume(repository.unlock(), handle.id))

        assertEquals(1, resumed.archiveCount)
        assertEquals(1, dao.conversations.getValue(handle.id).archiveCount)
    }

    @Test
    fun `le conversazioni non usate da piu' dei giorni della cronologia si cancellano da sole, le altre restano`() = runTest {
        val repository = repository()
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val old = repository.start({ summary }, turn("Vecchia"), now = now - ConversationRepository.RETENTION_DAYS * day - 1)
        val limit = repository.start({ summary }, turn("Al limite"), now = now - ConversationRepository.RETENTION_DAYS * day)
        // Cominciata prima del limite ma continuata dopo: conta l'ultimo turno.
        val continued = repository.start({ summary }, turn("Continuata"), now = now - 60 * day)
        repository.append(continued, turn("Seguito"), now = now - day)

        repository.deleteExpired(now)

        val left = repository.list(repository.unlock()).map { it.id }.toSet()
        assertEquals(setOf(limit.id, continued.id), left)
        assertTrue(dao.turns.none { it.conversationId == old.id })
    }
}
