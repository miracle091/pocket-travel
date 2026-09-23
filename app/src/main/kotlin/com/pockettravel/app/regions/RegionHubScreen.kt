package com.pockettravel.app.regions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.feature.ai.AiAssistantScreen
import com.pockettravel.feature.guide.GuideScreen
import com.pockettravel.feature.map.MapRouteViewModel
import com.pockettravel.feature.map.MapScreen

private enum class RegionTab(val key: String, val label: String) {
    GUIDE("guide", "Guida"),
    MAP("map", "Mappa"),
    AI("ai", "Assistente"),
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
    viewModel: RegionHubViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable(regionId) { mutableStateOf(RegionTab.fromKey(initialTab)) }
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val regionMissing by viewModel.regionMissing.collectAsStateWithLifecycle()
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    LaunchedEffect(regionMissing) { if (regionMissing) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName ?: regionId) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = "Indietro")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                RegionTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon(),
                                contentDescription = tab.label,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
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

@Composable
private fun RegionTab.icon(): ImageVector = when (this) {
    RegionTab.GUIDE -> AppIcons.World
    RegionTab.MAP -> AppIcons.Map
    RegionTab.AI -> AppIcons.AiAssistant
}
