package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager

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

fun main(args: Array<String>) {
    require(args.size == 2) { "Uso: generateEmergencyNumbers <regionId> <output content.db>" }
    val regionId = args[0]
    val outputDb = File(args[1])

    val numbers = emergencyNumbersByRegion[regionId]
    writeEmergencyNumbersDb(numbers, regionId, outputDb)
    println("emergency_numbers: ${if (numbers != null) 1 else 0} riga scritta in ${outputDb.path}")
}

/**
 * Schema minimo (non lo schema Room di EmergencyNumbersEntity): una tabella
 * "emergency_numbers" con al piu' una riga (la regione stessa), che l'app importa in
 * region.db via EmergencyNumbersDao. Stesso file condiviso content.db di guide_sections/poi
 * (vedi commenti li'): solo la propria tabella viene ricreata, non l'intero file.
 */
fun writeEmergencyNumbersDb(numbers: EmergencyNumbers?, regionId: String, outputDb: File) {
    DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
        conn.createStatement().use { statement ->
            statement.execute("DROP TABLE IF EXISTS emergency_numbers")
            statement.execute(
                """
                CREATE TABLE emergency_numbers (
                    regionId TEXT NOT NULL,
                    general TEXT,
                    police TEXT NOT NULL,
                    ambulance TEXT NOT NULL,
                    fire TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
        if (numbers != null) {
            conn.prepareStatement(
                "INSERT INTO emergency_numbers (regionId, general, police, ambulance, fire) VALUES (?, ?, ?, ?, ?)"
            ).use { insert ->
                insert.setString(1, regionId)
                insert.setString(2, numbers.general)
                insert.setString(3, numbers.police)
                insert.setString(4, numbers.ambulance)
                insert.setString(5, numbers.fire)
                insert.execute()
            }
        }
    }
}
