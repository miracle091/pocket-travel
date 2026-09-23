package com.pockettravel.feature.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pockettravel.core.data.PoiCategory
import com.pockettravel.core.ui.PoiColors
import com.pockettravel.core.ui.R as UiR

internal fun PoiCategory.pinColor(): Color = when (this) {
    PoiCategory.ALLOGGIO -> PoiColors.Lodging
    PoiCategory.CIBO_BEVANDE -> PoiColors.FoodDrink
    PoiCategory.NEGOZI -> PoiColors.Shopping
    PoiCategory.ATTRAZIONI -> PoiColors.Attractions
    PoiCategory.AMBASCIATA_CONSOLATO -> PoiColors.Embassy
    PoiCategory.ALTRO -> PoiColors.Other
}

// Glifo della categoria dentro il segnalino (Material Symbols di core:ui); null = segnalino
// generico con un pallino, come il pin di default di Google Maps.
@DrawableRes
internal fun PoiCategory.glyph(): Int? = when (this) {
    PoiCategory.ALLOGGIO -> UiR.drawable.ms_hotel
    PoiCategory.CIBO_BEVANDE -> UiR.drawable.ms_restaurant
    PoiCategory.NEGOZI -> UiR.drawable.ms_shopping_bag
    PoiCategory.ATTRAZIONI -> UiR.drawable.ms_attractions
    PoiCategory.AMBASCIATA_CONSOLATO -> UiR.drawable.ms_account_balance
    PoiCategory.ALTRO -> null
}

@StringRes
internal fun PoiCategory.label(): Int = when (this) {
    PoiCategory.ALLOGGIO -> R.string.poi_lodging
    PoiCategory.CIBO_BEVANDE -> R.string.poi_food
    PoiCategory.NEGOZI -> R.string.poi_shopping
    PoiCategory.ATTRAZIONI -> R.string.poi_attractions
    PoiCategory.AMBASCIATA_CONSOLATO -> R.string.poi_embassy
    PoiCategory.ALTRO -> R.string.poi_other
}

// Segnalino in stile Google Maps disegnato su Bitmap (SymbolManager di MapLibre vuole un Bitmap
// per style.addImage, non un ImageVector): goccia nel colore fisso della categoria con bordo
// bianco, glifo bianco al centro della testa e una piccola ombra sotto la punta. Dimensioni in dp
// convertite con la densita' dello schermo. La punta cade esattamente sul bordo inferiore del
// bitmap (a parte l'ombra, che sborda di poco): con iconAnchor "bottom" indica il punto esatto.
internal fun poiPinBitmap(context: Context, category: PoiCategory): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = 30f * density
    val headRadius = width / 2f
    val tipY = 40f * density
    val stroke = 2f * density
    val bitmap = Bitmap.createBitmap(width.toInt(), (tipY + 2f * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = width / 2f
    val cy = headRadius

    canvas.drawOval(
        cx - 5f * density, tipY - 2f * density, cx + 5f * density, tipY + 2f * density,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 },
    )

    // Testa circolare + due tangenti che convergono nella punta.
    val r = headRadius - stroke / 2f
    val shape = Path().apply {
        addCircle(cx, cy, r, Path.Direction.CW)
        val tip = Path().apply {
            moveTo(cx - r * 0.72f, cy + r * 0.69f)
            lineTo(cx, tipY - stroke)
            lineTo(cx + r * 0.72f, cy + r * 0.69f)
            close()
        }
        op(tip, Path.Op.UNION)
    }
    canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = category.pinColor().toArgb() })
    canvas.drawPath(
        shape,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = PoiColors.Glyph.toArgb()
        },
    )

    val glyphRes = category.glyph()
    if (glyphRes != null) {
        val half = 9f * density
        context.resources.getDrawable(glyphRes, context.theme).mutate().apply {
            setTint(PoiColors.Glyph.toArgb())
            setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            draw(canvas)
        }
    } else {
        canvas.drawCircle(cx, cy, 4f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PoiColors.Glyph.toArgb() })
    }
    return bitmap
}
