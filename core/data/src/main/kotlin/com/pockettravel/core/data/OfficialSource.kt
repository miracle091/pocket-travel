package com.pockettravel.core.data

/** A cosa serve una fonte, per scegliere quella da citare nel banner dell'assistente ([officialSourceFor]). */
enum class OfficialSourceTopic { TRAVEL_ADVICE, HEALTH, CUSTOMS, OTHER }

data class OfficialSource(
    val name: String,
    val url: String,
    // A cosa serve, mostrato sotto il nome nel registro delle fonti.
    val description: String,
    // Paesi (ISO 3166-1 alpha-2) dei cui cittadini e' la fonte; vuoto per le fonti internazionali,
    // mostrate a tutti.
    val countries: Set<String> = emptySet(),
    val topic: OfficialSourceTopic = OfficialSourceTopic.OTHER,
)

private val EU = setOf(
    "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU", "IE", "IT", "LT", "LU",
    "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK",
)

// URL verificati a mano il 2026-09-28 (codice e titolo della pagina; travel.state.gov, step.state.gov
// ed eda.admin.ch rispondono 403 ai client non browser, smartraveller.gov.au non risponde fuori
// dall'Australia: indirizzi ufficiali noti).
val officialSourcesRegistry = listOf(
    // Internazionali: per chiunque, qualunque sia la nazionalita'.
    OfficialSource("OMS — International Travel and Health", "https://www.who.int/travel-advice", "Salute e vaccinazioni per chi viaggia", topic = OfficialSourceTopic.HEALTH),
    OfficialSource("CDC — Travelers' Health", "https://wwwnc.cdc.gov/travel", "Rischi sanitari e vaccini consigliati per destinazione", topic = OfficialSourceTopic.HEALTH),
    // Italia
    OfficialSource("Farnesina — Viaggiare Sicuri", "https://www.viaggiaresicuri.it", "Sicurezza, documenti e ingresso paese per paese", setOf("IT"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Farnesina — Dove siamo nel mondo", "https://www.dovesiamonelmondo.it", "Segnala il viaggio per essere rintracciato in un'emergenza", setOf("IT")),
    OfficialSource("Polizia di Stato — Passaporto", "https://www.poliziadistato.it/articolo/passaporto", "Come richiedere o rinnovare il passaporto", setOf("IT")),
    OfficialSource("ENAC — Diritti dei passeggeri", "https://www.enac.gov.it/passeggeri", "Ritardi, cancellazioni e bagagli nei voli", setOf("IT")),
    OfficialSource("Agenzia delle Dogane e dei Monopoli", "https://www.adm.gov.it", "Cosa si può portare in valigia e franchigie", setOf("IT"), OfficialSourceTopic.CUSTOMS),
    // Unione europea
    OfficialSource("Your Europe — Viaggiare", "https://europa.eu/youreurope/citizens/travel/index_it.htm", "Documenti, diritti e regole per viaggiare nell'UE", EU),
    OfficialSource(
        "Your Europe — Cure mediche nell'UE",
        "https://europa.eu/youreurope/citizens/health/unplanned-healthcare/temporary-stays/index_it.htm",
        "Tessera sanitaria europea e cure durante un soggiorno breve",
        EU,
    ),
    // Altri paesi: consigli di viaggio e registrazione dei viaggi del ministero degli esteri.
    OfficialSource("Auswärtiges Amt — Reise- und Sicherheitshinweise", "https://www.auswaertiges-amt.de/de/reiseundsicherheit", "Sicurezza e ingresso paese per paese", setOf("DE"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Auswärtiges Amt — Krisenvorsorgeliste", "https://krisenvorsorgeliste.diplo.de", "Segnala il viaggio per essere rintracciato in un'emergenza", setOf("DE")),
    OfficialSource("France Diplomatie — Conseils aux voyageurs", "https://www.diplomatie.gouv.fr/fr/conseils-aux-voyageurs/", "Sicurezza e ingresso paese per paese", setOf("FR"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("France Diplomatie — Fil d'Ariane", "https://fildariane.diplomatie.gouv.fr", "Segnala il viaggio per essere rintracciato in un'emergenza", setOf("FR")),
    OfficialSource("Exteriores — Recomendaciones de viaje", "https://www.exteriores.gob.es/es/ServiciosAlCiudadano/Paginas/Recomendaciones-de-viaje.aspx", "Sicurezza e ingresso paese per paese", setOf("ES"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Exteriores — Registro de Viajeros", "https://registroviajeros.exteriores.gob.es", "Segnala il viaggio per essere rintracciato in un'emergenza", setOf("ES")),
    OfficialSource("Nederland Wereldwijd — Reisadvies", "https://www.nederlandwereldwijd.nl/reisadvies", "Sicurezza e ingresso paese per paese", setOf("NL"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Diplomatie.be — Conseils aux voyageurs", "https://diplomatie.belgium.be/fr/pays", "Sicurezza e ingresso paese per paese", setOf("BE"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("DFAE — Consigli di viaggio", "https://www.eda.admin.ch/eda/it/dfae/rappresentanze-e-consigli-di-viaggio.html", "Sicurezza e ingresso paese per paese", setOf("CH"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("GOV.UK — Foreign travel advice", "https://www.gov.uk/foreign-travel-advice", "Sicurezza e ingresso paese per paese", setOf("GB"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("U.S. Department of State — Travel Advisories", "https://travel.state.gov", "Sicurezza e ingresso paese per paese", setOf("US"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("U.S. Department of State — STEP", "https://step.state.gov", "Segnala il viaggio per essere rintracciato in un'emergenza", setOf("US")),
    OfficialSource("Travel.gc.ca — Travel advice", "https://travel.gc.ca/travelling/advisories", "Sicurezza e ingresso paese per paese", setOf("CA"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Travel.gc.ca — Registration", "https://travel.gc.ca/travelling/registration", "Segnala il viaggio per essere rintracciato in un'emergenza", setOf("CA")),
    OfficialSource("Smartraveller", "https://www.smartraveller.gov.au", "Sicurezza e ingresso paese per paese", setOf("AU"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("SafeTravel", "https://www.safetravel.govt.nz", "Sicurezza e ingresso paese per paese", setOf("NZ"), OfficialSourceTopic.TRAVEL_ADVICE),
)

/** Fonti internazionali: nel registro per chiunque, qualunque sia la nazionalita'. */
val globalOfficialSources: List<OfficialSource> = officialSourcesRegistry.filter { it.countries.isEmpty() }

/** Fonti del paese di chi viaggia (comprese quelle dell'UE per i cittadini europei); vuota se non ne abbiamo. */
fun nationalOfficialSources(nationality: String?): List<OfficialSource> =
    officialSourcesRegistry.filter { nationality != null && nationality in it.countries }

/**
 * Fonte più pertinente da citare nel banner "Verifica sempre sulla fonte ufficiale": salute sempre
 * dall'OMS; dogane e il resto dal paese di chi viaggia (null se non ne abbiamo una per quel tema).
 */
fun officialSourceFor(category: GuideCategory, nationality: String?): OfficialSource? {
    val national = nationalOfficialSources(nationality)
    return when (category) {
        GuideCategory.SALUTE -> globalOfficialSources.first { it.topic == OfficialSourceTopic.HEALTH }
        GuideCategory.DOGANE -> national.firstOrNull { it.topic == OfficialSourceTopic.CUSTOMS }
            ?: national.firstOrNull { it.topic == OfficialSourceTopic.TRAVEL_ADVICE }
        else -> national.firstOrNull { it.topic == OfficialSourceTopic.TRAVEL_ADVICE }
    }
}
