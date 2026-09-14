package com.pockettravel.feature.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

private const val PIN_ICON = "pocket-travel-pin"

@Composable
fun MapScreen(tileSource: OfflineTileSource, regionId: String, pins: List<MapPin> = emptyList()) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)

    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    val styleJson = remember(tileSource, regionId) { tileSource.styleJson(regionId) }
    var configuredStyle by remember { mutableStateOf<String?>(null) }
    var symbolManager by remember { mutableStateOf<SymbolManager?>(null) }

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

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
        update = { view ->
            if (configuredStyle != styleJson) {
                configuredStyle = styleJson
                view.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                        style.addImage(PIN_ICON, pinBitmap(context))
                        symbolManager = SymbolManager(view, map, style)
                        renderPins(symbolManager, pins)
                    }
                }
            }
            renderPins(symbolManager, pins)
        },
    )
}

private fun renderPins(symbolManager: SymbolManager?, pins: List<MapPin>) {
    symbolManager ?: return
    symbolManager.deleteAll()
    pins.forEach { pin ->
        symbolManager.create(
            SymbolOptions()
                .withLatLng(LatLng(pin.latitude, pin.longitude))
                .withIconImage(PIN_ICON)
                .withIconAnchor("bottom")
        )
    }
}

// Icona reale (Material "place", vedi ic_map_pin.xml) renderizzata a Bitmap: SymbolManager di
// MapLibre richiede un Bitmap per style.addImage, non un ImageVector Compose.
private fun pinBitmap(context: Context): Bitmap {
    val size = 64
    val drawable = context.resources.getDrawable(R.drawable.ic_map_pin, context.theme)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap
}
