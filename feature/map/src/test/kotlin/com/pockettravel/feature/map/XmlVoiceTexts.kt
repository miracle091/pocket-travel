package com.pockettravel.feature.map

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * I testi della guida vocale letti dai veri `strings.xml` del modulo (italiano in `values`, inglese in `values-en`), per
 * provare le frasi nelle due lingue senza Android. Gli id di `R` tornano nomi con la riflessione.
 */
class XmlVoiceTexts(language: String) : VoiceTexts {
    override val locale: Locale = Locale.forLanguageTag(language)

    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val dir = if (language == "it") "values" else "values-$language"
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val root = document.documentElement
        root.getElementsByTagName("string").forEach { strings[it.getAttribute("name")] = unescape(it.textContent) }
        root.getElementsByTagName("plurals").forEach { plural ->
            plurals[plural.getAttribute("name")] = buildMap {
                plural.getElementsByTagName("item").forEach { put(it.getAttribute("quantity"), unescape(it.textContent)) }
            }
        }
    }

    override fun string(id: Int, vararg args: Any): String = String.format(locale, checkNotNull(strings[name(R.string::class.java, id)]), *args)

    // In italiano e in inglese "one" vale solo per 1.
    override fun plural(id: Int, quantity: Int, vararg args: Any): String {
        val forms = checkNotNull(plurals[name(R.plurals::class.java, id)])
        return String.format(locale, checkNotNull(forms[if (quantity == 1) "one" else "other"]), *args)
    }

    private fun name(type: Class<*>, id: Int): String = type.fields.first { it.getInt(null) == id }.name

    private fun unescape(text: String) = text.replace("\\'", "'").replace("\\\"", "\"")

    private fun org.w3c.dom.NodeList.forEach(action: (Element) -> Unit) {
        for (i in 0 until length) action(item(i) as Element)
    }
}
