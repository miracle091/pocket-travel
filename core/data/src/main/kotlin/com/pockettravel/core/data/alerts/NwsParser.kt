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
import java.time.OffsetDateTime

/**
 * Lettura delle allerte attive del National Weather Service (`api.weather.gov/alerts/active?area=XX`, GeoJSON).
 * Una risposta che non e' un JSON con l'elenco `features` lancia [AlertParseException]; un elenco vuoto e' valido.
 *
 * Si tengono solo i messaggi `Actual` nuovi o aggiornati (`Alert`, `Update`; `Cancel` e `Ack` no). Si scartano gravita'
 * `Minor` e `Unknown`: l'NWS emette centinaia di allerte minori (small craft advisory, frost advisory, outlook) che a
 * un viaggiatore non servono. Fanno eccezione tsunami e vulcani, utili anche se di grado basso.
 */
object NwsParser {
    private const val MAX_AREA_CHARS = 100
    private const val ELLIPSIS = "..."
    private const val AREA_SEPARATOR = "; "

    private val ACCEPTED_TYPES = setOf("Alert", "Update")
    private val ALWAYS_SHOWN = setOf(AlertKind.TSUNAMI, AlertKind.VOLCANO)

    // Allerte per chi naviga al largo, che non toccano chi viaggia a terra (scelta dell'utente del 2026-10-26); restano
    // quelle della costa: mareggiate, onde alte, correnti di risacca.
    private val MARINE_ONLY = setOf(
        "Gale Warning", "Gale Watch", "Storm Warning", "Storm Watch", "Hurricane Force Wind Warning",
        "Hurricane Force Wind Watch", "Hazardous Seas Warning", "Hazardous Seas Watch", "Special Marine Warning",
        "Marine Weather Statement", "Small Craft Advisory", "Heavy Freezing Spray Warning", "Heavy Freezing Spray Watch",
        "Freezing Spray Advisory", "Low Water Advisory", "Brisk Wind Advisory",
    )

    private val SEVERITIES = mapOf(
        "Minor" to AlertLevel.LOW,
        "Moderate" to AlertLevel.MODERATE,
        "Severe" to AlertLevel.HIGH,
        "Extreme" to AlertLevel.EXTREME,
    )

    // In ordine: la prima parola chiave trovata nel nome dell'evento decide il tipo. "Hurricane Force Wind" e' vento
    // di mare, non un ciclone: per questo "hurricane warning" e "hurricane watch" sono parole intere.
    private val KINDS = listOf(
        listOf("tsunami") to AlertKind.TSUNAMI,
        listOf("volcano", "ashfall") to AlertKind.VOLCANO,
        listOf("earthquake") to AlertKind.EARTHQUAKE,
        listOf("fire", "red flag") to AlertKind.FIRE,
        listOf("flood", "storm surge") to AlertKind.FLOOD,
        listOf("hurricane warning", "hurricane watch", "tropical storm", "tropical cyclone", "typhoon") to AlertKind.CYCLONE,
    )

    fun parse(body: String): List<Alert> = features(body).mapNotNull(::alert)

    private fun features(body: String): List<JsonObject> = try {
        Json.parseToJsonElement(body).jsonObject.getValue("features").jsonArray.mapNotNull { it as? JsonObject }
    } catch (e: SerializationException) {
        throw AlertParseException("Risposta NWS non leggibile", e)
    } catch (e: IllegalArgumentException) {
        throw AlertParseException("Risposta NWS inattesa", e)
    } catch (e: NoSuchElementException) {
        throw AlertParseException("Risposta NWS senza elenco di allerte", e)
    }

    private fun alert(feature: JsonObject): Alert? {
        val props = feature["properties"] as? JsonObject ?: return null
        val id = props.text("id") ?: feature.text("id")
        val event = props.text("event")
        val kind = kindOf(event.orEmpty())
        val level = SEVERITIES[props.text("severity")]?.takeIf { worthShowing(props, it, kind) }
        return if (id == null || event == null || level == null) null else Alert(
            id = id,
            source = AlertSource.NWS,
            kind = kind,
            level = level,
            sourceLevel = props.text("severity"),
            title = event,
            details = props.text("senderName"),
            startsAt = props.firstInstant("onset", "effective", "sent"),
            endsAt = props.firstInstant("ends", "expires"),
            // La maggior parte delle allerte NWS ha `geometry` nulla e si lega a zone: senza poligono l'area resta nulla.
            area = boundsOf(points((feature["geometry"] as? JsonObject)?.get("coordinates"))),
            areaName = props.text("areaDesc")?.let(::shortArea),
            // NWS non ha una pagina web per allerta: l'indirizzo `@id` e' il JSON dell'API, non da aprire.
            url = null,
            issuedAt = props.firstInstant("sent"),
            eventCode = event,
        )
    }

    private fun worthShowing(props: JsonObject, level: AlertLevel, kind: AlertKind): Boolean =
        props.text("status") == "Actual" && props.text("messageType") in ACCEPTED_TYPES &&
            (level != AlertLevel.LOW || kind in ALWAYS_SHOWN) && props.text("event") !in MARINE_ONLY

    private fun kindOf(event: String): AlertKind {
        val name = event.lowercase()
        return KINDS.firstOrNull { (words, _) -> words.any { it in name } }?.second ?: AlertKind.WEATHER
    }

    // Una zona puo' elencare decine di contee: si tengono le prime che stanno nel limite.
    private fun shortArea(areaDesc: String): String {
        if (areaDesc.length <= MAX_AREA_CHARS) return areaDesc
        val cut = areaDesc.take(MAX_AREA_CHARS).substringBeforeLast(AREA_SEPARATOR, areaDesc.take(MAX_AREA_CHARS))
        return cut + ELLIPSIS
    }

    private fun instant(text: String): Instant? =
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeException) {
            null
        }

    // Scende negli array annidati di un poligono o multipoligono fino alle coppie [lon, lat].
    private fun points(element: JsonElement?): List<Pair<Double, Double>> {
        val array = element as? JsonArray ?: return emptyList()
        val lon = (array.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
        val lat = (array.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
        return if (lon != null && lat != null) listOf(lon to lat) else array.flatMap(::points)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // Il primo dei campi che c'e' e si legge come data.
    private fun JsonObject.firstInstant(vararg keys: String): Instant? = keys.firstNotNullOfOrNull { text(it) }?.let { instant(it) }
}
