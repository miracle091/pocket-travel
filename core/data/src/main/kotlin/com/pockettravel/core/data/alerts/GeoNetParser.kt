package com.pockettravel.core.data.alerts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Lettura dei livelli di allerta vulcanica di GeoNet (Nuova Zelanda, `volcano/val`, GeoJSON con un punto per vulcano).
 * Si mostrano i vulcani dal livello 2 (agitazione da moderata a forte): il livello 1 e' un'agitazione lieve e
 * quasi permanente di qualche vulcano, che non cambia nulla per chi viaggia.
 * Una risposta che non e' un JSON con l'elenco `features` lancia [AlertParseException].
 */
object GeoNetParser {
    private const val MIN_LEVEL = 2
    private const val MODERATE_LEVEL = 3
    private const val HIGH_LEVEL = 4
    private const val PAGE_URL = "https://www.geonet.org.nz/volcano"

    fun parse(body: String): List<Alert> = features(body).mapNotNull(::alert)

    private fun features(body: String): List<JsonObject> = try {
        Json.parseToJsonElement(body).jsonObject.getValue("features").jsonArray.mapNotNull { it as? JsonObject }
    } catch (e: SerializationException) {
        throw AlertParseException("Risposta GeoNet non leggibile", e)
    } catch (e: IllegalArgumentException) {
        throw AlertParseException("Risposta GeoNet inattesa", e)
    } catch (e: NoSuchElementException) {
        throw AlertParseException("Risposta GeoNet senza elenco di vulcani", e)
    }

    private fun alert(feature: JsonObject): Alert? {
        val props = feature["properties"] as? JsonObject
        val level = (props?.get("level") as? JsonPrimitive)?.intOrNull
        return if (props == null || level == null || level < MIN_LEVEL) null else build(feature, props, level)
    }

    private fun build(feature: JsonObject, props: JsonObject, level: Int): Alert? {
        val id = props.text("volcanoID")
        val title = props.text("volcanoTitle")
        val coordinates = ((feature["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.doubleOrNull }
        val lon = coordinates?.getOrNull(0)
        val lat = coordinates?.getOrNull(1)
        return if (id == null || title == null) null else Alert(
            id = "VAL-$id",
            source = AlertSource.GEONET,
            kind = AlertKind.VOLCANO,
            level = alertLevel(level),
            sourceLevel = props.text("acc"),
            title = title,
            details = props.text("activity"),
            startsAt = null,
            endsAt = null,
            area = if (lon != null && lat != null) AlertBounds(lon, lat, lon, lat) else null,
            areaName = null,
            url = PAGE_URL,
            eventCode = "VAL$level",
        )
    }

    // Livello 2: agitazione; 3: eruzione lieve; 4: eruzione moderata; 5: eruzione grande.
    private fun alertLevel(level: Int): AlertLevel = when {
        level < MODERATE_LEVEL -> AlertLevel.LOW
        level == MODERATE_LEVEL -> AlertLevel.MODERATE
        level == HIGH_LEVEL -> AlertLevel.HIGH
        else -> AlertLevel.EXTREME
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
