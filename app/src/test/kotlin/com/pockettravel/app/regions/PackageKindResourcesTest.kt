package com.pockettravel.app.regions

import com.pockettravel.core.data.PackageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageKindResourcesTest {

    @Test
    fun `ogni tipo di pacchetto ha un nome proprio`() {
        val labels = PackageKind.entries.map { it.label() }

        assertTrue(labels.all { it != 0 })
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `ogni tipo di pacchetto ha un titolo di eliminazione proprio`() {
        val titles = PackageKind.entries.map { it.deleteTitle() }

        assertTrue(titles.all { it != 0 })
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `il titolo di eliminazione non e' il nome del pacchetto`() {
        PackageKind.entries.forEach { assertNotEquals(it.name, it.label().toLong(), it.deleteTitle().toLong()) }
    }

    @Test
    fun `le scelte della rete stradale sono tre, con testi diversi`() {
        assertEquals(listOf(RoutingVariantOption.CAR, RoutingVariantOption.BIKE_FOOT, RoutingVariantOption.ALL), RoutingVariantOption.entries)

        val labels = RoutingVariantOption.entries.map { it.label }
        val details = RoutingVariantOption.entries.map { it.detail }

        assertEquals(3, labels.toSet().size)
        assertEquals(3, details.toSet().size)
        assertTrue((labels + details).all { it != 0 })
    }
}
