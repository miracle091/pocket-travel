package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

class GenerateVaccinationsTest {

    // Codici ISO che Locale.getISOCountries() non ha: Kosovo e Isole Canarie (come il flagCode delle regioni).
    private val isoExceptions = setOf("xk", "ic")
    private val validIso: Set<String> = Locale.getISOCountries().map { it.lowercase() }.toSet() + isoExceptions

    private val polioCategories = setOf("WPV1_CVDPV1_CVDPV3", "CVDPV2", "PREVIOUSLY_INFECTED")

    private val files = mapOf(
        "yf-risk" to 6,
        "yf-entry" to 10,
        "polio-status" to 5,
        "polio-entry" to 9,
        "special-entry" to 10,
        "recommended" to 7,
    )

    /** Sigle dichiarate nelle righe `# F6 = ...` di intestazione del file. */
    private fun declaredSources(name: String): Set<String> =
        Regex("^#\\s*(F\\d+)\\s*=", RegexOption.MULTILINE).findAll(vaccinationFileText(name)).map { it.groupValues[1] }.toSet()

    private fun rows(name: String): List<List<String>> = readVaccinationRows(name, files.getValue(name))

    private fun checkIso(name: String, iso: String) {
        assertTrue("$name: codice ISO non valido '$iso'", iso in validIso)
    }

    private fun checkIsoList(name: String, list: String) {
        assertTrue("$name: lista vuota", list.isNotEmpty())
        list.split(",").forEach { checkIso(name, it) }
    }

    private fun checkNoDuplicates(name: String, keys: List<String>) {
        val duplicates = keys.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("$name: righe duplicate $duplicates", duplicates.isEmpty())
    }

    private fun checkSourcesAndDate(name: String, sources: String, verified: String) {
        val declared = declaredSources(name)
        assertTrue("$name: fonti vuote", sources.isNotEmpty())
        sources.split("+").forEach { assertTrue("$name: sigla '$it' non dichiarata nell'intestazione ($declared)", it in declared) }
        val date = LocalDate.parse(verified)
        assertTrue("$name: verificato $verified e' nel futuro", !date.isAfter(LocalDate.now()))
    }

    private fun checkAge(name: String, months: Int?) {
        if (months != null) assertTrue("$name: eta' $months fuori da 0-24 mesi", months in 0..24)
    }

    @Test
    fun `i file hanno il numero di colonne atteso e almeno una riga`() {
        files.forEach { (name, _) -> assertTrue("$name vuoto", rows(name).isNotEmpty()) }
    }

