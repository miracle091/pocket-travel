package com.pockettravel.pipeline

import com.google.protobuf.ByteString
import java.io.File
import java.io.FileOutputStream
import javax.xml.parsers.SAXParserFactory
import org.openstreetmap.osmosis.osmbinary.Osmformat
import org.openstreetmap.osmosis.osmbinary.file.BlockOutputStream
import org.openstreetmap.osmosis.osmbinary.file.FileBlock
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

data class OsmNode(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String> = emptyMap())
data class OsmWay(val id: Long, val nodeIds: List<Long>, val tags: Map<String, String>)
data class OsmData(val nodes: List<OsmNode>, val ways: List<OsmWay>)

/**
 * Duplicato intenzionale di :tools:data-pipeline:maptiles/OsmXmlToPbf.kt (stesso contenuto):
 * :routing evita una dipendenza di progetto su :maptiles solo per queste due funzioni, perche'
 * trascinerebbe planetiler-core (pesante, non necessario qui) nel classpath di un modulo che ora
 * lavora solo con BRouter — meno bloat che una dipendenza tra moduli per
 * ~100 righe stabili. Legge il sottoinsieme minimo di OSM XML usato dai fixture di test di questo
 * repo (solo <node> e <way>, nessuna relation) — non un parser OSM XML generico.
 *
 * I nodi vengono deduplicati per id: un estratto Overpass reale con un
 * `node[tag](bbox)` esplicito unito a una ricorsione `>;` sulle way puo' emettere lo stesso
 * `<node>` due volte se il nodo soddisfa entrambi i criteri — trovato generando un .rd5 da
 * un'estrazione reale di Milano, dove un nodo duplicato mandava in crash WayLinker
 * ("duplicate key found in late check") molto a valle nella pipeline. Senza dedup qui,
 * qualunque consumatore di OsmData duplicherebbe silenziosamente quel nodo.
 */
fun parseOsmXml(file: File): OsmData {
    // Parser SAX (streaming) invece di DOM: un DOM tiene l'intero albero XML in memoria con un
    // overhead per-nodo pesante (DeferredDocumentImpl di Xerces) - per un estratto Overpass di
    // una nazione grande (es. Italia, milioni di nodi) questo esaurisce lo heap di default della
    // JVM (visto: OutOfMemoryError generando i POI dell'Italia). SAX processa un elemento
    // alla volta e costruisce solo gli OsmNode/OsmWay leggeri che servono a valle.
    //
    // Il limite JAXP sulla dimensione dell'"entita' documento" (100.000 caratteri di default)
    // scatta anche su XML grandi ma innocui quando disallow-doctype-decl e' attivo (es. estratto
    // Overpass reale per una nazione grande, milioni di righe) - non e' l'XXE che
    // disallow-doctype-decl/external-entities gia' prevengono, quindi lo disattiviamo qui.
    // Si applica anche al parser SAX, non solo al DOM: va impostato comunque.
    System.setProperty("jdk.xml.maxGeneralEntitySizeLimit", "0")
    System.setProperty("jdk.xml.totalEntitySizeLimit", "0")
    System.setProperty("jdk.xml.entityExpansionLimit", "0")

    val nodes = mutableListOf<OsmNode>()
    val ways = mutableListOf<OsmWay>()
    var currentTags = mutableMapOf<String, String>()
    var currentNodeIds = mutableListOf<Long>()
    var currentId = 0L
    var currentLat = 0.0
    var currentLon = 0.0
    var inNode = false
    var inWay = false

    val handler = object : DefaultHandler() {
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (qName) {
                "node" -> {
                    inNode = true
                    currentId = attributes.getValue("id").toLong()
                    currentLat = attributes.getValue("lat").toDouble()
                    currentLon = attributes.getValue("lon").toDouble()
                    currentTags = mutableMapOf()
                }
                "way" -> {
                    inWay = true
                    currentId = attributes.getValue("id").toLong()
                    currentTags = mutableMapOf()
                    currentNodeIds = mutableListOf()
                }
                "tag" -> if (inNode || inWay) {
                    currentTags[attributes.getValue("k")] = attributes.getValue("v")
                }
                "nd" -> if (inWay) {
                    currentNodeIds += attributes.getValue("ref").toLong()
                }
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (qName) {
                "node" -> {
                    nodes += OsmNode(id = currentId, lat = currentLat, lon = currentLon, tags = currentTags)
                    inNode = false
                }
                "way" -> {
                    ways += OsmWay(id = currentId, nodeIds = currentNodeIds, tags = currentTags)
                    inWay = false
                }
            }
        }
    }

    SAXParserFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
    }.newSAXParser().parse(file, handler)

    return OsmData(nodes.distinctBy { it.id }, ways)
}

/**
 * Scrive un .osm.pbf minimale (un solo PrimitiveBlock, Node/Way non-dense, nessuna
 * compressione) usando i codec protobuf di osmosis-osm-binary. Planetiler legge lo
 * stesso formato OSMPBF standard (la sua classe interna e' un fork di crosby.binary,
 * stessa origine di osmosis-osm-binary) quindi l'interoperabilita' e' garantita dal
 * formato, non da una libreria condivisa — verificato via javap che il required_features
 * "OsmSchema-V0.6" (senza "DenseNodes", perche' qui non li usiamo) e' l'unico richiesto
 * da OsmInputFile.
 */
fun writeOsmPbf(data: OsmData, output: File) {
    FileOutputStream(output).use { fos ->
        val blockOutput = BlockOutputStream(fos)
        blockOutput.setCompress("none")

        val header = Osmformat.HeaderBlock.newBuilder()
            .addRequiredFeatures("OsmSchema-V0.6")
            .build()
        blockOutput.write(FileBlock.newInstance("OSMHeader", header.toByteString(), ByteString.EMPTY))

        val strings = mutableListOf("")
        val stringIndex = mutableMapOf("" to 0)
        fun internString(s: String): Int = stringIndex.getOrPut(s) {
            strings.add(s)
            strings.size - 1
        }

        val primitiveGroup = Osmformat.PrimitiveGroup.newBuilder()
        data.nodes.forEach { node ->
            primitiveGroup.addNodes(
                Osmformat.Node.newBuilder()
                    .setId(node.id)
                    .setLat(Math.round(node.lat * 1e7))
                    .setLon(Math.round(node.lon * 1e7))
            )
        }
        data.ways.forEach { way ->
            val wayBuilder = Osmformat.Way.newBuilder().setId(way.id)
            way.tags.forEach { (k, v) ->
                wayBuilder.addKeys(internString(k))
                wayBuilder.addVals(internString(v))
            }
            var previousRef = 0L
            way.nodeIds.forEach { ref ->
                wayBuilder.addRefs(ref - previousRef)
                previousRef = ref
            }
            primitiveGroup.addWays(wayBuilder)
        }

        val stringTable = Osmformat.StringTable.newBuilder()
        strings.forEach { s -> stringTable.addS(ByteString.copyFromUtf8(s)) }

        val primitiveBlock = Osmformat.PrimitiveBlock.newBuilder()
            .setStringtable(stringTable)
            .addPrimitivegroup(primitiveGroup)
            .build()
        blockOutput.write(FileBlock.newInstance("OSMData", primitiveBlock.toByteString(), ByteString.EMPTY))

        blockOutput.close()
    }
}
