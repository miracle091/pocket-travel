package com.pockettravel.pipeline

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regressione: un estratto Overpass reale con un `node[tag](bbox)` esplicito
 * unito a una ricorsione `>;` sulle way puo' emettere lo stesso `<node>` due volte se il nodo
 * soddisfa entrambi i criteri — trovato generando un .rd5 da un'estrazione reale di Milano, dove
 * il nodo duplicato mandava in crash WayLinker molto a valle nella pipeline
 * ("duplicate key found in late check").
 */
class OsmXmlToPbfTest {

    @Test
    fun `nodi duplicati con lo stesso id vengono deduplicati`() {
        val xml = """
            <?xml version='1.0' encoding='UTF-8'?>
            <osm version="0.6">
              <node id="1" lat="45.0" lon="9.0"/>
              <node id="1" lat="45.0" lon="9.0"><tag k="tourism" v="artwork"/></node>
              <node id="2" lat="45.001" lon="9.001"/>
              <way id="10">
                <nd ref="1"/>
                <nd ref="2"/>
                <tag k="highway" v="footway"/>
              </way>
            </osm>
        """.trimIndent()
        val file = File(createTempDirectory("pocket-travel-test").toFile(), "duplicate-node.osm.xml")
        file.writeText(xml)

        val data = parseOsmXml(file)

        assertEquals(2, data.nodes.size)
        assertEquals(setOf(1L, 2L), data.nodes.map { it.id }.toSet())
    }
}
