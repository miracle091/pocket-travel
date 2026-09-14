package com.pockettravel.core.content

import com.pockettravel.core.data.GuideSection

interface WikivoyageImporter {
    fun parse(regionId: String, sourceUrl: String, dumpText: String): List<GuideSection>
}
