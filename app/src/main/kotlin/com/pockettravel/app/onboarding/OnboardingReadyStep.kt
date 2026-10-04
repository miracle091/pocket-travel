package com.pockettravel.app.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.app.settings.AppLanguage
import com.pockettravel.app.settings.label
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.countryName
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.core.ui.R as UiR

/**
 * Passo "Pronto": riepilogo di quanto impostato (nulla da decidere qui). All'apertura parte in background anche il
 * download delle guide di tutte le nazioni, un pacchetto di meno di un MB.
 */
@Composable
internal fun OnboardingReadyStep(
    viewModel: OnboardingViewModel,
    startedDownloads: Int,
    guidesViewModel: GuidesDownloadViewModel = hiltViewModel(),
) {
    val usageModes by viewModel.usageModes.collectAsStateWithLifecycle()
    val nationality by viewModel.nationality.collectAsStateWithLifecycle()
    val guidesInstalled by guidesViewModel.isInstalled.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { guidesViewModel.startIfMissing() }
    val context = LocalContext.current
    // Chiave sulla configurazione: la lingua cambia senza ricreare l'activity.
    val language = remember(LocalConfiguration.current) { AppLanguage.current(context) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepHeader(
            icon = AppIcons.Check,
            title = stringResource(R.string.onboarding_ready_title),
            body = stringResource(R.string.onboarding_ready_body),
        )
        SummaryRow(AppIcons.Web, stringResource(R.string.more_language), language.label())
        SummaryRow(
            icon = ImageVector.vectorResource(UiR.drawable.ms_directions_walk),
            title = stringResource(R.string.onboarding_ready_modes),
            value = UsageMode.entries.filter { it in usageModes }.map { stringResource(it.label) }.joinToString(),
        )
        SummaryRow(
            icon = AppIcons.Passport,
            title = stringResource(R.string.more_nationality),
            value = nationality?.let { countryName(it) } ?: stringResource(R.string.more_nationality_none),
            flagCountry = nationality,
        )
        SummaryRow(
            icon = AppIcons.World,
            title = stringResource(R.string.onboarding_ready_countries),
            value = if (startedDownloads > 0) {
                pluralStringResource(R.plurals.onboarding_ready_countries_started, startedDownloads, startedDownloads)
            } else {
                stringResource(R.string.onboarding_ready_countries_none)
            },
        )
        SummaryRow(
            icon = AppIcons.Book,
            title = stringResource(R.string.onboarding_guides_title),
            value = stringResource(if (guidesInstalled) R.string.onboarding_guides_done else R.string.onboarding_guides_downloading),
        )
        SummaryRow(
            icon = AppIcons.AiAssistant,
            title = stringResource(R.string.onboarding_ready_ai_title),
            value = stringResource(R.string.onboarding_ready_ai_body),
        )
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, title: String, value: String, flagCountry: String? = null) {
    ListItem(
        leadingContent = { CountryFlag(flagCountry, size = 24.dp) { Icon(icon, contentDescription = null) } },
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
