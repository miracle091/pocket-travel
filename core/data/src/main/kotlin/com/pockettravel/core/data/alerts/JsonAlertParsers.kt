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
import kotlinx.serialization.json.longOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Lettura delle risposte GeoJSON di GDACS e USGS. Una risposta che non e' un JSON con l'elenco `features`
 * (troncata, HTML di errore, formato cambiato) lancia [AlertParseException]; un elenco vuoto e' una risposta valida
 * senza eventi. Un evento a cui manca un campo indispensabile si salta, gli altri restano.
 */
object JsonAlertParsers {
    private const val BBOX_SIZE = 4
    private const val USGS_MODERATE_FROM = 5.0
    private const val USGS_HIGH_FROM = 6.0
    private const val USGS_EXTREME_FROM = 7.0

    private val GDACS_KINDS = mapOf(
        "EQ" to AlertKind.EARTHQUAKE,
        "TC" to AlertKind.CYCLONE,
        "FL" to AlertKind.FLOOD,
        "VO" to AlertKind.VOLCANO,
        "WF" to AlertKind.FIRE,
        "DR" to AlertKind.DROUGHT,
        "TS" to AlertKind.TSUNAMI,
    )
    private val GDACS_LEVELS = mapOf(
        "Green" to AlertLevel.LOW,
        "Orange" to AlertLevel.MODERATE,
        "Red" to AlertLevel.HIGH,
    )

    /** Eventi di GDACS (`geteventlist/SEARCH`). */
    fun parseGdacs(body: String): List<Alert> = features(body).mapNotNull(::gdacsAlert)

    /** Terremoti del feed GeoJSON di USGS. */
    fun parseUsgs(body: String): List<Alert> = features(body).mapNotNull(::usgsAlert)

    private fun features(body: String): List<JsonObject> = try {
        Json.parseToJsonElement(body).jsonObject.getValue("features").jsonArray.mapNotNull { it as? JsonObject }
    } catch (e: SerializationException) {
        throw AlertParseException("Risposta GeoJSON non leggibile", e)
    } catch (e: IllegalArgumentException) {
        throw AlertParseException("Risposta GeoJSON inattesa", e)
    } catch (e: NoSuchElementException) {
        throw AlertParseException("Risposta GeoJSON senza elenco di eventi", e)
    }

    private fun gdacsAlert(feature: JsonObject): Alert? {
        val props = feature.obj("properties") ?: return null
        val type = props.text("eventtype")
        val id = props.text("eventid")
        val title = props.text("name") ?: props.text("description")
        val sourceLevel = props.text("alertlevel")
        return if (type == null || id == null || title == null) null else Alert(
            id = "$type-$id",
            source = AlertSource.GDACS,
            kind = GDACS_KINDS[type] ?: AlertKind.OTHER,
            level = GDACS_LEVELS[sourceLevel] ?: AlertLevel.LOW,
            sourceLevel = sourceLevel,
            title = title,
            details = props.obj("severitydata")?.text("severitytext"),
            startsAt = props.text("fromdate")?.let(::gdacsInstant),
            // Un evento ancora in corso non ha fine: `todate` e' solo l'ultimo aggiornamento.
            endsAt = if (props.text("iscurrent") == "true") null else props.text("todate")?.let(::gdacsInstant),
            area = gdacsArea(feature),
            areaName = props.text("country"),
            url = props.obj("url")?.text("report"),
        )
    }

    // GDACS da' le date UTC senza fuso ("2026-10-26T04:53:40").
    private fun gdacsInstant(text: String): Instant? =
        try {
            LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeException) {
            null
        }

    // Il `bbox` del GeoJSON e' [minLon, minLat, maxLon, maxLat]; senza, vale il punto della geometria.
    private fun gdacsArea(feature: JsonObject): AlertBounds? {
        val box = (feature["bbox"] as? JsonArray)?.map { it.number() }
        val values = box?.filterNotNull()?.takeIf { box.size == BBOX_SIZE && it.size == BBOX_SIZE }
        return if (values != null) {
            AlertBounds(values[0], values[1], values[2], values.last())
        } else {
            point(feature)?.let { (lon, lat) -> AlertBounds(lon, lat, lon, lat) }
        }
    }

    private fun usgsAlert(feature: JsonObject): Alert? {
        val props = feature.obj("properties") ?: return null
        val id = feature.text("id")
        val title = props.text("title")
        val point = point(feature)
        val magnitude = props["mag"].number()
        return if (id == null || title == null || point == null) null else Alert(
            id = id,
            source = AlertSource.USGS,
            kind = AlertKind.EARTHQUAKE,
            level = magnitude?.let(::usgsLevel) ?: AlertLevel.LOW,
            sourceLevel = magnitude?.let { "M $it" },
            title = title,
            details = props.text("place"),
            startsAt = (props["time"] as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochMilli),
            endsAt = null,
            area = AlertBounds(point.first, point.second, point.first, point.second),
            areaName = null,
            url = props.text("url"),
            magnitude = magnitude,
            feltReports = (props["felt"] as? JsonPrimitive)?.longOrNull?.toInt(),
            impactAlert = props.text("alert"),
        )
    }

    private fun usgsLevel(magnitude: Double): AlertLevel = when {
        magnitude < USGS_MODERATE_FROM -> AlertLevel.LOW
        magnitude < USGS_HIGH_FROM -> AlertLevel.MODERATE
        magnitude < USGS_EXTREME_FROM -> AlertLevel.HIGH
        else -> AlertLevel.EXTREME
    }

    // Coordinate GeoJSON [lon, lat, profondita'] di una geometria Point.
    private fun point(feature: JsonObject): Pair<Double, Double>? {
        val geometry = feature.obj("geometry")?.takeIf { it.text("type") == "Point" }
        val coordinates = (geometry?.get("coordinates") as? JsonArray)?.map { it.number() }
        val lon = coordinates?.getOrNull(0)
        val lat = coordinates?.getOrNull(1)
        return if (lon != null && lat != null) lon to lat else null
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

// Testo di un campo, anche se la fonte lo scrive come numero (eventid) o come "true".
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.doubleOrNull
