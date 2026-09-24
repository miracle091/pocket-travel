package com.pockettravel.pipeline

import java.io.File

data class EmergencyNumbers(val general: String?, val police: String, val ambulance: String, val fire: String)

// Dataset statico (nessuna fonte esterna da interrogare a ogni run, a differenza di
// guide_sections/poi): i numeri di emergenza nazionali cambiano di rarissimo. La tabella in
// resources/emergency-numbers.tsv e' stata compilata da Travel.gc.ca e confrontata con Wikipedia e
// Wikidata, piu' gov.uk (FCDO) dove queste non bastavano (fonti e note per riga nel file). Una riga
// senza numeri indica una regione senza numero di emergenza centralizzato; una regione assente dal
// file non ha la scheda nell'app. "general" e' il numero unico (dove esiste, es. il 112 europeo); nullo per i
// paesi senza un unico numero universale (es. Giappone).
private val emergencyRows: List<List<String>> by lazy {
    val tsv = EmergencyNumbers::class.java.getResource("/emergency-numbers.tsv")!!.readText()
    tsv.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split("\t") }
        .toList()
}

private val emergencyNumbersByRegion: Map<String, EmergencyNumbers> by lazy {
    emergencyRows
        .filter { it[2].isNotEmpty() }
        .associate { (regionId, general, police, ambulance, fire) ->
            regionId to EmergencyNumbers(general = general.ifEmpty { null }, police = police, ambulance = ambulance, fire = fire)
        }
}

private val regionsWithoutCentralNumber: Set<String> by lazy {
    emergencyRows.filter { it[2].isEmpty() }.map { it[0] }.toSet()
}

/**
 * Tabelle "emergency_numbers" ed "emergency_numbers_none" di guides.db (schema minimo, non quello
 * Room), importate dall'app in region.db via EmergencyNumbersDao insieme alle guide. La prima ha una
 * riga per ciascuna delle regioni passate che ha numeri mappati. La seconda elenca le regioni senza
 * numero centralizzato: tabella a parte perche' l'app 0.5.0 legge police/ambulance/fire di
 * emergency_numbers come non nulli e ignora le tabelle che non conosce.
 */
fun writeEmergencyNumbersTable(regionIds: List<String>, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "emergency_numbers",
        createTableSql = """
            CREATE TABLE emergency_numbers (
                regionId TEXT NOT NULL,
                general TEXT,
                police TEXT NOT NULL,
                ambulance TEXT NOT NULL,
                fire TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO emergency_numbers (regionId, general, police, ambulance, fire) VALUES (?, ?, ?, ?, ?)",
        rows = regionIds.mapNotNull { regionId -> emergencyNumbersByRegion[regionId]?.let { regionId to it } },
    ) { insert, (regionId, numbers) ->
        insert.setString(1, regionId)
        insert.setString(2, numbers.general)
        insert.setString(3, numbers.police)
        insert.setString(4, numbers.ambulance)
        insert.setString(5, numbers.fire)
    }
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "emergency_numbers_none",
        createTableSql = "CREATE TABLE emergency_numbers_none (regionId TEXT NOT NULL)",
        insertSql = "INSERT INTO emergency_numbers_none (regionId) VALUES (?)",
        rows = regionIds.filter { it in regionsWithoutCentralNumber },
    ) { insert, regionId ->
        insert.setString(1, regionId)
    }
}
