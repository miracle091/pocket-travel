package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenerateCountryFactsTest {
    private val lesotho = CountryFacts(
        capitalIt = "Maseru", capitalEn = "Maseru", currency = "LSL; ZAR", currencyIt = "loti; rand sudafricano",
        currencyEn = "loti; South African rand", driving = "left", callingCode = "+266", languagesIt = "sesotho; inglese",
        languagesEn = "Sesotho; English", timeZones = "UTC+02:00", plugs = "M", voltage = "220",
    )

    @Test
    fun `campi italiani in ordine con valori multipli e prese`() {
        assertEquals(
            mapOf(
                "Capitale" to "Maseru",
                "Lingua" to "Sesotho, inglese",
                "Elettricità" to "220 V, presa M",
                "Fuso orario" to "UTC+2",
                "Valuta" to "loti, rand sudafricano (LSL, ZAR)",
                "Prefisso telefonico" to "+266",
                "Lato di guida" to "sinistra",
            ).toList(),
            countryFactFields("ls", english = false, facts = lesotho).toList(),
        )
    }

    @Test
    fun `campi inglesi senza quelli assenti`() {
        val facts = lesotho.copy(capitalEn = "", plugs = "A, B", voltage = "127; 220", timeZones = "UTC-03:30; UTC+05:45", driving = "")
        assertEquals(
            mapOf(
                "Language" to "Sesotho, English",
                "Electricity" to "127/220 V, plug types A, B",
                "Time zone" to "UTC-3:30, UTC+5:45",
                "Currency" to "loti, South African rand (LSL, ZAR)",
                "Calling code" to "+266",
            ).toList(),
            countryFactFields("ls", english = true, facts = facts).toList(),
        )
        assertEquals(emptyMap<String, String>(), countryFactFields("regione-sconosciuta", english = true))
    }

    @Test
    fun `tsv con commenti e fusi abbreviati`() {
        val row = listOf("italia", "it", "Roma", "Rome", "EUR", "euro", "euro", "right", "+39", "italiano", "Italian", "UTC+01:00", "112", "C, F, L", "230")
        val facts = parseCountryFacts("# commento\n" + row.joinToString("\t") + "\n")
        assertEquals("Rome", facts.getValue("italia").capitalEn)
        assertEquals("C, F, L", facts.getValue("italia").plugs)
        assertEquals("UTC+0", shortUtcOffset("UTC+00:00"))
        assertEquals("UTC-9:30", shortUtcOffset("UTC-09:30"))
    }

    @Test
    fun `il tsv curato ha i dati delle regioni divise e le correzioni`() {
        val california = countryFactFields("stati-uniti-california", english = false)
        assertEquals("Sacramento", california["Capitale"])
        assertEquals("UTC-8", california["Fuso orario"])
        assertEquals("Inglese", california["Lingua"])
        assertEquals("UTC+5:30", countryFactFields("india", english = true)["Time zone"])
        assertEquals("UTC+0", countryFactFields("isole-canarie", english = false)["Fuso orario"])
    }

    // --- parseCountryFacts, shortUtcOffset, countryFactFields: casi limite ---

    private fun tsvRow(id: String, vararg overrides: Pair<Int, String>): String {
        val columns = mutableListOf(id, "xx", "CapIt", "CapEn", "XXX", "valutaIt", "valutaEn", "right", "+1", "linguaIt", "LinguaEn", "UTC+01:00", "112", "C", "230")
        overrides.forEach { (index, value) -> columns[index] = value }
        return columns.joinToString("\t")
    }

    @Test
    fun `parseCountryFacts - vuoto, solo commenti e righe vuote danno nessun paese`() {
        assertEquals(emptyMap<String, CountryFacts>(), parseCountryFacts(""))
        assertEquals(emptyMap<String, CountryFacts>(), parseCountryFacts("# a\n\n   \n# b\n"))
    }

    @Test
    fun `parseCountryFacts - una riga con il numero di colonne sbagliato fa fallire con il nome della regione`() {
        val short = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { parseCountryFacts("italia\tit\tRoma\n") }
        assertEquals("countries.tsv: riga con 3 colonne: italia", short.message)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { parseCountryFacts(tsvRow("x") + "\textra\n") }
    }

    @Test
    fun `parseCountryFacts - un regionId ripetuto tiene l'ultima riga e i campi vuoti restano stringhe vuote`() {
        val facts = parseCountryFacts(tsvRow("x", 2 to "Prima") + "\n" + tsvRow("x", 2 to "Seconda", 13 to "", 14 to "") + "\n")

        assertEquals(1, facts.size)
        assertEquals("Seconda", facts.getValue("x").capitalIt)
        assertEquals("", facts.getValue("x").plugs)
        assertEquals("", facts.getValue("x").voltage)
    }

    @Test
    fun `parseCountryFacts non usa le colonne dei numeri di emergenza del tsv`() {
        val facts = parseCountryFacts(tsvRow("x", 12 to "999; 998")).getValue("x")

        assertEquals("230", facts.voltage)
        assertEquals("C", facts.plugs)
    }

    @Test
    fun `shortUtcOffset - solo il formato UTC+hh_mm si abbrevia, gli altri restano`() {
        assertEquals("UTC+14", shortUtcOffset("UTC+14:00"))
        assertEquals("UTC-12", shortUtcOffset("UTC-12:00"))
        assertEquals("UTC-0:30", shortUtcOffset("UTC-00:30"))
        assertEquals("UTC+5:45", shortUtcOffset("UTC+05:45"))
        assertEquals("UTC+1", shortUtcOffset("UTC+1"))
        assertEquals("UTC", shortUtcOffset("UTC"))
        assertEquals("GMT+01:00", shortUtcOffset("GMT+01:00"))
        assertEquals("UTC+01:00 (estate)", shortUtcOffset("UTC+01:00 (estate)"))
        assertEquals("", shortUtcOffset(""))
    }

    @Test
    fun `countryFactFields - senza dati nessun campo, con i soli codici valuta o i soli nomi`() {
        val empty = CountryFacts("", "", "", "", "", "", "", "", "", "", "", "")
        assertEquals(emptyMap<String, String>(), countryFactFields("x", english = false, facts = empty))

        val codesOnly = lesotho.copy(currencyIt = "", currencyEn = "")
        assertEquals(null, countryFactFields("x", english = false, facts = codesOnly)["Valuta"])

        val namesOnly = lesotho.copy(currency = "")
        assertEquals("loti, rand sudafricano", countryFactFields("x", english = false, facts = namesOnly)["Valuta"])
    }

    @Test
    fun `countryFactFields - una sola presa, piu' prese e prese vuote nelle due lingue`() {
        assertEquals("plug type M", countryFactFields("x", english = true, facts = lesotho.copy(voltage = ""))["Electricity"])
        assertEquals("presa M", countryFactFields("x", english = false, facts = lesotho.copy(voltage = ""))["Elettricità"])
        assertEquals("prese C, F, L", countryFactFields("x", english = false, facts = lesotho.copy(voltage = "", plugs = "C, F, L"))["Elettricità"])
        assertEquals("220 V", countryFactFields("x", english = false, facts = lesotho.copy(plugs = ""))["Elettricità"])
        assertEquals(null, countryFactFields("x", english = false, facts = lesotho.copy(plugs = "", voltage = ""))["Elettricità"])
    }

    @Test
    fun `countryFactFields - lato di guida sconosciuto, capitali e prefissi multipli, fusi in ordine`() {
        val facts = lesotho.copy(driving = "centro", capitalIt = "Pretoria; Citta' del Capo", callingCode = "+27; +266", timeZones = "UTC+02:00; UTC-04:30")

        val fields = countryFactFields("x", english = false, facts = facts)

        assertEquals(null, fields["Lato di guida"])
        assertEquals("Pretoria, Citta' del Capo", fields["Capitale"])
        assertEquals("+27, +266", fields["Prefisso telefonico"])
        assertEquals("UTC+2, UTC-4:30", fields["Fuso orario"])
        assertEquals(listOf("Capitale", "Lingua", "Elettricità", "Fuso orario", "Valuta", "Prefisso telefonico"), fields.keys.toList())
    }

    @Test
    fun `countryFactFields - la lingua ha la prima lettera maiuscola secondo la lingua della guida`() {
        val italian = countryFactFields("x", english = false, facts = lesotho.copy(languagesIt = "èbraico; arabo"))["Lingua"]
        val english = countryFactFields("x", english = true, facts = lesotho.copy(languagesEn = "ægir; Swedish"))["Language"]

        assertEquals("Èbraico, arabo", italian)
        assertEquals("Ægir, Swedish", english)
    }

    @Test
    fun `QuickFactLabels - l'ordine dei campi e' lo stesso nelle due lingue e le etichette sono distinte`() {
        assertEquals(7, QuickFactLabels.ITALIAN.order.size)
        assertEquals(7, QuickFactLabels.ENGLISH.order.size)
        assertEquals(14, (QuickFactLabels.ITALIAN.order + QuickFactLabels.ENGLISH.order).toSet().size)
        assertEquals("Capitale", QuickFactLabels.ITALIAN.order.first())
        assertEquals("Driving side", QuickFactLabels.ENGLISH.order.last())
    }

    // --- il tsv curato ---

    private fun curatedLines(): List<List<String>> =
        requireNotNull(CountryFacts::class.java.getResource("/countries.tsv")) { "countries.tsv mancante" }
            .readText().lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split("\t") }.toList()

    @Test
    fun `il tsv curato ha quindici colonne, regionId senza duplicati e valori nei formati attesi`() {
        val rows = curatedLines()
        assertTrue("troppe poche righe: ${rows.size}", rows.size > 300)
        rows.forEach { row ->
            assertEquals("${row[0]}: colonne", 15, row.size)
            assertTrue("${row[0]}: lato di guida '${row[7]}'", row[7] in setOf("right", "left", ""))
            row[11].split("; ").filter { it.isNotEmpty() }.forEach { zone ->
                assertTrue("${row[0]}: fuso '$zone'", zone.matches(Regex("UTC([+-]\\d{2}:\\d{2})?")))
            }
            row[8].split("; ").filter { it.isNotEmpty() }.forEach { code -> assertTrue("${row[0]}: prefisso '$code'", code.matches(Regex("\\+\\d+( \\d+)*"))) }
            row.forEach { cell -> assertEquals("${row[0]}: spazi ai bordi in '$cell'", cell.trim(), cell) }
        }
        assertEquals("regionId duplicati", rows.size, rows.map { it[0] }.toSet().size)
    }

    @Test
    fun `ogni regione del tsv curato e' ancora in regions_sh`() {
        val script = File("../scripts/regions.sh").takeIf { it.exists() } ?: File("scripts/regions.sh")
        val known = script.readLines().map { it.trim() }
            .filter { it.startsWith("\"") && it.count { c -> c == '|' } >= 10 }
            .map { it.trim('"').substringBefore('|') }.toSet()
        assertTrue("nessuna regione letta da ${script.path}", known.size > 100)

        assertEquals("righe di regioni che non esistono piu'", emptyList<String>(), curatedLines().map { it[0] }.filterNot { it in known })
    }
}
