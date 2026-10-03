package com.pockettravel.app.navigation

import kotlinx.serialization.Serializable

// Rotte type-safe di Navigation Compose: gli argomenti sono proprieta' tipizzate e la libreria li
// codifica da sola nella rotta (url e titolo del browser contengono ':' '/' '?' '&').

@Serializable data object OnboardingRoute

@Serializable data object TutorialRoute

@Serializable data object RegionsRoute

@Serializable data object StorageRoute

@Serializable data object SourcesRoute

@Serializable data object LicensesRoute

@Serializable data object VaultRoute

@Serializable data object MoreRoute

@Serializable data object NavigatorRoute

@Serializable data object SettingsRoute

@Serializable data class RegionHubRoute(val regionId: String, val tab: String = "guide")

@Serializable data class RegionPreviewRoute(val regionId: String, val name: String = "")

@Serializable data class InAppBrowserRoute(val url: String, val title: String = "")
