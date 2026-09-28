package com.pockettravel.app.navigation

import android.net.Uri

object PocketTravelDestinations {
    const val ONBOARDING = "onboarding"
    const val TUTORIAL = "tutorial"
    const val REGIONS = "regions"
    const val STORAGE = "storage"
    const val SOURCES = "sources"
    const val LICENSES = "licenses"
    const val VAULT = "vault"
    const val NOTES = "notes"
    const val MORE = "more"

    const val ARG_REGION_ID = "regionId"
    const val ARG_TAB = "tab"
    const val REGION_HUB_PATTERN = "region/{$ARG_REGION_ID}?tab={$ARG_TAB}"

    fun regionHub(regionId: String, tab: String = "guide") = "region/$regionId?tab=$tab"

    const val ARG_URL = "url"
    const val ARG_TITLE = "title"
    const val IN_APP_BROWSER_PATTERN = "browser?$ARG_URL={$ARG_URL}&$ARG_TITLE={$ARG_TITLE}"

    // url/title vanno con URL-encoding esplicito: contengono ':' '/' '?' '&', tutti caratteri che
    // altrimenti spezzerebbero il parsing della rotta di Navigation Compose.
    fun inAppBrowser(url: String, title: String) =
        "browser?$ARG_URL=${Uri.encode(url)}&$ARG_TITLE=${Uri.encode(title)}"

    const val ARG_NAME = "name"
    const val REGION_PREVIEW_PATTERN = "region-preview/{$ARG_REGION_ID}?$ARG_NAME={$ARG_NAME}"

    fun regionPreview(regionId: String, displayName: String) =
        "region-preview/$regionId?$ARG_NAME=${Uri.encode(displayName)}"

    // lat/lon come stringhe: un Float di Navigation perderebbe precisione (metri) sulle coordinate.
    // Stessi nomi degli argomenti letti da NavigationViewModel (SavedStateHandle).
    const val ARG_LATITUDE = "lat"
    const val ARG_LONGITUDE = "lon"
    const val NAVIGATION_PATTERN = "navigation/{$ARG_REGION_ID}?$ARG_LATITUDE={$ARG_LATITUDE}&$ARG_LONGITUDE={$ARG_LONGITUDE}&$ARG_NAME={$ARG_NAME}"

    fun navigation(regionId: String, latitude: Double, longitude: Double, name: String) =
        "navigation/$regionId?$ARG_LATITUDE=$latitude&$ARG_LONGITUDE=$longitude&$ARG_NAME=${Uri.encode(name)}"
}
