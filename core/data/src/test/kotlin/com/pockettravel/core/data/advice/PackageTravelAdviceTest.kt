package com.pockettravel.core.data.advice

import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PackageTravelAdviceTest {

    private fun section(url: String, body: String) = GuideSection("spagna", GuideCategory.SICUREZZA, "Titolo", body, url)

    @Test
    fun readsSlugAndDayFromTheFooterOfTheFcdoSections() {
        val sections = listOf(
            section("https://www.wikivoyage.org/x", "Altro."),
            section(
                "https://www.gov.uk/foreign-travel-advice/spain/safety-and-security",
                "Testo.\n\nLast updated by the UK government: October 17, 2026.",
            ),
        )

        assertEquals(PackageTravelAdvice("spain", LocalDate.of(2026, 10, 17)), packageTravelAdvice(sections, "2026.10.24.0300"))
    }

    @Test
    fun slugComesAlsoFromTheBritishOnlySections() {
        val sections = listOf(
            section("https://www.gov.uk/foreign-travel-advice/spain/entry-requirements#for-nationality=GB", "Last updated by the UK government: May 2, 2026."),
        )

        assertEquals(PackageTravelAdvice("spain", LocalDate.of(2026, 5, 2)), packageTravelAdvice(sections, null))
    }

    @Test
    fun fallsBackToTheGuidesVersionDayWhenTheFooterIsMissingOrTranslated() {
        val sections = listOf(section("https://www.gov.uk/foreign-travel-advice/spain/health", "Ultimo aggiornamento del governo britannico: 17 ottobre 2026."))

        assertEquals(PackageTravelAdvice("spain", LocalDate.of(2026, 10, 24)), packageTravelAdvice(sections, "2026.10.24.0300"))
    }

    @Test
    fun nothingWithoutFcdoSectionsOrWithoutAnyDate() {
        assertNull(packageTravelAdvice(listOf(section("https://travel.gc.ca/destinations/es", "Last updated by the Government of Canada: May 2, 2026.")), "2026.10.24.0300"))
        assertNull(packageTravelAdvice(listOf(section("https://www.gov.uk/foreign-travel-advice/spain/health", "Testo.")), "versione"))
    }
}
