package com.pockettravel.feature.vault

import androidx.compose.ui.graphics.ImageBitmap

// Cache LRU di miniature, misurata in byte dei bitmap. In Kotlin puro (non android.util.LruCache) per girare nei test JVM.
internal class ThumbnailCache(private val maxBytes: Int) {
    private val entries = LinkedHashMap<String, ImageBitmap>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var sizeBytes = 0

    @Synchronized
    fun get(key: String): ImageBitmap? = entries[key]

    @Synchronized
    fun put(key: String, image: ImageBitmap) {
        entries.put(key, image)?.let { sizeBytes -= sizeOf(it) }
        sizeBytes += sizeOf(image)
        val oldest = entries.entries.iterator()
        while (sizeBytes > maxBytes && oldest.hasNext()) {
            sizeBytes -= sizeOf(oldest.next().value)
            oldest.remove()
        }
    }

    @Synchronized
    fun evictAll() {
        entries.clear()
        sizeBytes = 0
    }

    private fun sizeOf(image: ImageBitmap): Int = image.width * image.height * BYTES_PER_PIXEL

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
        const val BYTES_PER_PIXEL = 4
    }
}
