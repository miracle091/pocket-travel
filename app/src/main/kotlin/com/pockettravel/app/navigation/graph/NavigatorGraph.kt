package com.pockettravel.app.navigation.graph

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.pockettravel.app.navigation.NavigatorRoute
import com.pockettravel.app.navigation.RegionsRoute
import com.pockettravel.app.navigation.navigateTopLevel
import com.pockettravel.app.navigation.topLevelEnter
import com.pockettravel.app.navigation.topLevelExit
import com.pockettravel.app.navigation.topLevelPopEnter
import com.pockettravel.app.regions.GlobalNavigatorScreen

internal fun NavGraphBuilder.navigatorScreen(navController: NavHostController) {
    composable<NavigatorRoute>(enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) {
        GlobalNavigatorScreen(onOpenCountries = { navController.navigateTopLevel(RegionsRoute) })
    }
}
