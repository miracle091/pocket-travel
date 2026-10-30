package com.pockettravel.feature.ai

import android.content.ComponentCallbacks2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_*: deprecati da Android 14, arrivano ancora prima
class ModelIdleReleaserTest {
    private val coordinator = AiModelCoordinator()
    private var releases = 0

    private fun TestScope.releaser() = ModelIdleReleaser(backgroundScope, coordinator) { releases++ }

    private suspend fun ModelIdleReleaser.use(block: suspend () -> Unit = {}) = withModelUse(block)

    @Test
    fun releasesAfterTheIdleTime() = runTest {
        val releaser = releaser()
        releaser.use()
        advanceTimeBy(ModelIdleReleaser.IDLE_MILLIS - 1)
        runCurrent()
        assertEquals(0, releases)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun aNewUseRestartsTheIdleTime() = runTest {
        val releaser = releaser()
        releaser.use()
        advanceTimeBy(ModelIdleReleaser.IDLE_MILLIS - 1_000)
        releaser.use()
        advanceTimeBy(ModelIdleReleaser.IDLE_MILLIS - 1_000)
        runCurrent()
        assertEquals(0, releases)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun noReleaseWhenAUseStartsWhileWaitingForTheLock() = runTest {
        val releaser = releaser()
        releaser.use()
        // Allo scadere del tempo il lock e' preso da un'operazione che non e' un uso del modello (download),
        // e una domanda parte mentre il rilascio aspetta il lock: il modello resta.
        val download = CompletableDeferred<Unit>()
        launch { coordinator.withModelLock { download.await() } }
        advanceTimeBy(ModelIdleReleaser.IDLE_MILLIS + 1)
        runCurrent()
        val question = launch { releaser.use() }
        download.complete(Unit)
        question.join()
        runCurrent()
        assertEquals(0, releases)
        advanceTimeBy(ModelIdleReleaser.IDLE_MILLIS + 1)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun appInBackgroundReleasesRightAfterTheAnswerInProgress() = runTest {
        val releaser = releaser()
        val answer = CompletableDeferred<Unit>()
        val generation = launch { releaser.use { answer.await() } }
        runCurrent()
        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        runCurrent()
        assertEquals(0, releases)
        answer.complete(Unit)
        generation.join()
        advanceTimeBy(ModelIdleReleaser.HIDDEN_DELAY_MILLIS + 1)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun appInBackgroundKeepsTheModelBetweenTheStepsOfAnAnswer() = runTest {
        val releaser = releaser()
        val rerank = CompletableDeferred<Unit>()
        val first = launch { releaser.use { rerank.await() } }
        runCurrent()
        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        rerank.complete(Unit)
        first.join()
        // Il secondo passo della risposta parte subito dopo il primo: il modello resta.
        advanceTimeBy(1)
        val answer = CompletableDeferred<Unit>()
        val second = launch { releaser.use { answer.await() } }
        runCurrent()
        advanceTimeBy(ModelIdleReleaser.HIDDEN_DELAY_MILLIS + 1)
        runCurrent()
        assertEquals(0, releases)
        answer.complete(Unit)
        second.join()
        advanceTimeBy(ModelIdleReleaser.HIDDEN_DELAY_MILLIS + 1)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun appInBackgroundWithoutAnAnswerReleasesAfterAShortDelay() = runTest {
        val releaser = releaser()
        releaser.use()
        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        advanceTimeBy(ModelIdleReleaser.HIDDEN_DELAY_MILLIS - 1)
        runCurrent()
        assertEquals(0, releases)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun lowMemoryInForegroundReleasesOnlyWithoutAGeneration() = runTest {
        val releaser = releaser()
        val answer = CompletableDeferred<Unit>()
        val generation = launch { releaser.use { answer.await() } }
        runCurrent()
        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        answer.complete(Unit)
        generation.join()
        runCurrent()
        assertEquals(0, releases)

        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun moderateTrimLevelsDoNothing() = runTest {
        val releaser = releaser()
        releaser.use()
        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        runCurrent()
        assertEquals(0, releases)
    }
}
