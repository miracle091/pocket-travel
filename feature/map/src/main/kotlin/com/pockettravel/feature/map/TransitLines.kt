package com.pockettravel.feature.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.ui.Spacing

// Il colore della linea e' solo decorazione: sigla, mezzo e destinazione stanno in chiaro.
@Composable
internal fun LineBadge(departure: TransitDeparture) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = departure.color?.let(::Color) ?: MaterialTheme.colorScheme.secondaryContainer,
        contentColor = departure.textColor?.let(::Color) ?: MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = departure.line,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 44.dp, max = 96.dp).padding(horizontal = Spacing.s, vertical = Spacing.xs),
        )
    }
}

// Una chip per linea, piu' "Tutte le linee": scorre di lato quando le linee sono tante.
@Composable
internal fun LineFilter(lines: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.nearby_line_all)) })
        lines.forEach { line ->
            FilterChip(selected = selected == line, onClick = { onSelect(line) }, label = { Text(line) })
        }
    }
}
