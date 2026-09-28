package com.pockettravel.pipeline

import org.json.JSONObject
import java.io.File

// Mappa titoli di sezione delle pagine citta' di Wikivoyage IT -> categoria (fase 1 di
// rag-knowledge-plan.md, "Contratto tra le parti"): diversa da headingToCategory
// (GenerateGuideContent.kt, pagine nazione) perche' le pagine citta' usano titoli di sezione
// diversi (Da sapere, Cosa vedere, Cosa fare...). Passata a parseWikivoyageDump, che riusa cosi'
// lo stesso parsing e la stessa cleanBody delle guide senza duplicarli.
private val cityHeadingToCategory = mapOf(
    "da sapere" to "DA_SAPERE",
    "come arrivare" to "TRASPORTI",
    "come spostarsi" to "TRASPORTI",
    "cosa vedere" to "COSA_VEDERE",
    "cosa fare" to "COSA_VEDERE",
    "acquisti" to "ACQUISTI",
    "dove mangiare" to "CIBO_BEVANDE",
    "dove alloggiare" to "ALLOGGIO",
    "sicurezza" to "SICUREZZA",
    "come restare in contatto" to "CONNETTIVITA",
    "informazioni utili" to "VITA_QUOTIDIANA",
)

// Stesso contratto per le pagine citta' di Wikivoyage EN (GenerateCities --lang en).
private val cityHeadingToCategoryEn = mapOf(
    "understand" to "DA_SAPERE",
    "get in" to "TRASPORTI",
    "get around" to "TRASPORTI",
    "see" to "COSA_VEDERE",
    "do" to "COSA_VEDERE",
    "buy" to "ACQUISTI",
    "eat" to "CIBO_BEVANDE",
    "drink" to "CIBO_BEVANDE",
    "sleep" to "ALLOGGIO",
    "stay safe" to "SICUREZZA",
    "connect" to "CONNETTIVITA",
    "cope" to "VITA_QUOTIDIANA",
)

data class CitySectionRow(val city: String, val category: String, val title: String, val body: String, val sourceUrl: String)

/**
 * Righe di un <regionId>.cities.jsonl (una per citta': {"city": titolo, "text": wikitext grezzo}),
 * scritto da extract-cities-dump.py (build-cities-dump.sh) con un solo passaggio sul dump di
 * Wikivoyage IT per tutte le regioni -> le sue city_sections.
 */
fun parseCitiesJsonl(jsonl: String, english: Boolean = false): List<CitySectionRow> =
    jsonl.lineSequence().filter { it.isNotBlank() }.flatMap { line ->
        val obj = JSONObject(line)
        val city = obj.getString("city")
        val sourceUrl = (if (english) "https://en.wikivoyage.org/wiki/" else "https://it.wikivoyage.org/wiki/") + city.replace(" ", "_")
        parseWikivoyageDump(obj.getString("text"), if (english) cityHeadingToCategoryEn else cityHeadingToCategory).map { section ->
            CitySectionRow(city = city, category = section.category, title = section.title, body = section.body, sourceUrl = sourceUrl)
        }
    }.toList()

/**
 * Schema minimo (non lo schema Room di CitySectionEntity, niente FTS4): una tabella
 * "city_sections" con le stesse colonne meno l'id autogenerato, come guide_sections in
 * GenerateGuideContent.kt — l'app importa riga per riga in region.db via CityDao.insertAll().
 */
fun writeCitiesDb(sections: List<CitySectionRow>, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "city_sections",
        createTableSql = """
            CREATE TABLE city_sections (
                city TEXT NOT NULL,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                sourceUrl TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO city_sections (city, category, title, body, sourceUrl) VALUES (?, ?, ?, ?, ?)",
        rows = sections,
    ) { insert, section ->
        insert.setString(1, section.city)
        insert.setString(2, section.category)
        insert.setString(3, section.title)
        insert.setString(4, section.body)
        insert.setString(5, section.sourceUrl)
    }
}

/**
 * Genera cities.db di UNA regione (vedi build-cities.sh): un solo file, non un pacchetto unico
 * come guides.db, perche' le citta' pesano troppo per stare tutte in un pacchetto scaricato da
 * ogni app (vedi le misure in rag-knowledge-plan.md).
 *
 * cities.jsonl assente o vuoto (nessuna citta' abbinata alla regione in questo run, vedi
 * build-cities-dump.sh) o senza sezioni utili: nessun cities.db scritto, il chiamante tiene la
 * voce "cities" gia' pubblicata (se c'e').
 */
fun main(rawArgs: Array<String>) {
    // --lang en: pagine di Wikivoyage EN (<regionId>.cities-en.jsonl, extract-cities-dump-en.py).
    val english = rawArgs.firstOrNull() == "--lang" && rawArgs.getOrNull(1) == "en"
    val args = if (rawArgs.firstOrNull() == "--lang") rawArgs.drop(2) else rawArgs.toList()
    require(args.size == 2) { "Uso: generateCities [--lang en] <cities.jsonl> <output cities.db>" }
    val jsonlFile = File(args[0])
    val outputDb = File(args[1])
    outputDb.delete()

    val sections = if (jsonlFile.exists() && jsonlFile.length() > 0) parseCitiesJsonl(jsonlFile.readText(), english) else emptyList()
    if (sections.isEmpty()) {
        println("citta': nessuna sezione, cities.db non generato")
        return
    }
    writeCitiesDb(sections, outputDb)
    println("citta': ${sections.size} sezioni di ${sections.map { it.city }.distinct().size} citta' scritte in ${outputDb.path}")
}
