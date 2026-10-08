package com.pockettravel.app.licenses

import androidx.annotation.StringRes
import com.pockettravel.app.R

// Registro delle licenze di terze parti: solo componenti realmente in uso nell'app, tenuto
// sincronizzato a mano con gradle/libs.versions.toml e i moduli che li dichiarano. Contiene solo
// i dati mostrati nell'app, senza note di verifica. I testi stanno in strings.xml (italiano e inglese,
// mostrati nella lingua dell'app); le diciture di attribuzione delle licenze restano nella forma richiesta.
data class LicenseEntry(
    @StringRes val component: Int,
    @StringRes val license: Int,
    @StringRes val note: Int? = null,
)

val thirdPartyLicenses: List<LicenseEntry> = listOf(
    LicenseEntry(R.string.licenses_component_maplibre, R.string.licenses_name_bsd2),
    LicenseEntry(R.string.licenses_component_brouter, R.string.licenses_name_mit),
    LicenseEntry(R.string.licenses_component_planetiler, R.string.licenses_name_apache),
    LicenseEntry(R.string.licenses_component_jetpack, R.string.licenses_name_apache),
    LicenseEntry(R.string.licenses_component_kotlinx_serialization, R.string.licenses_name_apache),
    LicenseEntry(R.string.licenses_component_llama_cpp, R.string.licenses_name_mit, R.string.licenses_note_llama_cpp),
    LicenseEntry(R.string.licenses_component_nlohmann_json, R.string.licenses_name_mit),
    LicenseEntry(R.string.licenses_component_subprocess, R.string.licenses_name_unlicense),
    LicenseEntry(R.string.licenses_component_cpp_httplib, R.string.licenses_name_mit),
    LicenseEntry(R.string.licenses_component_pmtiles_reader, R.string.licenses_name_bsd3),
    LicenseEntry(R.string.licenses_component_xz, R.string.licenses_name_0bsd),
    LicenseEntry(R.string.licenses_component_protomaps, R.string.licenses_name_bsd3),
    LicenseEntry(R.string.licenses_component_natural_earth, R.string.licenses_name_public_domain, R.string.licenses_note_natural_earth),
    LicenseEntry(R.string.licenses_component_airports, R.string.licenses_name_airports, R.string.licenses_note_airports),
    LicenseEntry(R.string.licenses_component_flags, R.string.licenses_name_flags, R.string.licenses_note_flags),
    LicenseEntry(R.string.licenses_component_material_symbols, R.string.licenses_name_apache, R.string.licenses_note_material_symbols),
    LicenseEntry(R.string.licenses_component_noto_sans, R.string.licenses_name_ofl, R.string.licenses_note_noto_sans),
    LicenseEntry(R.string.licenses_component_osm, R.string.licenses_name_odbl, R.string.licenses_note_osm),
    LicenseEntry(R.string.licenses_component_wikivoyage, R.string.licenses_name_cc_by_sa, R.string.licenses_note_wikivoyage),
    LicenseEntry(R.string.licenses_component_wikipedia, R.string.licenses_name_cc_by_sa, R.string.licenses_note_wikipedia),
    LicenseEntry(R.string.licenses_component_wikidata, R.string.licenses_name_cc0, R.string.licenses_note_wikidata),
    LicenseEntry(R.string.licenses_component_weather, R.string.licenses_name_cc_by, R.string.licenses_note_weather),
    LicenseEntry(R.string.licenses_component_emergency_canada, R.string.licenses_name_ogl_canada, R.string.licenses_note_emergency_canada),
    LicenseEntry(R.string.licenses_component_emergency_uk, R.string.licenses_name_ogl_uk, R.string.licenses_note_emergency_uk),
    LicenseEntry(R.string.licenses_component_travel_advice, R.string.licenses_name_ogl_canada, R.string.licenses_note_travel_advice),
    LicenseEntry(R.string.licenses_component_vaccinations_canada, R.string.licenses_name_ogl_canada, R.string.licenses_note_vaccinations_canada),
    LicenseEntry(R.string.licenses_component_vaccinations_uk, R.string.licenses_name_ogl_uk, R.string.licenses_note_vaccinations_uk),
)
