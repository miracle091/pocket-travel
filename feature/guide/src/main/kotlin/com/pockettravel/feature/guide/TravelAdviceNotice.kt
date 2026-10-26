package com.pockettravel.feature.guide

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pockettravel.core.data.advice.TravelAdviceUpdate
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.InfoCardHeader
import com.pockettravel.core.ui.Spacing

/**
 * Avviso che l'FCDO ha aggiornato i consigli di viaggio del paese dopo quelli del pacchetto guide installato, con il
 * pulsante che apre la pagina GOV.UK.
 */
@Composable
fun TravelAdviceNotice(update: TravelAdviceUpdate, onOpenSource: (url: String, title: String) -> Unit, modifier: Modifier = Modifier) {
    val date = DateUtils.formatDateTime(
        LocalContext.current,
        update.updatedAt.toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
    )
    val title = stringResource(R.string.guide_source_title_fcdo)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            InfoCardHeader(icon = AppIcons.Info, title = stringResource(R.string.guide_travel_advice_updated, date))
            TextButton(onClick = { onOpenSource(update.webUrl, title) }) {
                Text(stringResource(R.string.guide_travel_advice_open))
            }
        }
    }
}
