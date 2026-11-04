package com.pockettravel.core.data.alerts

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
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

    // Testo esterno: un nome di servizio non supera qualche decina di caratteri.
    private const val MAX_SENDER_CHARS = 120

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
            issuedAt = entry.cap("sent")?.let(::instant),
            zoneCode = entry.zoneCode(),
            zoneBounds = polygonBounds(entry.getElementsByTagNameNS(CAP, "polygon")),
        )
    }

    /** Indirizzo del messaggio CAP della prima voce del feed (`type="application/cap+xml"`), null se non ce n'e'. */
    fun firstCapUrl(body: String): String? {
        val entries = document(body).getElementsByTagNameNS(ATOM, "entry")
        val entry = entries.item(0) as? Element ?: return null
        val links = entry.getElementsByTagNameNS(ATOM, "link")
        return (0 until links.length).map { links.item(it) as? Element }
            .firstOrNull { it?.getAttribute("type") == "application/cap+xml" }
            ?.getAttribute("href")?.takeIf { it.startsWith("https://") }
    }

    /**
     * Nome del servizio meteorologico nazionale (`senderName`) in un messaggio CAP: il feed Atom non lo ha, e Meteoalarm
     * chiede di mostrarlo accanto alle allerte di un paese. Null se manca o il messaggio non si legge.
     */
    fun senderName(capXml: String): String? = runCatching {
        val names = document(capXml).getElementsByTagNameNS(CAP, "senderName")
        names.item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SENDER_CHARS }
    }.getOrNull()

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

    // `cap:geocode` ha come figli `valueName` e `value` (senza prefisso): serve il geocode EMMA_ID o, dove manca (la Francia
    // da' solo NUTS3, per esempio FR812 per il Gard), il NUTS3.
    private fun Element.zoneCode(): String? {
        val geocodes = getElementsByTagNameNS(CAP, "geocode")
        val byName = (0 until geocodes.length).mapNotNull { geocodes.item(it) as? Element }.associate { geocode ->
            geocode.getElementsByTagName("valueName").item(0)?.textContent?.trim() to
                geocode.getElementsByTagName("value").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
        return byName["EMMA_ID"] ?: byName["NUTS3"]
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

private val WHITESPACE = Regex("\\s+")

// `cap:polygon` e' una lista di punti "lat,lon" separati da spazi; il rettangolo che contiene i punti di tutti i poligoni
// della voce, null se non ce ne sono.
private fun polygonBounds(polygons: NodeList): AlertBounds? {
    val points = (0 until polygons.length).flatMap { i ->
        polygons.item(i).textContent.trim().split(WHITESPACE)
            .mapNotNull { point -> point.split(",").mapNotNull(String::toDoubleOrNull).takeIf { it.size == 2 } }
    }
    if (points.isEmpty()) return null
    return AlertBounds(points.minOf { it[1] }, points.minOf { it[0] }, points.maxOf { it[1] }, points.maxOf { it[0] })
}
