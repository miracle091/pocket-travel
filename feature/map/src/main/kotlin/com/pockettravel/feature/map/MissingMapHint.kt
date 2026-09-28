package com.pockettravel.feature.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap

// Id dell'immagine aggiunta allo stile con Style.addImage (vedi MapScreen), referenziata dal
// layer "missing_map_hint" costruito da OfflineTileSource quando manca ogni sorgente.
internal const val MISSING_MAP_HATCH_IMAGE = "missing-map-hatch"

// Reticolo discreto per far capire che manca la mappa di base: un "background-pattern" si ripete
// in pixel schermo, quindi resta uguale a ogni livello di zoom, a differenza di linee disegnate su
// coordinate geografiche (che a zoom diversi apparirebbero piu' fitte o piu' rade, o assenti del
// tutto senza una sorgente vettoriale da cui ritagliarle). Stessa tecnica delle icone dei POI
// (poiPinBitmap): un piccolo Bitmap disegnato con Canvas invece di un asset PNG da mantenere.
internal fun missingMapHatchBitmap(context: Context, dark: Boolean): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (16f * density).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0x33ffffff else 0x22000000
        strokeWidth = 1f * density
    }
    canvas.drawLine(0f, size.toFloat(), size.toFloat(), 0f, paint)
    return bitmap
}
