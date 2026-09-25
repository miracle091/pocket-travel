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
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.PoiColors
import com.pockettravel.core.ui.R as UiR

internal fun PoiCategory.pinColor(): Color = when (this) {
    PoiCategory.ALLOGGIO -> PoiColors.Lodging
    PoiCategory.CIBO_BEVANDE -> PoiColors.FoodDrink
    PoiCategory.NEGOZI, PoiCategory.DISTRIBUTORI -> PoiColors.Shopping
    PoiCategory.ATTRAZIONI, PoiCategory.PARCO_GIOCHI, PoiCategory.TAVOLI_PICNIC -> PoiColors.Attractions
    PoiCategory.SVAGO -> PoiColors.Entertainment
    PoiCategory.AMBASCIATA_CONSOLATO, PoiCategory.POLIZIA, PoiCategory.BAGNI_PUBBLICI, PoiCategory.ACQUA_POTABILE,
    PoiCategory.FARMACIA, PoiCategory.OSPEDALE, PoiCategory.VIGILI_DEL_FUOCO, PoiCategory.VETERINARIO,
    PoiCategory.BANCA, PoiCategory.BANCOMAT, PoiCategory.UFFICIO_POSTALE, PoiCategory.CASSETTA_POSTALE, PoiCategory.INFORMAZIONI ->
        PoiColors.PublicServices
    PoiCategory.CARBURANTE, PoiCategory.RICARICA, PoiCategory.NOLEGGIO, PoiCategory.PARCHEGGIO, PoiCategory.PARCHEGGIO_PRIVATO,
    PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI, PoiCategory.TRAGHETTO,
    PoiCategory.AEROPORTO -> PoiColors.Transport
    PoiCategory.ALTRO -> PoiColors.Other
}

// Glifo della categoria dentro il segnalino (Material Symbols di core:ui); null = segnalino
// generico con un pallino, come il pin di default di Google Maps.
@DrawableRes
internal fun PoiCategory.glyph(): Int? = when (this) {
    PoiCategory.ALLOGGIO -> UiR.drawable.ms_hotel
    PoiCategory.CIBO_BEVANDE -> UiR.drawable.ms_restaurant
    PoiCategory.NEGOZI -> UiR.drawable.ms_shopping_bag
    PoiCategory.DISTRIBUTORI -> UiR.drawable.ms_local_convenience_store
    PoiCategory.PARCO_GIOCHI -> UiR.drawable.ms_playground
    PoiCategory.TAVOLI_PICNIC -> UiR.drawable.ms_deck
    PoiCategory.ACQUA_POTABILE -> UiR.drawable.ms_water_drop
    PoiCategory.CASSETTA_POSTALE -> UiR.drawable.ms_markunread_mailbox
    PoiCategory.ATTRAZIONI -> UiR.drawable.ms_attractions
    PoiCategory.SVAGO -> UiR.drawable.ms_theater_comedy
    PoiCategory.AMBASCIATA_CONSOLATO -> UiR.drawable.ms_account_balance
    PoiCategory.POLIZIA -> UiR.drawable.ms_local_police
    PoiCategory.BAGNI_PUBBLICI -> UiR.drawable.ms_wc
    PoiCategory.CARBURANTE -> UiR.drawable.ms_local_gas_station
    PoiCategory.RICARICA -> UiR.drawable.ms_ev_station
    PoiCategory.FARMACIA -> UiR.drawable.ms_local_pharmacy
    PoiCategory.OSPEDALE -> UiR.drawable.ms_local_hospital
    PoiCategory.VIGILI_DEL_FUOCO -> UiR.drawable.ms_fire_truck
    PoiCategory.VETERINARIO -> UiR.drawable.ms_pets
    PoiCategory.BANCA -> UiR.drawable.ms_savings
    PoiCategory.BANCOMAT -> UiR.drawable.ms_local_atm
    PoiCategory.UFFICIO_POSTALE -> UiR.drawable.ms_local_post_office
    PoiCategory.INFORMAZIONI -> UiR.drawable.ms_info
    PoiCategory.NOLEGGIO -> UiR.drawable.ms_car_rental
    PoiCategory.PARCHEGGIO, PoiCategory.PARCHEGGIO_PRIVATO -> UiR.drawable.ms_local_parking
    PoiCategory.TRENO -> UiR.drawable.ms_train
    PoiCategory.METRO -> UiR.drawable.ms_subway
    PoiCategory.AUTOBUS -> UiR.drawable.ms_directions_bus
    PoiCategory.TAXI -> UiR.drawable.ms_local_taxi
    PoiCategory.TRAGHETTO -> UiR.drawable.ms_directions_boat
    PoiCategory.AEROPORTO -> UiR.drawable.ms_flight
    PoiCategory.ALTRO -> null
}

@StringRes
internal fun PoiCategory.label(): Int = when (this) {
    PoiCategory.ALLOGGIO -> R.string.poi_lodging
    PoiCategory.CIBO_BEVANDE -> R.string.poi_food
    PoiCategory.NEGOZI -> R.string.poi_shopping
    PoiCategory.DISTRIBUTORI -> R.string.poi_vending
    PoiCategory.PARCO_GIOCHI -> R.string.poi_playground
    PoiCategory.TAVOLI_PICNIC -> R.string.poi_picnic_table
    PoiCategory.ACQUA_POTABILE -> R.string.poi_drinking_water
    PoiCategory.CASSETTA_POSTALE -> R.string.poi_post_box
    PoiCategory.ATTRAZIONI -> R.string.poi_attractions
    PoiCategory.SVAGO -> R.string.poi_entertainment
    PoiCategory.AMBASCIATA_CONSOLATO -> R.string.poi_embassy
    PoiCategory.POLIZIA -> R.string.poi_police
    PoiCategory.BAGNI_PUBBLICI -> R.string.poi_toilets
    PoiCategory.CARBURANTE -> R.string.poi_fuel
    PoiCategory.RICARICA -> R.string.poi_charging
    PoiCategory.FARMACIA -> R.string.poi_pharmacy
    PoiCategory.OSPEDALE -> R.string.poi_hospital
    PoiCategory.VIGILI_DEL_FUOCO -> R.string.poi_fire_station
    PoiCategory.VETERINARIO -> R.string.poi_veterinary
    PoiCategory.BANCA -> R.string.poi_bank
    PoiCategory.BANCOMAT -> R.string.poi_atm
    PoiCategory.UFFICIO_POSTALE -> R.string.poi_post_office
    PoiCategory.INFORMAZIONI -> R.string.poi_information
    PoiCategory.NOLEGGIO -> R.string.poi_rental
    PoiCategory.PARCHEGGIO -> R.string.poi_parking
    PoiCategory.PARCHEGGIO_PRIVATO -> R.string.poi_parking_private
    PoiCategory.TRENO -> R.string.poi_train
    PoiCategory.METRO -> R.string.poi_metro
    PoiCategory.AUTOBUS -> R.string.poi_bus
    PoiCategory.TAXI -> R.string.poi_taxi
    PoiCategory.TRAGHETTO -> R.string.poi_ferry
    PoiCategory.AEROPORTO -> R.string.poi_airport
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
