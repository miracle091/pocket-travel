package com.pockettravel.feature.map

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pockettravel.core.data.PoiCategory
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

private const val PIN_ICON_PREFIX = "pocket-travel-pin-"

private fun iconIdFor(category: PoiCategory) = PIN_ICON_PREFIX + category.name

// Nessuna nuova icona disegnata a mano (rischio di path SVG errati): stessa sagoma di
// ic_map_pin.xml, tinta con un colore diverso per categoria — abbastanza per distinguerle a
// colpo d'occhio sulla mappa senza introdurre asset grafici nuovi.
private fun colorFor(category: PoiCategory): Int = when (category) {
    PoiCategory.ALLOGGIO -> 0xFF1F4877.toInt()
    PoiCategory.CIBO_BEVANDE -> 0xFFC1440E.toInt()
    PoiCategory.NEGOZI -> 0xFF2E7D32.toInt()
    PoiCategory.ATTRAZIONI -> 0xFF6A1B9A.toInt()
    PoiCategory.AMBASCIATA_CONSOLATO -> 0xFFF9A825.toInt()
    PoiCategory.ALTRO -> 0xFF616161.toInt()
}

private fun PoiCategory.displayName(): String = when (this) {
    PoiCategory.ALLOGGIO -> "Alloggio"
    PoiCategory.CIBO_BEVANDE -> "Cibo e bevande"
    PoiCategory.NEGOZI -> "Negozi"
    PoiCategory.ATTRAZIONI -> "Attrazioni"
    PoiCategory.AMBASCIATA_CONSOLATO -> "Ambasciate e consolati"
    PoiCategory.ALTRO -> "Altro"
}

@Composable
fun MapScreen(tileSource: OfflineTileSource, regionId: String, pins: List<MapPin> = emptyList()) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)

    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    val styleJson = remember(tileSource, regionId) { tileSource.styleJson(regionId) }
    var configuredStyle by remember { mutableStateOf<String?>(null) }
    var symbolManager by remember { mutableStateOf<SymbolManager?>(null) }
    var selectedCategories by remember { mutableStateOf(PoiCategory.entries.toSet()) }
    var symbolPinMap by remember { mutableStateOf<Map<Long, MapPin>>(emptyMap()) }
    var selectedPin by remember { mutableStateOf<MapPin?>(null) }
    var cameraFitted by remember { mutableStateOf(false) }
    val visiblePins = pins.filter { it.category in selectedCategories }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(PoiCategory.entries) { category ->
                FilterChip(
                    selected = category in selectedCategories,
                    onClick = {
                        selectedCategories = if (category in selectedCategories) {
                            selectedCategories - category
                        } else {
                            selectedCategories + category
                        }
                    },
                    label = { Text(category.displayName()) },
                )
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize().weight(1f),
            factory = { mapView },
            update = { view ->
                if (configuredStyle != styleJson) {
                    configuredStyle = styleJson
                    view.getMapAsync { map ->
                        // Basso, non il default (4 livelli): con le tile pmtiles:// gia' tutte in
                        // locale (l'intero z0-z14 scaricato all'installazione da PmtilesExtractor),
                        // un placeholder piu' vicino allo zoom target non costa una richiesta di
                        // rete in piu' come costerebbe con tile remote, solo un parsing leggermente
                        // anticipato di una tile che verra' comunque renderizzata.
                        map.setPrefetchZoomDelta(1)
                        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                            PoiCategory.entries.forEach { category ->
                                style.addImage(iconIdFor(category), pinBitmap(context, colorFor(category)))
                            }
                            symbolManager = SymbolManager(view, map, style)
                            symbolManager?.addClickListener { symbol ->
                                selectedPin = symbolPinMap[symbol.id]
                                true
                            }
                            symbolPinMap = renderPins(symbolManager, visiblePins)
                        }
                    }
                }
                symbolPinMap = renderPins(symbolManager, visiblePins)

                // PmtilesExtractor scarica solo le tile che intersecano il bounding box della
                // regione (vedi core:sync/PmtilesExtractor.tileRangeFor), per tenere piccolo il
                // pacchetto — ai livelli di zoom bassi una singola tile copre un'area enorme, e le
                // tile "vicine" che non toccano il bbox non vengono mai scaricate. La mappa parte
                // pero' sempre dalla vista mondo (nessun fit iniziale): zoomando manualmente da li'
                // verso la regione si attraversa una fascia di zoom bassa dove meta' schermo mostra
                // il solo "background" (bug osservato: "carica sempre a sezioni quando faccio lo
                // zoom" — non tile che arrivano in ritardo, tile che semplicemente non esistono
                // nel pacchetto scaricato). Il fix reale e' non passarci mai: centrare/zoomare la
                // camera sui pin della regione (gia' ben dentro il bbox estratto) non appena sono
                // disponibili, cosi' l'utente apre la mappa gia' inquadrato sull'area completa
                // invece di doverci arrivare a mano dalla vista mondo. Una tantum (guardia
                // cameraFitted): dopo il primo fit l'utente deve restare libero di ripristinare la
                // vista mondo senza che ogni ricomposizione lo forzi indietro sulla regione.
                if (!cameraFitted && pins.isNotEmpty()) {
                    cameraFitted = true
                    view.getMapAsync { map ->
                        val boundsBuilder = LatLngBounds.Builder()
                        pins.forEach { pin -> boundsBuilder.include(LatLng(pin.latitude, pin.longitude)) }
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 64))
                    }
                }
            },
        )
    }

    selectedPin?.let { pin ->
        AlertDialog(
            onDismissRequest = { selectedPin = null },
            title = { Text(pin.name) },
            text = {
                Column {
                    Text(pin.category.displayName())
                    pin.phone?.let { phone -> Text(phone) }
                }
            },
            confirmButton = {
                val phone = pin.phone
                if (phone != null) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                        selectedPin = null
                    }) { Text("Chiama") }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPin = null }) { Text("Chiudi") }
            },
        )
    }
}

