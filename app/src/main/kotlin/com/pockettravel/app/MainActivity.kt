package com.pockettravel.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.pockettravel.app.navigation.PocketTravelNavHost
import com.pockettravel.app.settings.AppLanguage
import com.pockettravel.app.settings.ThemePreferences
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.currentGuidesLanguage
import com.pockettravel.core.sync.isEnglishGuidesVersion
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.feature.map.NavigationService
import com.pockettravel.feature.map.NavigationSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

// FragmentActivity (sottoclasse di ComponentActivity) invece di ComponentActivity: richiesta da
// BiometricPrompt (feature:vault, cassaforte documenti).
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var themePreferences: ThemePreferences
    @Inject lateinit var regionRepository: RegionRepository
    @Inject lateinit var regionSyncScheduler: RegionSyncScheduler

    @Inject lateinit var navigationSession: NavigationSession

    // Notifica della guida toccata con l'app gia' aperta: l'hub della regione porta in primo piano il Navigatore.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(NavigationService.EXTRA_OPEN_NAVIGATOR, false)) navigationSession.requestOpen()
    }

    // Fino ad Android 12 la lingua scelta nell'app si applica qui (da 13 ci pensa il sistema).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        AppLanguage.applyDefault(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Guide installate in una lingua diversa da quella dell'interfaccia (lingua appena cambiata):
        // si scaricano subito quelle giuste. Se il manifest non offre guide inglesi il worker non fa nulla.
        lifecycleScope.launch {
            val installed = regionRepository.installedGuidesVersion()
            if (installed != null && isEnglishGuidesVersion(installed) != (currentGuidesLanguage() == "en")) {
                regionSyncScheduler.enqueueGuidesSync(onlyOnWifi = false)
            }
        }
        setContent {
            val useDynamicColor by themePreferences.useDynamicColor.collectAsStateWithLifecycle()
            val forceDark by themePreferences.forceDark.collectAsStateWithLifecycle()
            val darkTheme = forceDark || isSystemInDarkTheme()
            // enableEdgeToEdge() sopra segue il tema del telefono: con il tema scuro forzato le icone
            // delle barre di sistema resterebbero scure su sfondo scuro. Scrim come quelli di default.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.argb(0xe6, 0xff, 0xff, 0xff), Color.argb(0x80, 0x1b, 0x1b, 0x1b)) { darkTheme },
                )
            }
            PocketTravelTheme(darkTheme = darkTheme, dynamicColor = useDynamicColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PocketTravelNavHost()
                }
            }
        }
    }
}
