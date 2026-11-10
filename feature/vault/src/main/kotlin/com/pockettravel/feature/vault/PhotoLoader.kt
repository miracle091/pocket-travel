package com.pockettravel.feature.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.pockettravel.core.data.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.inject.Inject

/** Decodifica una foto della cassaforte già decifrata; null se i byte non sono un'immagine. */
fun interface PhotoLoader {
    suspend fun decode(bytes: ByteArray, targetPx: Int): ImageBitmap?
}

// Decodifica fuori dal main thread, ridotta finché il lato corto resta almeno targetPx: una foto
// della fotocamera a piena risoluzione occupa decine di MB in memoria.
class BitmapPhotoLoader @Inject constructor(
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : PhotoLoader {
    override suspend fun decode(bytes: ByteArray, targetPx: Int): ImageBitmap? = withContext(defaultDispatcher) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null
        // CameraX salva la rotazione del dispositivo nel tag EXIF Orientation, che BitmapFactory ignora: senza,
        // una foto scattata in verticale comparirebbe di lato.
        val degrees = runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
        val upright = if (degrees == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        }
        upright.asImageBitmap()
    }
}

/** inSampleSize di BitmapFactory: la potenza di 2 più grande che lascia il lato corto almeno [targetPx]. */
internal fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    var sampleSize = 1
    while (minOf(width, height) / (sampleSize * 2) >= targetPx) sampleSize *= 2
    return sampleSize
}
