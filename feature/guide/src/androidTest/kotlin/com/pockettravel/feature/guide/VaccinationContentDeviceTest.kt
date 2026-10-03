package com.pockettravel.feature.guide

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.OfficialSourceTopic
import com.pockettravel.core.data.officialSourcesRegistry
import com.pockettravel.core.data.vaccination.Vaccine
import com.pockettravel.core.data.vaccination.VaccinationItem
import com.pockettravel.core.data.vaccination.VaccinationLevel
import com.pockettravel.core.data.vaccination.VaccinationReason
import com.pockettravel.core.data.vaccination.VaccinationResult
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.countryName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Contenuto della schermata vaccinazioni senza ViewModel: stato fisso in ingresso, azioni registrate dai test.
// Le stringhe si leggono a runtime, cosi' i test valgono sia in italiano sia in inglese.
class VaccinationContentDeviceTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val locale = context.resources.configuration.locales[0]

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val baseState = VaccinationUiState(available = true, destination = "KE", departure = "IT", nationality = "IT")

    private val yellowFeverResult = VaccinationResult(
        items = listOf(
            VaccinationItem(
                vaccine = Vaccine.YELLOW_FEVER,
                level = VaccinationLevel.REQUIRED,
                reason = VaccinationReason.YF_ENTRY_ALL,
            ),
        ),
        noCertificateFound = false,
        checkedOn = "2026-01-15",
        polioDataStale = false,
        polioStatement = null,
    )

    private fun actions(
        setDeparture: (String) -> Unit = {},
        addTransit: () -> Unit = {},
        setChildUnderOne: (Boolean) -> Unit = {},
    ) = VaccinationActions(
        setDeparture = setDeparture,
        addRecentCountry = {},
        removeRecentCountry = {},
        addTransit = addTransit,
        updateTransit = { _, _ -> },
        removeTransit = {},
        setChildUnderOne = setChildUnderOne,
        setChildMonths = {},
        setStayOverFourWeeks = {},
        setHajj = {},
    )

    private fun show(
        state: VaccinationUiState,
        actions: VaccinationActions = actions(),
        onOpenSource: (String, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                VaccinationContent(state, actions, onOpenSource)
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
    fun disclaimerAndRouteAreShown() {
        show(baseState)

        compose.onNodeWithText(string(R.string.vacc_disclaimer_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.vacc_from)).assertIsDisplayed()
        compose.onNodeWithText(countryName("IT", locale)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.vacc_to)).assertIsDisplayed()
        compose.onNodeWithText(countryName("KE", locale)).assertIsDisplayed()
        // senza risultato ma con partenza valida resta l'invito a scegliere un altro paese
        compose.onNodeWithText(string(R.string.vacc_choose_departure)).assertIsDisplayed()
    }

    @Test
    fun tappingDepartureOpensThePickerAndSelectingReportsTheCountry() {
        var picked: String? = null
        show(baseState, actions(setDeparture = { picked = it }))

        compose.onNodeWithText(countryName("IT", locale)).performClick()
        // il campo di ricerca contiene lo stesso testo della voce: si esclude dalla ricerca del nodo da toccare
        val france = countryName("FR", locale)
        compose.onNode(hasSetTextAction()).performTextInput(france)
        compose.onNode(hasText(france) and hasClickAction() and !hasSetTextAction()).performClick()

        compose.waitForIdle()
        assertEquals("FR", picked)
    }

    @Test
    fun addTransitButtonInvokesTheAction() {
        var added = 0
        show(baseState, actions(addTransit = { added++ }))

        compose.onNodeWithText(string(R.string.vacc_add_transit)).performClick()

        assertEquals(1, added)
    }

    @Test
    fun childSwitchReportsTheNewValue() {
        var childUnderOne: Boolean? = null
        show(baseState, actions(setChildUnderOne = { childUnderOne = it }))

        scrollTo(hasText(string(R.string.vacc_child))).performClick()

        assertEquals(true, childUnderOne)
    }

    @Test
    fun resultShowsTheRequiredGroupAndTheVaccine() {
        show(baseState.copy(result = yellowFeverResult))

        scrollTo(hasText(string(R.string.vacc_required))).assertIsDisplayed()
        scrollTo(hasText(string(R.string.vacc_vaccine_yellow_fever))).assertIsDisplayed()
        scrollTo(hasText(string(R.string.vacc_level_required))).assertIsDisplayed()
    }

    @Test
    fun officialSourceLinkOpensItsUrl() {
        val source = officialSourcesRegistry.first { it.topic == OfficialSourceTopic.HEALTH && it.countries.isEmpty() }
        var opened: Pair<String, String>? = null
        show(baseState, onOpenSource = { url, title -> opened = url to title })

        scrollTo(hasText(source.name)).performClick()

        assertTrue(opened != null)
        assertEquals(source.url to source.name, opened)
    }
}
