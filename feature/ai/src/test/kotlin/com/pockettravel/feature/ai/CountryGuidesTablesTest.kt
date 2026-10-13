package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Coerenza interna delle tabelle di CountryGuides e riconoscimento della nazione nella domanda.
class CountryGuidesTablesTest {

    private val regionIds = countryRegions.values.flatMap { it.split(' ') }

    @Test
    fun `ogni regione e' elencata sotto una sola nazione`() {
        assertEquals(regionIds.size, regionIds.toSet().size)
    }

    @Test
    fun `le regioni delle nazioni divise sono tutte nella tabella delle nazioni`() {
        assertTrue(subregionNames.keys.filter { it !in regionIds }.toString(), regionIds.containsAll(subregionNames.keys))
    }

    @Test
    fun `nessuna regione ha un nome vuoto`() {
        subregionNames.forEach { (regionId, names) ->
            assertTrue("$regionId senza nome", names.isNotBlank())
            assertTrue("$regionId ha un nome vuoto", names.split(", ").none { it.isBlank() })
        }
    }

    @Test
    fun `i codici delle nazioni sono ISO alpha-2 minuscoli`() {
        assertTrue(countryRegions.keys.all { it.length == 2 && it == it.lowercase() })
    }

    @Test
    fun `ogni nome d'uso riconosce la sua nazione`() {
        countryAliases.forEach { (name, code) ->
            assertEquals("alias $name", code, namedPlace("Is it safe in $name?", "italia", "en")?.code)
        }
    }

    @Test
    fun `una nazione con una sola regione cerca solo in quella`() {
        assertEquals(listOf("giappone"), namedPlace("Cosa vedere in Giappone?", "italia", "it")?.regionIds)
    }

    @Test
    fun `una nazione divisa cerca in tutte le sue regioni`() {
        assertEquals(countryRegions.getValue("ca").split(' '), namedPlace("Is it safe in Canada?", "italia", "en")?.regionIds)
    }

    @Test
    fun `la nazione della regione aperta non cambia la ricerca`() {
        assertNull(namedPlace("Cosa vedere in Giappone?", "giappone", "it"))
        assertNotNull(namedPlace("Cosa vedere in Giappone?", "corea-del-sud", "it"))
    }

    @Test
    fun `una regione aperta sconosciuta non impedisce di riconoscere la nazione`() {
        assertEquals("jp", namedPlace("Cosa vedere in Giappone?", "regione-che-non-esiste", "it")?.code)
    }

    @Test
    fun `un nome di nazione in minuscolo conta solo dopo una preposizione di luogo`() {
        assertEquals("jp", namedPlace("voglio viaggiare in giappone", "italia", "it")?.code)
        assertNull(namedPlace("il giappone mi piace", "italia", "it"))
    }

    @Test
    fun `senza nomi di luogo non c'e' nazione`() {
        assertNull(namedPlace("", "italia", "it"))
        assertNull(namedPlace("Quanto costa un taxi?", "italia", "it"))
    }

    @Test
    fun `il testo senza accenti e in minuscolo e' quello dell'indice di ricerca`() {
        assertEquals("citta", folded("Città"))
        assertEquals("zurich", folded("ZÜRICH"))
        assertEquals("ile-de-france", folded("Île-de-France"))
    }
}
