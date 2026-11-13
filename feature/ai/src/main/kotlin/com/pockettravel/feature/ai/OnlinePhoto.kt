package com.pockettravel.feature.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lato lungo della foto per il servizio: il massimo che il modello usa senza ridurla lui (Claude 1568 px, Mistral
 * 1540 px), 1024 px per gli altri, che contano i token per tessera o per immagine (OpenAI, Gemini).
 */
internal fun photoLongSide(provider: OnlineProvider): Int = PHOTO_LONG_SIDE.getOrDefault(provider, DEFAULT_PHOTO_LONG_SIDE)

/** Larghezza e altezza con il lato lungo al massimo [maxSide], in proporzione; una foto piu' piccola resta com'e'. */
internal fun scaledSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    val longSide = max(width, height)
    if (longSide <= maxSide) return width to height
    val scale = maxSide.toFloat() / longSide
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/** Il campionamento piu' grande (potenza di 2) che lascia il lato lungo almeno a [maxSide]: decodifica senza la foto intera in memoria. */
internal fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/**
 * La foto della domanda in fondo. Un cambio di conversazione, motore o servizio toglie o cambia [AiUiState.askedQuestion]:
 * la foto di prima non si mostra sopra un'altra domanda e non riparte con lei.
 */
val AiUiState.lastQuestionPhoto: AttachedPhoto? get() = askedPhoto?.takeIf { it.question == askedQuestion }

/** [photo] se si puo' mandare: solo online e con un modello che legge le foto. */
fun AiUiState.photoToSend(photo: AttachedPhoto?): AttachedPhoto? = photo?.takeIf { mode == AiEngineMode.ONLINE && canAttachPhoto }

/**
 * La foto che parte con la domanda: quella allegata o, in un seguito ([followUp]), l'ultima della conversazione. Mai senza
 * il consenso del servizio scelto ([consented]).
 */
internal fun AiUiState.photoForQuestion(followUp: Boolean, consented: Boolean): AttachedPhoto? =
    (photoToSend(attachedPhoto) ?: photoToSend(askedPhoto)?.takeIf { followUp })?.takeIf { consented }

/**
 * La foto letta con [open] (aperta tre volte: misure, pixel, rotazione) pronta per il servizio: girata come la mostra
 * la galleria, lato lungo al massimo [maxSide], JPEG di qualita' 80. La nuova codifica non scrive nessun metadato: posizione (GPS), data, telefono e gli altri campi EXIF
 * dell'originale non escono mai. A 1568 px il JPEG resta sotto 1 MB, molto sotto i limiti dei servizi (5 MB per immagine
 * il piu' basso). Lavoro su disco e di CPU: da chiamare fuori dal main thread.
 */
internal fun preparePhoto(open: () -> InputStream?, maxSide: Int): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Foto illeggibile" }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
    val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IllegalArgumentException("Foto illeggibile")
    val exif = open()?.use { ExifInterface(it) }
    val (width, height) = scaledSize(decoded.width, decoded.height, maxSide)
    val matrix = Matrix().apply {
        postScale(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
        // Specchiata prima di girare: e' l'ordine in cui rotationDegrees conta gli orientamenti FLIP, TRANSPOSE e TRANSVERSE.
        if (exif?.isFlipped == true) postScale(-1f, 1f)
        postRotate((exif?.rotationDegrees ?: 0).toFloat())
    }
    val photo = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).onWhite()
    return ByteArrayOutputStream().use {
        photo.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        it.toByteArray()
    }
}

/** True se la foto allegata aspetta il consenso ([consented] falso): si apre l'avviso alla prima foto (onPhotoConsent). */
internal fun MutableStateFlow<AiUiState>.awaitsPhotoConsent(consented: Boolean): Boolean {
    if (consented || value.photoToSend(value.attachedPhoto) == null) return false
    update { it.copy(confirmsPhoto = true, sendsPhotos = false) }
    return true
}

/**
 * La foto letta con [open], ridotta per il servizio in uso ([preparePhoto] su [dispatcher]) e allegata alla prossima
 * domanda; una foto illeggibile mostra un errore. [discard] gira sempre alla fine: cancella lo scatto della fotocamera.
 */
internal suspend fun MutableStateFlow<AiUiState>.attachPhoto(open: () -> InputStream?, discard: () -> Unit, dispatcher: CoroutineDispatcher) {
    val maxSide = photoLongSide(value.onlineProvider)
    update { it.copy(isPreparingPhoto = true) }
    val photo = try {
        withContext(dispatcher) { preparePhoto(open, maxSide) }
    } catch (error: IOException) {
        unreadable(error)
    } catch (error: IllegalArgumentException) {
        unreadable(error)
    } catch (error: SecurityException) {
        unreadable(error)
    } finally {
        discard()
        update { it.copy(isPreparingPhoto = false) }
    }
    update { if (photo == null) it.copy(errorMessage = R.string.ai_photo_unreadable) else it.copy(attachedPhoto = AttachedPhoto(photo), errorMessage = null) }
}

private fun unreadable(error: Exception): ByteArray? {
    Log.w("OnlinePhoto", "Foto non leggibile: ${error.javaClass.name}")
    return null
}

// Il JPEG non ha trasparenza: un PNG trasparente diventerebbe nero sotto il disegno.
private fun Bitmap.onWhite(): Bitmap {
    if (!hasAlpha()) return this
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(Color.WHITE)
            drawBitmap(this@onWhite, 0f, 0f, null)
        }
    }
}

private val PHOTO_LONG_SIDE = mapOf(OnlineProvider.CLAUDE to 1568, OnlineProvider.MISTRAL to 1540)
private const val DEFAULT_PHOTO_LONG_SIDE = 1024
private const val JPEG_QUALITY = 80
