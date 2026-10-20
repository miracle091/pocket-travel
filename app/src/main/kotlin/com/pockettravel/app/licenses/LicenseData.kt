package com.pockettravel.app.licenses

import android.content.Context
import androidx.annotation.StringRes
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.pockettravel.app.R

// Le voci della schermata Licenze vengono da res/raw/aboutlibraries.json, generato al build dal plugin AboutLibraries
// (app/build.gradle.kts): le dipendenze Gradle dell'APK, transitive comprese, e le definizioni a mano di
// app/aboutlibraries/libraries per quello che Gradle non vede (codice nativo, BRouter, font, icone, modelli e dati),
// con i testi delle licenze di app/aboutlibraries/licenses. Qui solo gruppo, nomi e note nella lingua dell'app.

/** Sezione della schermata, dal campo `tag` delle definizioni a mano: senza tag la voce è software. */
enum class LicenseGroup(@StringRes val title: Int) {
    SOFTWARE(R.string.licenses_section_software),
    MODELS(R.string.licenses_section_models),
    DATA(R.string.licenses_section_data),
}

fun Library.group(): LicenseGroup = when (tag) {
    "models" -> LicenseGroup.MODELS
    "data" -> LicenseGroup.DATA
    else -> LicenseGroup.SOFTWARE
}

/**
 * Testi in strings.xml (italiano e inglese) per una voce: [name] al posto del nome della definizione, [license] al
 * posto dei nomi delle licenze nella riga dell'elenco (licenze miste), [note] sotto il nome.
 */
data class LicenseLabel(@StringRes val name: Int? = null, @StringRes val license: Int? = null, @StringRes val note: Int? = null)

fun Library.displayName(context: Context): String = licenseLabels[uniqueId]?.name?.let(context::getString) ?: name

/**
 * Licenze da mostrare. Il plugin unisce le licenze di una definizione a mano a quelle del POM con lo stesso
 * `uniqueId`: quando la definizione dà al componente i suoi testi, con l'avviso di copyright (`hash` diverso
 * dall'SPDX), le licenze generiche del POM (`hash` uguale all'SPDX) non si mostrano. Così si corregge anche un POM
 * con la licenza sbagliata (pmtiles-reader dichiara BSD-3-Clause, il suo LICENCE.txt è MIT).
 */
fun Library.shownLicenses(): List<License> {
    val ownTexts = licenses.any { it.spdxId != null && it.hash != it.spdxId }
    return licenses.filterNot { ownTexts && it.hash == it.spdxId }
}

/**
 * Testo di una licenza per lo schermo: unisce le righe che il testo originale spezza a 70–80 colonne, così ogni
 * capoverso va a capo alla larghezza dello schermo invece di alternare righe lunghe e corte. Titoli, voci di elenco,
 * righe di copyright, separatori e righe "Nome: valore" restano su righe proprie. Cambiano solo gli a capo.
 */
fun reflowLicenseText(text: String): String {
    val out = mutableListOf<String>()
    var previous = ""
    for (line in text.lines().map(String::trimEnd)) {
        if (out.isNotEmpty() && continuesLine(previous, line)) out[out.lastIndex] = out.last() + " " + line.trim() else out += line
        previous = line
    }
    return out.joinToString("\n")
}

/** Sotto questa lunghezza una riga è un titolo o la fine voluta di una riga, non un a capo per la larghezza. */
private const val WRAPPED_LINE_MIN_LENGTH = 50
private val listItem = Regex("""^(?:[-*•+]\s|\d+(?:\.\d+)*[.)]\s|\([0-9a-zA-Z]{1,4}\)\s|[A-Za-z][A-Za-z ]{0,20}:\s|#)""")
private val separator = Regex("""[\s=\-_*#~`]+""")
private val copyrightLine = Regex("""^(?:Copyright|\([cC]\)|©)""")

private fun continuesLine(line: String, next: String): Boolean {
    val current = line.trim()
    val following = next.trim()
    val indent = { text: String -> text.length - text.trimStart().length }
    val startsSentence = following.firstOrNull(Char::isLetterOrDigit)?.let { it.isUpperCase() || it.isDigit() } == true
    return current.length >= WRAPPED_LINE_MIN_LENGTH && following.isNotEmpty() &&
        !separator.matches(current) && !separator.matches(following) &&
        indent(next) >= indent(line) &&
        !listItem.containsMatchIn(following) && !current.startsWith('#') &&
        !copyrightLine.containsMatchIn(current) && "Copyright" !in following &&
        current.last() != ';' && !(current.last() in ".:!?" && startsSentence)
}

