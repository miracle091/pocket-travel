package com.pockettravel.feature.vault

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailCacheTest {

    // 10x10 pixel a 4 byte = 400 byte.
    private fun bitmap() = FakeImageBitmap(10, 10)

    @Test
    fun `una miniatura messa in cache si rilegge`() {
        val cache = ThumbnailCache(1_000)
        val image = bitmap()
        cache.put("a", image)

        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `oltre il limite di byte esce la miniatura usata meno di recente`() {
        val cache = ThumbnailCache(1_000)
        cache.put("a", bitmap())
        cache.put("b", bitmap())
        cache.get("a")
        cache.put("c", bitmap())

        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `sostituire una chiave non conta due volte i suoi byte`() {
        val cache = ThumbnailCache(1_000)
        cache.put("a", bitmap())
        cache.put("a", bitmap())
        cache.put("b", bitmap())

        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("b"))
    }

    @Test
    fun `evictAll svuota la cache e riparte da zero byte`() {
        val cache = ThumbnailCache(1_000)
        cache.put("a", bitmap())
        cache.put("b", bitmap())
        cache.evictAll()
        cache.put("c", bitmap())
        cache.put("d", bitmap())

        assertNull(cache.get("a"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
    }
}
