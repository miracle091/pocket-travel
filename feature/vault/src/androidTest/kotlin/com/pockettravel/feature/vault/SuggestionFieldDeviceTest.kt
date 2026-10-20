package com.pockettravel.feature.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.pockettravel.core.ui.PocketTravelTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// Completamento di aeroporti nel form di un biglietto: campo e tratta con stato fisso e indice in memoria.
// Nessuna cassaforte, nessun asset e nessun file sul dispositivo.
class SuggestionFieldDeviceTest {

    @get:Rule val compose = createComposeRule()

    private val airports = SuggestionIndex(
        parseAirports(
            listOf(
                "FCO\tLIRF\tRome–Fiumicino Leonardo da Vinci International Airport\tRome\tIT\tRoma",
                "JFK\tKJFK\tJohn F. Kennedy International Airport\tNew York\tUS",
            ),
        ),
    )

    private val jfkRow = "JFK · John F. Kennedy International Airport"

    @Test
    fun typingShowsSuggestionsAndChoosingOneFillsTheField() {
        var current = ""
        compose.setContent {
            var value by remember { mutableStateOf("") }
            PocketTravelTheme(dynamicColor = false) {
                SuggestionField(value, { value = it; current = it }, "Partenza", airports)
            }
        }

        compose.onNodeWithText(jfkRow).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("jfk")
        compose.onNodeWithText(jfkRow).assertIsDisplayed()
        compose.onNodeWithText(jfkRow).performClick()

        assertEquals("JFK New York", current)
        compose.onNodeWithText(jfkRow).assertDoesNotExist()
    }

    @Test
    fun italianSelectionUsesTheItalianCityInRowAndField() {
        var current = ""
        compose.setContent {
            var value by remember { mutableStateOf("") }
            PocketTravelTheme(dynamicColor = false) {
                SuggestionField(value, { value = it; current = it }, "Partenza", airports, italian = true)
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("roma")
        compose.onNodeWithText("Roma, IT").assertIsDisplayed()
        compose.onNodeWithText("Roma, IT").performClick()

        assertEquals("FCO Roma", current)
    }

    @Test
    fun withoutAnIndexTheFieldIsPlain() {
        var current = ""
        compose.setContent {
            var value by remember { mutableStateOf("") }
            PocketTravelTheme(dynamicColor = false) {
                SuggestionField(value, { value = it; current = it }, "Partenza", index = null)
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("jfk")

        assertEquals("jfk", current)
        compose.onAllNodesWithText(jfkRow).assertCountEquals(0)
    }

    @Test
    fun noMatchShowsNoSuggestions() {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            PocketTravelTheme(dynamicColor = false) {
                SuggestionField(value, { value = it }, "Partenza", airports)
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("zzzz")

        compose.onAllNodesWithText(jfkRow).assertCountEquals(0)
    }

    @Test
    fun routeKeepsTheDepartureWhenTheArrivalIsChosen() {
        var current = "FCO Rome"
        compose.setContent {
            var value by remember { mutableStateOf("FCO Rome") }
            PocketTravelTheme(dynamicColor = false) {
                RouteFields(value, { value = it; current = it }, airports, italian = false)
            }
        }

        // Due campi: partenza e arrivo, nell'ordine.
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("jfk")
        compose.onNodeWithText(jfkRow).performClick()

        assertEquals("FCO Rome – JFK New York", current)
    }
}
