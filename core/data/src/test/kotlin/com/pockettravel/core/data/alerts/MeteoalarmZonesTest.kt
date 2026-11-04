package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class MeteoalarmZonesTest {

    private fun alert(name: String, zone: String) = Alert(
        id = zone, source = AlertSource.METEOALARM, kind = AlertKind.WEATHER, level = AlertLevel.MODERATE, sourceLevel = null,
        title = name, details = null, startsAt = null, endsAt = null, area = null, areaName = name, url = null, zoneCode = zone,
    )

    private val spain = listOf(
        alert("Lanzarote", "ES123"), alert("Norte de Tenerife", "ES130"), alert("Ceuta", "ES250"), alert("Melilla", "ES251"),
        alert("Litoral de Barcelona", "ES181"),
    )

    @Test
    fun canaryCeutaAndMelillaZonesAreNotInTheMainlandRegion() {
        assertEquals(listOf("ES181"), spain.inMeteoalarmRegion("spagna", "es").map { it.id })
    }

    @Test
    fun canaryZonesWithOnlyNutsAreNotInTheMainlandRegion() {
        assertEquals(emptyList<Alert>(), listOf(alert("Las Palmas", "ES705")).inMeteoalarmRegion("spagna", "es"))
    }

    private val france = listOf(alert("Gard", "FR812"), alert("Haute-Corse", "FR832"), alert("Finistère", "FR522"), alert("Nuova", "FRJ12"))

    @Test
    fun frenchDepartmentsStayInTheirRegion() {
        assertEquals(listOf("FR812", "FRJ12"), france.inMeteoalarmRegion("francia-occitania", "fr").map { it.id })
        assertEquals(listOf("FR832", "FRJ12"), france.inMeteoalarmRegion("francia-corsica", "fr").map { it.id })
        assertEquals(listOf("FR522", "FRJ12"), france.inMeteoalarmRegion("francia-bretagna", "fr").map { it.id })
        assertEquals(listOf("FRJ12"), france.inMeteoalarmRegion("francia-ile-de-france", "fr").map { it.id })
    }

    @Test
    fun zoneIsTheNutsCodeWhenEmmaIdIsMissing() {
        val feed = """
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:cap="urn:oasis:names:tc:emergency:cap:1.2">
              <entry>
                <cap:geocode><valueName>NUTS3</valueName><value>FR813</value></cap:geocode>
                <cap:areaDesc>Hérault</cap:areaDesc>
                <cap:event>Moderate flooding warning</cap:event>
                <cap:severity>Moderate</cap:severity>
                <cap:message_type>Alert</cap:message_type>
                <cap:status>Actual</cap:status>
                <cap:identifier>2.49.0.0.250.0.FR.1</cap:identifier>
                <title>Moderate flooding warning issued for France - Hérault</title>
              </entry>
              <entry>
                <cap:geocode><valueName>NUTS3</valueName><value>HR031</value></cap:geocode>
                <cap:geocode><valueName>EMMA_ID</valueName><value>HR801</value></cap:geocode>
                <cap:areaDesc>Rijeka</cap:areaDesc>
                <cap:event>Yellow Wind Warning</cap:event>
                <cap:severity>Moderate</cap:severity>
                <cap:message_type>Alert</cap:message_type>
                <cap:status>Actual</cap:status>
                <cap:identifier>2.49.0.0.191.0.HR.1</cap:identifier>
                <title>Yellow Wind Warning issued for Croatia - Rijeka</title>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals(listOf("FR813", "HR801"), MeteoalarmParser.parse(feed).map { it.zoneCode })
    }

    @Test
    fun otherRegionsKeepTheWholeCountryFeed() {
        assertEquals(5, spain.inMeteoalarmRegion("ceuta", "xx").size)
    }
}
