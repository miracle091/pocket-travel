package com.pockettravel.core.data

data class OfficialSource(
    val name: String,
    val url: String,
)

val officialSourcesRegistry = listOf(
    OfficialSource("Farnesina — Viaggiare Sicuri", "https://www.viaggiaresicuri.it"),
    OfficialSource("OMS — International Travel and Health", "https://www.who.int/travel-advice"),
    OfficialSource("Agenzia delle Dogane e dei Monopoli", "https://www.adm.gov.it"),
)

/** Fonte più pertinente da citare nel banner "Verifica sempre sulla fonte ufficiale". */
fun officialSourceFor(category: GuideCategory): OfficialSource = when (category) {
    GuideCategory.SALUTE -> officialSourcesRegistry.first { it.name.startsWith("OMS") }
    GuideCategory.DOGANE -> officialSourcesRegistry.first { it.name.startsWith("Agenzia delle Dogane") }
    else -> officialSourcesRegistry.first { it.name.startsWith("Farnesina") }
}
