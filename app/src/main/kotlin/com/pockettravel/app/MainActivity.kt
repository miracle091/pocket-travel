package com.pockettravel.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.pockettravel.app.navigation.PocketTravelNavHost
import com.pockettravel.core.ui.PocketTravelTheme
import dagger.hilt.android.AndroidEntryPoint

// FragmentActivity (sottoclasse di ComponentActivity) invece di ComponentActivity: richiesto da
// BiometricPrompt (feature:vault, cassaforte documenti) — nessun altro cambio di comportamento.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PocketTravelTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PocketTravelNavHost()
                }
            }
        }
    }
}
