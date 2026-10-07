package com.pockettravel.app.regions

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.app.R
import com.pockettravel.core.ui.PocketTravelTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Elenco delle nazioni in modalita' elenco (senza la mappa delle nazioni): raggruppamento, ricerca, tap sulle righe e
// avviso di catalogo irraggiungibile. Il mapping tra tap e navigazione (installata: apre; non installata:
// anteprima) sta in RegionListScreen: qui si verifica che la riga giusta venga passata a onRegionClick.
class RegionListContentDeviceTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val items = listOf(
        RegionUiItem("san-marino", "San Marino", 76L shl 20, RegionStatus.INSTALLED, "Europa"),
        RegionUiItem("italia", "Italia", 940L shl 20, RegionStatus.UPDATE_AVAILABLE, "Europa"),
        RegionUiItem("andorra", "Andorra", 114L shl 20, RegionStatus.NOT_INSTALLED, "Europa"),
        RegionUiItem("giappone", "Giappone", 566L shl 20, RegionStatus.NOT_INSTALLED, "Asia"),
    )

    private val rowActions = RegionRowActions(
        observeProgress = { flowOf(null) },
        onDownload = {},
        onDelete = {},
        onDownloadPackage = { _, _ -> },
        onDeletePackage = { _, _ -> },
    )

    private fun setContent(
        uiState: RegionListUiState,
        onQueryChange: (String) -> Unit = {},
        onRetry: () -> Unit = {},
        onRegionClick: (RegionUiItem) -> Unit = {},
    ) {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                RegionListContent(
                    uiState = uiState,
                    onQueryChange = onQueryChange,
                    onCheckUpdates = {},
                    onRetry = onRetry,
                    onMessageShown = {},
                    rowActions = rowActions,
                    onRegionClick = onRegionClick,
                )
            }
        }
    }

    @Test
    fun regionsAreGroupedByDownloadedStateAndContinent() {
        setContent(RegionListUiState(isLoading = false, items = items))

        // Le nazioni scaricate (o da aggiornare) sono in un gruppo aperto in cima; i continenti partono chiusi.
        compose.onNodeWithText(context.getString(R.string.regions_downloaded)).assertIsDisplayed()
        compose.onNodeWithText("San Marino").assertIsDisplayed()
        compose.onNodeWithText("Italia").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.continent_europe)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.continent_asia)).assertIsDisplayed()
        compose.onNodeWithText("Andorra").assertDoesNotExist()
        compose.onNodeWithText("Giappone").assertDoesNotExist()

        // Aprendo un continente compaiono solo le sue nazioni non ancora scaricate.
        compose.onNodeWithText(context.getString(R.string.continent_europe)).performClick()
        compose.onNodeWithText("Andorra").assertIsDisplayed()
        compose.onNodeWithText("Giappone").assertDoesNotExist()
    }

    @Test
    fun typingInSearchFieldReportsTheQuery() {
        val queries = mutableListOf<String>()
        setContent(RegionListUiState(isLoading = false, items = items), onQueryChange = { queries += it })

        compose.onNode(hasSetTextAction()).performTextInput("ita")
        compose.waitForIdle()

        assertEquals("ita", queries.last())
    }

    @Test
    fun tappingAnInstalledRegionReportsIt() {
        val clicked = mutableListOf<RegionUiItem>()
        setContent(RegionListUiState(isLoading = false, items = items), onRegionClick = { clicked += it })

        compose.onNodeWithText("San Marino").performClick()

        assertEquals(listOf("san-marino"), clicked.map { it.regionId })
        assertEquals(RegionStatus.INSTALLED, clicked.single().status)
    }

    @Test
    fun tappingANotInstalledRegionReportsItWithItsStatus() {
        val clicked = mutableListOf<RegionUiItem>()
        setContent(RegionListUiState(isLoading = false, items = items), onRegionClick = { clicked += it })

        compose.onNodeWithText(context.getString(R.string.continent_europe)).performClick()
        compose.onNodeWithText("Andorra").performClick()

        // RegionListScreen manda questo stato all'anteprima invece di aprire la regione.
        assertEquals(listOf("andorra"), clicked.map { it.regionId })
        assertEquals(RegionStatus.NOT_INSTALLED, clicked.single().status)
    }

    @Test
    fun offlineNoticeKeepsInstalledRegionsAndRetries() {
        var retries = 0
        setContent(
            RegionListUiState(isLoading = false, items = items.take(1), loadError = R.string.regions_offline_installed),
            onRetry = { retries++ },
        )

        compose.onNodeWithText(context.getString(R.string.regions_offline_installed)).assertIsDisplayed()
        compose.onNodeWithText("San Marino").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.regions_retry)).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun unreachableCatalogWithoutRegionsShowsRetryButton() {
        var retries = 0
        setContent(
            RegionListUiState(isLoading = false, items = emptyList(), loadError = R.string.regions_offline_installed),
            onRetry = { retries++ },
        )

        compose.onNodeWithText(context.getString(R.string.regions_retry)).performClick()

        assertTrue(retries == 1)
    }
}
