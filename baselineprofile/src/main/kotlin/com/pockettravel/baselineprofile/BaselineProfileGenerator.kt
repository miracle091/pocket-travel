package com.pockettravel.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Avvio a freddo dell'app fino alla prima schermata: il codice che gira qui entra nel baseline profile. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = "com.pockettravel.app", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }
}
