package com.pockettravel.core.data

data class OfficialSource(
    val name: String,
    val url: String,
    // A cosa serve, mostrato sotto il nome nel registro delle fonti.
    val description: String,
)

// URL verificati a mano (codice e titolo della pagina) il 2026-09-28.
val officialSourcesRegistry = listOf(
    OfficialSource("Farnesina — Viaggiare Sicuri", "https://www.viaggiaresicuri.it", "Sicurezza, documenti e ingresso paese per paese"),
    OfficialSource("Farnesina — Dove siamo nel mondo", "https://www.dovesiamonelmondo.it", "Segnala il viaggio per essere rintracciato in un'emergenza"),
    OfficialSource("Polizia di Stato — Passaporto", "https://www.poliziadistato.it/articolo/passaporto", "Come richiedere o rinnovare il passaporto"),
    OfficialSource("Your Europe — Viaggiare", "https://europa.eu/youreurope/citizens/travel/index_it.htm", "Documenti, diritti e regole per viaggiare nell'UE"),
    OfficialSource(
        "Your Europe — Cure mediche nell'UE",
        "https://europa.eu/youreurope/citizens/health/unplanned-healthcare/temporary-stays/index_it.htm",
        "Tessera sanitaria europea e cure durante un soggiorno breve",
    ),
    OfficialSource("ENAC — Diritti dei passeggeri", "https://www.enac.gov.it/passeggeri", "Ritardi, cancellazioni e bagagli nei voli"),
    OfficialSource("OMS — International Travel and Health", "https://www.who.int/travel-advice", "Salute e vaccinazioni per chi viaggia"),
    OfficialSource("CDC — Travelers' Health", "https://wwwnc.cdc.gov/travel", "Rischi sanitari e vaccini consigliati per destinazione"),
    OfficialSource("Agenzia delle Dogane e dei Monopoli", "https://www.adm.gov.it", "Cosa si può portare in valigia e franchigie"),
)

/** Fonte più pertinente da citare nel banner "Verifica sempre sulla fonte ufficiale". */
fun officialSourceFor(category: GuideCategory): OfficialSource = when (category) {
    GuideCategory.SALUTE -> officialSourcesRegistry.first { it.name.startsWith("OMS") }
    GuideCategory.DOGANE -> officialSourcesRegistry.first { it.name.startsWith("Agenzia delle Dogane") }
    else -> officialSourcesRegistry.first { it.name.startsWith("Farnesina — Viaggiare") }
}
