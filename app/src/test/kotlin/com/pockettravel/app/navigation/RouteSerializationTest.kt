package com.pockettravel.app.navigation

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class RouteSerializationTest {
    private fun SerialDescriptor.names(): List<String> = (0 until elementsCount).map { getElementName(it) }

    private val objectRoutes = mapOf(
        "OnboardingRoute" to OnboardingRoute.serializer().descriptor,
        "TutorialRoute" to TutorialRoute.serializer().descriptor,
        "RegionsRoute" to RegionsRoute.serializer().descriptor,
        "StorageRoute" to StorageRoute.serializer().descriptor,
        "UpdatesRoute" to UpdatesRoute.serializer().descriptor,
        "SourcesRoute" to SourcesRoute.serializer().descriptor,
        "LicensesRoute" to LicensesRoute.serializer().descriptor,
        "VaultRoute" to VaultRoute.serializer().descriptor,
        "MoreRoute" to MoreRoute.serializer().descriptor,
        "NavigatorRoute" to NavigatorRoute.serializer().descriptor,
        "SettingsRoute" to SettingsRoute.serializer().descriptor,
    )

    private val argumentRoutes = mapOf(
        "RegionHubRoute" to RegionHubRoute.serializer().descriptor,
        "RegionPreviewRoute" to RegionPreviewRoute.serializer().descriptor,
        "InAppBrowserRoute" to InAppBrowserRoute.serializer().descriptor,
    )

    @Test
    fun `le schermate senza argomenti sono oggetti senza elementi`() {
        objectRoutes.forEach { (name, descriptor) ->
            assertEquals(name, StructureKind.OBJECT, descriptor.kind)
            assertEquals(name, 0L, descriptor.elementsCount.toLong())
        }
    }

    @Test
    fun `ogni rotta ha un nome di serializzazione diverso e quello della sua classe`() {
        val all = objectRoutes + argumentRoutes
        val serialNames = all.values.map { it.serialName }

        assertEquals(serialNames.size, serialNames.toSet().size)
        all.forEach { (name, descriptor) -> assertTrue(name, descriptor.serialName.endsWith(".$name")) }
    }

    @Test
    fun `l'hub regione ha id e scheda, la scheda ha un valore di default`() {
        val descriptor = RegionHubRoute.serializer().descriptor

        assertEquals(StructureKind.CLASS, descriptor.kind)
        assertEquals(listOf("regionId", "tab"), descriptor.names())
        assertFalse(descriptor.isElementOptional(0))
        assertTrue(descriptor.isElementOptional(1))
    }

    @Test
    fun `l'anteprima ha id e nome, il nome ha un valore di default`() {
        val descriptor = RegionPreviewRoute.serializer().descriptor

        assertEquals(listOf("regionId", "name"), descriptor.names())
        assertFalse(descriptor.isElementOptional(0))
        assertTrue(descriptor.isElementOptional(1))
    }

    @Test
    fun `il browser ha indirizzo e titolo, il titolo ha un valore di default`() {
        val descriptor = InAppBrowserRoute.serializer().descriptor

        assertEquals(listOf("url", "title"), descriptor.names())
        assertFalse(descriptor.isElementOptional(0))
        assertTrue(descriptor.isElementOptional(1))
    }

    @Test
    fun `le rotte con gli stessi argomenti sono uguali e hanno lo stesso hash`() {
        assertEquals(InAppBrowserRoute("https://a.org/x?y=1&z=2", "T"), InAppBrowserRoute("https://a.org/x?y=1&z=2", "T"))
        assertEquals(InAppBrowserRoute("https://a.org").hashCode(), InAppBrowserRoute("https://a.org", "").hashCode())
        assertEquals(RegionPreviewRoute("italia").copy(name = "Italia"), RegionPreviewRoute("italia", "Italia"))
    }
}
