package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.text.Normalizer

class GeneratePoiNfcTest {

    private fun nfd(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)

    // La ricerca per nome dell'app (GLOB con le varianti accentate) confronta caratteri precomposti: un nome
    // scomposto ("e" + accento) non verrebbe mai trovato.
    @Test
    fun `i nomi scomposti in OSM arrivano precomposti nel pacchetto`() {
        val osm = File.createTempFile("pocket-travel-nfc", ".osm.xml")
        try {
            osm.writeText(
                """
                <?xml version='1.0' encoding='UTF-8'?>
                <osm version="0.6">
                  <node id="1" lat="45.0" lon="9.0">
                    <tag k="tourism" v="museum"/>
                    <tag k="name" v="${nfd("Musée d'Orsay")}"/>
                    <tag k="name:en" v="${nfd("Orsay Muséum")}"/>
                    <tag k="name:it" v="${nfd("Museo d'Orsay è")}"/>
                  </node>
                </osm>
                """.trimIndent(),
            )

            val poi = readPois(listOf(osm), listOf("tourism")).single()

            assertEquals("Musée d'Orsay", poi.name)
            assertEquals("Orsay Muséum", poi.nameEn)
            assertEquals("Museo d'Orsay è", poi.nameIt)
        } finally {
            osm.delete()
        }
    }
}
