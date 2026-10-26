package com.pockettravel.core.data.alerts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Lettura di `https://www.jma.go.jp/bosai/warning/data/warning/map.json`: un solo file per tutto il Giappone, con un
 * rapporto per ufficio (prefettura o sua parte) e, per ogni zona di previsione, i codici delle allerte in corso.
 * I testi giapponesi non si leggono: titoli e nomi vengono dai codici ([JmaHazard], [JmaAreas]).
 *
 * Una risposta che non e' un elenco di rapporti (troncata, HTML di errore) lancia [AlertParseException]; un elenco senza
 * allerte e' valido. Si fa una [Alert] per ufficio e tipo di allerta, con le zone interessate in [Alert.details].
 * Le segnalazioni (livello `Advisory`) si saltano, come le allerte annullate e quelle di codice sconosciuto.
 * La fonte non da' coordinate: [Alert.area] e' nulla.
 */
object JmaParser {
    private const val CANCELLED = "解除"
    private const val MAX_ZONES_LISTED = 3
    private const val PAGE = "https://www.jma.go.jp/bosai/warning/#area_type=offices&lang=en&area_code="

    fun parse(body: String): List<Alert> {
        val reports = try {
            Json.parseToJsonElement(body) as? JsonArray ?: throw AlertParseException("Risposta JMA non e' un elenco")
        } catch (e: SerializationException) {
            throw AlertParseException("Risposta JMA non leggibile", e)
        }
        return reports.flatMap { report -> (report as? JsonObject)?.let(::reportAlerts).orEmpty() }
    }

    private fun reportAlerts(report: JsonObject): List<Alert> {
        val issuedAt = report.text("reportDatetime")?.let(::instant)
        // Il primo gruppo di aree (`areaTypes[0]`) sono le zone di previsione; il secondo i comuni, con gli stessi codici.
        val zones = ((report["areaTypes"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("areas") as? JsonArray
        val hits = LinkedHashMap<Pair<String, JmaHazard>, MutableList<String>>()
        zones?.mapNotNull { it as? JsonObject }?.forEach { zone ->
            val zoneCode = zone.text("code") ?: return@forEach
            activeHazards(zone).forEach { hazard ->
                hits.getOrPut(JmaAreas.officeOf(zoneCode) to hazard) { mutableListOf() }.add(zoneCode)
            }
        }
        return hits.map { (key, zoneCodes) -> alert(key.first, key.second, zoneCodes, issuedAt) }
    }

    // Allerte in corso (non annullate) di una zona, di un livello da mostrare. Le voci senza `code` dicono "nessuna allerta".
    private fun activeHazards(zone: JsonObject): List<JmaHazard> =
        (zone["warnings"] as? JsonArray).orEmpty().mapNotNull { item ->
            val warning = item as? JsonObject ?: return@mapNotNull null
            JmaHazard.fromCode(warning.text("code").orEmpty())
                ?.takeIf { it.level.shown && warning.text("status") != CANCELLED }
        }

    private fun alert(office: String, hazard: JmaHazard, zoneCodes: List<String>, issuedAt: Instant?): Alert {
        val zoneNames = zoneCodes.mapNotNull(JmaAreas::zoneName).distinct()
        // Se l'allerta copre tutto l'ufficio, o troppe zone, l'elenco non aggiunge nulla.
        val partial = zoneCodes.size < JmaAreas.zoneCount(office) && zoneNames.size <= MAX_ZONES_LISTED
        return Alert(
            id = "jma-$office-${hazard.code}",
            source = AlertSource.JMA,
            kind = AlertKind.WEATHER,
            level = hazard.level.alertLevel,
            sourceLevel = hazard.level.englishName,
            title = hazard.title,
            details = zoneNames.takeIf { partial && it.isNotEmpty() }?.joinToString(", "),
            startsAt = null,
            endsAt = null,
            area = null,
            areaName = JmaAreas.officeName(office),
            url = PAGE + office,
            issuedAt = issuedAt,
            eventCode = hazard.eventCode,
        )
    }

    private fun instant(text: String): Instant? =
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeException) {
            null
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
