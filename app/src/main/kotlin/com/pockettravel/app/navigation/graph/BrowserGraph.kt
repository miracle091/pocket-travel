package com.pockettravel.app.navigation.graph

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.pockettravel.app.browser.InAppBrowserScreen
import com.pockettravel.app.navigation.InAppBrowserRoute

internal fun NavGraphBuilder.inAppBrowserScreen(navController: NavHostController) {
    composable<InAppBrowserRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<InAppBrowserRoute>()
        InAppBrowserScreen(url = route.url, title = route.title, onBack = dropUnlessResumed { navController.popBackStack() })
    }
}
