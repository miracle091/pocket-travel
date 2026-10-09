package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

// Unico parser delle guide Wikivoyage.
// Titoli IT (build-region.sh preferisce la pagina Wikivoyage italiana quando esiste, vedi
// quel file): stesso schema di sezioni delle voci EN sul template di pagina-nazione, titoli
// diversi. "Tenersi informati" mappa su VITA_QUOTIDIANA come il piu' vicino equivalente di "cope"
// (entrambe sezioni "vita pratica in loco" generiche) — non e' una traduzione letterale.
private val headingToCategory = mapOf(
    "respect" to "USI_COSTUMI",
    "rispettare le usanze" to "USI_COSTUMI",
    "get in" to "DOGANE",
    "come arrivare" to "DOGANE",
    "stay healthy" to "SALUTE",
    "situazione sanitaria" to "SALUTE",
    "stay safe" to "SICUREZZA",
    "sicurezza" to "SICUREZZA",
    "get around" to "TRASPORTI",
    "come spostarsi" to "TRASPORTI",
    "talk" to "FRASI_UTILI",
    "sleep" to "ALLOGGIO",
    "eat" to "CIBO_BEVANDE",
    "drink" to "CIBO_BEVANDE",
    "a tavola" to "CIBO_BEVANDE",
    "buy" to "ACQUISTI",
    "valuta e acquisti" to "ACQUISTI",
    "connect" to "CONNETTIVITA",
    "come restare in contatto" to "CONNETTIVITA",
    "cope" to "VITA_QUOTIDIANA",
    "tenersi informati" to "VITA_QUOTIDIANA",
)

// (?!=)/(?<!=) escludono i sotto-titoli ===/==== (3+ segni "="): senza, un "===Get in==="
// verrebbe trattato come un nuovo titolo di sezione (non mappato), troncando silenziosamente
// tutto il testo reale di Wikivoyage dopo la prima sottosezione.
private val headingRegex = Regex("""^==(?!=)\s*(.+?)\s*(?<!=)==$""")
private val htmlCommentRegex = Regex("""(?s)<!--.*?-->""")
// Da tenere prima di htmlTagRegex: quest'ultimo toglie solo i tag <ref>/</ref>, lasciando il
// testo della citazione come prosa vagante in mezzo al corpo della sezione.
private val refTagRegex = Regex("""(?is)<ref\b[^>]*?/>|<ref\b[^>]*?>.*?</ref>""")
// L'indirizzo finisce alla prima "]" o spazio, il testo sulla stessa riga: con \S+ un link senza testo
// ("[http://x.al]") includerebbe la "]" e arriverebbe fino alla riga dopo.
private val externalLinkWithTextRegex = Regex("""\[https?://[^\s\]]+[ \t]+([^\]\n]+)]""")
private val bareExternalLinkRegex = Regex("""\[https?://[^\s\]]+]""")
// Corsivo ('') e grassetto (''') si tolgono, ma un apostrofo attaccato resta: "l'''Arte" e' l' + corsivo,
// "''''" e' apostrofo + grassetto (come li legge MediaWiki), cosi' non diventa "lArte".
private val boldItalicRegex = Regex("""'{2,}""")

private fun stripBoldItalic(text: String): String = boldItalicRegex.replace(text) { match ->
    val between = text.getOrNull(match.range.first - 1)?.isLetter() == true && text.getOrNull(match.range.last + 1)?.isLetter() == true
    when (match.value.length) {
        3 -> if (between) "'" else ""
        4 -> "'"
        else -> if (match.value.length > 5) "'".repeat(match.value.length - 5) else ""
    }
}
private val templateRegex = Regex("""\{\{[^}]*}}""")
// Template di Wikivoyage che portano testo da mostrare: il nome del luogo (marker, see, do, eat...)
// con la sua descrizione, e il codice dell'aeroporto (IATA). Tolti interi da templateRegex lascerebbero
// frasi rotte come "L' (), situato nel sobborgo di...". Espansi prima di resolveLinks perche' il nome
// puo' contenere [[link|testo]] (le | dentro il link non separano i parametri).
private val iataRegex = Regex("""(?i)\{\{\s*IATA\s*\|\s*([A-Z]{3})\s*}}""")
private val listingRegex = Regex("""(?is)\{\{\s*(?:marker|see|do|go|eat|drink|sleep|buy|listing)\s*\|([^{}]*)}}""")
private val listingParamSplitRegex = Regex("""\|(?![^\[]*]])""")
// "()" o "(, )" rimasti dopo aver tolto i template che stavano tra parentesi
private val emptyParenthesesRegex = Regex("""\s*\(\s*[,;]?\s*\)""")

private fun expandListing(params: String): String {
    val fields = listingParamSplitRegex.split(params).associate { part ->
        part.substringBefore('=').trim().lowercase() to part.substringAfter('=', "").trim()
    }
    val name = fields["nome"].orEmpty().ifEmpty { fields["name"].orEmpty() }
    val description = fields["descrizione"].orEmpty().ifEmpty { fields["content"].orEmpty() }
    return when {
        name.isEmpty() -> description
        description.isEmpty() -> name
        // "...del {{see|nome=X|descrizione=, l'attrazione...}}" continua la frase
        description.first() in ",.;:" -> "$name${description.first()} ${description.drop(1).trimStart()}"
        else -> "$name: $description"
    }
}
private val htmlTagRegex = Regex("""<[^>]+>""")

// Gallerie e tabelle: le righe "File:Pizza.jpg|Pizza" di <gallery> e il markup {| ... |} resterebbero nel
// testo. Si tolgono intere (le tabelle anche annidate, dall'interno).
private val galleryRegex = Regex("""(?is)<gallery\b[^>]*>.*?</gallery>""")
private val innermostTableRegex = Regex("""(?s)\{\|(?:(?!\{\|).)*?\|}""")

