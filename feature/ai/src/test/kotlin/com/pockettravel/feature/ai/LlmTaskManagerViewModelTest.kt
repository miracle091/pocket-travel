package com.pockettravel.feature.ai

import android.net.Uri
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeTaskMonitor : TaskMonitor {
    override val latest: StateFlow<LlmTaskSnapshot?> = MutableStateFlow(null)
    override suspend fun export(target: Uri): Boolean = true
}

private class FakeCpuBackendStore(
    private val available: List<String>,
    var saved: String? = null,
) : CpuBackendStore {
    override fun available(): List<String> = available
    override fun saved(): String? = saved
    override fun save(fileName: String?) {
        saved = fileName
    }
}

// Il disco e le preferenze passano dal dispatcher iniettato: un dispatcher di test li controlla come il resto.
@OptIn(ExperimentalCoroutinesApi::class)
class LlmTaskManagerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val files = InMemoryPreferencesFactory()
    private var restarts = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel(cpuBackends: CpuBackendStore = FakeCpuBackendStore(emptyList())) = LlmTaskManagerViewModel(
        monitor = FakeTaskMonitor(),
        wordListsOverride = WordListsOverride(files, isDebuggable = true),
        cpuBackends = cpuBackends,
        appRestarter = AppRestarter { restarts++ },
        conversationMonitor = ConversationMonitor(),
        ioDispatcher = dispatcher,
    )

    @Test
    fun `le varianti CPU si leggono sul dispatcher iniettato`() = runTest(dispatcher) {
        val viewModel = newViewModel(FakeCpuBackendStore(listOf("libggml-cpu-a.so", "libggml-cpu-b.so"), saved = "libggml-cpu-b.so"))
        assertEquals(emptyList<String>(), viewModel.availableCpuBackends.value)

        advanceUntilIdle()

        assertEquals(listOf("libggml-cpu-a.so", "libggml-cpu-b.so"), viewModel.availableCpuBackends.value)
        assertEquals("libggml-cpu-b.so", viewModel.chosenCpuBackend.value)
    }

    @Test
    fun `una variante scelta e tolta dall'APK vale come automatica`() = runTest(dispatcher) {
        val viewModel = newViewModel(FakeCpuBackendStore(listOf("libggml-cpu-a.so"), saved = "libggml-cpu-vecchia.so"))
        advanceUntilIdle()

        assertNull(viewModel.chosenCpuBackend.value)
    }

    @Test
    fun `scegliere una variante la salva e la mostra`() = runTest(dispatcher) {
        val store = FakeCpuBackendStore(listOf("libggml-cpu-a.so"))
        val viewModel = newViewModel(store)
        advanceUntilIdle()

        viewModel.chooseCpuBackend("libggml-cpu-a.so")
        advanceUntilIdle()

        assertEquals("libggml-cpu-a.so", store.saved)
        assertEquals("libggml-cpu-a.so", viewModel.chosenCpuBackend.value)
    }

    @Test
    fun `spegnere le liste di parole le salva e le mostra spente`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.wordLists.value)

        viewModel.setWordLists(false)
        advanceUntilIdle()

        assertFalse(viewModel.wordLists.value)
        assertFalse(WordListsOverride(files, isDebuggable = true).enabled())
    }

    @Test
    fun `le liste spente in una sessione precedente restano spente`() = runTest(dispatcher) {
        WordListsOverride(files, isDebuggable = true).save(false)
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.wordLists.value)
    }

    @Test
    fun `riavviare l'app passa dal riavviatore`() {
        newViewModel().restartApp()

        assertEquals(1, restarts)
    }
}
