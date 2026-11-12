package com.pockettravel.app.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.pockettravel.app.runCatchingCancellable
import com.pockettravel.core.data.AppInitializer
import com.pockettravel.core.data.DefaultDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Ridisegna i widget quando cambia quello che mostrano (WidgetStateLoader.changes): avvia la sessione dei widget che non ne
 * hanno una, quelli con la sessione aperta seguono i cambi da soli. Senza widget sulla Home `updateAll` non fa nulla.
 */
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loader: WidgetStateLoader,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : AppInitializer {
    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    @OptIn(FlowPreview::class)
    override fun onAppCreate() {
        scope.launch {
            loader.changes
                // Un'installazione cambia piu' righe di seguito: un solo ridisegno.
                .debounce(REFRESH_DEBOUNCE_MS)
                .collect {
                    // Un errore di Glance (un dispositivo senza widget) non deve chiudere l'app.
                    runCatchingCancellable {
                        EmergencyWidget().updateAll(context)
                        ShortcutsWidget().updateAll(context)
                    }.onFailure { Log.w(TAG, "Ridisegno dei widget fallito (${it::class.simpleName})") }
                }
        }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 1_000L
        const val TAG = "WidgetRefresher"
    }
}