/** Per `uniqueId` delle definizioni a mano; le dipendenze Gradle mostrano nome e licenza dei loro POM. */
val licenseLabels: Map<String, LicenseLabel> = mapOf(
    "org.ggml:llama.cpp" to LicenseLabel(R.string.licenses_component_llama_cpp, note = R.string.licenses_note_llama_cpp),
    "com.github.nlohmann:json" to LicenseLabel(R.string.licenses_component_nlohmann_json),
    "com.github.yhirose:cpp-httplib" to LicenseLabel(R.string.licenses_component_cpp_httplib),
    "com.arm:kleidiai" to LicenseLabel(R.string.licenses_component_kleidiai),
    "org.llvm:openmp" to LicenseLabel(R.string.licenses_component_openmp),
    "btools:brouter" to LicenseLabel(R.string.licenses_component_brouter, note = R.string.licenses_note_brouter),
    "com.google:material-symbols" to LicenseLabel(R.string.licenses_component_material_symbols, note = R.string.licenses_note_material_symbols),
    "com.google:noto-sans" to LicenseLabel(R.string.licenses_component_noto_sans, note = R.string.licenses_note_noto_sans),
    "org.publicsuffix:list" to LicenseLabel(R.string.licenses_component_public_suffix),
    "pockettravel.models:qwen" to LicenseLabel(R.string.licenses_component_qwen, note = R.string.licenses_note_qwen),
    "pockettravel.models:trained" to LicenseLabel(R.string.licenses_component_trained_models, note = R.string.licenses_note_trained_models),
    "pockettravel.data:openstreetmap" to LicenseLabel(R.string.licenses_component_osm, note = R.string.licenses_note_osm),
    "pockettravel.data:protomaps" to LicenseLabel(R.string.licenses_component_protomaps, note = R.string.licenses_note_protomaps),
    "pockettravel.data:natural-earth" to LicenseLabel(
        R.string.licenses_component_natural_earth,
        R.string.licenses_name_public_domain,
        R.string.licenses_note_natural_earth,
    ),
    "pockettravel.data:airports" to LicenseLabel(R.string.licenses_component_airports, R.string.licenses_name_airports, R.string.licenses_note_airports),
    "pockettravel.data:flags" to LicenseLabel(R.string.licenses_component_flags, R.string.licenses_name_flags, R.string.licenses_note_flags),
    "pockettravel.data:wikivoyage" to LicenseLabel(note = R.string.licenses_note_wikivoyage),
    "pockettravel.data:wikipedia" to LicenseLabel(R.string.licenses_component_wikipedia, note = R.string.licenses_note_wikipedia),
    "pockettravel.data:wikidata" to LicenseLabel(R.string.licenses_component_wikidata, note = R.string.licenses_note_wikidata),
    "pockettravel.data:weather" to LicenseLabel(R.string.licenses_component_weather, note = R.string.licenses_note_weather),
    "pockettravel.data:emergency-canada" to LicenseLabel(R.string.licenses_component_emergency_canada, note = R.string.licenses_note_emergency_canada),
    "pockettravel.data:emergency-uk" to LicenseLabel(R.string.licenses_component_emergency_uk, note = R.string.licenses_note_emergency_uk),
    "pockettravel.data:travel-advice-canada" to LicenseLabel(R.string.licenses_component_travel_advice, note = R.string.licenses_note_travel_advice),
    "pockettravel.data:travel-advice-uk" to LicenseLabel(R.string.licenses_component_travel_advice_uk, note = R.string.licenses_note_travel_advice_uk),
    "pockettravel.data:vaccinations-canada" to LicenseLabel(
        R.string.licenses_component_vaccinations_canada,
        note = R.string.licenses_note_vaccinations_canada,
    ),
    "pockettravel.data:vaccinations-uk" to LicenseLabel(R.string.licenses_component_vaccinations_uk, note = R.string.licenses_note_vaccinations_uk),
    "pockettravel.data:vaccinations-fcdo" to LicenseLabel(R.string.licenses_component_vaccinations_fcdo, note = R.string.licenses_note_vaccinations_fcdo),
    "pockettravel.data:vaccinations-france" to LicenseLabel(
        R.string.licenses_component_vaccinations_france,
        note = R.string.licenses_note_vaccinations_france,
    ),
)
