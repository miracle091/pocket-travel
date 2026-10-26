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
 * Lettura del feed Atom/CAP di un paese di Meteoalarm. Una risposta che non e' un feed Atom ben formato
 * (troncata, HTML di errore) lancia [AlertParseException]; un feed senza voci e' valido. Si tengono le voci con
 * stato `Actual` e non annullate. La fonte non da' coordinate: [Alert.area] e' nulla e vale il nome dell'area.
 */
object MeteoalarmParser {
    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val CAP = "urn:oasis:names:tc:emergency:cap:1.2"
    private const val ACTUAL = "Actual"
    private const val CANCEL = "Cancel"

    private val SEVERITIES = mapOf(
        "Minor" to AlertLevel.LOW,
        "Moderate" to AlertLevel.MODERATE,
        "Severe" to AlertLevel.HIGH,
        "Extreme" to AlertLevel.EXTREME,
    )

    fun parse(body: String): List<Alert> {
        val root = document(body)
        if (root.localName != "feed" || root.namespaceURI != ATOM) throw AlertParseException("Non e' un feed Atom")
        val entries = root.getElementsByTagNameNS(ATOM, "entry")
        return (0 until entries.length).mapNotNull { (entries.item(it) as? Element)?.let(::alert) }
    }

    private fun document(body: String): Element = try {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        // Niente DTD ed entita' esterne; dove la funzione non esiste (Android) il lettore non le risolve comunque.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newDocumentBuilder().parse(InputSource(StringReader(body))).documentElement
    } catch (e: SAXException) {
        throw AlertParseException("Feed XML non leggibile", e)
    } catch (e: IOException) {
        throw AlertParseException("Feed XML non leggibile", e)
    } catch (e: ParserConfigurationException) {
        throw AlertParseException("Lettore XML non disponibile", e)
    }

    private fun alert(entry: Element): Alert? {
        val id = entry.cap("identifier")
        val title = entry.atom("title") ?: entry.cap("event")
        val valid = entry.cap("status") == ACTUAL && entry.cap("message_type") != CANCEL
        if (!valid || id == null || title == null) return null
        val severity = entry.cap("severity")
        return Alert(
            id = id,
            source = AlertSource.METEOALARM,
            kind = AlertKind.WEATHER,
            level = SEVERITIES[severity] ?: AlertLevel.LOW,
            sourceLevel = severity,
            title = title,
            details = null,
            startsAt = (entry.cap("onset") ?: entry.cap("effective") ?: entry.cap("sent"))?.let(::instant),
            endsAt = entry.cap("expires")?.let(::instant),
            area = null,
            areaName = entry.cap("areaDesc"),
            url = relatedLink(entry),
        )
    }

    private fun instant(text: String): Instant? =
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeException) {
            null
        }

    // Pagina del paese su meteoalarm.org (`rel="related"`).
    private fun relatedLink(entry: Element): String? {
        val links = entry.getElementsByTagNameNS(ATOM, "link")
        return (0 until links.length).map { links.item(it) as? Element }
            .firstOrNull { it?.getAttribute("rel") == "related" }
            ?.getAttribute("href")?.takeIf { it.isNotBlank() }
    }

    private fun Element.cap(name: String): String? = child(CAP, name)

    private fun Element.atom(name: String): String? = child(ATOM, name)

    // Solo i figli diretti della voce: i `title` annidati (es. in `author`) non contano.
    private fun Element.child(namespace: String, name: String): String? {
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == namespace && node.localName == name) {
                return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
            node = node.nextSibling
        }
        return null
    }
}
