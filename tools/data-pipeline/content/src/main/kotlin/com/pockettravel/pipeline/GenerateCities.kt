package com.pockettravel.pipeline

import org.json.JSONObject
import java.io.File

// Mappa titoli di sezione delle pagine citta' di Wikivoyage IT -> categoria (contratto
// tra pipeline e app, vedi GuideCategory): diversa da headingToCategory
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

// Sezioni della voce di Wikipedia della citta' (city_wikipedia.py le estrae gia' come "== Titolo ==", anche
// quando nella voce sono sottosezioni, es. "=== Clima ===" sotto "Geografia fisica").
private val wikipediaHeadingToCategory = mapOf(
    "storia" to "STORIA",
    "clima" to "CLIMA",
)
private val wikipediaHeadingToCategoryEn = mapOf(
    "history" to "STORIA",
    "climate" to "CLIMA",
)

// La Storia di Wikipedia e' lunga (15-30 KB per una citta' media): se ne tiene l'inizio, il resto e' sulla
// voce collegata tra le fonti. Il Clima resta intero (pochi KB, le tabelle climatiche sono gia' tolte).
private const val STORIA_MAX_CHARS = 4000
private val sentenceEndRegex = Regex("""[.!?](?=\s|$)""")

/**
 * [body] accorciato a [maxChars]: alla fine dell'ultimo paragrafo (riga) che ci sta intero, senza un
 * sottotitolo "▸" rimasto in fondo; con un solo paragrafo troppo lungo, all'ultima frase intera.
 */
internal fun truncateSection(body: String, maxChars: Int = STORIA_MAX_CHARS): String {
    if (body.length <= maxChars) return body
    val window = body.take(maxChars + 1)
    val lines = window.substring(0, window.lastIndexOf('\n').coerceAtLeast(0)).trimEnd().lines()
        .dropLastWhile { it.isBlank() || it.startsWith("▸ ") }
    if (lines.isNotEmpty()) return lines.joinToString("\n").trimEnd()
    val lastSentenceEnd = sentenceEndRegex.findAll(window.take(maxChars)).lastOrNull()
    return (lastSentenceEnd?.let { window.substring(0, it.range.last + 1) } ?: window.take(maxChars)).trimEnd()
}

/**
 * [body] senza le frasi che chiudono un paragrafo con ":" (Wikipedia: "Here are climate normals for ...:"):
 * introducevano una tabella o un elenco che cleanBody ha tolto. Il paragrafo fatto solo di quella frase sparisce.
 */
internal fun dropDanglingIntros(body: String): String =
    body.lines().mapNotNull { line ->
        val trimmed = line.trimEnd()
        if (!trimmed.endsWith(':')) return@mapNotNull line
        val lastSentenceEnd = sentenceEndRegex.findAll(trimmed).lastOrNull() ?: return@mapNotNull null
        trimmed.substring(0, lastSentenceEnd.range.last + 1)
    }.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()

/**
 * [population]: abitanti della citta' (city_population.py: Abitanti del QuickbarCity o Wikidata), null se ignota;
 * [capital]: e' la capitale della regione (Wikidata P36); [latitude], [longitude]: coordinate della citta' (Wikidata P625),
 * null se ignote, per la distanza tra due citta' dell'assistente.
 */
data class CitySectionRow(
    val city: String,
    val category: String,
    val title: String,
    val body: String,
    val sourceUrl: String,
    val population: Long? = null,
    val capital: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val translated: Boolean = false,
    val translatedBy: String? = null,
)

/**
 * Righe di un <regionId>.cities.jsonl (una per citta': {"city": titolo, "text": wikitext grezzo}),
 * scritto da extract-cities-dump.py (build-cities-dump.sh) con un solo passaggio sul dump di
 * Wikivoyage IT per tutte le regioni -> le sue city_sections. Il campo facoltativo "wikipedia"
 * ({"title": voce, "text": sezioni Storia/Clima in wikitext}, city_wikipedia.py) aggiunge le sezioni
 * STORIA e CLIMA dopo quelle di Wikivoyage, pulite con la stessa cleanBody.
 */
