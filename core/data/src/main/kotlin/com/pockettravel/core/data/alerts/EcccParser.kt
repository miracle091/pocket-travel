package com.pockettravel.core.data.alerts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.DateTimeException
import java.time.Instant

/**
 * Lettura delle allerte meteo del Canada dall'API OGC di MSC GeoMet (`weather-alerts`, GeoJSON con una feature per
 * zona). Dati di Environment and Climate Change Canada: il testo resta quello originale, in inglese.
 * Una risposta che non e' un JSON con l'elenco `features` lancia [AlertParseException]; un elenco vuoto e' valido.
 */
object EcccParser {
    private val ENDED_STATUSES = setOf("ended", "cancelled")
    private val LEVELS = mapOf(
        "yellow" to AlertLevel.MODERATE,
        "orange" to AlertLevel.HIGH,
        "red" to AlertLevel.EXTREME,
    )

    // Le pagine per provincia del vecchio sito non esistono piu' e quelle nuove non si ricavano dai dati: vale la home.
    private const val PAGE_URL = "https://weather.gc.ca/index_e.html"

    fun parse(body: String): List<Alert> = features(body).mapNotNull(::alert)

    private fun features(body: String): List<JsonObject> = try {
        Json.parseToJsonElement(body).jsonObject.getValue("features").jsonArray.mapNotNull { it as? JsonObject }
    } catch (e: SerializationException) {
        throw AlertParseException("Risposta ECCC non leggibile", e)
    } catch (e: IllegalArgumentException) {
        throw AlertParseException("Risposta ECCC inattesa", e)
    } catch (e: NoSuchElementException) {
        throw AlertParseException("Risposta ECCC senza elenco di allerte", e)
    }

    // Le allerte terminate o annullate restano nella risposta fino alla scadenza: si saltano.
    private fun alert(feature: JsonObject): Alert? {
        val props = feature["properties"] as? JsonObject
        return if (props == null || props.text("status_en")?.lowercase() in ENDED_STATUSES) null else build(feature, props)
    }

    private fun build(feature: JsonObject, props: JsonObject): Alert? {
        val id = feature.text("id")
        val name = props.text("alert_name_en")?.replaceFirstChar { it.uppercaseChar() }
        val colour = props.text("risk_colour_en")?.lowercase()
        return if (id == null || name == null) null else Alert(
            id = id,
            source = AlertSource.ECCC,
            kind = AlertKind.WEATHER,
            level = LEVELS[colour] ?: AlertLevel.LOW,
            sourceLevel = colour,
            title = name,
            details = props.text("alert_text_en"),
            startsAt = props.text("validity_datetime")?.let(::instant),
            endsAt = (props.text("event_end_datetime") ?: props.text("expiration_datetime"))?.let(::instant),
            area = bounds(feature["geometry"]),
            areaName = props.text("feature_name_en"),
            url = PAGE_URL,
            issuedAt = props.text("publication_datetime")?.let(::instant),
            eventCode = name,
        )
    }

    private fun instant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeException) {
        null
    }

    // Rettangolo che contiene tutte le coordinate [lon, lat] di un Polygon o MultiPolygon.
    private fun bounds(geometry: JsonElement?): AlertBounds? {
        val points = mutableListOf<Pair<Double, Double>>()
        collect((geometry as? JsonObject)?.get("coordinates"), points)
        return if (points.isEmpty()) null else AlertBounds(
            points.minOf { it.first },
            points.minOf { it.second },
            points.maxOf { it.first },
            points.maxOf { it.second },
        )
    }

    private fun collect(node: JsonElement?, into: MutableList<Pair<Double, Double>>) {
        val items = node as? JsonArray
        val lon = (items?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
        val lat = (items?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
        if (lon != null && lat != null) into.add(lon to lat) else items?.forEach { collect(it, into) }
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
