package com.pockettravel.app.licenses

import androidx.annotation.StringRes
import com.pockettravel.app.R

// Registro delle licenze di terze parti: solo componenti realmente in uso nell'app, tenuto
// sincronizzato a mano con gradle/libs.versions.toml e i moduli che li dichiarano. Contiene solo
// i dati mostrati nell'app, senza note di verifica. I testi stanno in strings.xml (italiano e inglese,
// mostrati nella lingua dell'app); le diciture di attribuzione delle licenze restano nella forma richiesta.
data class LicenseEntry(
    @StringRes val component: Int,
    val license: License,
    @StringRes val note: Int? = null,
)

/** Nome della licenza e pagina con il suo testo (null per pubblico dominio e licenze miste, senza un testo unico). */
data class License(@StringRes val name: Int, val url: String?)

private val MIT = License(R.string.licenses_name_mit, "https://opensource.org/license/mit")
private val APACHE_2 = License(R.string.licenses_name_apache, "https://www.apache.org/licenses/LICENSE-2.0")
private val BSD_2 = License(R.string.licenses_name_bsd2, "https://opensource.org/license/bsd-2-clause")
private val BSD_3 = License(R.string.licenses_name_bsd3, "https://opensource.org/license/bsd-3-clause")
private val UNLICENSE = License(R.string.licenses_name_unlicense, "https://unlicense.org/")
private val ZERO_BSD = License(R.string.licenses_name_0bsd, "https://opensource.org/license/0bsd")
private val OFL_1_1 = License(R.string.licenses_name_ofl, "https://openfontlicense.org/open-font-license-official-text/")
private val ODBL_1_0 = License(R.string.licenses_name_odbl, "https://opendatacommons.org/licenses/odbl/1-0/")
private val CC_BY_SA_4 = License(R.string.licenses_name_cc_by_sa, "https://creativecommons.org/licenses/by-sa/4.0/")
private val CC0_1 = License(R.string.licenses_name_cc0, "https://creativecommons.org/publicdomain/zero/1.0/")
private val CC_BY_4 = License(R.string.licenses_name_cc_by, "https://creativecommons.org/licenses/by/4.0/")
private val OGL_CANADA = License(R.string.licenses_name_ogl_canada, "https://open.canada.ca/en/open-government-licence-canada")
private val OGL_UK_3 = License(
    R.string.licenses_name_ogl_uk,
    "https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/",
)

val thirdPartyLicenses: List<LicenseEntry> = listOf(
    LicenseEntry(R.string.licenses_component_maplibre, BSD_2),
    LicenseEntry(R.string.licenses_component_brouter, MIT),
    LicenseEntry(R.string.licenses_component_planetiler, APACHE_2),
    LicenseEntry(R.string.licenses_component_jetpack, APACHE_2),
    LicenseEntry(R.string.licenses_component_kotlinx_serialization, APACHE_2),
    LicenseEntry(R.string.licenses_component_llama_cpp, MIT, R.string.licenses_note_llama_cpp),
    LicenseEntry(R.string.licenses_component_nlohmann_json, MIT),
    LicenseEntry(R.string.licenses_component_subprocess, UNLICENSE),
    LicenseEntry(R.string.licenses_component_cpp_httplib, MIT),
    LicenseEntry(R.string.licenses_component_pmtiles_reader, BSD_3),
    LicenseEntry(R.string.licenses_component_xz, ZERO_BSD),
    LicenseEntry(R.string.licenses_component_protomaps, BSD_3),
    LicenseEntry(
        R.string.licenses_component_natural_earth,
        License(R.string.licenses_name_public_domain, url = null),
        R.string.licenses_note_natural_earth,
    ),
    LicenseEntry(R.string.licenses_component_airports, License(R.string.licenses_name_airports, url = null), R.string.licenses_note_airports),
    LicenseEntry(R.string.licenses_component_flags, License(R.string.licenses_name_flags, url = null), R.string.licenses_note_flags),
    LicenseEntry(R.string.licenses_component_material_symbols, APACHE_2, R.string.licenses_note_material_symbols),
    LicenseEntry(R.string.licenses_component_noto_sans, OFL_1_1, R.string.licenses_note_noto_sans),
    LicenseEntry(R.string.licenses_component_osm, ODBL_1_0, R.string.licenses_note_osm),
    LicenseEntry(R.string.licenses_component_wikivoyage, CC_BY_SA_4, R.string.licenses_note_wikivoyage),
    LicenseEntry(R.string.licenses_component_wikipedia, CC_BY_SA_4, R.string.licenses_note_wikipedia),
    LicenseEntry(R.string.licenses_component_wikidata, CC0_1, R.string.licenses_note_wikidata),
    LicenseEntry(R.string.licenses_component_weather, CC_BY_4, R.string.licenses_note_weather),
    LicenseEntry(R.string.licenses_component_emergency_canada, OGL_CANADA, R.string.licenses_note_emergency_canada),
    LicenseEntry(R.string.licenses_component_emergency_uk, OGL_UK_3, R.string.licenses_note_emergency_uk),
    LicenseEntry(R.string.licenses_component_travel_advice, OGL_CANADA, R.string.licenses_note_travel_advice),
    LicenseEntry(R.string.licenses_component_vaccinations_canada, OGL_CANADA, R.string.licenses_note_vaccinations_canada),
    LicenseEntry(R.string.licenses_component_vaccinations_uk, OGL_UK_3, R.string.licenses_note_vaccinations_uk),
)
