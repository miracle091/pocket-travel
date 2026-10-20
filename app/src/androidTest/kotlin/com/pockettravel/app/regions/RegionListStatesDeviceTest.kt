package com.pockettravel.app.regions

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.app.R
import com.pockettravel.core.ui.PocketTravelTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// Gli stati dell'elenco delle nazioni che non sono l'elenco pieno: ricerca senza risultati e messaggio una tantum.
class RegionListStatesDeviceTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val rowActions = RegionRowActions(
        observeProgress = { flowOf(null) },
        onDownload = {},
        onDelete = {},
        onDownloadPackage = { _, _ -> },
        onDeletePackage = { _, _ -> },
    )

    private fun setContent(uiState: RegionListUiState, onMessageShown: () -> Unit = {}) {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                RegionListContent(
                    uiState = uiState,
                    onQueryChange = {},
                    onRetry = {},
                    onMessageShown = onMessageShown,
                    rowActions = rowActions,
                    onRegionClick = {},
                )
            }
        }
    }

    @Test
    fun searchWithoutMatchesShowsTheQueryInTheEmptyState() {
        setContent(RegionListUiState(isLoading = false, items = emptyList(), query = "atlantide"))

        compose.onNodeWithText(context.getString(R.string.regions_search_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.regions_search_empty_subtitle, "atlantide")).assertIsDisplayed()
    }

    @Test
    fun oneShotMessageIsShownAndThenReportedAsShown() {
        var shown = 0
        setContent(
            RegionListUiState(
                isLoading = false,
                items = listOf(RegionUiItem("san-marino", "San Marino", 76L shl 20, RegionStatus.INSTALLED, "Europa")),
                message = R.string.regions_delete_error,
            ),
            onMessageShown = { shown++ },
        )

        compose.onNodeWithText(context.getString(R.string.regions_delete_error)).assertIsDisplayed()
        // Lo Snackbar sparisce da solo: solo allora il messaggio si segna come mostrato.
        compose.waitUntil(timeoutMillis = 10_000) { shown == 1 }
        assertEquals(1, shown)
    }
}
