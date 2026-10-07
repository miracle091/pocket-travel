package com.pockettravel.feature.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WorldStyleJsonTest {

    private fun style(status: Map<String, CountryStatus>, locale: Locale = Locale.ITALIAN) = worldStyleJson(
        countryStatus = status,
        locale = locale,
        ocean = "#001",
        land = "#land",
        available = "#av",
        downloaded = "#dl",
        border = "#bd",
        label = "#lb",
        labelHalo = "#halo",
    )

    private fun fill(json: String) = json.lineSequence().first { "countries-fill" in it }

    @Test
    fun `senza nazioni il riempimento e' il colore della terra, senza espressione match`() {
        val line = fill(style(emptyMap()))
        assertTrue(line.contains("\"fill-color\": \"#land\""))
        assertFalse(line.contains("match"))
    }

    @Test
    fun `con sole nazioni scaricati non c'e' il ramo dei disponibili, che match non ammette vuoto`() {
        val line = fill(style(mapOf("IT" to CountryStatus.DOWNLOADED, "FR" to CountryStatus.DOWNLOADED)))
        assertTrue(line.contains("[\"match\", [\"get\", \"iso\"], [\"FR\",\"IT\"], \"#dl\", \"#land\"]"))
        assertFalse(line.contains("#av"))
    }

    @Test
    fun `con sole nazioni disponibili non c'e' il ramo degli scaricati`() {
        val line = fill(style(mapOf("DE" to CountryStatus.AVAILABLE)))
        assertTrue(line.contains("[\"match\", [\"get\", \"iso\"], [\"DE\"], \"#av\", \"#land\"]"))
        assertFalse(line.contains("#dl"))
    }

    @Test
    fun `con scaricati e disponibili gli scaricati vengono per primi e i codici sono ordinati`() {
        val line = fill(
            style(mapOf("IT" to CountryStatus.DOWNLOADED, "ES" to CountryStatus.AVAILABLE, "AT" to CountryStatus.AVAILABLE, "FR" to CountryStatus.DOWNLOADED)),
        )
        assertTrue(line.contains("[\"FR\",\"IT\"], \"#dl\", [\"AT\",\"ES\"], \"#av\", \"#land\"]"))
    }

    @Test
    fun `in italiano le etichette usano il nome dell'asset`() {
        val json = style(emptyMap(), Locale.ITALIAN)
        assertTrue(json.contains("\"text-field\": [\"get\", \"name\"]"))
    }

    @Test
    fun `in un'altra lingua il nome viene dal codice iso, con il nome dell'asset come ripiego`() {
        val json = style(emptyMap(), Locale.ENGLISH)
        assertTrue(json.contains("\"text-field\": [\"match\", [\"get\", \"iso\"], "))
        assertTrue(json.contains("\"it\", \"Italy\""))
        assertTrue(json.contains(", [\"get\", \"name\"]], \"text-font\""))
    }

    @Test
    fun `i colori passati finiscono nello stile`() {
        val json = style(emptyMap())
        assertTrue(json.contains("\"background-color\": \"#001\""))
        assertTrue(json.contains("\"line-color\": \"#bd\""))
        assertTrue(json.contains("\"text-color\": \"#lb\""))
        assertTrue(json.contains("\"text-halo-color\": \"#halo\""))
    }
}
