package com.pockettravel.feature.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.PoiColors
import com.pockettravel.core.ui.R as UiR

internal fun PoiCategory.pinColor(): Color = when (this) {
    PoiCategory.ALLOGGIO -> PoiColors.Lodging
    PoiCategory.CIBO_BEVANDE -> PoiColors.FoodDrink
    PoiCategory.NEGOZI, PoiCategory.DISTRIBUTORI -> PoiColors.Shopping
    // Stesso colore per tutto "Da vedere": le distingue il glifo.
    PoiCategory.LUOGHI_DI_CULTO, PoiCategory.MUSEI_ARTE, PoiCategory.LUOGHI_STORICI, PoiCategory.NATURA, PoiCategory.PANORAMI,
    PoiCategory.PARCHI_DIVERTIMENTO, PoiCategory.ZOO, PoiCategory.PARCHI_ACQUATICI, PoiCategory.ATTRAZIONI, PoiCategory.PARCO_GIOCHI,
    PoiCategory.TAVOLI_PICNIC, PoiCategory.RIPARI ->
        PoiColors.Attractions
    PoiCategory.SVAGO, PoiCategory.SPORT -> PoiColors.Entertainment
    PoiCategory.AMBASCIATA_CONSOLATO, PoiCategory.BIBLIOTECHE, PoiCategory.POLIZIA, PoiCategory.MUNICIPIO, PoiCategory.AMBULATORI, PoiCategory.BAGNI_PUBBLICI, PoiCategory.ACQUA_POTABILE,
    PoiCategory.FARMACIA, PoiCategory.OSPEDALE, PoiCategory.VIGILI_DEL_FUOCO, PoiCategory.VETERINARIO,
    PoiCategory.BANCA, PoiCategory.BANCOMAT, PoiCategory.CAMBIO_VALUTA, PoiCategory.UFFICIO_POSTALE, PoiCategory.CASSETTA_POSTALE, PoiCategory.INFORMAZIONI ->
        PoiColors.PublicServices
    PoiCategory.CARBURANTE, PoiCategory.RICARICA, PoiCategory.SERVIZI_CAMPER, PoiCategory.RIPARAZIONE_BICI, PoiCategory.NOLEGGIO, PoiCategory.PARCHEGGIO, PoiCategory.PARCHEGGIO_PRIVATO,
    PoiCategory.PARCHEGGIO_DISABILI, PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI, PoiCategory.TRAGHETTO, PoiCategory.PORTI_TURISTICI,
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
    PoiCategory.RIPARI -> UiR.drawable.ms_roofing
    PoiCategory.SERVIZI_CAMPER -> UiR.drawable.ms_rv_hookup
    PoiCategory.ACQUA_POTABILE -> UiR.drawable.ms_water_drop
    PoiCategory.CASSETTA_POSTALE -> UiR.drawable.ms_markunread_mailbox
    PoiCategory.LUOGHI_DI_CULTO -> UiR.drawable.ms_church
    PoiCategory.MUSEI_ARTE -> UiR.drawable.ms_museum
    PoiCategory.LUOGHI_STORICI -> UiR.drawable.ms_castle
    PoiCategory.NATURA -> UiR.drawable.ms_park
    PoiCategory.PANORAMI -> UiR.drawable.ms_landscape
    PoiCategory.PARCHI_DIVERTIMENTO -> UiR.drawable.ms_attractions
    PoiCategory.ZOO -> UiR.drawable.ms_cruelty_free
    PoiCategory.PARCHI_ACQUATICI -> UiR.drawable.ms_pool
    PoiCategory.ATTRAZIONI -> UiR.drawable.ms_star
    PoiCategory.SVAGO -> UiR.drawable.ms_theater_comedy
    PoiCategory.SPORT -> UiR.drawable.ms_fitness_center
    PoiCategory.BIBLIOTECHE -> UiR.drawable.ms_local_library
    PoiCategory.MUNICIPIO -> UiR.drawable.ms_location_city
    PoiCategory.AMBULATORI -> UiR.drawable.ms_medical_services
    PoiCategory.RIPARAZIONE_BICI -> UiR.drawable.ms_home_repair_service
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
    PoiCategory.CAMBIO_VALUTA -> UiR.drawable.ms_currency_exchange
    PoiCategory.UFFICIO_POSTALE -> UiR.drawable.ms_local_post_office
    PoiCategory.INFORMAZIONI -> UiR.drawable.ms_info
    PoiCategory.NOLEGGIO -> UiR.drawable.ms_car_rental
    PoiCategory.PARCHEGGIO, PoiCategory.PARCHEGGIO_PRIVATO -> UiR.drawable.ms_local_parking
    PoiCategory.PARCHEGGIO_DISABILI -> UiR.drawable.ms_accessible
    PoiCategory.TRENO -> UiR.drawable.ms_train
    PoiCategory.METRO -> UiR.drawable.ms_subway
    PoiCategory.AUTOBUS -> UiR.drawable.ms_directions_bus
    PoiCategory.TAXI -> UiR.drawable.ms_local_taxi
    PoiCategory.TRAGHETTO -> UiR.drawable.ms_directions_boat
    PoiCategory.PORTI_TURISTICI -> UiR.drawable.ms_sailing
    PoiCategory.AEROPORTO -> UiR.drawable.ms_flight
    PoiCategory.ALTRO -> null
}


