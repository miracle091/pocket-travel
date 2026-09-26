@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.pockettravel.app.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

// Motion M3 "container transform" tra la riga di una regione nell'elenco e il suo hub: la riga si
// allarga fino a diventare la schermata. Gli scope arrivano per CompositionLocal perche' riga e hub
// stanno in schermate diverse del NavHost.
internal val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
private val LocalRegionContainerScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

// Da usare solo nelle destinazioni del NavHost fra cui c'e' la trasformazione (elenco compatto e
// hub): nel layout lista-dettaglio riga e hub sono visibili insieme e non va applicata.
@Composable
internal fun RegionContainerTransformScope(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRegionContainerScope provides scope, content = content)
}

@Composable
internal fun Modifier.regionContainer(regionId: String): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val visibilityScope = LocalRegionContainerScope.current ?: return this
    return with(sharedScope) {
        this@regionContainer.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "region-container-$regionId"),
            animatedVisibilityScope = visibilityScope,
        )
    }
}
