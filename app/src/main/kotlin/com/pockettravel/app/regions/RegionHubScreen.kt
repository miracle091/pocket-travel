package com.pockettravel.app.regions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.R as UiR
import com.pockettravel.feature.ai.AiAssistantScreen
import com.pockettravel.feature.guide.GuideScreen
import com.pockettravel.feature.map.MapRouteViewModel
import com.pockettravel.feature.map.MapScreen

private enum class RegionTab(val key: String, @StringRes val label: Int) {
    GUIDE("guide", R.string.nav_guide),
    MAP("map", R.string.nav_map),
    AI("ai", R.string.nav_assistant),
    ;

    companion object {
        fun fromKey(key: String): RegionTab = entries.firstOrNull { it.key == key } ?: GUIDE
    }
}

// Guida/Mappa/Assistente sono viste sorelle della stessa regione, senza bisogno di un proprio
// back-stack indipendente: il tab selezionato e' stato locale (rememberSaveable), non un nested
// NavHost — un nested graph qui sarebbe un'astrazione non necessaria per tre viste che condividono
// la stessa "torna alla lista regioni".
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionHubScreen(
    regionId: String,
    initialTab: String = "guide",
    onBack: () -> Unit,
    onOpenOfficialSource: (url: String) -> Unit = {},
    onOpenSource: (url: String, title: String) -> Unit = { _, _ -> },
    // true quando l'hub e' il pannello di dettaglio accanto all'elenco regioni (schermi larghi):
    // barra in basso invece della rail, che finirebbe in mezzo allo schermo.
    compactNavigation: Boolean = false,
    viewModel: RegionHubViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable(regionId) { mutableStateOf(RegionTab.fromKey(initialTab)) }
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val regionMissing by viewModel.regionMissing.collectAsStateWithLifecycle()
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    LaunchedEffect(regionMissing) { if (regionMissing) onBack() }

    val adaptiveInfo = currentWindowAdaptiveInfo()
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            RegionTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                item(
                    selected = selected,
                    onClick = { selectedTab = tab },
                    icon = { Icon(imageVector = tab.icon(selected), contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
        layoutType = if (compactNavigation) {
            NavigationSuiteType.NavigationBar
        } else {
            NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
        },
    ) {
        // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
        // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = { Text(displayName ?: regionId) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                        }
                    },
                )
            },
        ) { innerPadding ->
            // imePadding: con l'edge-to-edge la tastiera non ridimensiona piu' la finestra, il campo
            // di testo dell'assistente deve restare sopra la tastiera da solo.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            ) {
                when (selectedTab) {
                    RegionTab.GUIDE -> GuideScreen(regionId = regionId, onOpenSource = onOpenSource)
                    RegionTab.MAP -> {
                        val mapViewModel: MapRouteViewModel = hiltViewModel()
                        LaunchedEffect(regionId) { mapViewModel.loadPins(regionId) }
                        val pins by mapViewModel.pins.collectAsStateWithLifecycle()
                        MapScreen(tileSource = mapViewModel.tileSource, regionId = regionId, pins = pins)
                    }
                    RegionTab.AI -> AiAssistantScreen(regionId = regionId, onOpenOfficialSource = onOpenOfficialSource)
                }
            }
        }
    }
}

@Composable
private fun RegionTab.icon(selected: Boolean): ImageVector = when (this) {
    RegionTab.GUIDE -> if (selected) AppIcons.WorldFilled else AppIcons.World
    RegionTab.MAP -> if (selected) AppIcons.MapFilled else AppIcons.Map
    RegionTab.AI -> if (selected) AppIcons.AiAssistantFilled else AppIcons.AiAssistant
}
