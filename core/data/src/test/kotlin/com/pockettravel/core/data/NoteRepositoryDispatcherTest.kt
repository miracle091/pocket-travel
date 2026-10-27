package com.pockettravel.core.data

import com.pockettravel.core.data.crypto.TextCipher
import com.pockettravel.core.data.db.NoteDao
import com.pockettravel.core.data.db.NoteEntity
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** La cifratura delle note gira sul dispatcher IO iniettato, mai su quello di chi chiama o raccoglie. */
class NoteRepositoryDispatcherTest {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, IO_THREAD) }
    private val ioDispatcher = executor.asCoroutineDispatcher()
    private val cipher = ThreadRecordingCipher()
    private val dao = FakeNoteDao()
    private val repository = NoteRepository(dao, ioDispatcher, cipher)

    @After
    fun tearDown() = executor.shutdown()

    @Test
    fun `observeAll decifra sul dispatcher IO`() = runTest {
        dao.state.value = listOf(NoteEntity(id = 1, title = "t", body = "b", updatedAt = 0))

        val notes = repository.observeAll().first()

        assertEquals(listOf("t", "b"), notes.flatMap { listOf(it.title, it.body) })
        assertEquals(setOf(IO_THREAD), cipher.decryptThreads.toSet())
    }

    @Test
    fun `save cifra sul dispatcher IO`() = runTest {
        repository.save(Note(title = "t", body = "b"))

        assertEquals(listOf(IO_THREAD, IO_THREAD), cipher.encryptThreads)
    }

    @Test
    fun `search decifra sul dispatcher IO`() = runTest {
        dao.state.value = listOf(NoteEntity(id = 1, title = "volo", body = "diretto", updatedAt = 0))

        val result = repository.search("volo diretto", limit = 5)

        assertEquals(1, result.size)
        assertEquals(setOf(IO_THREAD), cipher.decryptThreads.toSet())
    }

    private class ThreadRecordingCipher : TextCipher {
        val encryptThreads = CopyOnWriteArrayList<String>()
        val decryptThreads = CopyOnWriteArrayList<String>()

        override fun encrypt(plainText: String): String {
            encryptThreads += threadName()
            return plainText
        }

        override fun decrypt(payload: String): String? {
            decryptThreads += threadName()
            return payload
        }
    }

    private class FakeNoteDao : NoteDao {
        val state = MutableStateFlow<List<NoteEntity>>(emptyList())

        override suspend fun upsert(note: NoteEntity): Long {
            state.value = state.value + note
            return note.id
        }

        override fun observeAll(): Flow<List<NoteEntity>> = state

        override suspend fun all(): List<NoteEntity> = state.value

        override suspend fun delete(id: Long) {
            state.value = state.value.filterNot { it.id == id }
        }
    }

    private companion object {
        // Con le asserzioni attive kotlinx.coroutines aggiunge " @coroutine#N" al nome del thread.
        fun threadName(): String = Thread.currentThread().name.substringBefore(" @")

        const val IO_THREAD = "notes-io"
    }
}
