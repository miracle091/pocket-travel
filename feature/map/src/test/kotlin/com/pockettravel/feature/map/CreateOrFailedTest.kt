package com.pockettravel.feature.map

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class CreateOrFailedTest {
    private val from = RoutePoint(45.0, 9.0)
    private val to = RoutePoint(41.9, 12.5)

    @Test
    fun `un errore nella creazione del motore diventa un motore che risponde Failed`() = runTest {
        val engine = createOrFailed { throw IOException("disco pieno") }

        assertEquals(RouteResult.Failed("disco pieno"), engine.route(from, to))
    }

    @Test
    fun `senza messaggio l'errore e' nominato dalla sua classe`() = runTest {
        val engine = createOrFailed { throw IllegalStateException() }

        assertEquals(RouteResult.Failed("IllegalStateException"), engine.route(from, to))
    }

    @Test
    fun `se la creazione riesce il motore e' quello creato`() = runTest {
        val created = object : RouteEngine {
            override suspend fun route(
                from: RoutePoint, to: RoutePoint, profile: String?, profileParams: Map<String, String>, onProgress: (Double) -> Unit,
            ): RouteResult = RouteResult.NotFound
        }

        assertSame(created, createOrFailed { created })
    }

    @Test
    fun `l'annullamento non diventa un errore del motore`() = runTest {
        try {
            createOrFailed { throw CancellationException("annullato") }
            fail("l'annullamento doveva propagarsi")
        } catch (expected: CancellationException) {
            assertEquals("annullato", expected.message)
        }
    }
}
