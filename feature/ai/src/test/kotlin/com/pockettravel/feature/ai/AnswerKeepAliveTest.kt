package com.pockettravel.feature.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnswerKeepAliveTest {
    private var starts = 0
    private val keepAlive = AnswerKeepAlive { starts++ }

    @Test
    fun theServiceStartsWithTheAnswerAndIsReleasedAtTheEnd() = runTest {
        val answer = CompletableDeferred<Unit>()
        val job = launch { keepAlive.during { answer.await() } }
        runCurrent()
        assertEquals(1, starts)
        assertTrue(keepAlive.active.value)
        answer.complete(Unit)
        job.join()
        assertFalse(keepAlive.active.value)
    }

    @Test
    fun anErrorOrACancellationAlsoEndsTheAnswer() = runTest {
        runCatching { keepAlive.during { error("servizio online") } }
        assertFalse(keepAlive.active.value)

        val job = launch { keepAlive.during { CompletableDeferred<Unit>().await() } }
        runCurrent()
        job.cancel()
        job.join()
        assertFalse(keepAlive.active.value)
    }

    @Test
    fun twoAnswersInARowShareOneServiceUntilTheLastEnds() = runTest {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val a = launch { keepAlive.during { first.await() } }
        val b = launch { keepAlive.during { second.await() } }
        runCurrent()
        assertEquals(1, starts)
        first.complete(Unit)
        a.join()
        assertTrue(keepAlive.active.value)
        second.complete(Unit)
        b.join()
        assertFalse(keepAlive.active.value)
    }

    @Test
    fun stopFromTheNotificationReachesTheScreen() = runTest {
        val stop = launch { keepAlive.stopRequests.first() }
        runCurrent()
        keepAlive.requestStop()
        stop.join()
        assertTrue(stop.isCompleted)
    }
}
