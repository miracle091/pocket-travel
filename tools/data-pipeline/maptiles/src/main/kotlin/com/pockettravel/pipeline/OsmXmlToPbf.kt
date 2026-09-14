package com.pockettravel.pipeline

import com.google.protobuf.ByteString
import java.io.File
import java.io.FileOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.openstreetmap.osmosis.osmbinary.Osmformat
import org.openstreetmap.osmosis.osmbinary.file.BlockOutputStream
import org.openstreetmap.osmosis.osmbinary.file.FileBlock
import org.w3c.dom.Element

data class OsmNode(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String> = emptyMap())
data class OsmWay(val id: Long, val nodeIds: List<Long>, val tags: Map<String, String>)
data class OsmData(val nodes: List<OsmNode>, val ways: List<OsmWay>)

/**
 * Legge il sottoinsieme minimo di OSM XML usato dai fixture di test di questo repo
 * (solo <node> e <way>, nessuna relation) — non un parser OSM XML generico.
 *
 * Duplicato intenzionale in :tools:data-pipeline:routing/OsmXmlToPbf.kt (stesso contenuto,
 * per evitare che :routing dipenda da :maptiles solo per queste due funzioni — vedi il
 * commento li'). Se cambia questo file, aggiornare anche quella copia.
 *
 * I nodi vengono deduplicati per id: un estratto Overpass reale con un
 * `node[tag](bbox)` esplicito unito a una ricorsione `>;` sulle way puo' emettere lo stesso
 * `<node>` due volte se il nodo soddisfa entrambi i criteri — trovato generando un .rd5 da
 * un'estrazione reale di Milano, dove un nodo duplicato mandava in crash WayLinker
 * ("duplicate key found in late check") molto a valle nella pipeline.
 */
fun parseOsmXml(file: File): OsmData {
    // Il limite JAXP sulla dimensione dell'"entita' documento" (100.000 caratteri di default)
    // scatta anche su XML grandi ma innocui quando disallow-doctype-decl e' attivo (es. estratto
    // Overpass reale per una nazione grande, milioni di righe) - non e' l'XXE che
    // disallow-doctype-decl/external-entities gia' prevengono, quindi lo disattiviamo qui.
    System.setProperty("jdk.xml.maxGeneralEntitySizeLimit", "0")
    System.setProperty("jdk.xml.totalEntitySizeLimit", "0")
    System.setProperty("jdk.xml.entityExpansionLimit", "0")
    val doc = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(file)
    val nodes = doc.getElementsByTagName("node").let { list ->
        (0 until list.length).map { i ->
            val el = list.item(i) as Element
            val tags = mutableMapOf<String, String>()
            val children = el.childNodes
            for (c in 0 until children.length) {
                val child = children.item(c) as? Element ?: continue
                if (child.tagName == "tag") tags[child.getAttribute("k")] = child.getAttribute("v")
            }
            OsmNode(
                id = el.getAttribute("id").toLong(),
                lat = el.getAttribute("lat").toDouble(),
                lon = el.getAttribute("lon").toDouble(),
                tags = tags,
            )
        }.distinctBy { it.id }
    }
    val ways = doc.getElementsByTagName("way").let { list ->
        (0 until list.length).map { i ->
            val el = list.item(i) as Element
            val nodeIds = mutableListOf<Long>()
            val tags = mutableMapOf<String, String>()
            val children = el.childNodes
            for (c in 0 until children.length) {
                val child = children.item(c) as? Element ?: continue
                when (child.tagName) {
                    "nd" -> nodeIds += child.getAttribute("ref").toLong()
                    "tag" -> tags[child.getAttribute("k")] = child.getAttribute("v")
                }
            }
            OsmWay(id = el.getAttribute("id").toLong(), nodeIds = nodeIds, tags = tags)
        }
    }
    return OsmData(nodes, ways)
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
