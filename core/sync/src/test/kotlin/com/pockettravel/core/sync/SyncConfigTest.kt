package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class SyncConfigTest {

    @Before
    fun setUp() {
        // Con un catalogo di debug (-PpocketTravel.manifestUrl, solo debug) indirizzo e host sono quelli del server locale.
        assumeTrue(BuildConfig.MANIFEST_URL_OVERRIDE.isEmpty())
        SyncConfig.useCatalog(null)
    }

    private inline fun withCatalog(catalog: CustomCatalog, block: () -> Unit) {
        try {
            SyncConfig.useCatalog(catalog)
            block()
        } finally {
            SyncConfig.useCatalog(null)
        }
    }

    @Test
    fun `la chiave pubblica ufficiale e' una chiave EC P-256 leggibile`() {
        val key = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(SyncConfig.OFFICIAL_PUBLIC_KEY)),
        ) as ECPublicKey
        assertEquals(256, key.params.curve.field.fieldSize)
    }

    @Test
    fun `senza catalogo scelto valgono l'indirizzo e la chiave ufficiali`() {
        assertTrue(SyncConfig.MANIFEST_URL.startsWith("https://"))
        assertEquals(SyncConfig.OFFICIAL_PUBLIC_KEY, SyncConfig.MANIFEST_PUBLIC_KEY)
        assertNull(SyncConfig.CLEARTEXT_MANIFEST_HOST)
    }

    @Test
    fun `gli host ammessi sono i fissi e quello del catalogo`() {
        val hosts = SyncConfig.ALLOWED_MANIFEST_HOSTS
        assertEquals(setOf("brouter.de", "build.protomaps.com", "github.com", URI(SyncConfig.MANIFEST_URL).host), hosts)
    }

    @Test
    fun `l'indirizzo di app-status e' https su un host ammesso, per scaricarne la firma`() {
        assertTrue(SyncConfig.APP_STATUS_URL.startsWith("https://"))
        assertTrue(URI(SyncConfig.APP_STATUS_URL).host in SyncConfig.ALLOWED_MANIFEST_HOSTS)
        assertEquals("app-status.json", URI(SyncConfig.APP_STATUS_URL).path.substringAfterLast('/'))
    }

    @Test
    fun `un catalogo https aggiunge il suo host e sostituisce la chiave`() {
        withCatalog(CustomCatalog("https://catalogo.example.org/m/manifest.json", "chiave")) {
            assertEquals("https://catalogo.example.org/m/manifest.json", SyncConfig.MANIFEST_URL)
            assertEquals("chiave", SyncConfig.MANIFEST_PUBLIC_KEY)
            assertTrue("catalogo.example.org" in SyncConfig.ALLOWED_MANIFEST_HOSTS)
            assertTrue("github.com" in SyncConfig.ALLOWED_MANIFEST_HOSTS)
            assertNull(SyncConfig.CLEARTEXT_MANIFEST_HOST)
            assertTrue(isAllowedManifestUrl("https://catalogo.example.org/x.db"))
            assertFalse(isAllowedManifestUrl("http://catalogo.example.org/x.db"))
        }
        assertFalse("catalogo.example.org" in SyncConfig.ALLOWED_MANIFEST_HOSTS)
        assertFalse(isAllowedManifestUrl("https://catalogo.example.org/x.db"))
    }

    @Test
    fun `un catalogo in chiaro ammette in chiaro solo il proprio host`() {
        withCatalog(CustomCatalog("http://10.0.2.2:8000/manifest.json", "chiave")) {
            assertEquals("10.0.2.2", SyncConfig.CLEARTEXT_MANIFEST_HOST)
            assertTrue(isAllowedManifestUrl("http://10.0.2.2:8000/poi.db"))
            assertFalse(isAllowedManifestUrl("http://github.com/poi.db"))
            assertFalse(isAllowedManifestUrl("http://192.168.0.5/poi.db"))
        }
        assertNull(SyncConfig.CLEARTEXT_MANIFEST_HOST)
    }

    @Test
    fun `i nomi dei lavori e dei tag non si sovrappongono`() {
        val names = listOf(
            SyncConfig.PERIODIC_SYNC_WORK_NAME, SyncConfig.ONE_TIME_SYNC_WORK_NAME, SyncConfig.GUIDES_SYNC_WORK_NAME,
            SyncConfig.APP_UPDATE_CHECK_WORK_NAME,
        )
        assertEquals(names.size, names.toSet().size)
        // Il nome del download di una regione non deve coincidere con un lavoro unico degli altri.
        assertTrue(names.none { it.startsWith(SyncConfig.DOWNLOAD_WORK_NAME_PREFIX) })
    }
}
