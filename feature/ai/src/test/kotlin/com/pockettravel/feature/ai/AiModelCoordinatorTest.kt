package com.pockettravel.feature.ai

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiModelCoordinatorTest {
    @Test
    fun modelOperationsAreSerialized() = runBlocking {
        val coordinator = AiModelCoordinator()
        val active = AtomicInteger(0)
        val overlap = AtomicBoolean(false)

        suspend fun operation() {
            coordinator.withModelLock {
                if (active.incrementAndGet() > 1) overlap.set(true)
                delay(10)
                active.decrementAndGet()
            }
        }

        val first = launch { operation() }
        val second = launch { operation() }
        first.join()
        second.join()
        assertFalse(overlap.get())
        assertEquals(0, active.get())
    }
}