fun parseCitiesJsonl(jsonl: String, english: Boolean = false): List<CitySectionRow> =
    jsonl.lineSequence().filter { it.isNotBlank() }.flatMap { line ->
        val obj = JSONObject(line)
        val city = obj.getString("city")
        val population = if (obj.isNull("population")) null else obj.optLong("population").takeIf { it > 0 }
        val capital = obj.optBoolean("capital", false)
        val latitude = if (obj.isNull("lat")) null else obj.optDouble("lat").takeUnless { it.isNaN() }
        val longitude = if (obj.isNull("lon")) null else obj.optDouble("lon").takeUnless { it.isNaN() }
        val lang = if (english) "en" else "it"
        val sourceUrl = "https://$lang.wikivoyage.org/wiki/" + city.replace(" ", "_")
        val wikivoyage = parseWikivoyageDump(obj.getString("text"), if (english) cityHeadingToCategoryEn else cityHeadingToCategory).map { section ->
            CitySectionRow(city = city, category = section.category, title = section.title, body = section.body, sourceUrl = sourceUrl, population = population, capital = capital, latitude = latitude, longitude = longitude)
        }
        val wikipedia = obj.optJSONObject("wikipedia")?.let { wp ->
            val wpUrl = "https://$lang.wikipedia.org/wiki/" + wp.getString("title").replace(" ", "_")
            parseWikivoyageDump(wp.getString("text"), if (english) wikipediaHeadingToCategoryEn else wikipediaHeadingToCategory).mapNotNull { section ->
                val cleaned = dropDanglingIntros(section.body)
                val body = if (section.category == "STORIA") truncateSection(cleaned) else cleaned
                if (body.isEmpty()) return@mapNotNull null
                CitySectionRow(city = city, category = section.category, title = section.title, body = body, sourceUrl = wpUrl, population = population, capital = capital, latitude = latitude, longitude = longitude)
            }
        }.orEmpty()
        wikivoyage + wikipedia
    }.toList()

/** Clima di un mese ([month] 1..12) di una citta': valori null se la voce non li da'. */
data class CityClimateRow(
    val city: String,
    val month: Int,
    val tempMax: Double?,
    val tempMin: Double?,
    val precipMm: Double?,
    val rainDays: Double?,
    val sourceUrl: String,
)

private fun JSONObject.numberOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

/**
 * Righe di clima del campo facoltativo "climate" di ogni riga del cities.jsonl
 * ({"title": voce, "months": [12 oggetti da gennaio con tempMax, tempMin, precipMm, rainDays]}):
 * una riga per citta' e mese con almeno un valore, i mesi tutti null si saltano.
 */
fun parseCitiesClimateJsonl(jsonl: String, english: Boolean = false): List<CityClimateRow> =
    jsonl.lineSequence().filter { it.isNotBlank() }.flatMap { line ->
        val obj = JSONObject(line)
        val climate = obj.optJSONObject("climate") ?: return@flatMap emptySequence()
        val months = climate.optJSONArray("months") ?: return@flatMap emptySequence()
        val city = obj.getString("city")
        val lang = if (english) "en" else "it"
        val sourceUrl = "https://$lang.wikipedia.org/wiki/" + climate.getString("title").replace(" ", "_")
        (0 until minOf(months.length(), 12)).asSequence().mapNotNull { index ->
            val month = months.optJSONObject(index) ?: return@mapNotNull null
            val row = CityClimateRow(
                city = city,
                month = index + 1,
                tempMax = month.numberOrNull("tempMax"),
                tempMin = month.numberOrNull("tempMin"),
                precipMm = month.numberOrNull("precipMm"),
                rainDays = month.numberOrNull("rainDays"),
                sourceUrl = sourceUrl,
            )
            row.takeUnless { it.tempMax == null && it.tempMin == null && it.precipMm == null && it.rainDays == null }
        }
    }.toList()

