package com.pockettravel.feature.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
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
import org.maplibre.android.geometry.LatLng
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
    PoiCategory.ALTRO -> 0xFF616161.toInt()
}

private fun PoiCategory.displayName(): String = when (this) {
    PoiCategory.ALLOGGIO -> "Alloggio"
    PoiCategory.CIBO_BEVANDE -> "Cibo e bevande"
    PoiCategory.NEGOZI -> "Negozi"
    PoiCategory.ATTRAZIONI -> "Attrazioni"
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
                        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                            PoiCategory.entries.forEach { category ->
                                style.addImage(iconIdFor(category), pinBitmap(context, colorFor(category)))
                            }
                            symbolManager = SymbolManager(view, map, style)
                            renderPins(symbolManager, visiblePins)
                        }
                    }
                }
                renderPins(symbolManager, visiblePins)
            },
        )
    }
}

private fun renderPins(symbolManager: SymbolManager?, pins: List<MapPin>) {
    symbolManager ?: return
    symbolManager.deleteAll()
    pins.forEach { pin ->
        symbolManager.create(
            SymbolOptions()
                .withLatLng(LatLng(pin.latitude, pin.longitude))
                .withIconImage(iconIdFor(pin.category))
                .withIconAnchor("bottom")
        )
    }
}

// Icona reale (Material "place", vedi ic_map_pin.xml) renderizzata a Bitmap e tinta per
// categoria: SymbolManager di MapLibre richiede un Bitmap per style.addImage, non un
// ImageVector Compose.
private fun pinBitmap(context: Context, tintColor: Int): Bitmap {
    val size = 64
    val drawable = context.resources.getDrawable(R.drawable.ic_map_pin, context.theme).mutate()
    drawable.setTint(tintColor)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap
}
