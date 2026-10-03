package com.pockettravel.pipeline

import java.io.File

data class YfRiskRow(val iso2: String, val scope: String, val areasIt: String, val areasEn: String, val sources: String, val verified: String)

data class YfEntryRow(
    val iso2: String,
    val rule: String,
    val minAgeMonths: Int?,
    val transit: String,
    val fromList: String,
    val exitRequired: Boolean,
    val noteIt: String,
    val noteEn: String,
    val sources: String,
    val verified: String,
)

data class PolioStatusRow(val iso2: String, val category: String, val statement: String, val sources: String, val verified: String)

data class PolioEntryRow(
    val iso2: String,
    val origin: String,
    val vaccine: String,
    val timeWindow: String,
    val applies: String,
    val noteIt: String,
    val noteEn: String,
    val sources: String,
    val verified: String,
)

data class SpecialEntryRow(
    val iso2: String,
    val purpose: String,
    val vaccine: String,
    val minAgeMonths: Int?,
    val minDaysBefore: Int?,
    val validityYears: Int?,
    val noteIt: String,
    val noteEn: String,
    val sources: String,
    val verified: String,
)

data class RecommendedRow(
    val iso2: String,
    val vaccine: String,
    val level: String,
    val conditionIt: String,
    val conditionEn: String,
    val sources: String,
    val verified: String,
)

/**
 * Legge resources/vaccinations/<name>.tsv: salta righe vuote e commenti (`#`, comprese le righe di
 * intestazione con le fonti e i nomi delle colonne) e verifica il numero di colonne di ogni riga,
 * cosi' un tab di troppo o mancante fa fallire la generazione invece di spostare i valori.
 */
fun readVaccinationRows(name: String, columns: Int): List<List<String>> =
    parseVaccinationRows(name, vaccinationFileText(name), columns)

fun vaccinationFileText(name: String): String =
    VaccinationData::class.java.getResource("/vaccinations/$name.tsv")!!.readText()

fun parseVaccinationRows(name: String, text: String, columns: Int): List<List<String>> =
    text.lineSequence().withIndex()
        .filter { (_, line) -> line.isNotBlank() && !line.startsWith("#") }
        .map { (index, line) ->
            line.split("\t").also {
                require(it.size == columns) { "$name.tsv riga ${index + 1}: attese $columns colonne, trovate ${it.size}" }
            }
        }
        .toList()

private fun String.toIntOrNullStrict(): Int? = if (isEmpty()) null else toInt()

/**
 * Dati vaccinali curati a mano (i file TSV di resources/vaccinations, uno per regola; fonti e licenze nelle
 * righe `#` di ciascun file). Sono fatti per paese (ISO 3166-1 alpha-2 minuscolo, come il flagCode
 * delle regioni) riscritti con parole nostre, non testo copiato dalle fonti. Il calcolo del
 * percorso lo fa l'app: qui si pubblicano solo le righe.
 */
object VaccinationData {
    val yfRisk: List<YfRiskRow> by lazy {
        readVaccinationRows("yf-risk", 6).map { r -> YfRiskRow(r[0], r[1], r[2], r[3], r[4], r[5]) }
    }
    val yfEntry: List<YfEntryRow> by lazy {
        readVaccinationRows("yf-entry", 10).map { r ->
            YfEntryRow(r[0], r[1], r[2].toIntOrNullStrict(), r[3], r[4], r[5] == "1", r[6], r[7], r[8], r[9])
        }
    }
    val polioStatus: List<PolioStatusRow> by lazy {
        readVaccinationRows("polio-status", 5).map { (a, b, c, d, e) -> PolioStatusRow(a, b, c, d, e) }
    }
    val polioEntry: List<PolioEntryRow> by lazy {
        readVaccinationRows("polio-entry", 9).map { r -> PolioEntryRow(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]) }
    }
    val special: List<SpecialEntryRow> by lazy {
        readVaccinationRows("special-entry", 10).map { r ->
            SpecialEntryRow(
                r[0], r[1], r[2], r[3].toIntOrNullStrict(), r[4].toIntOrNullStrict(), r[5].toIntOrNullStrict(), r[6], r[7], r[8], r[9],
            )
        }
    }
    val recommended: List<RecommendedRow> by lazy {
        readVaccinationRows("recommended", 7).map { r -> RecommendedRow(r[0], r[1], r[2], r[3], r[4], r[5], r[6]) }
    }
}

