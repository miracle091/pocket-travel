package com.pockettravel.feature.ai

import com.pockettravel.core.data.ConversationRepository
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.crypto.HistoryKeys
import com.pockettravel.core.data.crypto.HistoryReader
import com.pockettravel.core.data.testing.FakeConversationDao
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemorySharedPreferences
import com.pockettravel.core.data.testing.SoftwareHistoryMasterKey
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantConversationTest {
    private val dao = FakeConversationDao()
    private val keys = HistoryKeys(InMemorySharedPreferences(), SoftwareHistoryMasterKey())
    private val regions = FakeRegionRepository(listOf(region("portogallo", "Portogallo"), region("spagna", "Spagna")))
    private var biometrics = true
    private val monitor = ConversationMonitor()

    private fun region(id: String, name: String) = InstalledRegion(id, name, null, null, null, null, null, null, null, null, 0L)

    private fun TestScope.repository() = ConversationRepository(dao, keys, StandardTestDispatcher(testScheduler))

    private fun conversation(repository: ConversationRepository) =
        AssistantConversation(repository, regions, object : HistoryAvailability { override fun isAvailable() = biometrics }, monitor)

    private suspend fun ConversationRepository.unlock(): HistoryReader {
        val unlock = prepareUnlock()
        check(unlock is HistoryKeys.Unlock.Ready) { "atteso Ready, trovato $unlock" }
        return keys.completeUnlock(unlock.cipher)
    }

    private fun answer(question: String, cities: List<String> = emptyList(), destination: String? = null) =
        AssistantAnswer("Risposta", listOf("Guida › Trasporti"), showOfficialSourceBanner = false, question = question, cities = cities, destination = destination)

    private fun AssistantConversation.ask(
        mode: AiEngineMode,
        asked: String,
        resolved: String = asked,
        region: String = "portogallo",
        cities: List<String> = emptyList(),
        destination: String? = null,
    ) =
        onAnswered(mode, region, "qwen", asked, answer(resolved, cities, destination))

    @Test
    fun `con il motore Online non si salva niente ma il seguito resta in memoria`() = runTest {
        val repository = repository()
        val conversation = conversation(repository)
        conversation.ask(AiEngineMode.ONLINE, "Come arrivo a Lisbona?")
        conversation.ask(AiEngineMode.ONLINE, "E a Porto?", resolved = "Come arrivo a Porto?")
        conversation.end()
        testScheduler.advanceUntilIdle()

        assertTrue(dao.conversations.isEmpty())
        assertTrue(dao.turns.isEmpty())
        assertEquals(HistoryKeys.Unlock.Empty, repository.prepareUnlock())
    }

    @Test
    fun `con il motore Online la base del seguito e' la domanda unita`() = runTest {
        val conversation = conversation(repository())
        conversation.ask(AiEngineMode.ONLINE, "E a Porto?", resolved = "Come arrivo a Porto?")
        assertEquals("Come arrivo a Porto?", conversation.previousQuestion)
    }

    @Test
    fun `online il seguito usa solo una domanda fatta online`() = runTest {
        val repository = repository()
        val conversation = conversation(repository)
        conversation.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?")
        assertNull(conversation.previousQuestion(AiEngineMode.ONLINE))
        assertEquals("Come arrivo a Lisbona?", conversation.previousQuestion(AiEngineMode.ON_DEVICE))

        conversation.ask(AiEngineMode.ONLINE, "Serve il visto?")
        assertEquals("Serve il visto?", conversation.previousQuestion(AiEngineMode.ONLINE))
        conversation.end()
        testScheduler.advanceUntilIdle()

        // Ripresa dalla cronologia: le domande salvate restano sul telefono.
        val resumed = conversation(repository)
        checkNotNull(resumed.resume(repository.unlock(), repository.list(repository.unlock()).single().id))
        assertNull(resumed.previousQuestion(AiEngineMode.ONLINE))
        assertEquals("Come arrivo a Lisbona?", resumed.previousQuestion(AiEngineMode.ON_DEVICE))
    }

    @Test
    fun `una conversazione cominciata sul dispositivo e continuata Online non salva i turni Online`() = runTest {
        val repository = repository()
        val conversation = conversation(repository)
        conversation.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?")
        conversation.ask(AiEngineMode.ONLINE, "E a Porto?")
        testScheduler.advanceUntilIdle()

        assertEquals(1, dao.turns.size)
    }

    @Test
    fun `sul dispositivo i turni vanno nella stessa conversazione fino alla fine`() = runTest {
        val repository = repository()
        val conversation = conversation(repository)
        conversation.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?")
        conversation.ask(AiEngineMode.ON_DEVICE, "E a Porto?", resolved = "Come arrivo a Porto?")
        conversation.end()
        conversation.ask(AiEngineMode.ON_DEVICE, "Serve il visto?")
        testScheduler.advanceUntilIdle()

        assertEquals(2, dao.conversations.size)
        assertEquals(3, dao.turns.size)
        val first = repository.list(repository.unlock()).single { it.summary.title == "Come arrivo a Lisbona?" }
        assertEquals("Portogallo", first.summary.regionName)
        assertEquals(1, dao.conversations.getValue(first.id).archiveCount)
    }

    @Test
    fun `senza biometria forte non si salva niente`() = runTest {
        biometrics = false
        val conversation = conversation(repository())
        conversation.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?")
        testScheduler.advanceUntilIdle()

        assertTrue(dao.conversations.isEmpty())
        assertEquals("Come arrivo a Lisbona?", conversation.previousQuestion)
    }

    @Test
    fun `una domanda senza risposta toglie la base del seguito`() = runTest {
        val conversation = conversation(repository())
        conversation.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?")
        conversation.onFailed()
        assertNull(conversation.previousQuestion)
    }

    @Test
    fun `la ripresa rimette la base del seguito e la regione, e i turni dopo continuano la stessa conversazione`() = runTest {
        val repository = repository()
        val live = conversation(repository)
        live.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?")
        live.ask(AiEngineMode.ON_DEVICE, "E a Porto?", resolved = "Come arrivo a Porto?")
        val withoutResume = live.previousQuestion
        live.end()

        // Un'altra schermata (dopo il riavvio dell'app): la regione ripresa e' quella salvata con la conversazione.
        val resumed = conversation(repository)
        val id = repository.list(repository.unlock()).single().id
        val opened = checkNotNull(resumed.resume(repository.unlock(), id))
        assertEquals(listOf("Come arrivo a Lisbona?", "E a Porto?"), opened.turns.map { it.askedQuestion })
        assertEquals(withoutResume, resumed.previousQuestion)
        assertEquals("portogallo", resumed.regionId)

        resumed.ask(AiEngineMode.ON_DEVICE, "E a Faro?", region = "portogallo")
        testScheduler.advanceUntilIdle()
        assertEquals(1, dao.conversations.size)
        assertEquals(3, dao.turns.size)
        assertEquals(1, dao.conversations.getValue(id).resumeCount)
    }

    @Test
    fun `una conversazione di una regione non piu' installata si riapre in sola lettura`() = runTest {
        val repository = repository()
        val live = conversation(repository)
        live.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Madrid?", region = "spagna")
        live.end()
        testScheduler.advanceUntilIdle()
        regions.installedRegions.value = regions.installedRegions.value.filter { it.regionId != "spagna" }

        val resumed = conversation(repository)
        val id = repository.list(repository.unlock()).single().id
        val opened = checkNotNull(resumed.resume(repository.unlock(), id))
        assertEquals(listOf("Come arrivo a Madrid?"), opened.turns.map { it.askedQuestion })
        assertEquals("Spagna", resumed.readOnlyRegionName)
        assertNull(resumed.id)
        assertEquals(id, resumed.shownId)
        assertNull(resumed.regionId)
        assertNull(resumed.previousQuestion)

        // La conversazione dopo, nella regione della schermata, e' un'altra: quella salvata resta com'era.
        resumed.end()
        assertNull(resumed.readOnlyRegionName)
        assertNull(resumed.shownId)
        resumed.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?")
        testScheduler.advanceUntilIdle()
        assertEquals(2, dao.conversations.size)
        assertEquals(1, dao.turns.count { it.conversationId == id })
    }

    @Test
    fun `solo una conversazione ripresa dalla cronologia mostra contenuto salvato`() = runTest {
        val repository = repository()
        val live = conversation(repository)
        live.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?")
        assertEquals(false, live.showsSavedContent)
        live.end()
        testScheduler.advanceUntilIdle()

        val resumed = conversation(repository)
        checkNotNull(resumed.resume(repository.unlock(), repository.list(repository.unlock()).single().id))
        assertTrue(resumed.showsSavedContent)
        resumed.ask(AiEngineMode.ON_DEVICE, "E a Porto?")
        assertTrue(resumed.showsSavedContent)
        resumed.end()
        assertEquals(false, resumed.showsSavedContent)
    }

    @Test
    fun `anche la conversazione in sola lettura mostra contenuto salvato`() = runTest {
        val repository = repository()
        val live = conversation(repository)
        live.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Madrid?", region = "spagna")
        live.end()
        testScheduler.advanceUntilIdle()
        regions.installedRegions.value = regions.installedRegions.value.filter { it.regionId != "spagna" }

        val resumed = conversation(repository)
        checkNotNull(resumed.resume(repository.unlock(), repository.list(repository.unlock()).single().id))
        assertTrue(resumed.showsSavedContent)
        resumed.end()
        assertEquals(false, resumed.showsSavedContent)
    }

    @Test
    fun `lo stato dopo una ripresa e' lo stesso di quello senza ripresa`() = runTest {
        val repository = repository()
        val live = conversation(repository)
        live.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?", cities = listOf("Lisbona"))
        live.ask(AiEngineMode.ON_DEVICE, "Serve il visto per il Brasile?", destination = "Brasile")
        live.ask(AiEngineMode.ON_DEVICE, "E a Porto?", resolved = "Come arrivo a Porto?", cities = listOf("Porto"))
        val withoutResume = checkNotNull(live.state)
        live.end()

        val resumed = conversation(repository)
        checkNotNull(resumed.resume(repository.unlock(), repository.list(repository.unlock()).single().id))

        assertEquals(withoutResume, resumed.state)
        assertEquals(ConversationState("portogallo", listOf("Lisbona", "Porto"), "Brasile", "Come arrivo a Porto?"), resumed.state)
    }

    @Test
    fun `il task manager vede la conversazione aperta finche' non finisce`() = runTest {
        val repository = repository()
        val live = conversation(repository)
        live.ask(AiEngineMode.ON_DEVICE, "Come arrivo a Lisbona?")
        live.ask(AiEngineMode.ON_DEVICE, "E a Porto?")
        val info = checkNotNull(monitor.current.value)
        assertEquals(2, info.turns)
        assertTrue(info.saved)
        assertEquals("E a Porto?", info.state.lastQuestion)

        // Un'altra schermata dell'assistente (le Impostazioni) senza conversazione non la toglie.
        conversation(repository).end()
        assertEquals(info, monitor.current.value)

        live.end()
        assertNull(monitor.current.value)
        val resumed = conversation(repository)
        checkNotNull(resumed.resume(repository.unlock(), repository.list(repository.unlock()).single().id))
        val after = checkNotNull(monitor.current.value)
        assertEquals(2, after.turns)
        assertEquals(1, after.resumeCount)
        assertEquals(1, after.archiveCount)
    }

    @Test
    fun `con il motore Online il task manager dice che non si salva`() = runTest {
        val live = conversation(repository())
        live.ask(AiEngineMode.ONLINE, "Come arrivo a Lisbona?")
        assertEquals(false, monitor.current.value?.saved)
        live.end()
        assertNull(monitor.current.value)
    }
}
