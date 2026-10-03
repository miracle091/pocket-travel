package com.pockettravel.pipeline

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException

/** cleanBody (casi di bordo non coperti da GuideMarkupTest) e il processo a righe JSON usato dagli script Python. */
class CleanWikitextTest {

    @Test
    fun `testo vuoto o solo spazi e a capo diventa vuoto`() {
        assertEquals("", cleanBody(""))
        assertEquals("", cleanBody("  \n\n \t\n"))
    }

    @Test
    fun `commenti e citazioni spariscono`() {
        assertEquals("Testo pulito.", cleanBody("Testo<!-- nota\ndi servizio --> pulito<ref name=\"a\">fonte</ref><ref name=\"b\"/>."))
    }

    @Test
    fun `elenchi numerati diventano puntati e i rientri perdono il marcatore`() {
        assertEquals("• uno\n• due\nrientrato", cleanBody("# uno\n## due\n: rientrato"))
    }

    @Test
    fun `un termine di definizione con voci e' un sottotitolo, uno senza voci sparisce`() {
        assertEquals("▸ Vini rossi\n• Chianti", cleanBody(";Vini rossi\n* Chianti\n;Vini bianchi\n"))
    }

    @Test
    fun `parentesi rimaste vuote dopo aver tolto un template vengono tolte`() {
        assertEquals("Il museo apre alle 9.", cleanBody("Il museo ({{cita|x}}, ) apre alle 9."))
        assertEquals("Aeroporto FCO", cleanBody("Aeroporto {{IATA|FCO}}"))
    }

    @Test
    fun `le misure dei template di Wikipedia restano nel testo con la loro unita'`() {
        assertEquals("Tra 15 and 25 °C, 641 mm di pioggia, 105.4 km².", cleanBody("Tra {{cvt|15|and|25|°C|°F}}, {{convert|641|mm|in|1}} di pioggia, {{convert|105.4|km2|sqmi|abbr=on}}."))
        assertEquals("Massima 32 °C.", cleanBody("Massima {{cvt|32|C}}."))
        assertEquals("Luglio 23,1 °C, 128500 ettari, anno 1100.", cleanBody("Luglio {{M|23.1|u=°C}}, {{M|128500|ul=ettari}}, anno {{M|1100}}."))
        assertEquals("Abitanti: 25 990.", cleanBody("Abitanti: {{TA|25 990}}."))
    }

    @Test
    fun `un link ad altra lingua mostra il testo o il titolo della voce`() {
        assertEquals("Il parco Fiabilandia e Rimini Centrale.", cleanBody("Il parco {{Interlanguage link|Fiabilandia|lt=|it|}} e {{ill|Stazione di Rimini Centrale|lt=Rimini Centrale|it}}."))
    }

    @Test
    fun `un template non chiuso resta nel testo`() {
        assertEquals("Testo {{non chiuso", cleanBody("Testo {{non chiuso"))
    }

    @Test
    fun `tre o piu' righe vuote diventano una sola`() {
        assertEquals("Prima.\n\n• voce", cleanBody("Prima.\n\n\n\n\n* voce"))
    }

    @Test
    fun `il processo risponde con una riga JSON per ogni riga di ingresso, nello stesso ordine`() {
        val input = listOf(
            JSONObject().put("raw", "* Uno\n* Due").toString(),
            JSONObject().put("raw", "").toString(),
            JSONObject().put("raw", "===Titolo===\n\nTesto con &egrave; e [[Roma|capitale]].").toString(),
        ).joinToString("\n", postfix = "\n")

        val lines = runMain(input)

        assertEquals(3, lines.size)
        assertEquals("• Uno\n• Due", JSONObject(lines[0]).getString("text"))
        assertEquals("", JSONObject(lines[1]).getString("text"))
        assertEquals("▸ Titolo\nTesto con è e capitale.", JSONObject(lines[2]).getString("text"))
    }

    @Test
    fun `senza ingresso il processo non scrive nulla`() {
        assertEquals(emptyList<String>(), runMain(""))
    }

    @Test
    fun `una riga che non e' JSON ferma il processo`() {
        assertThrows(JSONException::class.java) { runMain("non json\n") }
    }

    // main() senza argomenti: nello stesso package ci sono piu' main, quindi lo si chiama dalla classe
    // generata, con stdin e stdout sostituiti.
    private fun runMain(stdin: String): List<String> {
        val originalIn = System.`in`
        val originalOut = System.out
        val captured = ByteArrayOutputStream()
        try {
            System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
            System.setOut(PrintStream(captured, true, "UTF-8"))
            try {
                Class.forName("com.pockettravel.pipeline.CleanWikitextKt").getMethod("main").invoke(null)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } finally {
            System.setIn(originalIn)
            System.setOut(originalOut)
        }
        return captured.toString("UTF-8").lines().filter { it.isNotEmpty() }
    }
}
