package com.pockettravel.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.navigation.PocketTravelNavHost
import com.pockettravel.app.settings.ThemePreferences
import com.pockettravel.core.ui.PocketTravelTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// FragmentActivity (sottoclasse di ComponentActivity) invece di ComponentActivity: richiesto da
// BiometricPrompt (feature:vault, cassaforte documenti) — nessun altro cambio di comportamento.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var themePreferences: ThemePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val useDynamicColor by themePreferences.useDynamicColor.collectAsStateWithLifecycle()
            PocketTravelTheme(dynamicColor = useDynamicColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PocketTravelNavHost()
                }
            }
        }
    }
}