// Template annidati ({{listing|...|price={{EUR|5}}}}, {{cite|...[[x]]...}}): si risolvono dall'interno. I
// template di valore diventano testo, gli altri spariscono; listing e IATA restano per expandListing.
private val innermostTemplateRegex = Regex("""\{\{((?:(?!\{\{|}}).)*)}}""", RegexOption.DOT_MATCHES_ALL)
private val keptTemplateNames = setOf("marker", "see", "do", "go", "eat", "drink", "sleep", "buy", "listing", "iata")
// Template che mostrano il loro primo parametro cosi' com'e'. Non {{lang|fr|testo}}: il primo e' il codice della lingua.
private val textTemplateNames = setOf("nowrap", "phone", "tel", "telefono", "small", "smaller", "big", "nobr", "unbulleted list", "ta")
private val currencyTemplateRegex = Regex("""[A-Z]{3}""")

// Misure delle voci di Wikipedia (Storia e Clima delle citta'): {{convert|641|mm|in}}, {{cvt|15|and|25|°C}} (EN) e
// {{M|23.1|u=°C}} (IT). Tolte intere resterebbero frasi senza numeri ("temperature medie tra in luglio").
private val convertTemplateNames = setOf("convert", "cvt")
private val convertRangeWords = setOf("and", "to", "or", "-", "–")
private val unitSymbols = mapOf("C" to "°C", "F" to "°F", "km2" to "km²", "m2" to "m²", "m3" to "m³", "sqmi" to "sq mi")

private fun convertMeasure(params: List<String>): String {
    val positional = params.filter { '=' !in it }
    val isRange = positional.getOrNull(1) in convertRangeWords
    val value = if (isRange) positional.take(3).joinToString(" ") else positional.firstOrNull().orEmpty()
    val unit = positional.getOrNull(if (isRange) 3 else 1).orEmpty()
    return "$value ${unitSymbols[unit] ?: unit}".trim()
}

// {{M|valore|u=unita'}} di Wikipedia IT: il punto decimale si mostra come virgola.
private fun italianMeasure(params: List<String>): String {
    val value = params.firstOrNull { '=' !in it }.orEmpty().replace('.', ',')
    val unit = params.firstOrNull { it.startsWith("u=") || it.startsWith("ul=") }?.substringAfter('=').orEmpty()
    return "$value ${unitSymbols[unit] ?: unit}".trim()
}

