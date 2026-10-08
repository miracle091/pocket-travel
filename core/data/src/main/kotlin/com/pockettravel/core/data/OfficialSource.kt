package com.pockettravel.core.data

import androidx.annotation.StringRes

/** A cosa serve una fonte, per scegliere quella da citare nel banner dell'assistente ([officialSourceFor]). */
enum class OfficialSourceTopic { TRAVEL_ADVICE, HEALTH, CUSTOMS, OTHER }

data class OfficialSource(
    val name: String,
    val url: String,
    // A cosa serve, mostrato sotto il nome nel registro delle fonti (nella lingua dell'interfaccia).
    @StringRes val description: Int,
    // Paesi (ISO 3166-1 alpha-2) dei cui cittadini e' la fonte; vuoto per le fonti internazionali,
    // mostrate a tutti.
    val countries: Set<String> = emptySet(),
    val topic: OfficialSourceTopic = OfficialSourceTopic.OTHER,
)

private val EU = setOf(
    "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU", "IE", "IT", "LT", "LU",
    "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK",
)

// URL verificati a mano il 2026-09-28 e il 2026-10-02 (codice e titolo della pagina). Alcuni siti bloccano i client
// non browser (travel.state.gov, step.state.gov, eda.admin.ch, ireland.ie, regjeringen.no, mzv.sk, mae.ro, mfa.bg,
// keliauk.urm.lt, portales.sre.gob.mx) o non rispondono fuori dal paese (smartraveller.gov.au): per questi l'indirizzo
// e' confermato dai risultati di ricerca sui domini dei ministeri. Per i consigli di viaggio entrano solo i servizi con
// una pagina per ogni destinazione: i ministeri che pubblicano solo avvisi sui paesi a rischio (India, Brasile,
// Israele, Lettonia...) non direbbero nulla della maggior parte delle destinazioni, e per loro vale il ripiego qui
// sotto.
val officialSourcesRegistry = listOf(
    // Internazionali: per chiunque, qualunque sia la nazionalita'.
    OfficialSource("OMS — International Travel and Health", "https://www.who.int/travel-advice", R.string.source_desc_who, topic = OfficialSourceTopic.HEALTH),
    OfficialSource("CDC — Travelers' Health", "https://wwwnc.cdc.gov/travel", R.string.source_desc_cdc, topic = OfficialSourceTopic.HEALTH),
    // Italia
    OfficialSource("Farnesina — Viaggiare Sicuri", "https://www.viaggiaresicuri.it", R.string.source_desc_advice_documents, setOf("IT", "SM"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Farnesina — Dove siamo nel mondo", "https://www.dovesiamonelmondo.it", R.string.source_desc_register, setOf("IT")),
    OfficialSource("Polizia di Stato — Passaporto", "https://www.poliziadistato.it/articolo/passaporto", R.string.source_desc_passport, setOf("IT")),
    OfficialSource("ENAC — Diritti dei passeggeri", "https://www.enac.gov.it/passeggeri", R.string.source_desc_air_passengers, setOf("IT")),
    OfficialSource("Agenzia delle Dogane e dei Monopoli", "https://www.adm.gov.it", R.string.source_desc_customs, setOf("IT"), OfficialSourceTopic.CUSTOMS),
    // Unione europea
    OfficialSource("Your Europe — Viaggiare", "https://europa.eu/youreurope/citizens/travel/index_it.htm", R.string.source_desc_eu_travel, EU),
    OfficialSource(
        "Your Europe — Cure mediche nell'UE",
        "https://europa.eu/youreurope/citizens/health/unplanned-healthcare/temporary-stays/index_it.htm",
        R.string.source_desc_eu_health,
        EU,
    ),
    // Altri paesi: consigli di viaggio e registrazione dei viaggi del ministero degli esteri.
    OfficialSource("Auswärtiges Amt — Reise- und Sicherheitshinweise", "https://www.auswaertiges-amt.de/de/reiseundsicherheit", R.string.source_desc_advice, setOf("DE"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Auswärtiges Amt — Krisenvorsorgeliste", "https://krisenvorsorgeliste.diplo.de", R.string.source_desc_register, setOf("DE")),
    OfficialSource("France Diplomatie — Conseils aux voyageurs", "https://www.diplomatie.gouv.fr/fr/conseils-aux-voyageurs/", R.string.source_desc_advice, setOf("FR", "MC", "LU"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("France Diplomatie — Fil d'Ariane", "https://fildariane.diplomatie.gouv.fr", R.string.source_desc_register, setOf("FR")),
    OfficialSource("Exteriores — Recomendaciones de viaje", "https://www.exteriores.gob.es/es/ServiciosAlCiudadano/Paginas/Recomendaciones-de-viaje.aspx", R.string.source_desc_advice, setOf("ES"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Exteriores — Registro de Viajeros", "https://registroviajeros.exteriores.gob.es", R.string.source_desc_register, setOf("ES")),
    OfficialSource("Nederland Wereldwijd — Reisadvies", "https://www.nederlandwereldwijd.nl/reisadvies", R.string.source_desc_advice, setOf("NL"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Diplomatie.be — Conseils aux voyageurs", "https://diplomatie.belgium.be/fr/conseils-aux-voyageurs", R.string.source_desc_advice, setOf("BE"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("DFAE — Consigli di viaggio", "https://www.eda.admin.ch/eda/it/dfae/rappresentanze-e-consigli-di-viaggio.html", R.string.source_desc_advice, setOf("CH", "LI"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("GOV.UK — Foreign travel advice", "https://www.gov.uk/foreign-travel-advice", R.string.source_desc_advice, setOf("GB"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("U.S. Department of State — Travel Advisories", "https://travel.state.gov/en/international-travel/travel-advisories.html", R.string.source_desc_advice, setOf("US"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("U.S. Department of State — STEP", "https://step.state.gov", R.string.source_desc_register, setOf("US")),
    OfficialSource("Travel.gc.ca — Travel advice", "https://travel.gc.ca/travelling/advisories", R.string.source_desc_advice, setOf("CA"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Travel.gc.ca — Registration", "https://travel.gc.ca/travelling/registration", R.string.source_desc_register, setOf("CA")),
    OfficialSource("Smartraveller", "https://www.smartraveller.gov.au", R.string.source_desc_advice, setOf("AU"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("SafeTravel", "https://www.safetravel.govt.nz", R.string.source_desc_advice, setOf("NZ"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Ireland.ie — Travel advice", "https://www.ireland.ie/en/dfa/overseas-travel/advice/", R.string.source_desc_advice, setOf("IE"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("BMEIA — Reiseinformation", "https://www.bmeia.gv.at/reise-services/reiseinformation", R.string.source_desc_advice, setOf("AT"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Portal Diplomático — Viajar", "https://portaldiplomatico.mne.gov.pt/", R.string.source_desc_advice, setOf("PT"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Regeringen — UD:s reseinformation", "https://www.regeringen.se/uds-reseinformation/", R.string.source_desc_advice, setOf("SE"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Udenrigsministeriet — Rejsevejledninger", "https://um.dk/rejse-og-ophold/rejse-til-udlandet/rejsevejledninger", R.string.source_desc_advice, setOf("DK"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Regjeringen — UDs reiseinformasjon", "https://www.regjeringen.no/no/tema/utenrikssaker/reiseinformasjon/id2413163/", R.string.source_desc_advice, setOf("NO"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Ulkoministeriö — Matkustustiedotteet", "https://um.fi/matkustustiedotteet-a-o", R.string.source_desc_advice, setOf("FI"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("外務省 海外安全ホームページ", "https://www.anzen.mofa.go.jp/", R.string.source_desc_advice, setOf("JP"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("MSZ — Odyseusz", "https://odyseusz.gov.pl/", R.string.source_desc_advice, setOf("PL"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("MZV — Státy světa, informace na cesty", "https://mzv.gov.cz/jnp/cz/encyklopedie_statu/index.html", R.string.source_desc_advice, setOf("CZ"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("MZVEZ — Cestovné odporúčania podľa krajín", "https://www.mzv.sk/sk/staty/cestovne-odporucania-podla-krajin", R.string.source_desc_advice, setOf("SK"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Konzuli Szolgálat — Utazási tanácsok országonként", "https://konzinfo.mfa.gov.hu/utazas/utazasi-tanacsok-orszagonkent", R.string.source_desc_advice, setOf("HU"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("GOV.SI — Varnost potovanj", "https://www.gov.si/teme/varnost-potovanj/seznam-drzav/", R.string.source_desc_advice, setOf("SI"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("MVEP — Hrvatski konzularni portal", "https://konzularniportal.mvep.hr/?lang=hr", R.string.source_desc_advice, setOf("HR"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("MAE — Condiții de călătorie", "https://www.mae.ro/travel-conditions", R.string.source_desc_advice, setOf("RO"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("МВнР — Пътувам за...", "https://www.mfa.bg/bg/embassyinfo", R.string.source_desc_advice, setOf("BG"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("URM — Keliauk saugiai", "https://keliauk.urm.lt/", R.string.source_desc_advice, setOf("LT"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Välisministeerium — Reisi Targalt", "https://reisitargalt.vm.ee/", R.string.source_desc_advice, setOf("EE"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("ConsularPlus — Travel advice", "https://consularplus.gov.mt/travel-advice", R.string.source_desc_advice, setOf("MT"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Gov.cy — Travel advice", "https://www.gov.cy/en/information/travel-advice/", R.string.source_desc_advice, setOf("CY"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("МСП — Визе и савети за путовања", "https://www.mfa.gov.rs/gradjani/putovanje-u-inostranstvo/vize-i-informacije-o-drzavama", R.string.source_desc_advice, setOf("RS"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("МЗС — Корисна інформація для подорожуючих", "https://tripadvisor.mfa.gov.ua/", R.string.source_desc_advice, setOf("UA"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("МИД России — Страны мира", "https://dskc.mid.ru/countries", R.string.source_desc_advice, setOf("RU"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("외교부 해외안전여행", "https://www.0404.go.kr/ntnSafetyInfo/list", R.string.source_desc_advice, setOf("KR"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("中国领事服务网 — 了解目的地", "https://cs.mfa.gov.cn/zggmcg/ljmdd/", R.string.source_desc_advice, setOf("CN"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("外交部領事事務局 — 旅遊警示", "https://www.boca.gov.tw/sp-trwa-list-1.html", R.string.source_desc_advice, setOf("TW"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("MFA Singapore — Travel advisories", "https://www.mfa.gov.sg/travelling-overseas/travel-advisories-notices-and-visa-information/", R.string.source_desc_advice, setOf("SG"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("กรมการกงสุล — Travel Advisory", "https://consular.mfa.go.th/th/publicservice/travel-advisory-2", R.string.source_desc_advice, setOf("TH"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("Kemlu — Safe Travel", "https://safetravel.kemlu.go.id/country-info", R.string.source_desc_advice, setOf("ID"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("SRE — Guía del Viajero", "https://portales.sre.gob.mx/guiadelviajero/index.php/informacion-por-destino", R.string.source_desc_advice, setOf("MX"), OfficialSourceTopic.TRAVEL_ADVICE),
    OfficialSource("MOFA UAE — Travel updates", "https://www.mofa.gov.ae/en/travel-updates", R.string.source_desc_advice, setOf("AE"), OfficialSourceTopic.TRAVEL_ADVICE),
)

/** Fonti internazionali: nel registro per chiunque, qualunque sia la nazionalita'. */
val globalOfficialSources: List<OfficialSource> = officialSourcesRegistry.filter { it.countries.isEmpty() }

/** Fonti del paese di chi viaggia (comprese quelle dell'UE per i cittadini europei); vuota se non ce ne sono. */
fun nationalOfficialSources(nationality: String?): List<OfficialSource> =
    officialSourcesRegistry.filter { nationality != null && nationality in it.countries }

/** Consigli di viaggio del ministero degli esteri del paese di chi viaggia, null se mancano. */
fun travelAdviceSourceFor(nationality: String?): OfficialSource? =
    nationalOfficialSources(nationality).firstOrNull { it.topic == OfficialSourceTopic.TRAVEL_ADVICE }

/**
 * Ripiego per chi non ha un servizio del proprio paese: GOV.UK copre 226 destinazioni, e' aggiornato di continuo,
 * gratuito e con licenza aperta. Scritto per i cittadini britannici: sicurezza e salute valgono per tutti, i
 * requisiti d'ingresso no, e la riga che lo mostra lo dice.
 */
val fallbackTravelAdviceSource: OfficialSource =
    officialSourcesRegistry.first { it.topic == OfficialSourceTopic.TRAVEL_ADVICE && "GB" in it.countries }

/**
 * Fonte più pertinente da citare nel banner "Verifica sempre sulla fonte ufficiale": salute sempre
 * dall'OMS; dogane e il resto dal paese di chi viaggia (null se manca una fonte per quel tema).
 */
fun officialSourceFor(category: GuideCategory, nationality: String?): OfficialSource? {
    val national = nationalOfficialSources(nationality)
    return when (category) {
        GuideCategory.SALUTE -> globalOfficialSources.first { it.topic == OfficialSourceTopic.HEALTH }
        GuideCategory.DOGANE -> national.firstOrNull { it.topic == OfficialSourceTopic.CUSTOMS }
            ?: national.firstOrNull { it.topic == OfficialSourceTopic.TRAVEL_ADVICE }
        else -> travelAdviceSourceFor(nationality)
    }
}
