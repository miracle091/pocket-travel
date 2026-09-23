package com.pockettravel.pipeline

import java.io.File

data class EmergencyNumbers(val general: String?, val police: String, val ambulance: String, val fire: String)

// Dataset statico (nessuna fonte esterna da interrogare, a differenza di guide_sections/poi):
// i numeri di emergenza nazionali cambiano di rarissimo, non serve una pipeline di fetch.
// Stesso spirito di headingToCategory in GenerateGuideContent.kt e di Continent.of() in
// RegionListScreen.kt (app): mappatura a mano per regionId, da estendere quando la pipeline
// pubblica una regione fuori da queste. "general" e' il numero unico (dove esiste, es. il 112
// europeo); nullo per i paesi senza un unico numero universale (es. Giappone).
private val emergencyNumbersByRegion = mapOf(
    "italia" to EmergencyNumbers(general = "112", police = "113", ambulance = "118", fire = "115"),
    "san-marino" to EmergencyNumbers(general = "112", police = "113", ambulance = "118", fire = "115"),
    "andorra" to EmergencyNumbers(general = "112", police = "112", ambulance = "112", fire = "112"),
    "stati-uniti" to EmergencyNumbers(general = "911", police = "911", ambulance = "911", fire = "911"),
    "stati-uniti-alaska" to EmergencyNumbers(general = "911", police = "911", ambulance = "911", fire = "911"),
    "stati-uniti-hawaii" to EmergencyNumbers(general = "911", police = "911", ambulance = "911", fire = "911"),
    "giappone" to EmergencyNumbers(general = null, police = "110", ambulance = "119", fire = "119"),
)

/**
 * Tabella "emergency_numbers" di guides.db (schema minimo, non quello Room di
 * EmergencyNumbersEntity): una riga per ciascuna delle regioni passate che ha numeri mappati,
 * importata dall'app in region.db via EmergencyNumbersDao insieme alle guide.
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
}
