package com.pockettravel.core.content

import com.pockettravel.core.data.GuideSection

class WikivoyageDumpParser : WikivoyageImporter {

    override fun parse(regionId: String, sourceUrl: String, dumpText: String): List<GuideSection> {
        val sections = mutableListOf<GuideSection>()
        var currentHeading: String? = null
        val currentBody = StringBuilder()

        fun flush() {
            val heading = currentHeading ?: return
            val category = wikivoyageHeadingToCategory[heading.lowercase()] ?: return
            val body = cleanWikitext(currentBody.toString())
            if (body.isNotBlank()) {
                sections += GuideSection(
                    regionId = regionId,
                    category = category,
                    title = heading,
                    body = body,
                    sourceUrl = sourceUrl,
                )
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

    private companion object {
        // (?!=)/(?<!=) escludono i sotto-titoli ===/==== (3+ segni "="): senza, un
        // "===Get in===" verrebbe trattato come un nuovo titolo di sezione (non mappato),
        // troncando silenziosamente tutto il testo reale di Wikivoyage dopo la prima
        // sottosezione — quasi ogni sezione mappata reale ne contiene almeno una.
        val headingRegex = Regex("""^==(?!=)\s*(.+?)\s*(?<!=)==$""")
    }
}

private val wikiFileLinkRegex = Regex("""(?is)\[\[(?:File|Image):.*?]]""")
private val wikiLinkRegex = Regex("""\[\[(?:[^|\]]*\|)?([^\]]+)]]""")
private val externalLinkWithTextRegex = Regex("""\[https?://\S+\s+([^\]]+)]""")
private val bareExternalLinkRegex = Regex("""\[https?://\S+]""")
private val boldItalicRegex = Regex("""'{2,3}""")
private val templateRegex = Regex("""\{\{[^}]*}}""")
private val htmlTagRegex = Regex("""<[^>]+>""")
// Sotto-titoli ===/==== rimasti nel corpo (non piu' spezzati via headingRegex): mostrati come
// testo semplice invece che con i segni "=" grezzi.
private val subHeadingLineRegex = Regex("""(?m)^={3,}\s*(.+?)\s*={3,}$""")

private fun cleanWikitext(raw: String): String =
    raw
        .replace(wikiFileLinkRegex, "")
        .replace(externalLinkWithTextRegex, "$1")
        .replace(bareExternalLinkRegex, "")
        .replace(wikiLinkRegex, "$1")
        .replace(boldItalicRegex, "")
        .replace(templateRegex, "")
        .replace(htmlTagRegex, "")
        .replace(subHeadingLineRegex, "$1")
        .trim()
