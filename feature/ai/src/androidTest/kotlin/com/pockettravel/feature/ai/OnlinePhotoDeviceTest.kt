package com.pockettravel.feature.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// La foto per i servizi online con i decoder veri di Android: EXIF e GPS dell'originale tolti, rotazione applicata.
@RunWith(AndroidJUnit4::class)
class OnlinePhotoDeviceTest {

    private val original = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "exif-gps.jpg")

    @After
    fun tearDown() {
        original.delete()
    }

    @Test
    fun photoLosesExifAndGpsAndKeepsItsRotation() {
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(original).apply {
            setLatLong(41.9028, 12.4964)
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:13 10:00:00")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertTrue(ExifInterface(original).latLong != null)

        val photo = preparePhoto({ original.inputStream() }, maxSide = 1024)

        val exif = ExifInterface(photo.inputStream())
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertFalse("segmento EXIF nel JPEG", photo.decodeToString(throwOnInvalidSequence = false).contains("Exif"))
        // Girata come la mostra la galleria (verticale) e con il lato lungo a 1024.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
        assertEquals(683 to 1024, bounds.outWidth to bounds.outHeight)
    }
}