/** Tabella "city_climate" di cities.db (sempre scritta, vuota se nessuna citta' ha un clima). */
private fun writeCityClimateTable(climate: List<CityClimateRow>, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "city_climate",
        createTableSql = """
            CREATE TABLE city_climate (
                city TEXT NOT NULL,
                month INTEGER NOT NULL,
                tempMax REAL,
                tempMin REAL,
                precipMm REAL,
                rainDays REAL,
                sourceUrl TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO city_climate (city, month, tempMax, tempMin, precipMm, rainDays, sourceUrl) VALUES (?, ?, ?, ?, ?, ?, ?)",
        rows = climate,
    ) { insert, row ->
        insert.setString(1, row.city)
        insert.setInt(2, row.month)
        row.tempMax?.let { insert.setDouble(3, it) } ?: insert.setNull(3, java.sql.Types.REAL)
        row.tempMin?.let { insert.setDouble(4, it) } ?: insert.setNull(4, java.sql.Types.REAL)
        row.precipMm?.let { insert.setDouble(5, it) } ?: insert.setNull(5, java.sql.Types.REAL)
        row.rainDays?.let { insert.setDouble(6, it) } ?: insert.setNull(6, java.sql.Types.REAL)
        insert.setString(7, row.sourceUrl)
    }
}

/**
 * Schema minimo (non lo schema Room di CitySectionEntity, niente FTS4): una tabella
 * "city_sections" con le stesse colonne meno l'id autogenerato (population ripetuta su ogni sezione della citta'), come guide_sections in
 * GenerateGuideContent.kt — l'app importa riga per riga in region.db via CityDao.insertAll().
 * Segue la tabella "city_climate" con il clima mensile ([climate]).
 */
fun writeCitiesDb(sections: List<CitySectionRow>, outputDb: File, climate: List<CityClimateRow> = emptyList()) {
    writeCityClimateTable(climate, outputDb)
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "city_sections",
        createTableSql = """
            CREATE TABLE city_sections (
                city TEXT NOT NULL,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                sourceUrl TEXT NOT NULL,
                population INTEGER,
                capital INTEGER NOT NULL DEFAULT 0,
                latitude REAL,
                longitude REAL,
                translated INTEGER NOT NULL DEFAULT 0,
                translatedBy TEXT
            )
            """.trimIndent(),
        insertSql = "INSERT INTO city_sections (city, category, title, body, sourceUrl, population, capital, latitude, longitude, translated, translatedBy) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows = sections,
    ) { insert, section ->
        insert.setString(1, section.city)
        insert.setString(2, section.category)
        insert.setString(3, section.title)
        insert.setString(4, section.body)
        insert.setString(5, section.sourceUrl)
        section.population?.let { insert.setLong(6, it) } ?: insert.setNull(6, java.sql.Types.INTEGER)
        insert.setInt(7, if (section.capital) 1 else 0)
        section.latitude?.let { insert.setDouble(8, it) } ?: insert.setNull(8, java.sql.Types.REAL)
        section.longitude?.let { insert.setDouble(9, it) } ?: insert.setNull(9, java.sql.Types.REAL)
        insert.setInt(10, if (section.translated) 1 else 0)
        insert.setString(11, section.translatedBy)
    }
}

/**
 * Genera cities.db di UNA regione (vedi build-cities.sh): un solo file, non un pacchetto unico
 * come guides.db, perche' le citta' pesano troppo per stare tutte in un pacchetto scaricato da
 * ogni app.
 *
 * cities.jsonl assente o vuoto (nessuna citta' abbinata alla regione in questa run, vedi
 * build-cities-dump.sh) o senza sezioni utili: nessun cities.db scritto, il chiamante tiene la
 * voce "cities" gia' pubblicata (se c'e').
 */
fun main(rawArgs: Array<String>) {
    // --lang en: pagine di Wikivoyage EN (<regionId>.cities-en.jsonl, extract-cities-dump-en.py).
    val english = rawArgs.firstOrNull() == "--lang" && rawArgs.getOrNull(1) == "en"
    val args = if (rawArgs.firstOrNull() == "--lang") rawArgs.drop(2) else rawArgs.toList()
    // --translated <jsonl>: sezioni tradotte dall'altra lingua (translate_guides.py cities), al posto di quelle povere.
    val translatedIndex = args.indexOf("--translated")
    val translatedFile = if (translatedIndex >= 0) args.getOrNull(translatedIndex + 1)?.let(::File) else null
    val positional = if (translatedIndex >= 0) args.take(translatedIndex) + args.drop(translatedIndex + 2) else args
    require(positional.size == 2) { "Uso: generateCities [--lang en] [--translated <tradotte.jsonl>] <cities.jsonl> <output cities.db>" }
    val jsonlFile = File(positional[0])
    val outputDb = File(positional[1])
    outputDb.delete()

    val translated = translatedFile?.takeIf { it.exists() }?.let { parseTranslatedSections(it.readText()) }.orEmpty()
    val sections = (if (jsonlFile.exists() && jsonlFile.length() > 0) parseCitiesJsonl(jsonlFile.readText(), english) else emptyList())
        .withCityTranslations(translated)
    if (sections.isEmpty()) {
        println("citta': nessuna sezione, cities.db non generato")
        return
    }
    val climate = parseCitiesClimateJsonl(jsonlFile.readText(), english)
    writeCitiesDb(sections, outputDb, climate)
    println("citta': ${sections.size} sezioni di ${sections.map { it.city }.distinct().size} citta' e ${climate.size} mesi di clima scritti in ${outputDb.path}")
}
