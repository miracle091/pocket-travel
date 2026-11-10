package com.pockettravel.feature.ai

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pockettravel.core.sync.ONLINE_MODEL_ROLE_AUTO
import com.pockettravel.core.sync.OnlineModelEntry
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import java.text.NumberFormat
import java.util.Currency

// Consenso a mandare al servizio i dati personali (nota, posizione, partenza e nazionalita'): spento di default.
@Composable
internal fun SharePersonalDataSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) =
    SettingSwitch(R.string.ai_online_share_title, R.string.ai_online_share_hint, checked, onCheckedChange)

@Composable
internal fun SettingSwitch(@StringRes title: Int, @StringRes hint: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.s)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun ApiKeySetup(uiState: AiUiState, actions: AiActions) {
    var isKeyVisible by rememberSaveable { mutableStateOf(false) }
    // Con la chiave in chiaro niente screenshot, condivisione dello schermo ne' miniatura delle app recenti.
    SecureWindow(enabled = isKeyVisible)
    val uriHandler = LocalUriHandler.current
    val provider = uiState.onlineProvider
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.ai_api_key_intro), style = MaterialTheme.typography.bodyLarge)
        // Servizio (OnlineProvider), con il modello in uso (OnlineModelCatalog), raggruppato per cosa fa delle domande.
        Text(
            stringResource(R.string.ai_provider_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Column(modifier = Modifier.selectableGroup()) {
            OnlinePrivacy.entries.forEach { privacy ->
                Text(
                    stringResource(privacyGroupTitle(privacy)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.xs).semantics { heading() },
                )
                OnlineProvider.entries.filter { it.terms.privacy == privacy }.forEach { candidate ->
                    ListItem(
                        leadingContent = { RadioButton(selected = candidate == provider, onClick = null) },
                        supportingContent = {
                            uiState.onlineModels[candidate]?.let { Text(stringResource(R.string.ai_provider_model, it)) }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = candidate == provider, role = Role.RadioButton) {
                            actions.onProviderSelected(candidate)
                        },
                        content = { Text(candidate.displayName) },
                    )
                }
            }
        }
        ProviderPrivacy(provider, uiState.onlineModels[provider])
        OnlineModelChoice(
            provider = provider,
            options = uiState.onlineModelOptions,
            others = uiState.onlineOtherModels,
            setting = uiState.onlineModelSetting,
            onSettingChanged = actions.onOnlineModelChanged,
        )
        TextButton(onClick = { uriHandler.openUri(provider.keyPageUrl) }) {
            Text(stringResource(R.string.ai_provider_get_key, provider.displayName))
            Spacer(modifier = Modifier.width(Spacing.s))
            Icon(AppIcons.OpenExternal, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            stringResource(R.string.ai_api_key_privacy, provider.displayName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        provider.terms.idField?.let { idField ->
            val invalid = uiState.accountIdInput.isNotBlank() && !isValidAccountId(uiState.accountIdInput)
            OutlinedTextField(
                value = uiState.accountIdInput,
                onValueChange = actions.onAccountIdInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(accountIdLabel(idField))) },
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.ai_account_id_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = uiState.apiKeyInput,
            onValueChange = actions.onApiKeyInputChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ai_api_key_label)) },
            placeholder = provider.keyPrefix?.let { prefix -> { Text(stringResource(R.string.ai_api_key_placeholder, prefix)) } },
            visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            // Password e senza correzione: la tastiera non deve memorizzare ne' suggerire la chiave.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                    Icon(
                        if (isKeyVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                        contentDescription = stringResource(
                            if (isKeyVisible) R.string.ai_api_key_hide else R.string.ai_api_key_show,
                        ),
                    )
                }
            },
            singleLine = true,
        )
        Button(
            onClick = actions.onSaveApiKey,
            enabled = uiState.apiKeyInput.isNotBlank() && (provider.terms.idField == null || isValidAccountId(uiState.accountIdInput)),
        ) {
            Text(stringResource(R.string.ai_api_key_save))
        }
    }
}

