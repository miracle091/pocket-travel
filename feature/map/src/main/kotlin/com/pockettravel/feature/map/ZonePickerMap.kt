package com.pockettravel.feature.map

import android.graphics.PointF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import com.pockettravel.core.data.MapBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import javax.inject.Inject

// Holder per far arrivare OfflineTileSource a ZonePickerMap tramite hiltViewModel() nell'app, come MapRouteViewModel per MapScreen.
@HiltViewModel
class ZonePickerMapViewModel @Inject constructor(val tileSource: OfflineTileSource) : ViewModel()

/**
 * Mappa per scegliere la zona da scaricare di una regione: si sposta e si ingrandisce la mappa, la zona e' tutto quello
 * che si vede. Usa la mappa della regione se c'e' (anche solo la mappa d'insieme), altrimenti la mappa del mondo con
 * citta' e strade principali. [onZoneChange] riceve il rettangolo (ovest, sud, est, nord) ogni volta che la mappa si ferma.
 */
@Composable
fun ZonePickerMap(
    tileSource: OfflineTileSource,
    regionId: String,
    // Inquadratura iniziale: la zona gia' scelta o tutta la regione.
    initialBounds: MapBounds,
    onZoneChange: (MapBounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)
    val mapView = rememberMapViewWithLifecycle()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val language = LocalLocale.current.platformLocale.language
    // Lo stile legge i file della mappa: fuori dal main thread (null finche' non e' pronto).
    val styleJson by produceState<String?>(null, tileSource, regionId, dark, language) {
        value = withContext(Dispatchers.IO) { tileSource.styleJson(regionId, dark = dark, language = language, worldFallback = true) }
    }
    val currentOnZoneChange by rememberUpdatedState(onZoneChange)
    var configured by remember { mutableStateOf(false) }
    // Finche' non si muove la mappa la zona resta [initialBounds]: l'area visibile, adattata alle proporzioni dello
    // schermo, e' un po' piu' grande, e confermando senza toccare niente la zona salvata crescerebbe a ogni apertura.
    var moved by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                val json = styleJson ?: return@AndroidView
                view.getMapAsync { map ->
                    if (configured) return@getMapAsync
                    configured = true
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.uiSettings.isTiltGesturesEnabled = false
                    map.setStyle(Style.Builder().fromJson(json))
                    fun reportZone() {
                        if (!moved) {
                            currentOnZoneChange(initialBounds)
                            return
                        }
                        val projection = map.projection
                        val northWest = projection.fromScreenLocation(PointF(0f, 0f))
                        val southEast = projection.fromScreenLocation(PointF(view.width.toFloat(), view.height.toFloat()))
                        currentOnZoneChange(MapBounds(northWest.longitude, southEast.latitude, southEast.longitude, northWest.latitude))
                    }
                    map.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) moved = true }
                    map.addOnCameraIdleListener { reportZone() }
                    // Dopo il layout: per inquadrare un rettangolo la mappa deve conoscere le sue dimensioni.
                    view.post {
                        val bounds = LatLngBounds.Builder()
                            .include(LatLng(initialBounds.maxLat, initialBounds.minLon))
                            .include(LatLng(initialBounds.minLat, initialBounds.maxLon))
                            .build()
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0))
                        reportZone()
                    }
                }
            },
        )
    }
}
