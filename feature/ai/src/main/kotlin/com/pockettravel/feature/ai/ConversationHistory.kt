package com.pockettravel.feature.ai

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.pockettravel.core.data.SavedConversation
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.crypto.Cipher
import com.pockettravel.core.ui.R as UiR

/**
 * La cronologia delle conversazioni a schermo intero, sopra l'assistente. Si apre solo dopo la biometria forte legata
 * a un CryptoObject, come la cassaforte (niente PIN: androidx.biometric non lo ammette con un CryptoObject). La
 * finestra e' protetta (FLAG_SECURE): niente conversazioni negli screenshot e nella miniatura delle app recenti.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationHistoryDialog(history: HistoryUiState, deleteFailed: Boolean, actions: HistoryActions) {
    // L'activity del BiometricPrompt: dentro il Dialog LocalContext e' il contesto della sua finestra, non l'activity.
    val activity = LocalActivity.current as? FragmentActivity
    Dialog(
        onDismissRequest = actions.onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        // La finestra del dialogo va sotto le barre di sistema: le loro icone seguono il tema, come nell'activity.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > HALF_LUMINANCE
        SideEffect {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ai_history)) },
                    navigationIcon = {
                        IconButton(onClick = actions.onClose) { Icon(AppIcons.Back, contentDescription = stringResource(UiR.string.back)) }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (deleteFailed) {
                    Text(
                        stringResource(R.string.ai_history_delete_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when (history) {
                        HistoryUiState.Loading -> PocketTravelLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                        HistoryUiState.Unavailable -> NoBiometrics()
                        is HistoryUiState.Locked -> LockedHistory(history.cipher, activity, actions)
                        HistoryUiState.Invalidated -> InvalidatedHistory(actions.onDeleteAll)
                        is HistoryUiState.Unlocked -> ConversationList(history.conversations, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoBiometrics() {
    val context = LocalContext.current
    EmptyState(
        icon = AppIcons.Lock,
        title = stringResource(R.string.ai_history_no_biometric_title),
        subtitle = stringResource(R.string.ai_history_no_biometric_body),
        modifier = Modifier.fillMaxSize(),
        action = {
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }) {
                Text(stringResource(R.string.ai_history_open_security_settings))
            }
        },
    )
}

// Il prompt si apre da solo all'ingresso; annullato, resta il pulsante per riprovare (con un Cipher nuovo).
@Composable
private fun LockedHistory(cipher: Cipher?, activity: FragmentActivity?, actions: HistoryActions) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val promptTitle = stringResource(R.string.ai_history_prompt_title)
    val promptSubtitle = stringResource(R.string.ai_history_prompt_subtitle)
    val promptCancel = stringResource(UiR.string.confirm_cancel)
    val authFailed = stringResource(R.string.ai_history_auth_failed)

    LaunchedEffect(cipher) {
        if (cipher == null || activity == null) return@LaunchedEffect
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated == null) errorMessage = authFailed else actions.onUnlocked(authenticated)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Annullato: resta la spiegazione con il pulsante, non il testo del sistema.
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) return
                    errorMessage = errString.toString()
                }

                override fun onAuthenticationFailed() {
                    errorMessage = authFailed
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptTitle)
            .setSubtitle(promptSubtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(promptCancel)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    EmptyState(
        icon = AppIcons.Lock,
        title = stringResource(R.string.ai_history_locked_title),
        subtitle = errorMessage ?: stringResource(R.string.ai_history_locked_body),
        modifier = Modifier.fillMaxSize(),
        action = { Button(onClick = actions.onRetry) { Text(stringResource(R.string.ai_history_unlock)) } },
    )
}

@Composable
private fun InvalidatedHistory(onDeleteAll: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    EmptyState(
        icon = AppIcons.Lock,
        title = stringResource(R.string.ai_history_invalidated_title),
        subtitle = stringResource(R.string.ai_history_invalidated_body),
        modifier = Modifier.fillMaxSize(),
        action = { Button(onClick = { confirm = true }) { Text(stringResource(R.string.ai_history_delete_all)) } },
    )
    if (confirm) DeleteHistoryConfirmation(onConfirm = { confirm = false; onDeleteAll() }, onDismiss = { confirm = false })
}

@Composable
internal fun DeleteHistoryConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmationDialog(
        title = stringResource(R.string.ai_history_delete_all_title),
        message = stringResource(R.string.ai_history_delete_all_message),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ConversationList(conversations: List<SavedConversation>, actions: HistoryActions) {
    if (conversations.isEmpty()) {
        EmptyState(
            icon = AppIcons.History,
            title = stringResource(R.string.ai_history_empty_title),
            subtitle = stringResource(R.string.ai_history_empty_body),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    var toDelete by remember { mutableStateOf<SavedConversation?>(null) }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(conversations, key = { it.id }) { conversation ->
            var menuExpanded by remember { mutableStateOf(false) }
            val date = dateFormat.format(Instant.ofEpochMilli(conversation.updatedAt).atZone(ZoneId.systemDefault()))
            ListItem(
                headlineContent = { Text(conversation.summary.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        stringResource(R.string.ai_history_item_details, conversation.summary.regionName, date),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.ai_more_actions))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_history_delete)) },
                                leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                                onClick = { menuExpanded = false; toDelete = conversation },
                            )
                        }
                    }
                },
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.ai_history_resume)) { actions.onResume(conversation.id) },
            )
        }
    }
    toDelete?.let { conversation ->
        ConfirmationDialog(
            title = stringResource(R.string.ai_history_delete_title),
            message = stringResource(R.string.ai_history_delete_message, conversation.summary.title),
            onConfirm = { toDelete = null; actions.onDelete(conversation.id) },
            onDismiss = { toDelete = null },
        )
    }
}

private const val HALF_LUMINANCE = 0.5f
