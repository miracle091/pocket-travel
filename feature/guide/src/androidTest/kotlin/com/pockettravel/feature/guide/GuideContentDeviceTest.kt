package com.pockettravel.feature.guide

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.R as UiR
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// GuideContent con stati fissi e senza ViewModel: sezioni, stato vuoto, Indietro e apertura delle fonti.
class GuideContentDeviceTest {

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

    private fun show(
        uiState: GuideUiState,
        onOpenSource: (String, String) -> Unit = { _, _ -> },
        onBack: (() -> Unit)? = null,
    ) {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                GuideContent(uiState = uiState, onOpenSource = onOpenSource, onBack = onBack)
            }
        }
    }

    // La lista e' pigra: i nodi fuori dallo schermo esistono solo dopo lo scorrimento.
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    @Test
    fun sectionTitlesAreShown() {
        show(GuideUiState(isLoading = false, sections = sections))

        scrollTo(hasText("A tavola")).assertIsDisplayed()
        scrollTo(hasText("Al confine")).assertIsDisplayed()
    }

    @Test
    fun sourceCardOpensTheWikivoyagePage() {
        var opened: Pair<String, String>? = null
        show(GuideUiState(isLoading = false, sections = sections), onOpenSource = { url, title -> opened = url to title })

        scrollTo(hasText(sourcePageTitle(sourceUrl))).performClick()

        assertEquals(sourceUrl to context.getString(R.string.guide_source_title), opened)
    }

    @Test
    fun emptyGuideShowsTheEmptyStateAndBackWorks() {
        var backs = 0
        show(GuideUiState(isLoading = false), onBack = { backs++ })

        compose.onNodeWithText(context.getString(R.string.guide_empty_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(UiR.string.back)).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun backButtonAboveSectionsInvokesTheCallback() {
        var backs = 0
        show(GuideUiState(isLoading = false, sections = sections), onBack = { backs++ })

        compose.onNodeWithContentDescription(context.getString(UiR.string.back)).performClick()

        assertEquals(1, backs)
    }
}
