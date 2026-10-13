package com.pockettravel.feature.map.testing

import com.pockettravel.feature.map.DepartureReminderScheduler
import java.time.LocalDateTime

class FakeDepartureReminderScheduler : DepartureReminderScheduler {
    val scheduled = mutableListOf<Pair<LocalDateTime, String>>()
    var cancellations = 0
        private set

    override fun schedule(departure: LocalDateTime, destinationName: String) {
        scheduled += departure to destinationName
    }

    override fun cancel() {
        cancellations++
    }
}
