package com.pockettravel.app.navigation.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.pockettravel.app.R
import com.pockettravel.app.navigation.VaultRoute
import com.pockettravel.app.navigation.topLevelEnter
import com.pockettravel.app.navigation.topLevelExit
import com.pockettravel.app.navigation.topLevelPopEnter
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ContextualHint
import com.pockettravel.feature.vault.DocumentsScreen

internal fun NavGraphBuilder.vaultScreen() {
    composable<VaultRoute>(enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) {
        // Il suggerimento sta sotto la barra di stato (inset consumato, DocumentsScreen non lo ripete).
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            ContextualHint(
                hintId = "vault",
                icon = AppIcons.Passport,
                title = stringResource(R.string.hint_vault_title),
                body = stringResource(R.string.hint_vault_body),
            )
            Box(modifier = Modifier.weight(1f)) { DocumentsScreen() }
        }
    }
}
