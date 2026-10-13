package com.pockettravel.feature.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

// Ricerca a tutto schermo: campo in alto, sotto "La mia posizione" (solo per la partenza), i recenti a
// campo vuoto e poi i risultati con tipo, distanza e regione.
@Composable
internal fun PlannerSearch(
    field: PlannerField,
    query: String,
    results: List<PlannerResult>,
    recents: List<NavigationPlace>,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onUseMyPosition: () -> Unit,
    onChoose: (NavigationPlace) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(stringResource(if (field == PlannerField.FROM) R.string.planner_search_start else R.string.planner_where_to))
                },
                leadingIcon = {
                    IconButton(onClick = onBack) {
                        Icon(ImageVector.vectorResource(UiR.drawable.ms_arrow_back), contentDescription = stringResource(R.string.planner_back))
                    }
                },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { onQueryChange("") }) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.planner_clear)) } }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (field == PlannerField.FROM) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.planner_my_position)) },
                            leadingContent = { Icon(ImageVector.vectorResource(UiR.drawable.ms_my_location), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable(onClick = onUseMyPosition),
                        )
                    }
                }
                if (query.trim().length < 2) {
                    items(recents) { place ->
                        ListItem(
                            headlineContent = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(ImageVector.vectorResource(UiR.drawable.ms_history), contentDescription = null) },
                            modifier = Modifier.clickable { onChoose(place) },
                        )
                    }
                } else if (results.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.planner_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.l),
                        )
                    }
                } else {
                    items(results) { result -> ResultRow(result, onClick = { onChoose(result.place) }) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: PlannerResult, onClick: () -> Unit) {
    val details = listOfNotNull(
        result.typeLabel?.let { stringResource(it) },
        result.distanceMeters?.let { distanceText(it) },
        result.otherRegionName,
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(result.place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (details.isNotEmpty()) {
            { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        leadingContent = {
            if (result.isAddress) {
                Icon(ImageVector.vectorResource(UiR.drawable.ms_home_pin), contentDescription = null)
            } else {
                Icon(AppIcons.Place, contentDescription = null)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
