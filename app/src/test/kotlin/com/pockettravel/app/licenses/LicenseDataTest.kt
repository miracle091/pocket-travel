package com.pockettravel.app.licenses

import com.pockettravel.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseDataTest {

    @Test
    fun `il registro non e' vuoto`() {
        assertTrue(thirdPartyLicenses.isNotEmpty())
    }

    @Test
    fun `ogni componente compare una volta sola`() {
        val components = thirdPartyLicenses.map { it.component }

        assertEquals(components.size, components.toSet().size)
    }

    @Test
    fun `tutte le voci puntano a risorse di testo valide`() {
        assertTrue(thirdPartyLicenses.all { it.component != 0 && it.license.name != 0 })
        assertTrue(thirdPartyLicenses.mapNotNull { it.note }.all { it != 0 })
    }

    @Test
    fun `i collegamenti ai testi delle licenze sono https e senza spazi`() {
        val urls = thirdPartyLicenses.mapNotNull { it.license.url }

        assertTrue(urls.isNotEmpty())
        assertTrue(urls.all { it.startsWith("https://") && it.none(Char::isWhitespace) })
    }

    @Test
    fun `la stessa licenza ha sempre lo stesso collegamento`() {
        val urlsByName = thirdPartyLicenses.groupBy({ it.license.name }, { it.license.url }).mapValues { it.value.toSet() }

        assertTrue(urlsByName.values.all { it.size == 1 })
    }

    @Test
    fun `licenze diverse hanno collegamenti diversi`() {
        val urlByName = thirdPartyLicenses.mapNotNull { entry -> entry.license.url?.let { entry.license.name to it } }.toMap()

        assertEquals(urlByName.size, urlByName.values.toSet().size)
    }

    @Test
    fun `pubblico dominio, aeroporti e bandiere non hanno un testo unico da aprire`() {
        val withoutUrl = thirdPartyLicenses.filter { it.license.url == null }.map { it.license.name }.toSet()

        assertEquals(setOf(R.string.licenses_name_public_domain, R.string.licenses_name_airports, R.string.licenses_name_flags), withoutUrl)
    }

    @Test
    fun `il motore dell'assistente e i modelli addestrati portano una nota`() {
        val llama = thirdPartyLicenses.first { it.component == R.string.licenses_component_llama_cpp }
        val trained = thirdPartyLicenses.first { it.component == R.string.licenses_component_trained_models }

        assertEquals(R.string.licenses_note_llama_cpp, llama.note)
        assertEquals(R.string.licenses_note_trained_models, trained.note)
        assertNotEquals(llama.license, trained.license)
    }

    @Test
    fun `i componenti con licenza semplice non hanno note`() {
        val okhttp = thirdPartyLicenses.first { it.component == R.string.licenses_component_okhttp }

        assertNull(okhttp.note)
        assertEquals(License(R.string.licenses_name_apache, "https://www.apache.org/licenses/LICENSE-2.0"), okhttp.license)
    }

    @Test
    fun `licenze e voci si confrontano per valore, la nota di default e' assente`() {
        val a = License(1, "https://example.org")

        assertEquals(a, License(1, "https://example.org"))
        assertEquals(LicenseEntry(7, a), LicenseEntry(7, a, note = null))
        assertNotEquals(LicenseEntry(7, a), LicenseEntry(7, a, note = 8))
    }
}
