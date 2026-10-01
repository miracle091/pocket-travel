package com.pockettravel.app.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CountryPickerSheet
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.countryName
import com.pockettravel.feature.ai.AiSettingsSection
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModeOptions
import com.pockettravel.core.ui.R as UiR

// Le preferenze dell'app, raccolte qui da Altro: generali (lingua, nazionalita', aspetto) e del
// Navigatore (come ci si sposta, GPS all'arrivo) e dell'assistente IA (modello o chiave API).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
    val forceDark by viewModel.forceDark.collectAsStateWithLifecycle()
    val usageMode by viewModel.usageMode.collectAsStateWithLifecycle()
    val accessible by viewModel.accessible.collectAsStateWithLifecycle()
    val wantsDirections by viewModel.wantsDirections.collectAsStateWithLifecycle()
    val nationality by viewModel.nationality.collectAsStateWithLifecycle()
    val stopGpsOnArrival by viewModel.stopGpsOnArrival.collectAsStateWithLifecycle()
    val walkingHaptics by viewModel.walkingHaptics.collectAsStateWithLifecycle()
    var showUsageModes by rememberSaveable { mutableStateOf(false) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showNationality by rememberSaveable { mutableStateOf(false) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val language = remember { AppLanguage.current(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_section_general))
            SettingsItem(AppIcons.Web, stringResource(R.string.more_language), language.label()) { showLanguage = true }
            SettingsItem(
                AppIcons.Passport,
                stringResource(R.string.more_nationality),
                nationality?.let { countryName(it) } ?: stringResource(R.string.more_nationality_none),
            ) { showNationality = true }
            SettingsItem(AppIcons.Palette, stringResource(R.string.more_appearance), appearanceSummary(forceDark, useDynamicColor)) { showAppearance = true }

            SectionHeader(stringResource(R.string.settings_section_navigation))
            SettingsItem(
                ImageVector.vectorResource((usageMode ?: UsageMode.A_PIEDI).icon),
                stringResource(R.string.more_usage_mode),
                usageModeSummary(usageMode, accessible),
            ) { showUsageModes = true }
            SwitchItem(
                icon = ImageVector.vectorResource(UiR.drawable.ms_my_location),
                title = stringResource(R.string.settings_stop_gps_on_arrival),
                subtitle = stringResource(R.string.settings_stop_gps_on_arrival_subtitle),
                checked = stopGpsOnArrival,
                onCheckedChange = viewModel::setStopGpsOnArrival,
            )
            SwitchItem(
                icon = ImageVector.vectorResource(UiR.drawable.ms_vibration),
                title = stringResource(R.string.settings_walking_haptics),
                subtitle = stringResource(R.string.settings_walking_haptics_subtitle),
                checked = walkingHaptics,
                onCheckedChange = viewModel::setWalkingHaptics,
            )

            SectionHeader(stringResource(R.string.settings_section_ai))
            AiSettingsSection()
        }
    }

    // Scegliere una modalita' riporta i filtri della mappa ai suoi predefiniti: lo dice il testo del foglio.
    if (showUsageModes) {
        // Aperto per intero e scorrevole: 6 modalita' e la casella non stanno a mezza altezza, ne' con i caratteri grandi.
        ModalBottomSheet(onDismissRequest = { showUsageModes = false }, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.l)) {
                Text(
                    text = stringResource(R.string.more_usage_mode),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.xl).semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.more_usage_mode_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
                )
                UsageModeOptions(
                    selected = usageMode,
                    onSelect = viewModel::setUsageMode,
                    accessible = accessible,
                    onAccessibleChange = viewModel::setAccessible,
                    directions = wantsDirections,
                    onDirectionsChange = viewModel::setWantsDirections,
                    modifier = Modifier.padding(horizontal = Spacing.s),
                )
            }
        }
    }

    if (showLanguage) {
        ModalBottomSheet(onDismissRequest = { showLanguage = false }) {
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = Spacing.l)) {
                Text(
                    text = stringResource(R.string.more_language),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.xl).semantics { heading() },
                )
                LanguageOptions(onChosen = { showLanguage = false })
            }
        }
    }

    if (showNationality) {
        CountryPickerSheet(
            title = stringResource(R.string.onboarding_nationality_title),
            selected = nationality,
            onSelect = { viewModel.setNationality(it); showNationality = false },
            onDismiss = { showNationality = false },
        )
    }

    if (showAppearance) {
        ModalBottomSheet(onDismissRequest = { showAppearance = false }) {
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = Spacing.l)) {
                Text(
                    text = stringResource(R.string.more_appearance),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.xl).semantics { heading() },
                )
                SwitchItem(
                    icon = AppIcons.DarkMode,
                    title = stringResource(R.string.more_force_dark),
                    subtitle = stringResource(R.string.more_force_dark_subtitle),
                    checked = forceDark,
                    onCheckedChange = viewModel::setForceDark,
                )
                // Il dynamic color esiste solo da Android 12: sotto, l'interruttore non avrebbe effetto.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchItem(
                        icon = AppIcons.Palette,
                        title = stringResource(R.string.more_dynamic_color),
                        subtitle = stringResource(R.string.more_dynamic_color_subtitle),
                        checked = useDynamicColor,
                        onCheckedChange = viewModel::setUseDynamicColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.l, bottom = Spacing.xs).semantics { heading() },
    )
}

@Composable
private fun SwitchItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        content = { Text(title) },
    )
}

@Composable
private fun SettingsItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
        content = { Text(title) },
    )
}

@Composable
private fun appearanceSummary(forceDark: Boolean, useDynamicColor: Boolean): String {
    val theme = stringResource(if (forceDark) R.string.more_appearance_dark else R.string.more_appearance_system)
    return if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        stringResource(R.string.more_appearance_with_dynamic_color, theme)
    } else {
        theme
    }
}

@Composable
private fun usageModeSummary(mode: UsageMode?, accessible: Boolean): String {
    val label = mode?.let { stringResource(it.label) }
    return when {
        label == null && accessible -> stringResource(R.string.more_usage_mode_accessible_only)
        label == null -> stringResource(R.string.more_usage_mode_none)
        accessible -> stringResource(R.string.more_usage_mode_with_accessible, label)
        else -> label
    }
}
