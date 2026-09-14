package com.pockettravel.feature.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSettingsStoreDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetPreferences() {
        context.deleteSharedPreferences("ai_online_settings_v2")
    }

    @Test
    fun apiKeyRoundTripPersistsAndClears() {
        val store = AiSettingsStore(context)
        store.setApiKey("test-api-key")

        assertEquals("test-api-key", AiSettingsStore(context).apiKey())
        assertFalse(
            context.getSharedPreferences("ai_online_settings_v2", Context.MODE_PRIVATE)
                .all.values.any { it == "test-api-key" },
        )

        store.clearApiKey()
        assertNull(AiSettingsStore(context).apiKey())
    }

}