    @Test
    fun `una riga con il numero di colonne sbagliato fa fallire la lettura`() {
        try {
            parseVaccinationRows("prova", "# commento\na\tb\tc\n", 2)
            fail("attesa IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("riga 2"))
        }
    }

    @Test
    fun `yf-risk ha ISO validi senza duplicati e valori ammessi`() {
        val data = VaccinationData.yfRisk
        checkNoDuplicates("yf-risk", data.map { it.iso2 })
        data.forEach {
            checkIso("yf-risk", it.iso2)
            assertTrue("yf-risk ${it.iso2}: scope ${it.scope}", it.scope in setOf("WHOLE", "PARTIAL"))
            if (it.scope == "PARTIAL") assertTrue("yf-risk ${it.iso2}: PARTIAL senza aree", it.areasIt.isNotEmpty() && it.areasEn.isNotEmpty())
            checkSourcesAndDate("yf-risk", it.sources, it.verified)
        }
    }

    @Test
    fun `yf-entry ha ISO validi senza duplicati, regole coerenti e eta' tra 0 e 24 mesi`() {
        val data = VaccinationData.yfEntry
        checkNoDuplicates("yf-entry", data.map { it.iso2 })
        // La colonna exit_required deve essere 0 o 1 (il parser tratterebbe qualunque altro valore come 0).
        rows("yf-entry").forEach { assertTrue("yf-entry ${it[0]}: exit_required '${it[5]}'", it[5] in setOf("0", "1")) }
        data.forEach {
            checkIso("yf-entry", it.iso2)
            assertTrue("yf-entry ${it.iso2}: rule ${it.rule}", it.rule in setOf("NONE", "FROM_RISK", "ALL", "FROM_LIST"))
            assertTrue("yf-entry ${it.iso2}: transit ${it.transit}", it.transit in setOf("NONE", "ANY", "GT4H", "GT12H", "GT24H"))
            // from_list vuoto se e solo se rule != FROM_LIST.
            if (it.rule == "FROM_LIST") checkIsoList("yf-entry ${it.iso2}", it.fromList)
            else assertEquals("yf-entry ${it.iso2}: from_list con rule ${it.rule}", "", it.fromList)
            if (it.rule == "NONE") {
                assertEquals("yf-entry ${it.iso2}: transit con rule NONE", "NONE", it.transit)
                assertEquals("yf-entry ${it.iso2}: min_age con rule NONE", null, it.minAgeMonths)
            }
            checkAge("yf-entry ${it.iso2}", it.minAgeMonths)
            checkSourcesAndDate("yf-entry", it.sources, it.verified)
        }
    }

    @Test
    fun `ogni paese dell'elenco delle regioni ha una riga in yf-entry`() {
        val script = File("../scripts/regions.sh").takeIf { it.exists() } ?: File("scripts/regions.sh")
        // Righe "regionId|displayName|minLon|minLat|maxLon|maxLat|wikivoyagePageTitle|flagCode|...": flagCode e' l'ottavo campo.
        val flagCodes = script.readLines()
            .map { it.trim() }
            .filter { it.startsWith("\"") && it.count { c -> c == '|' } >= 10 }
            .map { it.trim('"').split("|")[7] }
            .toSet()
        assertTrue("nessun flagCode letto da ${script.path}", flagCodes.size > 100)
        val covered = VaccinationData.yfEntry.map { it.iso2 }.toSet()
        assertEquals("flagCode senza riga in yf-entry", emptySet<String>(), flagCodes - covered)
    }

    @Test
    fun `polio-status ha ISO validi senza duplicati, categorie ammesse e un solo statement`() {
        val data = VaccinationData.polioStatus
        checkNoDuplicates("polio-status", data.map { it.iso2 })
        data.forEach {
            checkIso("polio-status", it.iso2)
            assertTrue("polio-status ${it.iso2}: category ${it.category}", it.category in polioCategories)
            assertTrue("polio-status ${it.iso2}: statement vuoto", it.statement.isNotEmpty())
            checkSourcesAndDate("polio-status", it.sources, it.verified)
        }
        assertEquals("piu' statement diversi in polio-status", 1, data.map { it.statement }.distinct().size)
    }

    @Test
    fun `polio-entry ha ISO validi senza duplicati, origini e valori ammessi`() {
        val data = VaccinationData.polioEntry
        checkNoDuplicates("polio-entry", data.map { it.iso2 + "|" + it.origin })
        data.forEach {
            checkIso("polio-entry", it.iso2)
            if (it.origin.startsWith("CAT:")) {
                assertTrue("polio-entry ${it.iso2}: origine ${it.origin}", it.origin.removePrefix("CAT:") in polioCategories)
            } else if (it.origin != "UNLISTED") {
                checkIsoList("polio-entry ${it.iso2}", it.origin)
            }
            assertTrue("polio-entry ${it.iso2}: vaccine ${it.vaccine}", it.vaccine in setOf("BOPV_OR_IPV", "IPV"))
            assertTrue("polio-entry ${it.iso2}: window ${it.timeWindow}", it.timeWindow in setOf("4W_12M", "ANY"))
            assertTrue("polio-entry ${it.iso2}: applies ${it.applies}", it.applies in setOf("ALL", "VISA", "RESIDENTS", "HAJJ_UMRAH"))
            checkSourcesAndDate("polio-entry", it.sources, it.verified)
        }
    }

    @Test
    fun `special-entry ha ISO validi senza duplicati e valori ammessi`() {
        val data = VaccinationData.special
        checkNoDuplicates("special-entry", data.map { it.iso2 + "|" + it.purpose + "|" + it.vaccine })
        data.forEach {
            checkIso("special-entry", it.iso2)
            assertTrue("special-entry ${it.iso2}: purpose ${it.purpose}", it.purpose in setOf("HAJJ_UMRAH"))
            assertTrue("special-entry ${it.iso2}: vaccine ${it.vaccine}", it.vaccine in setOf("MENACWY"))
            checkAge("special-entry ${it.iso2}", it.minAgeMonths)
            it.minDaysBefore?.let { days -> assertTrue("special-entry ${it.iso2}: giorni $days", days in 0..365) }
            it.validityYears?.let { years -> assertTrue("special-entry ${it.iso2}: validita' $years", years in 1..10) }
            checkSourcesAndDate("special-entry", it.sources, it.verified)
        }
    }

    @Test
    fun `recommended ha ISO validi senza duplicati, vaccini e livelli ammessi`() {
        val data = VaccinationData.recommended
        checkNoDuplicates("recommended", data.map { it.iso2 + "|" + it.vaccine })
        val vaccines = setOf("HEPA", "HEPB", "TYPHOID", "RABIES", "JE", "TBE", "CHOLERA", "MENACWY", "YF", "DENGUE", "CHIK")
        data.forEach {
            checkIso("recommended", it.iso2)
            assertTrue("recommended ${it.iso2}: vaccine ${it.vaccine}", it.vaccine in vaccines)
            assertTrue("recommended ${it.iso2}: level ${it.level}", it.level in setOf("MOST", "SOME"))
            assertTrue("recommended ${it.iso2} ${it.vaccine}: condizioni vuote", it.conditionIt.isNotEmpty() && it.conditionEn.isNotEmpty())
            checkSourcesAndDate("recommended", it.sources, it.verified)
        }
    }

    @Test
    fun `avvisa se lo statement polio ha piu' di 120 giorni`() {
        // Lo statement del comitato OMS si rinnova ogni 3 mesi: un warning (non un errore) ricorda di rifare polio-status.
        val verified = LocalDate.parse(VaccinationData.polioStatus.maxOf { it.verified })
        val age = ChronoUnit.DAYS.between(verified, LocalDate.now())
        if (age > 120) System.err.println("ATTENZIONE: polio-status verificato il $verified ($age giorni fa): controlla se esiste uno statement piu' recente")
    }

    @Test
    fun `scrive le tabelle vacc_ in un db temporaneo con tutte le righe`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".guides.db")
        outputDb.delete()

