package com.pockettravel.feature.guide

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.ui.PocketTravelTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// GuideContent negli stati che GuideContentDeviceTest non copre: errore di caricamento, regione senza numero di
// emergenza unico, filtro per categoria e uscita verso la guida delle citta'. Stati fissi, nessun ViewModel.
class GuideStatesDeviceTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val sourceUrl = "https://it.wikivoyage.org/wiki/San_Marino"

    private val sections = listOf(
        GuideSection(
            regionId = "san-marino",
            category = GuideCategory.CIBO_BEVANDE,
            title = "A tavola",
            body = "Dolci tipici sono la torta Titano e la zuppa di ciliegie.",
            sourceUrl = sourceUrl,
        ),
        GuideSection(
            regionId = "san-marino",
            category = GuideCategory.DOGANE,
            title = "Al confine",
            body = "Il confine con l'Italia non ha controlli sistematici.",
            sourceUrl = sourceUrl,
        ),
    )

    private fun show(uiState: GuideUiState, onOpenCities: (String?) -> Unit = {}) {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                GuideContent(uiState = uiState, onOpenSource = { _, _ -> }, onOpenCities = onOpenCities)
            }
        }
    }

    // La lista e' pigra: i nodi fuori dallo schermo esistono solo dopo lo scorrimento.
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun chip(label: String) = hasText(label) and hasClickAction()

    @Test
    fun loadErrorShowsItsMessageInsteadOfTheGuide() {
        show(GuideUiState(isLoading = false, loadError = R.string.guide_load_error, sections = sections))

        compose.onNodeWithText(context.getString(R.string.guide_load_error)).assertIsDisplayed()
        compose.onNodeWithText("A tavola").assertDoesNotExist()
    }

    @Test
    fun regionWithoutACentralEmergencyNumberSaysSo() {
        show(GuideUiState(isLoading = false, noCentralEmergencyNumber = true))

        scrollTo(hasText(context.getString(R.string.emergency_no_central_number))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.guide_empty_title)).assertDoesNotExist()
    }

    @Test
    fun categoryChipFiltersTheSectionsAndAllShowsThemAgain() {
        show(GuideUiState(isLoading = false, sections = sections))

        scrollTo(hasText("A tavola")).assertIsDisplayed()
        compose.onNode(chip(context.getString(R.string.category_customs))).performClick()

        scrollTo(hasText("Al confine")).assertIsDisplayed()
        compose.onNodeWithText("A tavola").assertDoesNotExist()

        compose.onNode(chip(context.getString(R.string.guide_filter_all))).performClick()

        scrollTo(hasText("A tavola")).assertIsDisplayed()
    }

    @Test
    fun tappingTheSelectedCategoryChipAgainClearsTheFilter() {
        show(GuideUiState(isLoading = false, sections = sections))

        compose.onNode(chip(context.getString(R.string.category_food))).performClick()
        compose.onNodeWithText("Al confine").assertDoesNotExist()
        compose.onNode(chip(context.getString(R.string.category_food))).performClick()

        scrollTo(hasText("Al confine")).assertIsDisplayed()
    }

    @Test
    fun singleCategoryHasNoFilterChips() {
        show(GuideUiState(isLoading = false, sections = sections.take(1)))

        scrollTo(hasText("A tavola")).assertIsDisplayed()
        compose.onNode(chip(context.getString(R.string.guide_filter_all))).assertDoesNotExist()
    }

    @Test
    fun citiesEntryOpensTheCityList() {
        var opened = 0
        show(GuideUiState(isLoading = false, sections = sections, cities = listOf("San Marino")), onOpenCities = { opened++ })

        // Con poche citta' non c'e' "Vedi tutte": il tocco sul riquadro apre l'elenco.
        scrollTo(chip(context.getString(R.string.guide_cities_title))).performClick()

        assertEquals(1, opened)
    }
}
