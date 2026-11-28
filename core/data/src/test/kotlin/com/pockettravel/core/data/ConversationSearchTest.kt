package com.pockettravel.core.data

import com.pockettravel.core.data.crypto.HistoryKeys
import com.pockettravel.core.data.crypto.HistoryReader
import com.pockettravel.core.data.testing.FakeConversationDao
import com.pockettravel.core.data.testing.InMemorySharedPreferences
import com.pockettravel.core.data.testing.SoftwareHistoryMasterKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {
    private val dao = FakeConversationDao()
    private val masterKey = SoftwareHistoryMasterKey()
    private val keys = HistoryKeys(InMemorySharedPreferences(), masterKey)

    private fun TestScope.repository() =
        ConversationRepository(dao, keys, StandardTestDispatcher(testScheduler)).also { it.onWriteError = { error -> throw error } }

    private suspend fun ConversationRepository.unlock(): HistoryReader {
        val unlock = prepareUnlock()
        check(unlock is HistoryKeys.Unlock.Ready) { "atteso Ready, trovato $unlock" }
        return keys.completeUnlock(unlock.cipher)
    }

    private fun summary(title: String) = ConversationSummary(title = title, regionId = "italia", regionName = "Italia")

    private fun turn(asked: String, answer: String, citations: List<String> = emptyList(), at: Long = 1L) =
        ConversationTurn(askedQuestion = asked, question = asked, answer = answer, citations = citations, modelId = "qwen", askedAt = at)

    private suspend fun ConversationRepository.found(reader: HistoryReader, query: String) = search(reader, query).map { it.conversation.summary.title }

    @Test
    fun `trova nelle risposte, nei titoli e nelle domande ma non nelle fonti`() = runTest {
        val repository = repository()
        repository.start({ summary("Titolo uno") }, turn("Domanda uno", "Risposta con zanzariera", citations = listOf("Guida › Fonteunica")), now = 10)
        repository.start({ summary("Titolo con scooter") }, turn("Domanda due", "Risposta due"), now = 20)
        repository.start({ summary("Titolo tre") }, turn("Domanda sul rimborso", "Risposta tre"), now = 30)
        val reader = repository.unlock()

        assertEquals(listOf("Titolo uno"), repository.found(reader, "zanzar"))
        assertEquals(listOf("Titolo con scooter"), repository.found(reader, "scoot"))
        assertEquals(listOf("Titolo tre"), repository.found(reader, "rimborso"))
        assertEquals(emptyList<String>(), repository.found(reader, "fonteunica"))
        assertEquals(emptyList<String>(), repository.found(reader, "  "))
        val byField = listOf("zanzariera", "scooter", "rimborso").map { repository.search(reader, it).single().field }
        assertEquals(listOf(SearchField.ANSWER, SearchField.TITLE, SearchField.QUESTION), byField)
    }

    @Test
    fun `tutte le parole devono esserci, ognuna come inizio di parola, senza accenti ne' maiuscole`() = runTest {
        val repository = repository()
        repository.start({ summary("Viaggio") }, turn("Dove?", "L'Hotel Centrale della Città è aperto"), now = 10)
        val reader = repository.unlock()

        assertEquals(listOf("Viaggio"), repository.found(reader, "HOTEL citta cen"))
        assertEquals(listOf("Viaggio"), repository.found(reader, "l'hot"))
        assertEquals(emptyList<String>(), repository.found(reader, "hotel roma"))
        assertEquals(emptyList<String>(), repository.found(reader, "otel"))
    }

    @Test
    fun `prima i risultati nelle risposte, poi titoli e domande, dentro ogni gruppo le parole trovate e la piu' recente`() = runTest {
        val repository = repository()
        repository.start({ summary("Solo titolo recente: treno") }, turn("Domanda", "Altro"), now = 100)
        repository.start({ summary("Risposta vecchia") }, turn("Domanda", "Il treno parte"), now = 10)
        repository.start({ summary("Risposta nuova") }, turn("Domanda", "Il treno parte"), now = 50)
        repository.start({ summary("Risposta con due") }, turn("Domanda", "Treno e treno regionale"), now = 5)
        repository.start({ summary("Domanda") }, turn("Che treno?", "Altro"), now = 200)
        val reader = repository.unlock()

        assertEquals(
            listOf("Risposta con due", "Risposta nuova", "Risposta vecchia", "Domanda", "Solo titolo recente: treno"),
            repository.found(reader, "tren"),
        )
    }

    @Test
    fun `l'estratto ha circa 40 caratteri per parte e le parole trovate evidenziate nell'originale`() = runTest {
        val repository = repository()
        val answer = "Prima parte molto lunga che precede il punto cercato, poi l'Hôtel Centrale offre camere per tutti i viaggiatori della zona."
        repository.start({ summary("Titolo") }, turn("Domanda", answer), now = 10)
        val result = repository.search(repository.unlock(), "hotel").single()

        val excerpt = result.excerpt
        assertTrue(excerpt.text.startsWith("…") && excerpt.text.endsWith("…"))
        assertTrue(excerpt.text.length < 100)
        assertEquals(listOf("Hôtel"), excerpt.highlights.map { excerpt.text.substring(it.first, it.last + 1) })
        assertEquals(0, result.turnIndex)
    }

    @Test
    fun `una conversazione cancellata esce dai risultati, singola o globale`() = runTest {
        val repository = repository()
        val first = repository.start({ summary("Prima") }, turn("D", "Visto per il Nepal"), now = 10)
        repository.start({ summary("Seconda") }, turn("D", "Visto per il Peru'"), now = 20)
        val reader = repository.unlock()
        assertEquals(listOf("Seconda", "Prima"), repository.found(reader, "visto"))

        repository.delete(first.id)
        assertEquals(listOf("Seconda"), repository.found(reader, "visto"))

        repository.deleteAll()
        repository.start({ summary("Nuova") }, turn("D", "Altro visto"), now = 30)
        assertEquals(listOf("Nuova"), repository.found(repository.unlock(), "visto"))
    }

    @Test
    fun `una scrittura in coda e un ultimo turno sostituito si vedono alla ricerca dopo`() = runTest {
        val repository = repository()
        val handle = repository.start({ summary("Meteo") }, turn("Che tempo fa?", "Sereno"), now = 10)
        val reader = repository.unlock()
        assertEquals(listOf("Meteo"), repository.found(reader, "sereno"))

        // Con lo stesso updatedAt: l'indice non puo' accorgersene dalla data.
        repository.append(handle, turn("E domani?", "Nuvoloso"), now = 20)
        assertEquals(listOf("Meteo"), repository.found(reader, "nuvoloso"))
        repository.replaceLast(handle, turn("E domani?", "Pioggia"), now = 20)

        assertEquals(emptyList<String>(), repository.found(reader, "nuvoloso"))
        assertEquals(listOf("Meteo"), repository.found(reader, "pioggia"))
        assertEquals(1, repository.search(reader, "pioggia").single().turnIndex)
        assertEquals(listOf("Meteo"), repository.found(reader, "sereno"))
    }

    @Test
    fun `una conversazione con la chiave di un'altra cronologia non compare`() = runTest {
        val repository = repository()
        repository.start({ summary("Mia") }, turn("D", "Testo"), now = 10)
        val reader = repository.unlock()
        val other = HistoryKeys(InMemorySharedPreferences(), SoftwareHistoryMasterKey())
        dao.insertConversationWithTurn(
            dao.conversations.values.single().copy(id = "estranea", wrappedKey = other.wrap(other.newConversationKey())),
            dao.turns.first().copy(id = 0, conversationId = "estranea"),
        )

        assertEquals(listOf("Mia"), repository.found(reader, "testo"))
    }

    @Test
    fun `chiusa la ricerca l'indice si ricostruisce con lo stesso lettore`() = runTest {
        val repository = repository()
        repository.start({ summary("Meteo") }, turn("D", "Sereno"), now = 10)
        val reader = repository.unlock()
        assertEquals(listOf("Meteo"), repository.found(reader, "sereno"))

        repository.closeSearch()

        assertEquals(listOf("Meteo"), repository.found(reader, "sereno"))
    }

    @Test
    fun `una ricerca cominciata prima della chiusura non ricrea l'indice, una dopo si`() = runTest {
        val repository = repository()
        repository.start({ summary("Meteo") }, turn("D", "Sereno"), now = 10)
        val reader = repository.unlock()
        // UNDISPATCHED: la ricerca parte subito e aspetta il dispatcher, la chiusura arriva prima del suo lavoro.
        val late = async(start = CoroutineStart.UNDISPATCHED) { repository.found(reader, "sereno") }

        repository.closeSearch()

        assertEquals(emptyList<String>(), late.await())
        assertEquals(listOf("Meteo"), repository.found(reader, "sereno"))
    }
}