        try {
            writeVaccinationsTables(outputDb)
            // Una seconda scrittura rimpiazza le tabelle, non accumula righe.
            writeVaccinationsTables(outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                fun count(table: String): Int = conn.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) }
                }
                assertEquals(VaccinationData.yfRisk.size, count("vacc_yf_risk"))
                assertEquals(VaccinationData.yfEntry.size, count("vacc_yf_entry"))
                assertEquals(VaccinationData.polioStatus.size, count("vacc_polio_status"))
                assertEquals(VaccinationData.polioEntry.size, count("vacc_polio_entry"))
                assertEquals(VaccinationData.special.size, count("vacc_special"))
                assertEquals(VaccinationData.recommended.size, count("vacc_recommended"))
                assertEquals(3, count("vacc_meta"))

                conn.createStatement().use { statement ->
                    // Un paese senza obbligo e' una riga NONE (dato verificato), con eta' NULL.
                    val none = statement.executeQuery("SELECT rule, minAgeMonths, exitRequired FROM vacc_yf_entry WHERE iso2 = 'it'")
                    assertTrue(none.next())
                    assertEquals("NONE", none.getString("rule"))
                    none.getInt("minAgeMonths")
                    assertTrue(none.wasNull())
                }
                conn.createStatement().use { statement ->
                    val meta = statement.executeQuery("SELECT key, value FROM vacc_meta ORDER BY key")
                    val values = buildMap { while (meta.next()) put(meta.getString(1), meta.getString(2)) }
                    assertEquals(setOf("last_review", "polio_statement", "polio_verified"), values.keys)
                    assertEquals(VaccinationData.polioStatus.first().statement, values.getValue("polio_statement"))
                }
            }
        } finally {
            outputDb.delete()
        }
    }
}