private fun resolveTemplate(inner: String): String? {
    val parts = inner.split('|')
    val name = parts.first().trim()
    val lower = name.lowercase()
    if (lower in keptTemplateNames) return null
    val first = parts.getOrNull(1)?.trim().orEmpty()
    val params = parts.drop(1).map { it.trim() }
    return when {
        lower in textTemplateNames -> first.substringAfter('=', first)
        // {{lang|fr|Vieux-Port}} (il testo e' l'ultimo parametro senza nome) e {{lang-fr|Vieux-Port}} (il primo)
        lower == "lang" -> params.lastOrNull { '=' !in it }.orEmpty()
        lower.startsWith("lang-") -> first
        // Valute ({{EUR|5}}, {{ALL|500}}): "5 EUR", altrimenti resterebbe "almeno ." nel testo.
        currencyTemplateRegex.matches(name) && first.isNotEmpty() && first.first().isDigit() -> "$first $name"
        lower in convertTemplateNames -> convertMeasure(params)
        lower == "m" -> italianMeasure(params)
        // Link a una voce in un'altra lingua di Wikipedia EN: il testo (lt=) o il titolo.
        lower == "interlanguage link" || lower == "ill" ->
            params.firstOrNull { it.startsWith("lt=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() } ?: first
        else -> ""
    }
}

private fun resolveTemplates(text: String): String {
    var current = text
    while (true) {
        var changed = false
        val next = innermostTemplateRegex.replace(current) { match ->
            resolveTemplate(match.groupValues[1])?.also { changed = true } ?: match.value.replace("{{", "\u0001").replace("}}", "\u0002")
        }
        current = next
        if (!changed) return current.replace("\u0001", "{{").replace("\u0002", "}}")
    }
}

// Link interni, anche dentro la didascalia di un'immagine ([[File:x.jpg|thumb|Il [[Duomo]] di notte]]):
// dall'interno, cosi' il link del file resta intero e sparisce.
private val innermostLinkRegex = Regex("""\[\[([^\[\]]*)]]""")
private val fileLinkPrefixRegex = Regex("""(?i)^\s*(?:File|Image|Immagine|Media|Categoria|Category)\s*:""")

private fun resolveLinks(text: String): String {
    var current = text
    while (true) {
        val next = innermostLinkRegex.replace(current) { match ->
            val inner = match.groupValues[1]
            if (fileLinkPrefixRegex.containsMatchIn(inner)) "" else inner.substringAfterLast('|')
        }
        if (next == current) return current
        current = next
    }
}

// Spazi doppi (frequenti nel testo inglese dopo il punto) e spazi rimasti prima della punteggiatura.
private val repeatedSpacesRegex = Regex("""(?<=\S) {2,}""")
private val spaceBeforePunctuationRegex = Regex(""" +([,.;:!?)])""")
private val subHeadingLineRegex = Regex("""^={3,}\s*(.+?)\s*={3,}$""")
// ";Termine" (lista di definizione wiki): Wikivoyage la usa come sottotitolo dentro un elenco
// (es. ";Vini rossi" sotto "Bere"), trattato come ===Termine===.
private val definitionTermLineRegex = Regex("""^;\s*(.+)$""")
private val listMarkerRegex =Regex("""^[*#:]+\s*""")
private val blankLinesRegex = Regex("""\n{3,}""")

// Mai presente in un testo reale: marca una riga di sottotitolo (===Money===) nel passaggio
// riga-per-riga di cleanBody, cosi' da poterla distinguere da una riga di corpo normale prima
// di convertirla nella forma finale "▸ Titolo" — o di scartarla se la sottosezione e' vuota
// (vedi commento su cleanBody).
private const val SUBHEADING_MARKER = ""

/**
 * [sourceUrl] solo per le sezioni che non vengono dalla pagina della guida (tradotte dall'inglese): null = quella della
 * regione. [translated]: sezione tradotta dall'inglese da translate_guides.py, colonna "translated" di guide_sections.
 */
data class GuideSectionRow(
    val category: String,
    val title: String,
    val body: String,
    val sourceUrl: String? = null,
    val translated: Boolean = false,
)

// categories di default: le guide di regione (headingToCategory sopra). GenerateCities.kt passa la
// propria mappa (titoli di sezione delle pagine citta', diversi da quelli delle pagine nazione) per
// riusare qui sotto lo stesso parsing e la stessa cleanBody, senza duplicarli.
fun parseWikivoyageDump(dumpText: String, categories: Map<String, String> = headingToCategory): List<GuideSectionRow> {
    val sections = mutableListOf<GuideSectionRow>()
    var currentHeading: String? = null
    val currentBody = StringBuilder()

    fun flush() {
        val heading = currentHeading ?: return
        val category = categories[heading.lowercase()] ?: return
        val body = cleanBody(currentBody.toString())
        if (body.isNotBlank()) {
            sections += GuideSectionRow(category = category, title = heading, body = body)
        }
    }

    dumpText.lineSequence().forEach { line ->
        val match = headingRegex.find(line.trim())
        if (match != null) {
            flush()
            currentHeading = match.groupValues[1]
            currentBody.setLength(0)
        } else {
            currentBody.appendLine(line)
        }
    }
    flush()

    return sections
}

// Il wikitext grezzo di Wikivoyage porta sintassi che non ha senso mostrare cosi' com'e' in una
// Text semplice (nessun renderer markdown lato app, vedi GuideScreen): citazioni <ref> il cui
// contenuto resterebbe come prosa vagante, elenchi puntati/numerati con l'asterisco/cancelletto
// grezzo davanti, e sottotitoli ===Foo=== che — se la sottosezione e' solo un template gia'
// tolto (es. {{Pricerange}} su "Money" in molte pagine paese) — resterebbero come parola orfana
// seguita da una riga vuota enorme.
// Entita' HTML scritte nel wikitext (&mdash;, &nbsp;, &#8211;...): Wikivoyage le mostra come caratteri, nel
// testo pulito resterebbero tali e quali ("Roma &mdash; Firenze").
private val htmlEntityRegex = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});""")
private val namedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to "\u00A0", "thinsp" to "\u2009", "ensp" to "\u2002", "emsp" to "\u2003", "shy" to "",
    "mdash" to "—", "ndash" to "–", "minus" to "−", "hellip" to "…", "middot" to "·", "bull" to "•",
    "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„",
    "deg" to "°", "times" to "×", "divide" to "÷", "plusmn" to "±", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "copy" to "©", "reg" to "®", "trade" to "™",
    "sect" to "§", "para" to "¶", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔",
    "sup2" to "²", "sup3" to "³", "micro" to "µ", "iexcl" to "¡", "iquest" to "¿", "ordm" to "º", "ordf" to "ª",
    // Lettere accentate per nome: &egrave; &agrave; &ccedil; ...
    "agrave" to "à", "Agrave" to "À", "aacute" to "á", "Aacute" to "Á", "acirc" to "â", "Acirc" to "Â", "atilde" to "ã", "Atilde" to "Ã", "auml" to "ä", "Auml" to "Ä", "aring" to "å", "Aring" to "Å", "egrave" to "è", "Egrave" to "È", "eacute" to "é", "Eacute" to "É", "ecirc" to "ê", "Ecirc" to "Ê", "euml" to "ë", "Euml" to "Ë", "igrave" to "ì", "Igrave" to "Ì", "iacute" to "í", "Iacute" to "Í", "icirc" to "î", "Icirc" to "Î", "iuml" to "ï", "Iuml" to "Ï", "ograve" to "ò", "Ograve" to "Ò", "oacute" to "ó", "Oacute" to "Ó", "ocirc" to "ô", "Ocirc" to "Ô", "otilde" to "õ", "Otilde" to "Õ", "ouml" to "ö", "Ouml" to "Ö", "oslash" to "ø", "Oslash" to "Ø", "ugrave" to "ù", "Ugrave" to "Ù", "uacute" to "ú", "Uacute" to "Ú", "ucirc" to "û", "Ucirc" to "Û", "uuml" to "ü", "Uuml" to "Ü", "yacute" to "ý", "Yacute" to "Ý", "yuml" to "ÿ", "Yuml" to "Ÿ", "ntilde" to "ñ", "Ntilde" to "Ñ", "ccedil" to "ç", "Ccedil" to "Ç", "szlig" to "ß", "aelig" to "æ", "AElig" to "Æ", "oelig" to "œ", "OElig" to "Œ",
)

internal fun decodeHtmlEntities(text: String): String = htmlEntityRegex.replace(text) { match ->
    val name = match.groupValues[1]
    when {
        name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }
        name.startsWith("#") -> name.drop(1).toIntOrNull()?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }
        else -> namedEntities[name]
    } ?: match.value
}

private fun removeTables(text: String): String {
    var current = text
    while (true) {
        val next = current.replace(innermostTableRegex, "")
        if (next == current) return current
        current = next
    }
}

// Sottosezioni inutili a chi viaggia: "Costo della vita" parla di stipendi e
// spese delle famiglie del posto. Si toglie il sottotitolo con tutto il suo testo, fino al titolo successivo dello
// stesso livello o superiore; vale per guide, citta' e dataset di training (che usano tutti cleanBody).
private val skippedSubsections = setOf("costo della vita", "cost of living")
private val subsectionHeadingRegex = Regex("""^(={2,6})\s*(.+?)\s*\1\s*$""")

internal fun dropSkippedSubsections(raw: String): String {
    var skipLevel = 0
    return raw.lineSequence().filter { line ->
        val heading = subsectionHeadingRegex.find(line.trim())
        if (heading != null) {
            val level = heading.groupValues[1].length
            if (skipLevel != 0 && level <= skipLevel) skipLevel = 0
            if (skipLevel == 0 && heading.groupValues[2].lowercase() in skippedSubsections) skipLevel = level
        }
        skipLevel == 0
    }.joinToString("\n")
}

internal fun cleanBody(raw: String): String {
    val stripped = dropSkippedSubsections(raw)
        .replace(htmlCommentRegex, "")
        .replace(refTagRegex, "")
        .replace(galleryRegex, "")
        .let(::removeTables)
        .let(::resolveTemplates)
        .replace(iataRegex, "$1")
        .replace(listingRegex) { expandListing(it.groupValues[1]) }
        // Link esterni prima di quelli interni: possono stare nella didascalia di un'immagine.
        .replace(externalLinkWithTextRegex, "$1")
        .replace(bareExternalLinkRegex, "")
        .let(::resolveLinks)
        .let(::stripBoldItalic)
        .replace(templateRegex, "")
        .replace(htmlTagRegex, "")
        .let(::decodeHtmlEntities)
        .replace(emptyParenthesesRegex, "")
        .replace(repeatedSpacesRegex, " ")
        .replace(spaceBeforePunctuationRegex, "$1")

    val markedLines = stripped.lineSequence().map { rawLine ->
        val line = rawLine.trim()
        val heading = (subHeadingLineRegex.find(line) ?: definitionTermLineRegex.find(line))?.groupValues?.get(1)
        if (heading != null) {
            "$SUBHEADING_MARKER$heading"
        } else {
            listMarkerRegex.replace(line) { match -> if (match.value.any { it == '*' || it == '#' }) "• " else "" }
        }
    }.toList()

    val kept = mutableListOf<String>()
    for (index in markedLines.indices) {
        val line = markedLines[index]
        if (line.startsWith(SUBHEADING_MARKER)) {
            // Scansione in avanti fino al prossimo sottotitolo, senza copiare la lista a ogni sottotitolo.
            var next = index + 1
            var hasBody = false
            while (next < markedLines.size && !markedLines[next].startsWith(SUBHEADING_MARKER)) {
                if (markedLines[next].isNotBlank()) {
                    hasBody = true
                    break
                }
                next++
            }
            if (hasBody) kept += "▸ ${line.removePrefix(SUBHEADING_MARKER)}"
        } else {
            kept += line
        }
    }

    return joinWrappedLines(kept).joinToString("\n")
        .replace(blankLinesRegex, "\n\n")
        .replace(blankAfterSubheadingRegex, "$1\n")
        .replace(blankBetweenItemsRegex, "$1\n")
        .trim()
}

// Nel wikitext un solo a capo dentro un paragrafo non va a capo (MediaWiki lo legge come uno spazio): due
// righe di testo di seguito sono la stessa frase. Elenchi (•) e sottotitoli (▸) restano righe a se'.
private fun joinWrappedLines(lines: List<String>): List<String> {
    val joined = mutableListOf<String>()
    for (line in lines) {
        val previous = joined.lastOrNull()
        val isText = line.isNotBlank() && !line.startsWith("• ") && !line.startsWith("▸ ")
        val previousIsText = previous != null && previous.isNotBlank() && !previous.startsWith("• ") && !previous.startsWith("▸ ")
        if (isText && previousIsText) joined[joined.lastIndex] = "$previous $line" else joined += line
    }
    return joined
}

// Riga vuota subito dopo un sottotitolo, o fra due voci dello stesso elenco: a capo in piu' nel testo.
private val blankAfterSubheadingRegex = Regex("""(?m)^(▸ [^\n]*)\n\n+""")
private val blankBetweenItemsRegex = Regex("""(?m)^(• [^\n]*)\n\n+(?=• )""")

// Campi del {{QuickbarCountry}}/{{QuickbarRegion}} di Wikivoyage IT per la sezione "Fatti rapidi":
// nomi verificati sulle pagine reali (es. Italia, Venezuela, Isole Fær Øer). Cercato solo nei
// primi QUICKBAR_SCAN_CHARS caratteri, dove sta sempre il riquadro: evita di raccogliere per sbaglio un "Valuta =" che
// comparisse molto piu' in basso nella pagina. Il valore di un campo puo' andare su piu' righe
// (es. Valuta del Venezuela, un elenco puntato con tre voci): si ferma al campo successivo o alla
// chiusura "}}" del template.
private val quickFactFieldRegex = Regex(
    // Il valore si ferma anche a un "| Campo =" sulla stessa riga: con un campo vuoto ("|Elettricità= | Fuso
    // orario = UTC-3") il valore diventerebbe "| Fuso orario = UTC-3".
    """(?m)(?:^|(?<=\s))\|\s*(Lingua|Elettricità|Fuso orario|Valuta)\s*=\s*(.*?)(?=\n\s*\|[^|\n]*=|\s*\|\s*[^|\[\]{}=\n]+=|\n\s*}}|\z)""",
    RegexOption.DOT_MATCHES_ALL,
)
private const val QUICKBAR_SCAN_CHARS = 4000

// Righe "Campo: valore" nell'ordine di [labels]: i campi della pagina Wikivoyage prima (piu' ricchi: lingue regionali,
// prese per nome, fusi dei paesi grandi), quelli di Wikidata (countryFactFields) per i campi che la pagina non ha.
private fun quickFactLines(wikivoyage: Map<String, String>, wikidata: Map<String, String>, labels: QuickFactLabels): List<String> {
    val fields = wikidata + wikivoyage
    return labels.order.mapNotNull { field -> fields[field]?.let { "$field: $it" } }
}

// Pulizia inline di un valore di campo dei Fatti rapidi (una riga, non una sezione): stesse regex di
// rimozione del markup wiki di cleanBody, senza la gestione di sottotitoli/elenchi puntati su piu'
// righe — un valore come quello di Valuta (tre voci separate da "*") diventa una singola riga con
// le voci separate da virgola, non un elenco "▸/•" come nel corpo di una sezione.
private fun cleanQuickFactValue(raw: String): String =
    raw
        .replace(htmlCommentRegex, "")
        .replace(refTagRegex, "")
        .let(::resolveTemplates)
        .replace(externalLinkWithTextRegex, "$1")
        .replace(bareExternalLinkRegex, "")
        .let(::resolveLinks)
        .let(::stripBoldItalic)
        .replace(templateRegex, "")
        .replace(htmlTagRegex, "")
        .let(::decodeHtmlEntities)
        .replace(repeatedSpacesRegex, " ")
        .lineSequence()
        .map { listMarkerRegex.replace(it.trim(), "").trim() }
        .filter { it.isNotBlank() }
        .joinToString(", ")

/**
 * Sezione "Fatti rapidi" (categoria FATTI_RAPIDI) di una regione: i campi Lingua/Elettricità/Fuso orario/Valuta del
 * {{QuickbarCountry}}/{{QuickbarRegion}} della pagina, completati con quelli di Wikidata (Capitale, Prefisso
 * telefonico, Lato di guida e i campi che la pagina non ha, vedi GenerateCountryFacts.kt), piu' una riga con i numeri
 * di emergenza (vedi emergencyNumbersLine in GenerateEmergencyNumbers.kt). Nessuna riga per un dato assente, null
 * (nessuna sezione) se non c'e' nessun dato.
 */
fun quickFactsSection(regionId: String, dumpText: String): GuideSectionRow? {
    val fields = quickFactFieldRegex.findAll(dumpText.take(QUICKBAR_SCAN_CHARS))
        .associate { it.groupValues[1] to cleanQuickFactValue(it.groupValues[2]) }
        .filterValues { it.isNotBlank() }
    val lines = quickFactLines(fields, countryFactFields(regionId, english = false), QuickFactLabels.ITALIAN) +
        listOfNotNull(emergencyNumbersLine(regionId))
    return lines.takeIf { it.isNotEmpty() }?.let { GuideSectionRow(category = "FATTI_RAPIDI", title = "Fatti rapidi", body = it.joinToString("\n")) }
}

// Fatti rapidi in inglese: le pagine di Wikivoyage EN non li hanno nel testo ({{quickbar}} li prende da
// Wikidata quando la pagina si apre), quindi vengono dai Fatti rapidi della pagina italiana, dove si possono rendere
// in inglese: elettricita' e fuso orario (valori quasi neutri) e la lingua tradotta coi nomi delle lingue del JDK.
// Gli altri campi, e quelli italiani non traducibili, dai dati di Wikidata con le etichette inglesi; piu' i numeri di
// emergenza come nella guida italiana. Cosi' l'assistente in inglese vede gli stessi fatti dell'italiano.
private val plugWords = mapOf(
    "presa" to "plug", "prese" to "plugs", "europea" to "European", "britannica" to "British",
    "americana" to "American", "australiana" to "Australian", "tedesca" to "German", "francese" to "French",
    "cinese" to "Chinese", "argentina" to "Argentine", "svizzera" to "Swiss", "italiana" to "Italian",
    "giapponese" to "Japanese", "indiana" to "Indian", "sudafricana" to "South African", "danese" to "Danish",
    "israeliana" to "Israeli", "brasiliana" to "Brazilian", "e" to "and", "ed" to "and", "tipo" to "type",
)
private val neutralValueRegex = Regex("""^[0-9UTCGM\s/,.:+\-–~Vvz Hh]+$""")

// "220V/50Hz (presa europea e britannica)" -> "220V/50Hz (European and British plug)"; se nella parentesi
// resta una parola sconosciuta, solo la parte neutra ("220V/50Hz"), null se nemmeno quella.
internal fun englishElectricity(value: String): String? {
    val base = value.substringBefore('(').trim()
    if (!neutralValueRegex.matches(base)) return null
    val note = value.substringAfter('(', "").substringBeforeLast(')', "").trim()
    if (note.isEmpty()) return base
    val adjectives = note.lowercase().split(Regex("""\s*(?:,|/|\be\b|\bed\b)\s*""")).map { part ->
        part.replace(Regex("""\bpres[ae]\b"""), "").trim()
    }.filter { it.isNotBlank() }
    val translated = adjectives.map { plugWords[it] ?: return base }
    val plural = translated.size > 1 || Regex("""\bprese\b""").containsMatchIn(note.lowercase())
    val list = if (translated.size > 1) translated.dropLast(1).joinToString(", ") + " and " + translated.last() else translated.single()
    return "$base ($list " + (if (plural) "plugs" else "plug") + ")"
}

private val englishLanguageNames: Map<String, String> by lazy {
    Locale.getISOLanguages().map(::Locale).associate { it.getDisplayLanguage(Locale.ITALIAN).lowercase() to it.getDisplayLanguage(Locale.ENGLISH) }
}

// "Italiano, Tedesco (Trentino-Alto Adige)" -> "Italian, German": tolte le parentesi, ogni nome tradotto; dal
// primo nome sconosciuto in poi ("Tedesco, regionale: croato" -> "German") si tiene solo quello che precede,
// null se nemmeno il primo e' noto.
internal fun englishLanguage(value: String): String? =
    value.replace(Regex("""\([^)]*\)"""), "").split(Regex("""\s*(?:,|;|:|/|\be\b|\bed\b)\s*"""))
        .map { it.trim().lowercase() }.filter { it.isNotBlank() }
        .map { englishLanguageNames[it] }.takeWhile { it != null }.filterNotNull().distinct()
        .takeIf { it.isNotEmpty() }?.joinToString(", ")

// "UTC+1" resta com'e'; con testo italiano ("UTC-3 (costa orientale)...") solo se, tolte le parentesi, resta un valore neutro.
internal fun englishTimeZone(value: String): String? =
    value.takeIf { neutralValueRegex.matches(it) }
        ?: value.replace(Regex("""\([^)]*\)"""), "").replace(Regex("""\s+e\s+"""), ", ").trim().takeIf { neutralValueRegex.matches(it) && it.isNotBlank() }

/** Sezione "Quick facts" della guida inglese dai Fatti rapidi italiani, da Wikidata e dai numeri di emergenza (vedi sopra); null senza dati. */
fun englishQuickFactsSection(regionId: String, dumpIt: String): GuideSectionRow? {
    val fields = quickFactFieldRegex.findAll(dumpIt.take(QUICKBAR_SCAN_CHARS))
        .associate { it.groupValues[1] to cleanQuickFactValue(it.groupValues[2]) }
        .filterValues { it.isNotBlank() }
    val labels = QuickFactLabels.ENGLISH
    val translated = listOfNotNull(
        fields["Lingua"]?.let(::englishLanguage)?.let { labels.language to it },
        fields["Elettricità"]?.let(::englishElectricity)?.let { labels.electricity to it },
        fields["Fuso orario"]?.let(::englishTimeZone)?.let { labels.timeZone to it },
    ).toMap()
    val lines = quickFactLines(translated, countryFactFields(regionId, english = true), labels) +
        listOfNotNull(emergencyNumbersLine(regionId, english = true))
    return lines.takeIf { it.isNotEmpty() }?.let { GuideSectionRow(category = "FATTI_RAPIDI", title = "Quick facts", body = it.joinToString("\n")) }
}

/** Guida di una regione: sezioni estratte dal dump Wikivoyage e URL della pagina da cui vengono. */
data class RegionGuide(val regionId: String, val sourceUrl: String, val sections: List<GuideSectionRow>)

/**
 * Genera guides.db, il pacchetto guide unico per tutte le regioni (guide_sections +
 * emergency_numbers), scaricato dall'app separatamente da mappa, POI e rete stradale.
 *
 * regioni.tsv: una riga per regione "regionId<TAB>dump.txt<TAB>sourceUrl", con in piu'
 * "<TAB>dumpEn.txt<TAB>sourceUrlEn" quando build-guides.sh ha scaricato anche la pagina inglese.
 * Dump vuoto = pagina Wikivoyage non scaricata in questa run: si ricopiano le sezioni di quella
 * regione dal guides.db pubblicato, se passato, invece di farla sparire per un errore di rete.
 *
 * Se il contenuto generato e' identico a quello del guides.db pubblicato, l'output non viene
 * scritto: il chiamante riusa la voce gia' pubblicata e la versione non cambia, cosi' l'app non
 * riscarica le guide a ogni run.
 */
fun main(rawArgs: Array<String>) {
    // --lang en: guida inglese (guides-en.db). regioni.tsv ha allora "regionId<TAB>dumpEn.txt<TAB>sourceUrlEn",
    // con in piu' "<TAB>dumpIt.txt" (la pagina italiana, solo per i fatti rapidi, vedi englishQuickFactsSection).
    val english = rawArgs.firstOrNull() == "--lang" && rawArgs.getOrNull(1) == "en"
    val rest = if (rawArgs.firstOrNull() == "--lang") rawArgs.drop(2) else rawArgs.toList()
    // --missions <tsv>: missioni diplomatiche da Wikidata (wikidata_missions.py), tabella diplomatic_missions.
    val missionsIndex = rest.indexOf("--missions")
    val missionsTsv = if (missionsIndex >= 0) rest.getOrNull(missionsIndex + 1)?.let(::File) else null
    val withoutMissions = if (missionsIndex >= 0) rest.take(missionsIndex) + rest.drop(missionsIndex + 2) else rest
    // --translated <jsonl>: sezioni tradotte dall'altra lingua (translate_guides.py), al posto di quelle povere.
    val translatedIndex = withoutMissions.indexOf("--translated")
    val translatedJsonl = if (translatedIndex >= 0) withoutMissions.getOrNull(translatedIndex + 1)?.let(::File) else null
    val withoutTranslated = if (translatedIndex >= 0) withoutMissions.take(translatedIndex) + withoutMissions.drop(translatedIndex + 2) else withoutMissions
    // --travel-advice <tsv>: consigli di viaggio di travel.gc.ca o dell'FCDO per regione (GenerateTravelAdvice.kt, solo guida inglese).
    val adviceIndex = withoutTranslated.indexOf("--travel-advice")
    val adviceTsv = if (adviceIndex >= 0) withoutTranslated.getOrNull(adviceIndex + 1)?.let(::File) else null
    val args = if (adviceIndex >= 0) withoutTranslated.take(adviceIndex) + withoutTranslated.drop(adviceIndex + 2) else withoutTranslated
    require(args.size in 2..3) {
        "Uso: generateGuides [--lang en] [--missions <missioni.tsv>] [--translated <tradotte.jsonl>] [--travel-advice <consigli.tsv>] " +
            "<regioni.tsv> <output guides.db> [guides.db pubblicato]"
    }
    val outputDb = File(args[1])
    val publishedDb = args.getOrNull(2)?.let(::File)?.takeIf { it.exists() }

    val translated = translatedJsonl?.takeIf { it.exists() }?.let { parseTranslatedSections(it.readText()) }.orEmpty()
    val publishedAdviceMeta = publishedDb?.let(::readTravelAdviceMeta).orEmpty()
    val advice = adviceTsv?.takeIf { it.exists() }?.let { tsv ->
        val publishedSections = { regionId: String -> publishedDb?.let { readRegionGuide(it, regionId) }?.sections.orEmpty() }
        readTravelAdvice(tsv, publishedSections, publishedAdviceMeta, LocalDate.now(ZoneOffset.UTC))
    }
    val guides = File(args[0]).readLines().filter { it.isNotBlank() }.map { line ->
        val columns = line.split('\t')
        val (regionId, dumpPath, sourceUrl) = columns
        val dump = dumpPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.length() > 0 }
        val dumpOther = columns.getOrNull(3)?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.length() > 0 }
        if (dump != null && english) {
            RegionGuide(regionId, sourceUrl, listOfNotNull(englishQuickFactsSection(regionId, dumpOther?.readText().orEmpty())) + parseWikivoyageDump(dump.readText()))
        } else if (dump != null) {
            regionGuideFromDumps(regionId, dump.readText(), sourceUrl, dumpOther?.readText(), columns.getOrNull(4).orEmpty())
        } else {
            println("guide: $regionId senza dump in questa run, ricopio le sezioni pubblicate")
            publishedDb?.let { readRegionGuide(it, regionId) } ?: RegionGuide(regionId, sourceUrl, emptyList())
        }
    }.withTranslations(translated) { regionId -> publishedDb?.let { readRegionGuide(it, regionId) }?.sections.orEmpty() }
        .let { guides -> advice?.let { guides.withTravelAdvice(it) } ?: guides }

    outputDb.delete()
    writeGuidesDb(guides, outputDb)
    writeDiplomaticMissions(missionsTsv, publishedDb, outputDb)
    if (advice != null) {
        writeTravelAdviceMeta(advice, outputDb)
        // Con dei consigli gia' pubblicati: un cambio di rischio pubblica subito (keepPublishedGuides), qui l'avviso nel job.
        if (publishedAdviceMeta.isNotEmpty()) {
            travelAdviceRiskChanges(publishedAdviceMeta, advice.mapNotNull { (id, a) -> a.meta?.let { id to it } }.toMap())
                .forEach { println("::warning::consigli di viaggio, rischio cambiato: $it") }
        }
    }
    if (publishedDb != null && keepPublishedGuides(outputDb, publishedDb)) {
        outputDb.delete()
        println(
            "guide: contenuto identico a quello pubblicato (o cambiate solo le missioni da meno di $MISSIONS_MAX_AGE_DAYS giorni " +
                "e i consigli di viaggio da meno di $TRAVEL_ADVICE_MAX_AGE_DAYS senza cambi di rischio), nessun nuovo guides.db",
        )
        return
    }
    println("guide: ${guides.sumOf { it.sections.size }} sezioni di ${guides.size} regioni scritte in ${outputDb.path}")
}

/**
 * Guida di una regione dalla pagina scaricata (di norma quella italiana). Se non ne esce nessuna
 * sezione (pagina IT con i soli titoli, es. Siberia) e c'e' la pagina inglese, si usa quella.
 */
fun regionGuideFromDumps(regionId: String, dump: String, sourceUrl: String, dumpEn: String?, sourceUrlEn: String): RegionGuide {
    val sections = parseWikivoyageDump(dump)
    // Fatti rapidi: sempre dal riquadro della pagina scaricata (di norma quella italiana), anche
    // quando il corpo delle sezioni viene dall'inglese piu' sotto — i nomi dei campi (Lingua,
    // Elettricità...) sono quelli di Wikivoyage IT.
    val quickFacts = quickFactsSection(regionId, dump)
    if (sections.isEmpty() && dumpEn != null) {
        val sectionsEn = parseWikivoyageDump(dumpEn)
        if (sectionsEn.isNotEmpty()) {
            println("guide: $regionId senza sezioni nella pagina $sourceUrl, uso $sourceUrlEn")
            return RegionGuide(regionId, sourceUrlEn, listOfNotNull(quickFacts) + sectionsEn)
        }
    }
    return RegionGuide(regionId, sourceUrl, listOfNotNull(quickFacts) + sections)
}

/**
 * Schema minimo (non lo schema Room di GuideSectionEntity, niente FTS4): una tabella
 * "guide_sections" con le stesse colonne meno l'id autogenerato. L'app importa riga per
 * riga in region.db via GuideDao.insertAll(), che ripopola anche la shadow table FTS
 * come effetto collaterale dell'insert Room. La colonna "translated" (sezione tradotta dall'inglese) la ignorano le
 * app che non la conoscono: l'importer legge le colonne per nome. Nello stesso file la tabella emergency_numbers
 * (vedi GenerateEmergencyNumbers.kt).
 */
fun writeGuidesDb(guides: List<RegionGuide>, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "guide_sections",
        createTableSql = """
            CREATE TABLE guide_sections (
                regionId TEXT NOT NULL,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                sourceUrl TEXT NOT NULL,
                translated INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        insertSql = "INSERT INTO guide_sections (regionId, category, title, body, sourceUrl, translated) VALUES (?, ?, ?, ?, ?, ?)",
        rows = guides.flatMap { guide -> guide.sections.map { guide to it } },
    ) { insert, (guide, section) ->
        insert.setString(1, guide.regionId)
        insert.setString(2, section.category)
        insert.setString(3, section.title)
        insert.setString(4, section.body)
        insert.setString(5, section.sourceUrl ?: guide.sourceUrl)
        insert.setInt(6, if (section.translated) 1 else 0)
    }
    writeEmergencyNumbersTable(guides.map { it.regionId }, outputDb)
    writeVaccinationsTables(outputDb)
}

