package com.pockettravel.app.crash

import com.pockettravel.feature.ai.LlmModelCatalog
import com.pockettravel.feature.ai.RamTier
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTextTest {
    private val largest = LlmModelCatalog.ALL.maxBy { it.minRamTier }

    private fun report(
        kind: CrashKind = CrashKind.LOW_MEMORY,
        modelTier: RamTier? = largest.minRamTier,
        deviceTier: RamTier? = RamTier.MINIMO,
        details: String = "",
    ) = CrashReport(kind, 1_700_000_000_000L, "0.14.0 (14)", "15 (API 35)", "Google Pixel 8", 7600, largest.id, modelTier, deviceTier, "RegionHubRoute", details)

    private val italian = CrashReportLabels("Evento: %1\$s, %2\$s", "Schermata: %1\$s", "Modello caricato: %1\$s (fascia %2\$s, dispositivo %3\$s)")
    private val english = CrashReportLabels("Event: %1\$s, %2\$s", "Screen: %1\$s", "Loaded model: %1\$s (RAM tier %2\$s, device %3\$s)")

    @Test
    fun `le etichette seguono la lingua dell'app`() {
        val text = report().toText(english)
        assertTrue(text.contains("Event: LOW_MEMORY, 2023-11-14"))
        assertTrue(text.contains("Screen: RegionHubRoute"))
        assertTrue(text.contains("Loaded model: ${largest.id} (RAM tier ${largest.minRamTier.name}, device MINIMO)"))
        assertFalse(text.contains("Evento"))
        assertTrue(report().toText(italian).contains("Schermata: RegionHubRoute"))
    }

    @Test
    fun `il testo contiene versioni, dispositivo, modello e schermata`() {
        val text = report().toText(italian)
        assertTrue(text.contains("Pocket Travel 0.14.0 (14)"))
        assertTrue(text.contains("Google Pixel 8"))
        assertTrue(text.contains(largest.id))
        assertTrue(text.contains("RegionHubRoute"))
        assertTrue(text.contains("2023-11-14"))
    }

    @Test
    fun `la traccia dell'errore non porta i messaggi con testo dell'utente`() {
        val cause = IllegalArgumentException("nota privata: parcheggio dalla zia")
        val error = IllegalStateException("chat: dove dormire a Lisbona", cause)
        val text = stackDetails(error)
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException"))
        assertFalse(text.contains("nota privata"))
        assertFalse(text.contains("Lisbona"))
        assertFalse(report(details = text).toText(italian).contains("Lisbona"))
    }

    @Test
    fun `la traccia di sistema tiene solo il testo leggibile e si taglia`() {
        val bytes = byteArrayOf(0, 1, 7) + "ab".toByteArray() + byteArrayOf(0) + "Abort message".toByteArray() + byteArrayOf(2)
        assertEquals("Abort message", readableTrace(bytes, 100))
        assertEquals("Abort", readableTrace(bytes, 5))
    }

    @Test
    fun `l'URL compila i campi del modulo e resta sotto il limite`() {
        val long = report(details = "    at com.example.Classe.metodo(Classe.kt:1)\n".repeat(2000))
        val url = long.issueUrl(italian)
        assertTrue(url.length <= MAX_ISSUE_URL_LENGTH)
        assertTrue(url.startsWith("https://github.com/miracle091/pocket-travel/issues/new?template=problema-app.yml&"))
        val params = url.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals("0.14.0 (14)", params["versione-app"])
        assertEquals("15 (API 35)", params["android"])
        assertEquals("Google Pixel 8", params["dispositivo"])
        assertTrue(params.getValue("rapporto").startsWith("Pocket Travel 0.14.0"))
        assertTrue(params.getValue("rapporto").endsWith("[...]"))
    }

    @Test
    fun `un rapporto breve entra nell'URL per intero`() {
        val short = report()
        val rapporto = short.issueUrl(italian).substringAfter("rapporto=")
        assertEquals(short.toText(italian), URLDecoder.decode(rapporto, "UTF-8"))
    }

    @Test
    fun `con un modello di una fascia sopra il dispositivo si propone quello adatto`() {
        val (loaded, proposed) = requireNotNull(proposedModel(report(), "it"))
        assertEquals(largest.id, loaded.id)
        assertEquals(LlmModelCatalog.defaultFor(RamTier.MINIMO, "it").id, proposed.id)
        assertTrue(proposed.minRamTier <= RamTier.MINIMO)
        assertNotNull(proposedModel(report(kind = CrashKind.NATIVE), "en"))
    }

    @Test
    fun `nessuna proposta se il modello era della fascia giusta`() {
        assertNull(proposedModel(report(modelTier = RamTier.MINIMO), "it"))
        assertNull(proposedModel(report(modelTier = RamTier.MINIMO, deviceTier = RamTier.AMPIA), "it"))
    }

    @Test
    fun `nessuna proposta per altri tipi di chiusura o senza fasce note`() {
        assertNull(proposedModel(report(kind = CrashKind.JVM), "it"))
        assertNull(proposedModel(report(kind = CrashKind.ANR), "it"))
        assertNull(proposedModel(report(modelTier = null), "it"))
        assertNull(proposedModel(report(deviceTier = null), "it"))
        assertNull(proposedModel(report(deviceTier = RamTier.INSUFFICIENTE), "it"))
    }
}