// Segnalino in stile Google Maps disegnato su Bitmap (SymbolManager di MapLibre vuole un Bitmap
// per style.addImage, non un ImageVector): goccia nel colore fisso della categoria con bordo
// bianco, glifo bianco al centro della testa e una piccola ombra sotto la punta. Dimensioni in dp
// convertite con la densita' dello schermo. La punta cade esattamente sul bordo inferiore del
// bitmap (a parte l'ombra, che sborda di poco): con iconAnchor "bottom" indica il punto esatto.
internal fun poiPinBitmap(context: Context, category: PoiCategory, badge: AccessibilityBadge? = null): Bitmap =
    pinBitmap(context, category.pinColor().toArgb(), category.glyph(), badge)

/**
 * Lo stesso segnalino a goccia con colore, glifo e scala scelti: partenza e arrivo del percorso
 * (Navigatore e navigazione) piu' grandi dei POI, per riconoscerli a colpo d'occhio.
 */
internal fun pinBitmap(context: Context, fill: Int, glyphRes: Int?, badge: AccessibilityBadge? = null, scale: Float = 1f): Bitmap {
    val density = context.resources.displayMetrics.density * scale
    val width = 30f * density
    val headRadius = width / 2f
    val tipY = 40f * density
    val stroke = 2f * density
    // Con il distintivo l'immagine si allarga uguale a destra e a sinistra (e in alto), cosi' il
    // distintivo sporge dalla testa senza essere tagliato e la punta resta al centro in basso,
    // sul punto del POI (iconAnchor bottom).
    val pad = if (badge != null) 8f * density else 0f
    val bitmap = createBitmap((width + 2f * pad).toInt(), (tipY + 2f * density + pad).toInt())
    val canvas = Canvas(bitmap)
    canvas.translate(pad, pad)
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
    canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
    canvas.drawPath(
        shape,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = PoiColors.Glyph.toArgb()
        },
    )

    if (glyphRes != null) {
        val half = 9f * density
        ResourcesCompat.getDrawable(context.resources, glyphRes, context.theme)?.mutate()?.apply {
            setTint(PoiColors.Glyph.toArgb())
            setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            draw(canvas)
        }
    } else {
        canvas.drawCircle(cx, cy, 4f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PoiColors.Glyph.toArgb() })
    }
    // Centro a 2 dp dal bordo: raggio 9 dp + mezzo anello restano dentro i 8 dp di margine.
    if (badge != null) drawAccessibilityBadge(context, canvas, width - 2f * density, 2f * density, badge)
    return bitmap
}

/** Distintivo di accessibilita' sul segnalino, con "In sedia a rotelle" attivo (tag OSM wheelchair). */
enum class AccessibilityBadge(val imageId: String) {
    // Pieno: accessibile. Vuoto: in parte. Diversi anche senza distinguere il colore.
    YES("badge-accessible"),
    LIMITED("badge-accessible-limited"),
}

internal fun accessibilityBadgeOf(wheelchair: String?): AccessibilityBadge? = when (wheelchair) {
    "yes", "designated" -> AccessibilityBadge.YES
    "limited" -> AccessibilityBadge.LIMITED
    else -> null
}

/**
 * Cerchio con il simbolo della sedia a rotelle, disegnato nell'angolo in alto a destra del segnalino
 * (dentro la stessa immagine: se MapLibre nasconde un segnalino sovrapposto, sparisce anche il
 * distintivo). Pieno per [AccessibilityBadge.YES], vuoto per LIMITED.
 */
private fun drawAccessibilityBadge(context: Context, canvas: Canvas, cx: Float, cy: Float, badge: AccessibilityBadge) {
    val density = context.resources.displayMetrics.density
    val stroke = 2f * density
    val r = 9f * density
    // Blu scuro e bianco: contrasto forte con tutti i colori dei segnalini.
    val accent = 0xFF0D47A1.toInt()
    val filled = badge == AccessibilityBadge.YES
    val white = PoiColors.Glyph.toArgb()
    // Stesso bordo esterno blu per entrambi, con un alone bianco intorno che lo stacca dalla mappa:
    // con l'anello bianco sul bordo il distintivo pieno sembrerebbe piu' piccolo di quello vuoto.
    canvas.drawCircle(cx, cy, r + 1f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white })
    canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
    if (!filled) canvas.drawCircle(cx, cy, r - stroke, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white })
    val half = 6.5f * density
    ResourcesCompat.getDrawable(context.resources, UiR.drawable.ms_accessible, context.theme)?.mutate()?.apply {
        setTint(if (filled) white else accent)
        setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        draw(canvas)
    }
}
