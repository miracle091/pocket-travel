package com.pockettravel.app.updates

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.pockettravel.app.navigation.OpenScreenRequests
import com.pockettravel.core.sync.AppCompatibility
import com.pockettravel.core.sync.UpdateAvailableNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import com.pockettravel.core.sync.R as SyncR

/** I download bloccati perché i dati chiedono un'app più nuova (AppCompatibility), da qualunque schermata li abbia chiesti. */
@HiltViewModel
class UpdateRequiredViewModel @Inject constructor(appCompatibility: AppCompatibility) : ViewModel() {
    val messages: SharedFlow<Unit> = appCompatibility.updateRequiredMessages
}

/**
 * Il messaggio "Aggiorna Pocket Travel per scaricare le nazioni" con l'azione "Aggiorna", che apre Aggiornamenti, in basso.
 * [aboveBottomBar]: c'è la barra di navigazione in basso, che copre già la barra di sistema; senza, serve il margine.
 */
@Composable
internal fun BoxScope.UpdateRequiredMessages(aboveBottomBar: Boolean, viewModel: UpdateRequiredViewModel = hiltViewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(SyncR.string.app_update_required_for_download)
    val action = stringResource(SyncR.string.app_update_required_action)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect {
            val result = snackbarHostState.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) OpenScreenRequests.screen.value = UpdateAvailableNotifier.OPEN_UPDATES
        }
    }
    SnackbarHost(
        snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).then(
            if (aboveBottomBar) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        ),
    )
}