private fun readRegionGuide(db: File, regionId: String): RegionGuide? =
    DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
        // I guides.db pubblicati prima delle sezioni tradotte non hanno la colonna translated.
        val hasTranslated = conn.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(guide_sections)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == "translated" }
            }
        }
        val translatedColumn = if (hasTranslated) "translated" else "0"
        conn.prepareStatement("SELECT category, title, body, sourceUrl, $translatedColumn FROM guide_sections WHERE regionId = ?").use { query ->
            query.setString(1, regionId)
            val rs = query.executeQuery()
            var sourceUrl: String? = null
            val sections = mutableListOf<GuideSectionRow>()
            while (rs.next()) {
                // Le tradotte hanno l'url della pagina inglese: ognuna tiene il proprio.
                sections += GuideSectionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) != 0)
                if (rs.getInt(5) == 0) sourceUrl = rs.getString(4)
            }
            (sourceUrl ?: sections.firstOrNull()?.sourceUrl)?.let { RegionGuide(regionId, it, sections) }
        }
    }

private const val MISSIONS_QUERY =
    "SELECT wikidata, sending, host, kind, name, name_en, city, address, phone, website, email, lat, lon FROM diplomatic_missions ORDER BY 1"
private const val GUIDE_SECTIONS_QUERY = "SELECT regionId, category, title, body, sourceUrl, translated FROM guide_sections"
private const val TRAVEL_ADVICE_URLS = "(sourceUrl LIKE 'https://$TRAVEL_ADVICE_HOST/%' OR sourceUrl LIKE '$FCDO_ADVICE_PREFIX%')"
// Senza la data di download: cambia a ogni run anche quando i consigli no.
private const val TRAVEL_ADVICE_STATE_QUERY = "SELECT regionId, advisory_state, regional FROM travel_advice_meta ORDER BY 1"

