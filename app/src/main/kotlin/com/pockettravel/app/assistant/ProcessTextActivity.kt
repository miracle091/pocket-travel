package com.pockettravel.app.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.MainActivity
import com.pockettravel.app.navigation.OpenScreenRequests
import com.pockettravel.app.regions.RecentRegionStore
import com.pockettravel.app.settings.AppLanguage
import com.pockettravel.app.settings.ThemePreferences
import com.pockettravel.core.sync.UpdateAvailableNotifier
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.feature.ai.SelectedTextSheet
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * "Chiedi all'assistente": il testo selezionato in un'altra app (PROCESS_TEXT) o condiviso (SEND) arriva in un foglio
 * sopra quell'app. Non restituisce testo all'altra app: EXTRA_PROCESS_TEXT_READONLY non serve.
 */
@AndroidEntryPoint
class ProcessTextActivity : ComponentActivity() {
    @Inject lateinit var themePreferences: ThemePreferences

    @Inject lateinit var recentRegionStore: RecentRegionStore

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguage.applyDefault(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val text = intent.sharedText()
        setContent {
            val useDynamicColor by themePreferences.useDynamicColor.collectAsStateWithLifecycle()
            val forceDark by themePreferences.forceDark.collectAsStateWithLifecycle()
            PocketTravelTheme(darkTheme = forceDark || isSystemInDarkTheme(), dynamicColor = useDynamicColor) {
                SelectedTextSheet(
                    rawText = text,
                    regionId = recentRegionStore.lastRegionId(),
                    onDismiss = ::finish,
                    onOpenSettings = ::openSettings,
                    onOpenOfficialSource = ::openUrl,
                )
            }
        }
    }

    // Senza motore: l'app si apre sulle Impostazioni.
    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(UpdateAvailableNotifier.EXTRA_OPEN_SCREEN, OpenScreenRequests.OPEN_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // Nessun browser: il link non si apre.
        }
    }
}

// Il testo del menu di selezione (PROCESS_TEXT) o di Condividi (SEND); null per ogni altra azione.
private fun Intent.sharedText(): CharSequence? = when (action) {
    Intent.ACTION_PROCESS_TEXT -> getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
    Intent.ACTION_SEND -> getCharSequenceExtra(Intent.EXTRA_TEXT)
    else -> null
}
