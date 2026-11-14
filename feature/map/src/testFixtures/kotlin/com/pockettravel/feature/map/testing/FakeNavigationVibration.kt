package com.pockettravel.feature.map.testing

import com.pockettravel.feature.map.NavigationVibration
import com.pockettravel.feature.map.TurnType

/** Vibrazioni finte: le svolte e gli arrivi in ordine ("ARRIVED" per l'arrivo). */
class FakeNavigationVibration : NavigationVibration {
    val events = mutableListOf<String>()

    override fun turn(type: TurnType) {
        events += type.name
    }

    override fun arrived() {
        events += "ARRIVED"
    }
}
