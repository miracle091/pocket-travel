package com.pockettravel.feature.map

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

    private fun engine(): BRouterRouteEngine {
        val segments = tempFolder.newFolder().also { copy("E5_N45.rd5", "multi-tile-segments", it); copy("E10_N45.rd5", "multi-tile-segments", it) }
        val profiles = tempFolder.newFolder().also { copy("trekking.brf", "brouter-profile", it); copy("lookups.dat", "brouter-profile", it) }
        return BRouterRouteEngine(segments, profiles)
    }

    @Test
    fun `un profilo inesistente da' un risultato di errore`() = runBlocking {
        val result = engine().route(RoutePoint(45.5000, 9.9970), RoutePoint(45.5000, 10.0030), profile = "inesistente")

        assertTrue("$result", result is RouteResult.Failed)
    }

    @Test
    fun `una variabile del profilo non valida da' un risultato di errore`() = runBlocking {
        val result = engine().route(RoutePoint(45.5000, 9.9970), RoutePoint(45.5000, 10.0030), profileParams = mapOf("allow_steps" to "x&y"))

        assertTrue("$result", result is RouteResult.Failed)
    }
}
