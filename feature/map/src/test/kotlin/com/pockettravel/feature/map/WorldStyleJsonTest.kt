package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WorldStyleJsonTest {

    private fun style(locale: Locale = Locale.ITALIAN) = worldStyleJson(
        locale = locale,
        ocean = "#001",
        land = "#land",
        available = "#av",
        downloaded = "#dl",
        border = "#bd",
        label = "#lb",
        labelHalo = "#halo",
    )

    private fun layer(json: String, id: String) = json.lineSequence().first { "\"id\": \"$id\"" in it }

    @Test
    fun `i layer di stato hanno colore fisso, nessuna nazione e stanno tra terra e confini`() {
        val json = style()
        assertTrue(layer(json, "countries-fill").contains("\"fill-color\": \"#land\""))
        assertTrue(layer(json, "countries-available").contains("\"filter\": [\"in\", [\"get\", \"iso\"], [\"literal\", []]], \"paint\": { \"fill-color\": \"#av\" }"))
        assertTrue(layer(json, "countries-downloaded").contains("\"filter\": [\"in\", [\"get\", \"iso\"], [\"literal\", []]], \"paint\": { \"fill-color\": \"#dl\" }"))
        val order = listOf("countries-fill", "countries-available", "countries-downloaded", "countries-line").map { json.indexOf("\"id\": \"$it\"") }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `il filtro di uno stato contiene solo le sue nazioni, in ordine`() {
        val status = mapOf("IT" to CountryStatus.DOWNLOADED, "ES" to CountryStatus.AVAILABLE, "AT" to CountryStatus.AVAILABLE, "FR" to CountryStatus.DOWNLOADED)
        assertEquals("[\"in\", [\"get\", \"iso\"], [\"literal\", [\"AT\",\"ES\"]]]", countryStatusFilter(status, CountryStatus.AVAILABLE))
        assertEquals("[\"in\", [\"get\", \"iso\"], [\"literal\", [\"FR\",\"IT\"]]]", countryStatusFilter(status, CountryStatus.DOWNLOADED))
    }

    @Test
    fun `senza nazioni in uno stato il filtro ha la lista vuota`() {
        assertEquals("[\"in\", [\"get\", \"iso\"], [\"literal\", []]]", countryStatusFilter(mapOf("IT" to CountryStatus.AVAILABLE), CountryStatus.DOWNLOADED))
    }

    @Test
    fun `in italiano le etichette usano il nome dell'asset`() {
        val json = style(Locale.ITALIAN)
        assertTrue(json.contains("\"text-field\": [\"get\", \"name\"]"))
    }

    @Test
    fun `in un'altra lingua il nome viene dal codice iso, con il nome dell'asset come ripiego`() {
        val json = style(Locale.ENGLISH)
        assertTrue(json.contains("\"text-field\": [\"match\", [\"get\", \"iso\"], "))
        assertTrue(json.contains("\"it\", \"Italy\""))
        assertTrue(json.contains(", [\"get\", \"name\"]], \"text-font\""))
    }

    @Test
    fun `i colori passati finiscono nello stile`() {
        val json = style()
        assertTrue(json.contains("\"background-color\": \"#001\""))
        assertTrue(json.contains("\"line-color\": \"#bd\""))
        assertTrue(json.contains("\"text-color\": \"#lb\""))
        assertTrue(json.contains("\"text-halo-color\": \"#halo\""))
    }
}
