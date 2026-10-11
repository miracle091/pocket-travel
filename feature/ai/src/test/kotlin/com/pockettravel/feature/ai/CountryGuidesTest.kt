package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CountryGuidesTest {

    @Test
    fun `la tabella delle regioni per nazione e' quella di regions sh`() {
        val script = File("../../tools/data-pipeline/scripts/regions.sh")
        // Righe "regionId|displayName|minLon|minLat|maxLon|maxLat|wikivoyagePageTitle|flagCode|...": flagCode e' l'ottavo campo.
        val fromScript = script.readLines().map { it.trim() }
            .filter { it.startsWith("\"") && it.count { c -> c == '|' } >= 10 }
            .map { it.trim('"').split('|') }
            .groupBy({ it[7] }, { it[0] })
            .mapValues { (_, ids) -> ids.joinToString(" ") }
        assertEquals("rigenera countryRegions da ${script.path}", fromScript, countryRegions)
        // groupName (nono campo) non vuoto: le regioni delle nazioni divise, che hanno un nome in subregionNames.
        val grouped = script.readLines().map { it.trim() }
            .filter { it.startsWith("\"") && it.count { c -> c == '|' } >= 10 }
            .map { it.trim('"').split('|') }
            .filter { it[8].isNotEmpty() }
            .map { it[0] }
            .toSet()
        assertEquals(grouped, subregionNames.keys)
    }

    @Test
    fun `nomi d'uso delle nazioni oltre a quelli del JDK`() {
        assertTrue("alias di nazioni fuori tabella", countryRegions.keys.containsAll(countryAliases.values))
        assertEquals("tr", namedPlace("Is it safe to travel to Turkey?", "italia", "en")?.code)
        assertEquals("hk", namedPlace("Serve il visto per Hong Kong?", "italia", "it")?.code)
        assertEquals("ps", namedPlace("È sicuro andare in Palestina?", "italia", "it")?.code)
        assertEquals("nl", namedPlace("Cosa si mangia in Olanda?", "italia", "it")?.code)
        assertEquals("gb", namedPlace("Do I need a visa for England?", "italia", "en")?.code)
        assertEquals("ba", namedPlace("Is Bosnia and Herzegovina safe?", "italia", "en")?.code)
        // "Corea del Nord" e' piu' lungo di "Corea": vince.
        assertEquals("kp", namedPlace("Si puo' andare in Corea del Nord?", "italia", "it")?.code)
    }

    @Test
    fun `una regione di una nazione divisa vince sul nome di nazione che contiene, e i nomi ambigui restano nella regione aperta`() {
        assertEquals(listOf("stati-uniti-nuovo-messico"), namedPlace("Is it safe in New Mexico?", "italia", "en")?.regionIds)
        assertNull(namedPlace("Is it safe in New Mexico?", "stati-uniti-texas", "en"))
        assertEquals(listOf("francia-bretagna"), namedPlace("Cosa vedere in Bretagna?", "italia", "it")?.regionIds)
        assertNull(namedPlace("What to see in Georgia?", "stati-uniti-florida", "en"))
        assertEquals("ge", namedPlace("Serve il visto per la Georgia?", "italia", "it")?.code)
        assertNull(namedPlace("Cosa vedere a Monaco?", "germania", "it"))
        assertEquals("gb", namedPlace("Cosa vedere in Irlanda del Nord?", "italia", "it")?.code)
    }

    @Test
    fun `nazione nominata in italiano e in inglese, non quella della regione aperta`() {
        assertEquals("jp", namedPlace("Serve il visto per il Giappone?", "italia", "it")?.code)
        assertEquals("Greece", namedPlace("Is it safe to travel in Greece?", "italia", "en")?.name)
        assertNull(namedPlace("Cosa si mangia in Italia?", "italia", "it"))
        assertNull(namedPlace("Dove dormire a Roma?", "italia", "it"))
        // Una nazione divisa da' tutte le sue regioni.
        assertEquals(13, namedPlace("Serve il passaporto per la Francia?", "italia", "it")?.regionIds?.size)
    }
}
