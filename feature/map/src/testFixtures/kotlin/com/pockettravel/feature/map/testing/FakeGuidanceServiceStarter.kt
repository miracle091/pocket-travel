package com.pockettravel.feature.map.testing

import com.pockettravel.feature.map.GuidanceServiceStarter

class FakeGuidanceServiceStarter : GuidanceServiceStarter {
    var starts = 0
        private set

    override fun start() {
        starts++
    }
}
