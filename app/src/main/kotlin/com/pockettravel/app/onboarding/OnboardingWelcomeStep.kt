package com.pockettravel.app.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.app.settings.LanguageChips
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.CountryPickerSheet
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.countryName
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.core.ui.R as UiR

/**
 * Passo "Benvenuto": lingua, modi di spostarsi e nazionalita' in una sola schermata a chip. Lingua e nazionalita'
 * partono dai valori del telefono; i modi di spostarsi vanno scelti (almeno uno) per poter proseguire.
 */
@Composable
internal fun OnboardingWelcomeStep(viewModel: OnboardingViewModel) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        StepHeader(
            icon = AppIcons.Compass,
            title = stringResource(R.string.onboarding_welcome_title),
            body = stringResource(R.string.onboarding_welcome_body),
        )
        ChipSection(title = stringResource(R.string.onboarding_language_title)) { LanguageChips() }
        ChipSection(
            title = stringResource(R.string.onboarding_usage_mode_title),
            body = stringResource(R.string.onboarding_usage_mode_body),
        ) { UsageModeChips(viewModel) }
        ChipSection(
            title = stringResource(R.string.onboarding_nationality_title),
            body = stringResource(R.string.onboarding_nationality_body),
        ) { NationalityChip(viewModel) }
    }
}

// Titolo breve della sezione, eventuale riga di spiegazione e, sotto, i chip.
@Composable
private fun ChipSection(title: String, body: String? = null, chips: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = Spacing.xs).semantics { heading() },
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s),
            )
        }
        chips()
    }
}

@Composable
private fun OnboardingChip(selected: Boolean, onClick: () -> Unit, label: String, icon: ImageVector) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

// Modi di spostarsi a scelta multipla, piu' "In sedia a rotelle" e "Percorsi di navigazione", che valgono con
// qualsiasi modo (stesse preferenze della sezione "Come ti sposti" di Altro).
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageModeChips(viewModel: OnboardingViewModel) {
    val usageModes by viewModel.usageModes.collectAsStateWithLifecycle()
    val accessible by viewModel.accessible.collectAsStateWithLifecycle()
    val wantsDirections by viewModel.wantsDirections.collectAsStateWithLifecycle()
    // Accendendo i percorsi si chiede subito la posizione, solo in primo piano; chi rifiuta la concede dopo dal Navigatore.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        // "Escursionismo" nascosto finche' non ha contenuti suoi: resta solo per chi l'aveva gia' scelto.
        UsageMode.entries.filter { it != UsageMode.ESCURSIONISMO || it in usageModes }.forEach { mode ->
            val checked = mode in usageModes
            OnboardingChip(
                selected = checked,
                onClick = { viewModel.setUsageModes(if (checked) usageModes - mode else usageModes + mode) },
                label = stringResource(mode.label),
                icon = ImageVector.vectorResource(mode.icon),
            )
        }
        OnboardingChip(
            selected = accessible,
            onClick = { viewModel.setAccessible(!accessible) },
            label = stringResource(R.string.onboarding_accessible),
            icon = ImageVector.vectorResource(UiR.drawable.ms_accessible),
        )
        OnboardingChip(
            selected = wantsDirections,
            onClick = {
                viewModel.setWantsDirections(!wantsDirections)
                if (!wantsDirections) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            },
            label = stringResource(R.string.onboarding_directions),
            icon = AppIcons.Route,
        )
    }
}

// Proposta: il paese del telefono (NationalityPreferences); il chip apre l'elenco dei paesi per cambiarla.
@Composable
private fun NationalityChip(viewModel: OnboardingViewModel) {
    val nationality by viewModel.nationality.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    FilterChip(
        selected = nationality != null,
        onClick = { showPicker = true },
        label = { Text(nationality?.let { countryName(it) } ?: stringResource(R.string.more_nationality_none)) },
        leadingIcon = { CountryFlag(nationality, size = 24.dp) { Icon(AppIcons.World, contentDescription = null) } },
        trailingIcon = { Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
    if (showPicker) {
        CountryPickerSheet(
            title = stringResource(R.string.onboarding_nationality_title),
            selected = nationality,
            onSelect = { viewModel.setNationality(it); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}