/**
 * Stesse righe in guide_sections e nelle tabelle dei numeri di emergenza, vaccinali (vacc_*) e delle missioni diplomatiche, a
 * prescindere dall'ordine di inserimento. [includeTravelAdvice] false: senza le sezioni di travel.gc.ca e dell'FCDO e il loro livello di
 * rischio (travel_advice_meta), che keepPublishedGuides valuta a parte come le missioni.
 */
fun sameGuidesContent(a: File, b: File, includeMissions: Boolean = true, includeTravelAdvice: Boolean = true): Boolean {
    val queries = listOfNotNull(
        (if (includeTravelAdvice) GUIDE_SECTIONS_QUERY else "$GUIDE_SECTIONS_QUERY WHERE NOT $TRAVEL_ADVICE_URLS") + " ORDER BY 1, 2, 3, 4, 5, 6",
        TRAVEL_ADVICE_STATE_QUERY.takeIf { includeTravelAdvice },
        "SELECT regionId, general, police, ambulance, fire FROM emergency_numbers ORDER BY 1",
        "SELECT regionId FROM emergency_numbers_none ORDER BY 1",
        "SELECT * FROM vacc_yf_risk ORDER BY 1, 2, 3",
        "SELECT * FROM vacc_yf_entry ORDER BY 1, 2, 3",
        "SELECT * FROM vacc_polio_status ORDER BY 1, 2, 3",
        "SELECT * FROM vacc_polio_entry ORDER BY 1, 2, 3",
        "SELECT * FROM vacc_special ORDER BY 1, 2, 3",
        "SELECT * FROM vacc_recommended ORDER BY 1, 2, 3",
        "SELECT * FROM vacc_meta ORDER BY 1",
        MISSIONS_QUERY.takeIf { includeMissions },
    )
    return queries.all { sql -> readRows(a, sql) == readRows(b, sql) }
}

/** Stesse missioni diplomatiche nei due guides.db. */
fun sameMissions(a: File, b: File): Boolean = readRows(a, MISSIONS_QUERY) == readRows(b, MISSIONS_QUERY)

/** Stesse sezioni di travel.gc.ca e dell'FCDO e stesso livello di rischio per regione nei due guides.db. */
fun sameTravelAdvice(a: File, b: File): Boolean = listOf(
    "$GUIDE_SECTIONS_QUERY WHERE $TRAVEL_ADVICE_URLS ORDER BY 1, 2, 3, 4, 5, 6",
    TRAVEL_ADVICE_STATE_QUERY,
).all { sql -> readRows(a, sql) == readRows(b, sql) }

internal fun readRows(db: File, sql: String): List<List<String?>>? =
    DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
        conn.createStatement().use { statement ->
            // Tabella assente (file non generato da questo tool): null, mai uguale a un file valido.
            val rs = runCatching { statement.executeQuery(sql) }.getOrNull() ?: return@use null
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..columns).map { rs.getString(it) }) }
        }
    }
