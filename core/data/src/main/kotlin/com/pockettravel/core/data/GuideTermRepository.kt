package com.pockettravel.core.data

import com.pockettravel.core.data.db.GuideTermDao
import javax.inject.Inject

/** Termine locale di una guida, con la voce di Wikipedia che lo spiega ([title], [summary], [sourceUrl]). */
data class GuideTerm(
    val regionId: String,
    val term: String,
    val aliases: List<String>,
    val title: String,
    val summary: String,
    val sourceUrl: String,
)

class GuideTermRepository @Inject constructor(
    private val guideTermDao: GuideTermDao,
) {
    suspend fun termsFor(regionIds: List<String>): List<GuideTerm> =
        guideTermDao.termsForRegions(regionIds).map {
            GuideTerm(it.regionId, it.term, it.aliases.split("\n").filter(String::isNotEmpty), it.title, it.summary, it.sourceUrl)
        }
}
