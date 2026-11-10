package com.pockettravel.app.navigation.graph

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.pockettravel.app.navigation.OnboardingRoute
import com.pockettravel.app.navigation.RegionsRoute
import com.pockettravel.app.navigation.TutorialRoute
import com.pockettravel.app.onboarding.OnboardingScreen
import com.pockettravel.app.onboarding.OnboardingViewModel

internal fun NavGraphBuilder.onboardingScreen(navController: NavHostController, viewModel: OnboardingViewModel) {
    composable<OnboardingRoute> {
        OnboardingScreen(
            viewModel = viewModel,
            // Un secondo tocco su "Inizia" durante la transizione non impila un'altra lista Regioni.
            onComplete = dropUnlessResumed {
                navController.navigate(RegionsRoute) {
                    popUpTo(OnboardingRoute) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
}

// Lo stesso onboarding, riaperto da Altro: alla fine torna indietro.
internal fun NavGraphBuilder.tutorialScreen(navController: NavHostController, viewModel: OnboardingViewModel) {
    composable<TutorialRoute> {
        OnboardingScreen(
            viewModel = viewModel,
            onComplete = dropUnlessResumed { navController.popBackStack() },
        )
    }
}
