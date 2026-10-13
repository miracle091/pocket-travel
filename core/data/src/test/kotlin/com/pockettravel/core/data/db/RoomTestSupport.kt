package com.pockettravel.core.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection

private fun moduleFile(relative: String): File {
    val file = listOf(relative, "core/data/$relative").map(::File).firstOrNull { it.exists() }
    return requireNotNull(file) { "$relative non trovato dalla cartella ${File(".").absolutePath}" }
}

/** La versione corrente del database, letta da `@Database(version = ...)` nel sorgente (l'annotazione non e' leggibile a runtime). */
internal fun currentRoomVersion(): Int {
    val source = moduleFile("src/main/kotlin/com/pockettravel/core/data/db/RegionDatabase.kt").readText()
    val version = Regex("""@Database\(.*?\bversion\s*=\s*(\d+)""", RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1)
    return requireNotNull(version?.toInt()) { "version non trovata in @Database di RegionDatabase.kt" }
}

/** Crea in [target] il database come lo avrebbe Room alla [version], dallo schema esportato: tabelle, indici e trigger delle FTS. */
internal fun createRoomSchema(version: Int, target: Connection) {
    val file = moduleFile("schemas/com.pockettravel.core.data.db.RegionDatabase/$version.json")
    val entities = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
    for (entity in entities.map { it.jsonObject }) {
        val table = entity.getValue("tableName").jsonPrimitive.content
        fun sql(text: String) = text.replace("\${TABLE_NAME}", table)
        target.createStatement().use { statement ->
            statement.execute(sql(entity.getValue("createSql").jsonPrimitive.content))
            (entity["indices"] as? JsonArray).orEmpty().forEach { statement.execute(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
            (entity["contentSyncTriggers"] as? JsonArray).orEmpty().forEach { statement.execute(it.jsonPrimitive.content) }
        }
    }
}

/**
 * Il testo SQL degli `@Query` di un DAO, per nome di metodo, letto dal sorgente della cartella db (gli `@Query` non sono
 * leggibili a runtime). Risolve le costanti `const val` del file e i `$NOME` dentro le stringhe, come fa il compilatore, cosi'
 * i test eseguono la query vera su sqlite-jdbc e non una copia.
 */
internal fun queriesOf(daoFile: String): Map<String, String> {
    val source = moduleFile("src/main/kotlin/com/pockettravel/core/data/db/$daoFile").readText()
    val resolved = constantsOf(source)
    val result = mutableMapOf<String, String>()
    var from = 0
    while (true) {
        val start = source.indexOf("@Query(", from)
        if (start < 0) break
        val bodyStart = start + "@Query(".length
        val end = closingParenthesis(source, start + "@Query".length)
        val name = Regex("""fun (\w+)""").find(source, end)?.groupValues?.get(1) ?: error("metodo dopo @Query non trovato")
        result[name] = expand(concatenate(source.substring(bodyStart, end), resolved), resolved)
        from = end
    }
    return result
}

// Le `const val` del file, gia' espanse. Una costante finisce alla prima riga che non e' di continuazione (le continuazioni sono rientrate).
private fun constantsOf(source: String): Map<String, String> {
    val constants = mutableMapOf<String, String>()
    for (match in Regex("""const val (\w+) =""").findAll(source)) {
        val rest = source.substring(match.range.last + 1)
        val end = Regex("\n(?=\\S)").find(rest)?.range?.first ?: rest.length
        constants[match.groupValues[1]] = concatenate(rest.substring(0, end), emptyMap())
    }
    return constants.mapValues { (_, text) -> expand(text, constants) }
}

// L'indice della parentesi che chiude quella in [open], saltando le parentesi dentro le stringhe.
private fun closingParenthesis(source: String, open: Int): Int {
    var depth = 0
    var inString = false
    var i = open
    while (i < source.length) {
        val c = source[i]
        when {
            inString && c == '\\' -> i++
            inString -> inString = c != '"'
            c == '"' -> inString = true
            c == '(' -> depth++
            c == ')' && --depth == 0 -> return i
        }
        i++
    }
    return i
}

// Le stringhe di un'espressione `"a" + "b" + NOME`, unite; i riferimenti a costanti fuori dalle stringhe si sostituiscono.
// Un riferimento non ancora noto resta `$NOME`: lo risolve expand, e se non si risolve il test di guardia lo segnala.
private fun concatenate(expression: String, constants: Map<String, String>): String {
    val out = StringBuilder()
    for (token in Regex(""""((?:[^"\\]|\\.)*)"|([A-Z][A-Z_0-9]+)""").findAll(expression)) {
        val literal = token.groups[1]
        if (literal != null) out.append(literal.value.replace("\\\"", "\"").replace("\\\\", "\\"))
        else out.append(constants[token.groupValues[2]] ?: "\$${token.groupValues[2]}")
    }
    return out.toString()
}

private fun expand(text: String, constants: Map<String, String>): String {
    var current = text
    repeat(10) { current = Regex("""\$([A-Z][A-Z_0-9]+)""").replace(current) { constants[it.groupValues[1]] ?: it.value } }
    return current
}

/** Esegue [sql] con parametri con nome come fa Room: le liste diventano un parametro per elemento, i booleani 0 e 1. */
internal fun <T> runNamed(connection: Connection, sql: String, params: Map<String, Any?>, read: (java.sql.ResultSet) -> T): List<T> {
    var expanded = sql
    val values = mutableMapOf<String, Any?>()
    for ((name, value) in params) {
        if (value is List<*>) {
            val names = value.indices.map { "$name$it" }
            expanded = Regex(":$name\\b").replace(expanded) { names.joinToString(",") { n -> ":$n" } }
            value.forEachIndexed { i, item -> values["$name$i"] = item }
        } else {
            values[name] = if (value is Boolean) (if (value) 1 else 0) else value
        }
    }
    val names = Regex(":(\\w+)").findAll(expanded).map { it.groupValues[1] }.distinct().toList()
    val numbered = Regex(":(\\w+)").replace(expanded) { "?" + (names.indexOf(it.groupValues[1]) + 1) }
    return connection.prepareStatement(numbered).use { statement ->
        names.forEachIndexed { i, name ->
            require(name in values) { "parametro :$name non fornito" }
            statement.setObject(i + 1, values[name])
        }
        if (numbered.trimStart().startsWith("SELECT", ignoreCase = true)) {
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        } else {
            statement.executeUpdate()
            emptyList()
        }
    }
}
