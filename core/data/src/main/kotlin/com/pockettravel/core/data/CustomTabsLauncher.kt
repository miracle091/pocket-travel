package com.pockettravel.core.data

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Apre un link esterno in Chrome Custom Tabs — mai in una WebView/iframe incorporata,
 * mai cacheato oltre la sessione.
 */
object CustomTabsLauncher {
    fun open(context: Context, url: String) {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    }
}
