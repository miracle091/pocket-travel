package com.pockettravel.core.data

/** Dove la ricerca ha trovato le parole. */
enum class SearchField { ANSWER, TITLE, QUESTION }

/** Parte di testo da mostrare; [highlights] sono le posizioni delle parole trovate in [text] (estremi compresi). */
data class SearchExcerpt(val text: String, val highlights: List<IntRange>)

/** Una conversazione trovata: [turnIndex] è il turno della risposta o della domanda, null per il titolo. */
data class ConversationSearchResult(
    val conversation: SavedConversation,
    val field: SearchField,
    val turnIndex: Int?,
    val excerpt: SearchExcerpt,
)

// Testo normalizzato e parole calcolati una volta, quando la conversazione entra nell'indice, non a ogni ricerca.
private class SearchDoc(val original: String) {
    val normalized: NormalizedText = normalizeForSearch(original)
    private val words: List<IntRange> = searchWords(normalized.text)

    fun matches(query: List<String>): List<IntRange>? = prefixMatches(normalized.text, query, words)
}

private class IndexedTurn(val question: SearchDoc, val answer: SearchDoc)

private class IndexedConversation(val conversation: SavedConversation, val title: SearchDoc, val turns: List<IndexedTurn>)

private class Hit(val field: SearchField, val turnIndex: Int?, val doc: SearchDoc, val matches: List<IntRange>)

/**
 * Indice in memoria delle conversazioni, per la ricerca: testi già normalizzati, mai scritti sul disco. Per ogni
 * conversazione tiene il suo `updatedAt`, così chi lo aggiorna rilegge solo quelle cambiate. Si butta quando la
 * cronologia si richiude (ConversationRepository.closeSearch).
 */
internal class ConversationSearchIndex {
    // Una conversazione che non si decifra resta come voce vuota: non si riprova a ogni ricerca.
    private class Entry(val updatedAt: Long, val content: IndexedConversation?)

    private val entries = HashMap<String, Entry>()

    /** Toglie le conversazioni che non sono più in [ids]. */
    @Synchronized
    fun retainOnly(ids: Set<String>) {
        entries.keys.retainAll(ids)
    }

    @Synchronized
    fun isCurrent(id: String, updatedAt: Long): Boolean = entries[id]?.updatedAt == updatedAt

    @Synchronized
    fun forget(id: String) {
        entries.remove(id)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun put(conversation: SavedConversation, turns: List<ConversationTurn>) {
        val content = IndexedConversation(
            conversation,
            SearchDoc(conversation.summary.title),
            turns.map { IndexedTurn(SearchDoc(it.askedQuestion), SearchDoc(it.answer)) },
        )
        entries[conversation.id] = Entry(conversation.updatedAt, content)
    }

    @Synchronized
    fun putUnreadable(id: String, updatedAt: Long) {
        entries[id] = Entry(updatedAt, null)
    }

    /**
     * Le conversazioni in cui ogni parola di [query] inizia una parola di risposta, titolo o domanda (non le fonti).
     * Prima quelle trovate in una risposta, poi quelle trovate in titolo o domanda; in ogni gruppo prima più parole
     * trovate, poi la conversazione più recente.
     */
    @Synchronized
    fun search(query: String): List<ConversationSearchResult> {
        val words = queryWords(query)
        if (words.isEmpty()) return emptyList()
        return entries.values.mapNotNull { it.content }
            .mapNotNull { content -> bestHit(content, words)?.let { content to it } }
            .sortedWith(
                compareBy<Pair<IndexedConversation, Hit>> { if (it.second.field == SearchField.ANSWER) 0 else 1 }
                    .thenByDescending { it.second.matches.size }
                    .thenByDescending { it.first.conversation.updatedAt },
            )
            .map { (content, hit) -> ConversationSearchResult(content.conversation, hit.field, hit.turnIndex, excerpt(hit)) }
    }

    // Il risultato di una conversazione: la risposta con più parole trovate (a parità la più recente); senza risposte,
    // il titolo o la domanda con più parole trovate (a parità il titolo, poi la domanda più vecchia).
    private fun bestHit(content: IndexedConversation, words: List<String>): Hit? {
        val answers = content.turns.mapIndexedNotNull { index, turn ->
            turn.answer.matches(words)?.let { Hit(SearchField.ANSWER, index, turn.answer, it) }
        }
        if (answers.isNotEmpty()) return answers.reduce { best, hit -> if (hit.matches.size >= best.matches.size) hit else best }
        val others = listOfNotNull(content.title.matches(words)?.let { Hit(SearchField.TITLE, null, content.title, it) }) +
            content.turns.mapIndexedNotNull { index, turn ->
                turn.question.matches(words)?.let { Hit(SearchField.QUESTION, index, turn.question, it) }
            }
        return others.reduceOrNull { best, hit -> if (hit.matches.size > best.matches.size) hit else best }
    }

    private fun excerpt(hit: Hit): SearchExcerpt {
        val normalized = hit.doc.normalized
        val matches = hit.matches.map { normalized.originStart(it.first) until normalized.originEnd(it.last) }
        // Ogni a capo diventa uno spazio: l'estratto sta su una riga, con le stesse posizioni.
        val flat = hit.doc.original.map { if (it.isWhitespace()) ' ' else it }
        val first = matches.first()
        val from = excerptStart(flat, first.first)
        val to = excerptEnd(flat, first.last + 1)
        val lead = if (from > 0) ELLIPSIS else ""
        val tail = if (to < flat.size) ELLIPSIS else ""
        val text = lead + flat.subList(from, to).joinToString("") + tail
        val highlights = matches.filter { it.first >= from && it.last < to }.map { (it.first - from + lead.length)..(it.last - from + lead.length) }
        return SearchExcerpt(text, highlights)
    }

    private companion object {
        const val ELLIPSIS = "…"
    }
}

private const val EXCERPT_CONTEXT = 40

// Da circa 40 caratteri prima della parola trovata, senza tagliare una parola a metà.
private fun excerptStart(text: List<Char>, matchStart: Int): Int {
    var from = (matchStart - EXCERPT_CONTEXT).coerceAtLeast(0)
    while (from in 1 until matchStart && text[from - 1].isLetterOrDigit() && text[from].isLetterOrDigit()) from++
    while (from < matchStart && !text[from].isLetterOrDigit()) from++
    return from
}

// Fino a circa 40 caratteri dopo la parola trovata, senza tagliare una parola a metà.
private fun excerptEnd(text: List<Char>, matchEnd: Int): Int {
    var to = (matchEnd + EXCERPT_CONTEXT).coerceAtMost(text.size)
    while (to in (matchEnd + 1) until text.size && text[to - 1].isLetterOrDigit() && text[to].isLetterOrDigit()) to--
    return to
}