/**
 * Tabelle "vacc_*" di guides.db (schema minimo, non quello Room), una per file di
 * resources/vaccinations: vacc_yf_risk, vacc_yf_entry, vacc_polio_status, vacc_polio_entry,
 * vacc_special, vacc_recommended, piu' vacc_meta (chiave/valore). Scrivono TUTTE le righe, senza
 * filtrare per regione: partenza e transiti di un viaggio possono essere paesi che l'app non ha
 * come regione (es. la Nigeria). Le app che non conoscono queste tabelle le ignorano.
 *
 * Colonne vuote: i numeri mancanti sono NULL, i testi mancanti sono stringa vuota. In vacc_meta:
 * polio_statement e polio_verified (ultimo statement polio dell'OMS recepito e data della
 * verifica, per l'avviso "dati polio forse non aggiornati"), last_review (verifica piu' recente di
 * tutti i file).
 */
fun writeVaccinationsTables(outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "vacc_yf_risk",
        createTableSql = """
            CREATE TABLE vacc_yf_risk (
                iso2 TEXT NOT NULL,
                scope TEXT NOT NULL,
                areasIt TEXT NOT NULL,
                areasEn TEXT NOT NULL,
                sources TEXT NOT NULL,
                verified TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO vacc_yf_risk (iso2, scope, areasIt, areasEn, sources, verified) VALUES (?, ?, ?, ?, ?, ?)",
        rows = VaccinationData.yfRisk,
    ) { insert, row ->
        insert.setString(1, row.iso2)
        insert.setString(2, row.scope)
        insert.setString(3, row.areasIt)
        insert.setString(4, row.areasEn)
        insert.setString(5, row.sources)
        insert.setString(6, row.verified)
    }
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "vacc_yf_entry",
        createTableSql = """
            CREATE TABLE vacc_yf_entry (
                iso2 TEXT NOT NULL,
                rule TEXT NOT NULL,
                minAgeMonths INTEGER,
                transit TEXT NOT NULL,
                fromList TEXT NOT NULL,
                exitRequired INTEGER NOT NULL,
                noteIt TEXT NOT NULL,
                noteEn TEXT NOT NULL,
                sources TEXT NOT NULL,
                verified TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO vacc_yf_entry (iso2, rule, minAgeMonths, transit, fromList, exitRequired, noteIt, noteEn, sources, verified) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows = VaccinationData.yfEntry,
    ) { insert, row ->
        insert.setString(1, row.iso2)
        insert.setString(2, row.rule)
        insert.setObject(3, row.minAgeMonths)
        insert.setString(4, row.transit)
        insert.setString(5, row.fromList)
        insert.setInt(6, if (row.exitRequired) 1 else 0)
        insert.setString(7, row.noteIt)
        insert.setString(8, row.noteEn)
        insert.setString(9, row.sources)
        insert.setString(10, row.verified)
    }
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "vacc_polio_status",
        createTableSql = """
            CREATE TABLE vacc_polio_status (
                iso2 TEXT NOT NULL,
                category TEXT NOT NULL,
                statement TEXT NOT NULL,
                sources TEXT NOT NULL,
                verified TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO vacc_polio_status (iso2, category, statement, sources, verified) VALUES (?, ?, ?, ?, ?)",
        rows = VaccinationData.polioStatus,
    ) { insert, row ->
        insert.setString(1, row.iso2)
        insert.setString(2, row.category)
        insert.setString(3, row.statement)
        insert.setString(4, row.sources)
        insert.setString(5, row.verified)
    }
    // "origin" e "timeWindow" e non from/window: sono parole riservate di SQLite.
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "vacc_polio_entry",
        createTableSql = """
            CREATE TABLE vacc_polio_entry (
                iso2 TEXT NOT NULL,
                origin TEXT NOT NULL,
                vaccine TEXT NOT NULL,
                timeWindow TEXT NOT NULL,
                applies TEXT NOT NULL,
                noteIt TEXT NOT NULL,
                noteEn TEXT NOT NULL,
                sources TEXT NOT NULL,
                verified TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO vacc_polio_entry (iso2, origin, vaccine, timeWindow, applies, noteIt, noteEn, sources, verified) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows = VaccinationData.polioEntry,
    ) { insert, row ->
        insert.setString(1, row.iso2)
        insert.setString(2, row.origin)
        insert.setString(3, row.vaccine)
        insert.setString(4, row.timeWindow)
        insert.setString(5, row.applies)
        insert.setString(6, row.noteIt)
        insert.setString(7, row.noteEn)
        insert.setString(8, row.sources)
        insert.setString(9, row.verified)
    }
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "vacc_special",
        createTableSql = """
            CREATE TABLE vacc_special (
                iso2 TEXT NOT NULL,
                purpose TEXT NOT NULL,
                vaccine TEXT NOT NULL,
                minAgeMonths INTEGER,
                minDaysBefore INTEGER,
                validityYears INTEGER,
                noteIt TEXT NOT NULL,
                noteEn TEXT NOT NULL,
                sources TEXT NOT NULL,
                verified TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO vacc_special (iso2, purpose, vaccine, minAgeMonths, minDaysBefore, validityYears, noteIt, noteEn, sources, verified) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows = VaccinationData.special,
    ) { insert, row ->
        insert.setString(1, row.iso2)
        insert.setString(2, row.purpose)
        insert.setString(3, row.vaccine)
        insert.setObject(4, row.minAgeMonths)
        insert.setObject(5, row.minDaysBefore)
        insert.setObject(6, row.validityYears)
        insert.setString(7, row.noteIt)
        insert.setString(8, row.noteEn)
        insert.setString(9, row.sources)
        insert.setString(10, row.verified)
    }
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "vacc_recommended",
        createTableSql = """
            CREATE TABLE vacc_recommended (
                iso2 TEXT NOT NULL,
                vaccine TEXT NOT NULL,
                level TEXT NOT NULL,
                conditionIt TEXT NOT NULL,
                conditionEn TEXT NOT NULL,
                sources TEXT NOT NULL,
                verified TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO vacc_recommended (iso2, vaccine, level, conditionIt, conditionEn, sources, verified) VALUES (?, ?, ?, ?, ?, ?, ?)",
        rows = VaccinationData.recommended,
    ) { insert, row ->
        insert.setString(1, row.iso2)
        insert.setString(2, row.vaccine)
        insert.setString(3, row.level)
        insert.setString(4, row.conditionIt)
        insert.setString(5, row.conditionEn)
        insert.setString(6, row.sources)
        insert.setString(7, row.verified)
    }
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "vacc_meta",
        createTableSql = "CREATE TABLE vacc_meta (key TEXT NOT NULL, value TEXT NOT NULL)",
        insertSql = "INSERT INTO vacc_meta (key, value) VALUES (?, ?)",
        rows = vaccinationMeta(),
    ) { insert, (key, value) ->
        insert.setString(1, key)
        insert.setString(2, value)
    }
}

private fun vaccinationMeta(): List<Pair<String, String>> {
    val polio = VaccinationData.polioStatus
    val allVerified = VaccinationData.yfRisk.map { it.verified } + VaccinationData.yfEntry.map { it.verified } +
        polio.map { it.verified } + VaccinationData.polioEntry.map { it.verified } +
        VaccinationData.special.map { it.verified } + VaccinationData.recommended.map { it.verified }
    return listOf(
        "polio_statement" to polio.map { it.statement }.distinct().single(),
        "polio_verified" to polio.maxOf { it.verified },
        "last_review" to allVerified.max(),
    )
}
