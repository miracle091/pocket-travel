package com.pockettravel.feature.sources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.globalOfficialSources
import com.pockettravel.core.data.nationalOfficialSources
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.countryName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.pockettravel.core.ui.R as UiR

// Registro delle fonti ufficiali con nazionalita' e azioni fisse: nessun ViewModel, rete o file sul dispositivo.
// Le stringhe si leggono a runtime, cosi' i test valgono sia in italiano sia in inglese.
class OfficialSourcesScreenDeviceTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val locale = context.resources.configuration.locales[0]

    private fun show(
        nationality: String?,
        onBack: () -> Unit = {},
        onOpenSource: (String, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            PocketTravelTheme(dynamicColor = false) {
                OfficialSourcesScreen(nationality = nationality, onBack = onBack, onOpenSource = onOpenSource)
            }
        }
    }

    @Test
    fun withoutNationalityOnlyTheGlobalSourcesAreShown() {
        show(nationality = null)

        compose.onNodeWithText(context.getString(R.string.sources_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sources_external)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sources_global)).assertExists()
        compose.onNodeWithText(globalOfficialSources.first().name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sources_national_none)).assertDoesNotExist()
    }

    @Test
    fun nationalSourcesComeWithTheirCountryHeading() {
        val national = nationalOfficialSources("IT")
        assertTrue("il registro ha fonti per l'Italia", national.isNotEmpty())

        show(nationality = "IT")

        compose.onNodeWithText(context.getString(R.string.sources_national, countryName("IT", locale))).assertExists()
        compose.onNodeWithText(national.first().name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sources_national_none)).assertDoesNotExist()
    }

    @Test
    fun countryWithoutSourcesSaysSoAndKeepsTheGlobalOnes() {
        assertTrue(nationalOfficialSources("ZZ").isEmpty())

        show(nationality = "ZZ")

        compose.onNodeWithText(context.getString(R.string.sources_national_none)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(globalOfficialSources.first().name).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingASourceReportsItsUrlAndName() {
        var opened: Pair<String, String>? = null
        val source = globalOfficialSources.first()
        show(nationality = null, onOpenSource = { url, title -> opened = url to title })

        compose.onNodeWithText(source.name).performScrollTo().performClick()

        assertEquals(source.url to source.name, opened)
    }

    @Test
    fun backButtonInvokesTheCallback() {
        var backs = 0
        show(nationality = null, onBack = { backs++ })

        compose.onNodeWithContentDescription(context.getString(UiR.string.back)).performClick()

        assertEquals(1, backs)
    }
}
