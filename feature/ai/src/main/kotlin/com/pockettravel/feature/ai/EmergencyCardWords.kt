package com.pockettravel.feature.ai

/**
 * Pericoli per la vita che fanno comparire il riquadro di emergenza prima della ricerca e del modello ("mio padre non
 * respira", "my friend is unconscious"): l'assistente mostra subito il numero da chiamare, la risposta arriva dopo.
 * Il confronto e' sul testo senza accenti e in minuscolo ([folded]), a parole intere. Solo frasi su qualcuno in pericolo
 * adesso: "un incidente" si', "gli incidenti stradali in Italia" no (plurale, domanda informativa); "accident" si',
 * "accidents" no.
 */
private val lifeThreatIt = listOf(
    """non (respira|respiro|respirano)""", """(smesso|smette) di respirare""", """non riesce a respirare""",
    """non (respira|riesce a respirare) bene""", """fatica a respirare""", """soffoca(ndo)?""",
    """incosciente""", """(e|sono) svenut[oaie]""", """svenimento""", """non si sveglia""", """non risponde piu""",
    """emorragia""", """perde (molto|tanto) sangue""", """sanguina (molto|tanto)""", """sangue che non si ferma""",
    """infarto""", """arresto cardiaco""", """attacco di cuore""", """dolore (forte )?al petto""", """ictus""",
    """un incidente""", """incidente (stradale|in moto|in macchina|in auto)""", """(e stat[oa]|sono stat[ie]) investit[oaie]""",
    """annega(ndo|to|ta)?""", """affoga(ndo|to|ta)?""", """overdose""", """avvelenat[oaie]""", """avvelenamento""",
    """convulsioni""", """shock anafilattico""", """reazione allergica grave""",
)

private val lifeThreatEn = listOf(
    """not breathing""", """(stopped|stops) breathing""", """(can ?not|can't|cant) breathe""", """struggling to breathe""",
    """choking""", """unconscious""", """passed out""", """fainted""", """(won't|wont|will not) wake up""",
    """not responding""", """(heavy|severe) bleeding""", """bleeding (heavily|badly|a lot)""", """won't stop bleeding""",
    """heart attack""", """cardiac arrest""", """chest pain""", """(a|having a|had a) stroke""",
    """an accident""", """(car|road|traffic|motorbike|bike) accident""", """(hit|run over) by a (car|bus|truck|motorbike)""",
    """drowning""", """drowned""", """overdose""", """poisoned""", """poisoning""", """(a )?seizure""",
    """anaphylactic""", """anaphylaxis""", """severe allergic reaction""",
)

private val lifeThreatWords = Regex(
    (lifeThreatIt + lifeThreatEn).joinToString("|", prefix = """\b(""", postfix = """)\b"""),
)

/** True se la domanda parla di un pericolo per la vita in corso (italiano o inglese). */
internal fun isLifeThreatening(question: String): Boolean = lifeThreatWords.containsMatchIn(folded(question))

/**
 * Catastrofi naturali ed eventi meteo estremi: con la rete il riquadro dell'assistente mostra le allerte in corso nella
 * regione ("c'e' un terremoto?", "is there a hurricane warning?"). Anche al plurale e nelle domande informative, perche'
 * le allerte servono proprio a chi chiede. Stesso confronto di [isLifeThreatening].
 */
private val disasterIt = listOf(
    """terremot[oi]""", """scoss[ae] (di terremoto|sismic[ah]e?)""", """sism[ai]""", """alluvion[ei]""", """inondazion[ei]""",
    """esondazion[ei]""", """nubifragi[oi]""", """uragan[oi]""", """ciclon[ei]""", """tifon[ei]""", """tempest[ae]""",
    """tsunami""", """maremot[oi]""", """incendi[o]?( boschiv[oi])?""", """eruzion[ei]""", """vulcan[oi]""", """fran[ae]""",
    """valanghe?""", """slavin[ae]""", """siccita""", """tromb[ae] d'aria""", """allert[ae]( meteo)?""", """emergenza meteo""",
)

private val disasterEn = listOf(
    """earthquakes?""", """quakes?""", """tremors?""", """floods?""", """flooding""", """flash floods?""", """hurricanes?""",
    """cyclones?""", """typhoons?""", """storms?""", """tsunamis?""", """wildfires?""", """bushfires?""", """forest fires?""",
    """eruptions?""", """volcano(es)?""", """volcanic""", """landslides?""", """avalanches?""", """droughts?""", """tornado(es)?""",
    """(weather )?alerts?""", """(weather )?warnings?""",
)

private val disasterWords = Regex(
    (disasterIt + disasterEn).joinToString("|", prefix = """\b(""", postfix = """)\b"""),
)

/** True se la domanda parla di una catastrofe naturale o di un'allerta meteo (italiano o inglese). */
internal fun isDisasterQuestion(question: String): Boolean = disasterWords.containsMatchIn(folded(question))