private fun renderPins(symbolManager: SymbolManager?, pins: List<MapPin>): Map<Long, MapPin> {
    symbolManager ?: return emptyMap()
    symbolManager.deleteAll()
    return pins.associate { pin ->
        val symbol = symbolManager.create(
            SymbolOptions()
                .withLatLng(LatLng(pin.latitude, pin.longitude))
                .withIconImage(iconIdFor(pin.category))
                .withIconAnchor("bottom")
        )
        symbol.id to pin
    }
}

// Icona reale (Material "place", vedi ic_map_pin.xml) renderizzata a Bitmap e tinta per
// categoria: SymbolManager di MapLibre richiede un Bitmap per style.addImage, non un
// ImageVector Compose. Ombra di contatto + pallino centrale bianco (invece del buco trasparente
// che il path del vector lascerebbe da solo) per avvicinare l'aspetto ai segnalini di Google
// Maps — stesso posizionamento (0,0,size,size) di prima: l'anchor "bottom" di SymbolOptions deve
// continuare a combaciare esattamente con la punta del pin, quindi il canvas non cambia
// dimensione, solo cosa ci viene disegnato dentro.
private fun pinBitmap(context: Context, tintColor: Int): Bitmap {
    val size = 64
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    canvas.drawOval(size * 0.28f, size * 0.87f, size * 0.72f, size * 0.99f, shadowPaint)

    val drawable = context.resources.getDrawable(R.drawable.ic_map_pin, context.theme).mutate()
    drawable.setTint(tintColor)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)

    // Stesso centro/raggio del cerchio "bucato" nel path di ic_map_pin.xml (viewport 24x24,
    // cerchio a (12,9) raggio 2.5): riempirlo esattamente li' evita sia un buco trasparente sia
    // un bordo colorato residuo attorno al pallino bianco.
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.drawCircle(size * (12f / 24f), size * (9f / 24f), size * (2.5f / 24f), dotPaint)

    return bitmap
}