// Cosa fa delle domande il servizio scelto, con il collegamento ai suoi impegni sulla privacy; per Gemini anche
// l'avviso sul piano a pagamento nell'UE (condizioni di Gemini per le app usate nello Spazio economico europeo), per
// OpenRouter come escludere i fornitori che conservano le domande, per Claude i crediti API inclusi nei piani di
// claude.ai (un'app di terzi non può usare l'accesso con l'account claude.ai), per i modelli Max di Alibaba il calcolo
// che può avvenire fuori dall'UE. Con un modello di Anthropic o Google su DeepInfra
// valgono le regole di quei servizi ([OnlineProvider.upstreamFor]), e il collegamento porta alle loro.
@Composable
private fun ProviderPrivacy(provider: OnlineProvider, model: String?) {
    val uriHandler = LocalUriHandler.current
    val upstream = provider.upstreamFor(model)
    val text = when {
        upstream != null -> stringResource(R.string.ai_privacy_upstream, if (upstream == OnlineProvider.CLAUDE) "Anthropic" else "Google")
        else -> when (provider.terms.privacy) {
            OnlinePrivacy.NOT_KEPT -> stringResource(R.string.ai_privacy_not_kept, provider.displayName)
            OnlinePrivacy.KEPT_FOR_ABUSE_CHECKS -> pluralStringResource(
                R.plurals.ai_privacy_kept,
                provider.terms.retentionDays,
                provider.displayName,
                provider.terms.retentionDays,
            )
            OnlinePrivacy.FREE_PLAN_TRAINING -> stringResource(R.string.ai_privacy_free_plan_training, provider.displayName)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (provider.terms.privacy == OnlinePrivacy.FREE_PLAN_TRAINING) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (provider == OnlineProvider.GEMINI) {
            Text(
                stringResource(R.string.ai_privacy_gemini_paid_eu),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (provider.mayRunOutsideEu(model)) {
            Text(
                stringResource(R.string.ai_privacy_outside_eu),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (provider == OnlineProvider.CLAUDE) {
            Text(
                stringResource(R.string.ai_privacy_claude_plan_credits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { uriHandler.openUri(CLAUDE_PLAN_CREDITS_URL) }) {
                Text(stringResource(R.string.ai_privacy_claude_plan_credits_link))
                Spacer(modifier = Modifier.width(Spacing.s))
                Icon(AppIcons.OpenExternal, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        if (provider == OnlineProvider.OPENROUTER) {
            Text(
                stringResource(R.string.ai_privacy_openrouter_zdr_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { uriHandler.openUri(OPENROUTER_PRIVACY_SETTINGS_URL) }) {
                Text(stringResource(R.string.ai_privacy_openrouter_settings))
                Spacer(modifier = Modifier.width(Spacing.s))
                Icon(AppIcons.OpenExternal, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        TextButton(onClick = { uriHandler.openUri((upstream ?: provider).terms.privacyUrl) }) {
            Text(stringResource(R.string.ai_privacy_commitments))
            Spacer(modifier = Modifier.width(Spacing.s))
            Icon(AppIcons.OpenExternal, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

private const val OPENROUTER_PRIVACY_SETTINGS_URL = "https://openrouter.ai/settings/privacy"

private const val CLAUDE_PLAN_CREDITS_URL = "https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers"

private fun privacyGroupTitle(privacy: OnlinePrivacy): Int = when (privacy) {
    OnlinePrivacy.NOT_KEPT -> R.string.ai_privacy_group_not_kept
    OnlinePrivacy.KEPT_FOR_ABUSE_CHECKS -> R.string.ai_privacy_group_kept
    OnlinePrivacy.FREE_PLAN_TRAINING -> R.string.ai_privacy_group_free_plan_training
}

private fun accountIdLabel(idField: OnlineIdField): Int = when (idField) {
    OnlineIdField.ACCOUNT_ID -> R.string.ai_account_id_account
    OnlineIdField.PRODUCT_ID -> R.string.ai_account_id_product
    OnlineIdField.WORKSPACE_ID -> R.string.ai_account_id_workspace
}

// Modello del servizio: Automatico (consigliato), gli altri dell'elenco (OnlineModelCatalog) o uno scritto a
// mano. Ognuno con una riga che spiega la differenza e, per quelli dell'elenco, il costo stimato. Con "Altro" sotto
// il campo compaiono gli altri modelli del servizio che l'account vede ([others]), filtrati da quanto scritto.
@Composable
private fun OnlineModelChoice(
    provider: OnlineProvider,
    options: List<OnlineModelEntry>,
    others: List<OnlineModelEntry>,
    setting: String,
    onSettingChanged: (String) -> Unit,
) {
    val listed = options.filter { it.role != ONLINE_MODEL_ROLE_AUTO }
    // "Altro" resta selezionato mentre si scrive, anche col campo ancora vuoto; vale anche per una scelta salvata
    // che non e' (o non e' piu') tra i modelli mostrati, tolta dall'elenco o nascosta dopo la lettura di /models.
    var otherSelected by rememberSaveable(provider) { mutableStateOf(false) }
    val showOther = otherSelected || (setting.isNotBlank() && listed.none { it.id == setting.trim() })
    Text(
        stringResource(R.string.ai_online_model_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
    val auto = options.firstOrNull { it.role == ONLINE_MODEL_ROLE_AUTO }
    Column(modifier = Modifier.selectableGroup()) {
        auto?.let { auto ->
            ModelChoiceRow(
                selected = !showOther && setting.isBlank(),
                title = stringResource(R.string.ai_online_model_auto),
                description = stringResource(R.string.ai_online_model_auto_desc, auto.id),
                cost = costText(auto),
            ) { otherSelected = false; onSettingChanged("") }
        }
        listed.forEach { model ->
            // Piu' economico di quello di Automatico: il ripiego quando ne finisce la quota giornaliera (automaticCandidates).
            val lighter = auto != null && model.inputUsdPerMTok < auto.inputUsdPerMTok
            ModelChoiceRow(
                selected = !showOther && setting.trim() == model.id,
                title = stringResource(if (lighter) R.string.ai_online_model_lighter else R.string.ai_online_model_capable, model.id),
                description = stringResource(if (lighter) R.string.ai_online_model_lighter_desc else R.string.ai_online_model_capable_desc),
                cost = costText(model),
            ) { otherSelected = false; onSettingChanged(model.id) }
        }
        ModelChoiceRow(
            selected = showOther,
            title = stringResource(R.string.ai_online_model_other),
            description = stringResource(R.string.ai_online_model_other_desc),
        ) { otherSelected = true; if (listed.any { it.id == setting }) onSettingChanged("") }
    }
    if (showOther) {
        OutlinedTextField(
            value = setting,
            onValueChange = onSettingChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ai_online_model_label)) },
            singleLine = true,
        )
        val typed = setting.trim()
        others.filter { it.id != typed && it.id.contains(typed, ignoreCase = true) }.take(MAX_OTHER_SUGGESTIONS).forEach { model ->
            ListItem(
                supportingContent = { Text(costText(model), style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.clickable { onSettingChanged(model.id) },
                content = { Text(model.id) },
            )
        }
    }
}

// Proposte sotto il campo "Altro modello": scrivendo si restringono.
private const val MAX_OTHER_SUGGESTIONS = 6

// Costo stimato di 100 domande in dollari, nel formato della lingua dell'app.
@Composable
private fun costText(model: OnlineModelEntry): String {
    val usd = model.usdPer100Questions()
    val format = NumberFormat.getCurrencyInstance(LocalConfiguration.current.locales[0]).apply {
        currency = Currency.getInstance("USD")
        maximumFractionDigits = 2
    }
    return if (usd < MIN_SHOWN_COST) {
        stringResource(R.string.ai_online_model_cost_below, format.format(MIN_SHOWN_COST))
    } else {
        stringResource(R.string.ai_online_model_cost, format.format(usd))
    }
}

private const val MIN_SHOWN_COST = 0.01

@Composable
private fun ModelChoiceRow(selected: Boolean, title: String, description: String, cost: String? = null, onClick: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = {
            Column {
                Text(description)
                if (cost != null) Text(cost, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        content = { Text(title) },
    )
}
