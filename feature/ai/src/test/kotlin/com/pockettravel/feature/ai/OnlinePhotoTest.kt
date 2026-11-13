package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

// Dimensioni delle foto per i servizi online: lato lungo per servizio, proporzioni e campionamento della decodifica.
class OnlinePhotoTest {

    @Test
    fun `lato lungo per servizio`() {
        assertEquals(1568, photoLongSide(OnlineProvider.CLAUDE))
        assertEquals(1540, photoLongSide(OnlineProvider.MISTRAL))
        assertEquals(1024, photoLongSide(OnlineProvider.GEMINI))
        assertEquals(1024, photoLongSide(OnlineProvider.CHATGPT))
    }

    @Test
    fun `la foto grande si riduce in proporzione, quella piccola resta com'e'`() {
        assertEquals(1024 to 768, scaledSize(4000, 3000, 1024))
        assertEquals(768 to 1024, scaledSize(3000, 4000, 1024))
        assertEquals(800 to 600, scaledSize(800, 600, 1024))
        assertEquals(1024 to 1, scaledSize(9000, 3, 1024))
    }

    @Test
    fun `il campionamento non scende sotto il lato lungo voluto`() {
        assertEquals(2, sampleSize(4000, 3000, 1568))
        assertEquals(1, sampleSize(3000, 2000, 1568))
        assertEquals(4, sampleSize(4096, 3072, 1024))
        assertEquals(1, sampleSize(800, 600, 1024))
    }
}
