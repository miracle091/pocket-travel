package com.pockettravel.core.data.alerts

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Lettura del messaggio CAP-TSU dei centri tsunami del NOAA: PTWC per il Pacifico e i Caraibi (`PHEBCAP.xml`) e NTWC per
 * Alaska, Canada e costa ovest (`PAAQCAP.xml`), entrambi su `tsunami.gov/events/xml/`. Ogni file ha solo l'ultimo
 * messaggio emesso, con un blocco `info` per categoria.
 *
 * Si tengono `Warning`, `Advisory`, `Watch` e `Threat`: sono le categorie per cui il centro chiede di agire o di stare
 * attenti. Si scartano le `Information` (terremoto avvenuto senza pericolo di tsunami, o allerta valida per un altro
 * oceano), che sono la maggior parte e allarmerebbero senza motivo, i messaggi non `Actual` e quelli annullati.
 * L'area e' il rettangolo dei cerchi e dei poligoni CAP (le coste in allerta), altrimenti l'epicentro; il feed e'
 * mondiale e si filtra dopo con il rettangolo della regione. Un messaggio che non e' CAP ben formato lancia
 * [AlertParseException].
 */
object TsunamiParser {
    private const val CAP = "urn:oasis:names:tc:emergency:cap:1.2"
    private const val ACTUAL = "Actual"
    private const val CANCEL = "Cancel"
    private const val CATEGORY_CODE = "TsunamiSystemCategory"
    private const val EPICENTER_PARAMETER = "EventLatLon"
    private const val UTC_ZERO_SUFFIX = "-00:00"

    // Categorie da mostrare, con la gravita' di ripiego se il CAP non la dice.
    private val CATEGORIES = mapOf(
        "warning" to AlertLevel.EXTREME,
        "advisory" to AlertLevel.MODERATE,
        "watch" to AlertLevel.MODERATE,
        "threat" to AlertLevel.HIGH,
    )

    private val SEVERITIES = mapOf(
        "Minor" to AlertLevel.LOW,
        "Moderate" to AlertLevel.MODERATE,
        "Severe" to AlertLevel.HIGH,
        "Extreme" to AlertLevel.EXTREME,
    )

    fun parse(body: String): List<Alert> {
        val root = document(body)
        if (root.localName != "alert" || root.namespaceURI != CAP) throw AlertParseException("Non e' un messaggio CAP")
        val valid = root.text("status") == ACTUAL && root.text("msgType") != CANCEL
        return if (valid) root.children("info").mapNotNull { alert(root, it) } else emptyList()
    }

    private fun document(body: String): Element = try {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        // Niente DTD ed entita' esterne; dove la funzione non esiste (Android) il lettore non le risolve comunque.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newDocumentBuilder().parse(InputSource(StringReader(body))).documentElement
    } catch (e: SAXException) {
        throw AlertParseException("Messaggio XML non leggibile", e)
    } catch (e: IOException) {
        throw AlertParseException("Messaggio XML non leggibile", e)
    } catch (e: ParserConfigurationException) {
        throw AlertParseException("Lettore XML non disponibile", e)
    }

    private fun alert(root: Element, info: Element): Alert? {
        val id = root.text("identifier")
        val category = category(info)
        if (id == null || category == null) return null
        val severity = info.text("severity")
        val title = "Tsunami " + category.replaceFirstChar(Char::titlecase)
        return Alert(
            id = "$id-$category",
            source = AlertSource.TSUNAMI,
            kind = AlertKind.TSUNAMI,
            level = SEVERITIES[severity] ?: CATEGORIES.getValue(category),
            sourceLevel = severity,
            title = title,
            details = info.text("headline"),
            startsAt = (info.text("onset") ?: info.text("effective") ?: root.text("sent"))?.let(::instant),
            endsAt = info.text("expires")?.let(::instant),
            area = boundsOf(info.points()),
            areaName = info.children("area").firstNotNullOfOrNull { it.text("areaDesc") },
            url = info.text("web")?.replaceFirst("http://", "https://")?.takeIf { it.startsWith("https://") },
            issuedAt = root.text("sent")?.let(::instant),
            eventCode = title,
        )
    }

    // Il formato nuovo scrive la categoria nel nome dell'evento ("Tsunami Warning"), il vecchio in `eventCode`.
    private fun category(info: Element): String? {
        val code = info.children("eventCode").firstOrNull { it.text("valueName") == CATEGORY_CODE }?.text("value")
        val name = (code ?: info.text("event"))?.lowercase().orEmpty()
        return CATEGORIES.keys.firstOrNull { it in name }
    }

    // Coordinate dei cerchi ("lat,lon raggio") e dei poligoni ("lat,lon lat,lon ..."); se mancano, l'epicentro.
    private fun Element.points(): List<Pair<Double, Double>> {
        val shapes = children("area").flatMap { area ->
            area.children("circle").map { it.textContent.trim().substringBefore(' ') } +
                area.children("polygon").flatMap { it.textContent.trim().split(' ') }
        }
        val epicenter = children("parameter").firstOrNull { it.text("valueName") == EPICENTER_PARAMETER }
            ?.text("value")?.substringBefore(' ')
        return shapes.ifEmpty { listOfNotNull(epicenter) }.mapNotNull { pair ->
            val values = pair.split(',').mapNotNull { it.toDoubleOrNull() }
            if (values.size >= 2) values[1] to values[0] else null
        }
    }

    // CAP-TSU scrive l'ora UTC come "-00:00", che non tutti i lettori di date accettano.
    private fun instant(text: String): Instant? =
        try {
            OffsetDateTime.parse(if (text.endsWith(UTC_ZERO_SUFFIX)) text.removeSuffix(UTC_ZERO_SUFFIX) + "Z" else text).toInstant()
        } catch (_: DateTimeException) {
            null
        }

    private fun Element.text(name: String): String? =
        children(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    // Solo i figli diretti: `value` e `area` annidati non contano.
    private fun Element.children(name: String): List<Element> {
        val found = mutableListOf<Element>()
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == CAP && node.localName == name) found.add(node)
            node = node.nextSibling
        }
        return found
    }
}
