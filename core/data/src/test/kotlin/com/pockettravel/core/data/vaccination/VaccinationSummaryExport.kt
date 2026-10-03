package com.pockettravel.core.data.vaccination

import com.pockettravel.core.data.db.VaccMetaEntity
import com.pockettravel.core.data.db.VaccPolioEntryEntity
import com.pockettravel.core.data.db.VaccPolioStatusEntity
import com.pockettravel.core.data.db.VaccRecommendedEntity
import com.pockettravel.core.data.db.VaccSpecialEntity
import com.pockettravel.core.data.db.VaccYfEntryEntity
import com.pockettravel.core.data.db.VaccYfRiskEntity
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.LocalDate
import kotlin.random.Random

/**
 * Non e' un test: esporta i riassunti delle vaccinazioni (toSummaryText, lo stesso testo che l'assistente
 * mette nel contesto) per molti percorsi, in italiano e in inglese, per il dataset di training
 * (tools/data-pipeline/scripts/generate_sft.py --vaccinations). Gira solo con le variabili d'ambiente
 * VACC_GUIDES_DB (un guides.db con le tabelle vacc_*) e VACC_EXPORT_OUT (file JSONL da scrivere):
 *   VACC_GUIDES_DB=... VACC_EXPORT_OUT=... ./gradlew :core:data:testDebugUnitTest --tests '*VaccinationSummaryExport*'
 */
class VaccinationSummaryExport {
    @Test
    fun export() {
        val db = System.getenv("VACC_GUIDES_DB")
        val out = System.getenv("VACC_EXPORT_OUT")
        assumeTrue("VACC_GUIDES_DB e VACC_EXPORT_OUT non impostate", db != null && out != null)
        val data = DriverManager.getConnection("jdbc:sqlite:$db").use(::readData)
        val today = LocalDate.parse(data.meta.lastReview ?: LocalDate.now().toString())
        val random = Random(42)
        val risk = data.yfRisk.map { it.iso2 }.sorted()
        val polio = data.polioStatus.filter { it.category == PolioCategory.WPV1_CVDPV1_CVDPV3 }.map { it.iso2 }.sorted()
        val lines = mutableListOf<String>()
        for (destination in data.yfEntry.map { it.iso2 }.sorted()) {
            val trips = buildList {
                add(Trip(departure = COMMON_DEPARTURES.random(random), destination = destination))
                add(Trip(departure = risk.random(random), destination = destination))
                if (random.nextInt(3) == 0) {
                    add(Trip(departure = COMMON_DEPARTURES.random(random), destination = destination,
                        transits = listOf(TripLeg(risk.random(random), transitHours = listOf(3, 14, 26).random(random), leftAirport = false))))
                }
                if (random.nextInt(4) == 0) add(Trip(departure = risk.random(random), destination = destination, travellerAgeMonths = 8))
                if (random.nextInt(4) == 0) {
                    add(Trip(departure = polio.random(random), destination = destination, stayOverFourWeeksInDeparture = true))
                }
                if (destination == "sa") add(Trip(departure = COMMON_DEPARTURES.random(random), destination = destination, purpose = TripPurpose.HAJJ_UMRAH))
            }.filter { it.departure != it.destination }
            for (trip in trips) {
                val result = evaluateVaccinations(trip, data, today)
                for (language in listOf("it", "en")) {
                    fun vaccines(vararg levels: VaccinationLevel) =
                        result.items.filter { it.level in levels }.map { json(it.vaccine.name) }.distinct().joinToString(",", "[", "]")
                    lines += "{" + listOf(
                        "\"destination\":" + json(destination),
                        "\"departure\":" + json(trip.departure),
                        "\"language\":" + json(language),
                        "\"text\":" + json(result.toSummaryText(trip, language)),
                        "\"required\":" + vaccines(VaccinationLevel.REQUIRED, VaccinationLevel.REQUIRED_EXIT),
                        "\"recommended\":" + vaccines(VaccinationLevel.RECOMMENDED),
                        "\"consider\":" + vaccines(VaccinationLevel.CONSIDER),
                    ).joinToString(",") + "}"
                }
            }
        }
        File(out).writeText(lines.joinToString("\n", postfix = "\n"))
        println("riassunti delle vaccinazioni: ${lines.size} in $out")
    }

    private fun readData(c: Connection): VaccinationData = vaccinationDataFrom(
        yfRisk = c.rows("vacc_yf_risk") { VaccYfRiskEntity(iso2 = s("iso2"), scope = s("scope"), areasIt = s("areasIt"), areasEn = s("areasEn"), sources = s("sources"), verified = s("verified")) },
        yfEntry = c.rows("vacc_yf_entry") {
            VaccYfEntryEntity(iso2 = s("iso2"), rule = s("rule"), minAgeMonths = i("minAgeMonths"), transit = s("transit"), fromList = s("fromList"),
                exitRequired = getInt("exitRequired") == 1, noteIt = s("noteIt"), noteEn = s("noteEn"), sources = s("sources"), verified = s("verified"))
        },
        polioStatus = c.rows("vacc_polio_status") { VaccPolioStatusEntity(iso2 = s("iso2"), category = s("category"), statement = s("statement"), sources = s("sources"), verified = s("verified")) },
        polioEntry = c.rows("vacc_polio_entry") {
            VaccPolioEntryEntity(iso2 = s("iso2"), origin = s("origin"), vaccine = s("vaccine"), timeWindow = s("timeWindow"), applies = s("applies"),
                noteIt = s("noteIt"), noteEn = s("noteEn"), sources = s("sources"), verified = s("verified"))
        },
        special = c.rows("vacc_special") {
            VaccSpecialEntity(iso2 = s("iso2"), purpose = s("purpose"), vaccine = s("vaccine"), minAgeMonths = i("minAgeMonths"), minDaysBefore = i("minDaysBefore"),
                validityYears = i("validityYears"), noteIt = s("noteIt"), noteEn = s("noteEn"), sources = s("sources"), verified = s("verified"))
        },
        recommended = c.rows("vacc_recommended") {
            VaccRecommendedEntity(iso2 = s("iso2"), vaccine = s("vaccine"), level = s("level"), conditionIt = s("conditionIt"), conditionEn = s("conditionEn"), sources = s("sources"), verified = s("verified"))
        },
        meta = c.rows("vacc_meta") { VaccMetaEntity(key = s("key"), value = s("value")) },
    )

    private fun <T> Connection.rows(table: String, map: ResultSet.() -> T): List<T> =
        createStatement().use { st -> st.executeQuery("SELECT * FROM $table").use { rs -> buildList { while (rs.next()) add(rs.map()) } } }

    // org.json nei test JVM di un modulo Android e' solo uno stub: JSON scritto a mano
    private fun json(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun ResultSet.s(column: String): String = getString(column) ?: ""
    private fun ResultSet.i(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private companion object {
        // Partenze tipiche di chi usa l'app (Europa, Nord America, Oceania, Asia orientale)
        val COMMON_DEPARTURES = listOf("it", "gb", "de", "fr", "es", "nl", "ch", "us", "ca", "au", "jp", "cn", "in", "br")
    }
}
