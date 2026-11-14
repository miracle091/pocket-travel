package com.pockettravel.feature.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Il motore vero con richieste sbagliate: risponde con un [RouteResult], non con un'eccezione che arriva ai ViewModel. */
class BRouterRouteEngineErrorsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun copy(name: String, folder: String, dir: File) {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream("$folder/$name")) { "$folder/$name" }
        resource.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
    }

    private val logged = mutableListOf<String>()

    private fun engine(): BRouterRouteEngine {
        val segments = tempFolder.newFolder().also { copy("E5_N45.rd5", "multi-tile-segments", it); copy("E10_N45.rd5", "multi-tile-segments", it) }
        val profiles = tempFolder.newFolder().also { copy("trekking.brf", "brouter-profile", it); copy("lookups.dat", "brouter-profile", it) }
        return BRouterRouteEngine(segments, profiles, ioDispatcher = Dispatchers.IO, defaultDispatcher = Dispatchers.Default, logError = { logged += it })
    }

    @Test
    fun `un profilo inesistente da' un risultato di errore`() = runBlocking {
        val result = engine().route(RoutePoint(45.5000, 9.9970), RoutePoint(45.5000, 10.0030), profile = "inesistente")

        assertTrue("$result", result is RouteResult.Failed)
        assertTrue("$logged", logged.single().startsWith("inesistente: "))
    }

    @Test
    fun `una variabile del profilo non valida da' un risultato di errore`() = runBlocking {
        val result = engine().route(RoutePoint(45.5000, 9.9970), RoutePoint(45.5000, 10.0030), profileParams = mapOf("allow_steps" to "x&y"))

        assertTrue("$result", result is RouteResult.Failed)
    }

    @Test
    fun `un errore del calcolo va nel log con il messaggio di BRouter`() = runBlocking {
        // Arrivo fuori dai segmenti: BRouter non trova il file della tile e lo dice in errorMessage.
        val result = engine().route(RoutePoint(45.5000, 9.9970), RoutePoint(10.0, 10.0))

        assertTrue("$result", result is RouteResult.NoRoutingData)
        assertTrue("$logged", logged.single().let { it.startsWith("trekking, ") && "not" in it })
    }
}
