package com.pockettravel.core.data.advice

import com.pockettravel.core.data.GuideSection
import kotlinx.coroutines.CancellationException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Paese (slug della pagina FCDO) e giorno dei consigli di viaggio contenuti nel pacchetto guide installato. */
data class PackageTravelAdvice(val slug: String, val day: LocalDate)

private val fcdoUrl = Regex("""^https://www\.gov\.uk/foreign-travel-advice/([a-z0-9-]+)""")
private val footerDate = Regex("""Last updated by the UK government: ([A-Za-z]+ \d{1,2}, \d{4})\.""")
private val versionPrefix = Regex("""^(\d{4})\.(\d{2})\.(\d{2})""")
private val footerFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

/**
 * Lo slug si legge dal link delle sezioni FCDO della guida (tutte, anche quelle per una sola nazionalita'); il giorno dal
 * piede "Last updated by the UK government: ..." che la pipeline scrive nelle sezioni inglesi. Le guide tradotte possono
 * avere il piede tradotto: senza piede leggibile si usa il giorno della versione del pacchetto guide (aaaa.MM.gg.HHmm),
 * cioe' della sua pubblicazione. Null senza sezioni FCDO o senza nessuna data.
 */
fun packageTravelAdvice(sections: List<GuideSection>, guidesVersion: String?): PackageTravelAdvice? {
    val fcdo = sections.mapNotNull { section -> fcdoUrl.find(section.sourceUrl)?.let { section to it.groupValues[1] } }
    val day = fcdo.firstNotNullOfOrNull { (section, _) -> footerDay(section.body) } ?: guidesVersion?.let(::versionDay)
    val slug = fcdo.firstOrNull()?.second
    return if (slug != null && day != null) PackageTravelAdvice(slug, day) else null
}

private fun footerDay(body: String): LocalDate? = footerDate.find(body)?.let {
    try {
        LocalDate.parse(it.groupValues[1], footerFormat)
    } catch (_: DateTimeException) {
        null
    }
}

private fun versionDay(version: String): LocalDate? = versionPrefix.find(version)?.let { match ->
    try {
        val (year, month, day) = match.destructured
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    } catch (_: DateTimeException) {
        null
    }
}

/**
 * L'ultima modifica dei consigli FCDO se e' di un giorno successivo a quelli del pacchetto installato, altrimenti null.
 * Un errore inatteso non deve fermare la guida: l'avviso resta nascosto.
 */
suspend fun TravelAdviceUpdates.newerThanPackage(sections: List<GuideSection>, guidesVersion: String?): TravelAdviceUpdate? {
    val installed = packageTravelAdvice(sections, guidesVersion) ?: return null
    val update = try {
        latest(installed.slug)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
    return update?.takeIf { it.isAfter(installed.day) }
}
