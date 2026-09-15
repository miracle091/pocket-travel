package com.pockettravel.app.navigation

object PocketTravelDestinations {
    const val ONBOARDING = "onboarding"
    const val TUTORIAL = "tutorial"
    const val REGIONS = "regions"
    const val STORAGE = "storage"
    const val SOURCES = "sources"
    const val LICENSES = "licenses"
    const val VAULT = "vault"

    const val ARG_REGION_ID = "regionId"
    const val ARG_TAB = "tab"
    const val REGION_HUB_PATTERN = "region/{$ARG_REGION_ID}?tab={$ARG_TAB}"

    fun regionHub(regionId: String, tab: String = "guide") = "region/$regionId?tab=$tab"
}
