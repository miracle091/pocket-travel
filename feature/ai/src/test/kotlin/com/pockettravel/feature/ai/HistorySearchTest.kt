package com.pockettravel.feature.ai

import com.pockettravel.core.data.ConversationRepository
import com.pockettravel.core.data.ConversationSummary
import com.pockettravel.core.data.ConversationTurn
import com.pockettravel.core.data.crypto.HistoryKeys
import com.pockettravel.core.data.crypto.HistoryReader
import com.pockettravel.core.data.db.ConversationDao
import com.pockettravel.core.data.db.ConversationEntity
import com.pockettravel.core.data.testing.FakeConversationDao
import com.pockettravel.core.data.testing.InMemorySharedPreferences
import com.pockettravel.core.data.testing.SoftwareHistoryMasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HistorySearchTest {
    private val keys = HistoryKeys(InMemorySharedPreferences(), SoftwareHistoryMasterKey())
    private val failure = IOException("disco")

    // Un deposito il cui elenco delle conversazioni non si legge.
    private fun TestScope.failingRepository(error: Exception) = ConversationRepository(
        object : ConversationDao by FakeConversationDao() {
            override suspend fun all(): List<ConversationEntity> = throw error
        },
        keys,
        StandardTestDispatcher(testScheduler),
    )

    // Un lettore valido: serve una conversazione salvata per sbloccare.
    private suspend fun TestScope.reader(): HistoryReader {
        val repository = ConversationRepository(FakeConversationDao(), keys, StandardTestDispatcher(testScheduler))
        repository.start(
            { ConversationSummary(title = "Titolo", regionId = "italia", regionName = "Italia") },
            ConversationTurn(askedQuestion = "D", question = "D", answer = "R", citations = emptyList(), modelId = "qwen", askedAt = 1L),
            now = 1L,
        )
        return keys.completeUnlock((repository.prepareUnlock() as HistoryKeys.Unlock.Ready).cipher)
    }

    @Test
    fun `una ricerca che non riesce non trova niente e lo dice a chi ascolta`() = runTest {
        val seen = mutableListOf<Exception>()

        val results = failingRepository(failure).searchOrNull(reader(), "treno") { seen += it }

        assertEquals(emptyList<Any>(), results)
        // Il ripristino delle tracce di coroutines può copiare l'eccezione: si confronta il tipo.
        assertEquals(listOf(IOException::class.java), seen.map { it.javaClass })
    }

    @Test
    fun `una ricerca vuota non cerca e non segnala`() = runTest {
        var reported = false

        assertNull(failingRepository(failure).searchOrNull(reader(), "  ") { reported = true })
        assertFalse(reported)
    }

    @Test
    fun `la cancellazione non si scambia per un errore di ricerca`() = runTest {
        var reported = false

        try {
            failingRepository(CancellationException("annullata")).searchOrNull(reader(), "treno") { reported = true }
            fail("la cancellazione doveva uscire")
        } catch (_: CancellationException) {
            assertFalse(reported)
        }
    }
}
