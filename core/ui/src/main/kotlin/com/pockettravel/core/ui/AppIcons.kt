package com.pockettravel.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

// Sostituisce le 19 icone raster PNG di core/data (nero cotto nei pixel, non tintabili) con
// ImageVector reali. 16 dei 19 concetti risolvono da Icons.Filled.* (material-icons-core, via
// compose-material3, o material-icons-extended, dichiarata in questo modulo); i 3 concetti senza
// equivalente nemmeno nell'artifact esteso (customs/passport/vaccinations) sono XML vector
// drawable scelti a mano dal set Material Symbols ufficiale (vedi ic_fact_check.xml/ic_badge.xml/
// ic_vaccines.xml per la provenienza).
object AppIcons {
    val Download: ImageVector = Icons.Filled.Download
    val Favorite: ImageVector = Icons.Filled.Favorite
    val Help: ImageVector = Icons.AutoMirrored.Filled.Help
    val Info: ImageVector = Icons.Filled.Info
    val Settings: ImageVector = Icons.Filled.Settings
    val World: ImageVector = Icons.Filled.Public

    val Compass: ImageVector = Icons.Filled.Explore
    val Checklist: ImageVector = Icons.Filled.Checklist
    val Flights: ImageVector = Icons.Filled.Flight
    val HealthGuidance: ImageVector = Icons.Filled.HealthAndSafety
    val Map: ImageVector = Icons.Filled.Map
    val OfficialAuthority: ImageVector = Icons.Filled.AccountBalance
    val OfflineWifi: ImageVector = Icons.Filled.WifiOff
    val Translation: ImageVector = Icons.Filled.Translate
    val VerifiedLink: ImageVector = Icons.Filled.VerifiedUser
    val AiAssistant: ImageVector = Icons.Filled.AutoAwesome

    @Composable
    fun customs(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_fact_check)

    @Composable
    fun passport(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_badge)

    @Composable
    fun vaccinations(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_vaccines)
}
