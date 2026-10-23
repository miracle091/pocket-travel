#!/usr/bin/env python3
"""Genera il test esteso (eval_extended.jsonl) sulle regioni di test di train_lora.py (TEST_REGIONS in eval_common.py).

Il test base usa le stesse domande e la stessa forma di negativo del training: misura se il modello ha
imparato quel criterio, non se generalizza. Qui le domande sono scritte a mano e NON sono nei template
di training (QUESTIONS* in generate_sft_dataset.py), e il contesto e' composto come nell'app
(make_context: fino a 3 sezioni unite entro 2000 caratteri, come l'app; oppure il testo di fallback). Tipi:
  pos_para    positivo, domanda riformulata (IT o EN), sezione giusta + sezioni distraenti;
  neg_para    negativo (categoria assente dal contesto), domanda riformulata (IT o EN);
  neg_empty   negativo con il contesto di fallback (la ricerca dell'app non trova nulla);
  neg_off     domanda che non c'entra con la guida (cultura generale, cucina, sport; IT e EN).
Un positivo/negativo si tiene solo se il contesto tratta (o non tratta) davvero la categoria (KEYWORDS):
le sezioni Wikivoyage a volte coprono altro (es. 'restare in contatto' = posta).
Con --dump-dir (i dump di generate_sft_dataset.py) aggiunge in coda righe sulle citta' delle regioni di test,
che il training esclude: pos_city (domande riformulate, PARA_CITY) e neg_city (categoria assente dalla pagina).
In fondo, con un seme proprio (le righe precedenti non cambiano), domande su cosa c'e' qui vicino e sui mezzi con i
blocchi di contesto dell'app (nearby_rows, come generate_sft.py --nearby ma con domande, nomi e seme diversi):
  pos_near    il blocco "Punti di interesse entro..." ha la categoria chiesta (o la domanda e' generica);
  neg_near    il blocco non ha la categoria chiesta, o non c'e' (nessuna posizione): solo la guida;
  pos_dep     il blocco "Prossime partenze..." ha il mezzo o la linea chiesti, oppure dice che gli orari sono
              scaduti o che non ci sono partenze nelle prossime ore (si risponde con quello, non si rifiuta);
  neg_dep     il tabellone non ha il mezzo chiesto, o non c'e' (nessuna fermata vicina): solo la guida.
Con --cities-db (i cities.db pubblicati, come generate_sft.py --cities-db, e solo le regioni di test), ancora dopo,
domande di storia e clima delle loro citta' (wikipedia_rows, con un seme proprio):
  pos_wiki    la sezione Storia o Clima della citta' ha la risposta;
  neg_wiki    la citta' non ha la sezione chiesta: il contesto ha solo altre sezioni.
In coda a tutto, con "set": "v11" e semi propri (le righe precedenti non cambiano), righe in piu' per i gruppi piccoli:
  neg_city    CITY_EXTRA_NEG rifiuti con altre domande (PARA_CITY_EXTRA, due italiane e una inglese) e casi difficili
              (nel contesto la sezione di una categoria vicina, NEAR_CATEGORY), "question_lang" per riga;
  pos_near/neg_near  NEAR_EXTRA_QUOTAS righe con sinonimi e bisogni diversi dal training (NEAR_QUESTIONS_EXTRA).
I contesti dei rifiuti delle citta' non hanno sezioni che trattano la categoria chiesta (radici italiane o inglesi), come
nel training; i neg_para con un contesto inglese che la tratta con le radici inglesi si tolgono.
Legge solo la cache di generate_sft_dataset.py (data/sft/raw), i dump e i cities.db: niente rete.
Uso: python generate_eval_set.py [--dump-dir <cartella dei dump> [--dump-date MM-AAAA]] [--cities-db <cities.db> ...]
"""
import argparse
import json
import random
import sys
from collections import Counter
from pathlib import Path

from eval_common import TEST_REGIONS

import generate_sft
import sft_nearby
import wiki_dump
from generate_sft_dataset import (CITY_HEADING_TO_CATEGORY, CITY_MIN_SECTION, DUMP_WIKIS, EN_HEADING_TO_CATEGORY,
                                  FALLBACK_CONTEXT, HEADING_TO_CATEGORY, OUT, QUESTIONS, TOPIC, city_parents, covers,
                                  intro_sentences, load_regions, load_sources, make_context, on_device_prompt, parse_sections,
                                  pick_answer)

PARA = {  # riformulazioni generiche: coprono tutta la categoria, cosi' il positivo ha davvero la risposta nel contesto
    "USI_COSTUMI": ["Che galateo bisogna seguire in {r}?", "Consigli di buona educazione per {r}?", "Come evito gaffe con la popolazione di {r}?"],
    "DOGANE": ["Qual e' il modo migliore per arrivare in {r}?", "Con che mezzi si raggiunge {r}?", "Come faccio a entrare in {r}?"],
    "SALUTE": ["Cosa devo sapere per stare in salute in {r}?", "Ci sono malattie da tenere d'occhio in {r}?", "Com'e' la situazione sanitaria in {r}?"],
    "SICUREZZA": ["Si puo' girare tranquilli in {r}?", "Che precauzioni prendo in {r}?", "Quanto e' rischioso viaggiare in {r}?"],
    "TRASPORTI": ["Come funzionano gli spostamenti in {r}?", "Come ci si muove tra le citta' in {r}?", "Posso muovermi senza auto in {r}?"],
    "CIBO_BEVANDE": ["Quali piatti tipici provo in {r}?", "Dove si mangia bene in {r}?", "Com'e' la tavola in {r}?"],
    "ACQUISTI": ["Che moneta serve in {r}?", "Come mi regolo con i prezzi in {r}?", "Come funziona il denaro in {r}?"],
    "CONNETTIVITA": ["Come resto connesso in {r}?", "Com'e' la copertura telefonica in {r}?", "Riesco a stare online in {r}?"],
    "VITA_QUOTIDIANA": ["Come resto aggiornato durante il soggiorno in {r}?", "Che fonti uso per informarmi in {r}?", "Quali canali TV o radio si ricevono in {r}?"],
}
# Niente domande ambigue (vedi AMBIGUOUS_QUESTIONS di generate_sft.py): "Dove trovo info pratiche per X?" e "Where do I find
# practical info for X?" le soddisfa ogni sezione, quindi il loro rifiuto ha la risposta nel contesto. Sostituite il
# 2026-10-08: i risultati di prima su questo test non sono confrontabili con quelli dopo.
PARA_EN = {  # come PARA, in inglese (2 per categoria)
    "USI_COSTUMI": ["Which manners should I keep in mind in {r}?", "How do I avoid offending people in {r}?"],
    "DOGANE": ["What is the best way to arrive in {r}?", "Which entry requirements apply to {r}?"],
    "SALUTE": ["Is it healthy to travel in {r}?", "Which medical issues exist in {r}?"],
    "SICUREZZA": ["Can I walk around {r} without worries?", "Which precautions should I take in {r}?"],
    "TRASPORTI": ["How does getting around work in {r}?", "Can I travel around {r} without a car?"],
    "CIBO_BEVANDE": ["Which local dishes should I try in {r}?", "Where is the best place to eat in {r}?"],
    "ACQUISTI": ["Which money do I need in {r}?", "How do prices and payments work in {r}?"],
    "CONNETTIVITA": ["How do I stay connected in {r}?", "How is mobile coverage in {r}?"],
    "VITA_QUOTIDIANA": ["How do I keep up with news in {r}?", "Which TV channels can I watch in {r}?"],
}
PARA_CITY = {  # domande sulle citta' riformulate: non sono in CITY_QUESTIONS/CITY_QUESTIONS_EN del training
    "ARRIVARE": ["Con che mezzi arrivo fino a {r}?", "How can I reach {r}?"],
    "TRASPORTI": ["Come giro per {r} senza auto?", "What is the easiest way to move around {r}?"],
    "COSA_VEDERE": ["Che posti meritano una visita a {r}?", "Which places are worth visiting in {r}?"],
    "CIBO_BEVANDE": ["Qualche posto dove cenare a {r}?", "Where do locals eat in {r}?"],
    "ALLOGGIO": ["Che sistemazioni ci sono a {r}?", "Which kinds of accommodation exist in {r}?"],
    "SICUREZZA": ["Devo stare attento a qualcosa a {r}?", "How safe is it to walk around {r}?"],
    "CONNETTIVITA": ["Come mi collego a internet a {r}?", "Where can I find wifi in {r}?"],
    "SHOPPING": ["Dove compro souvenir a {r}?", "Where are the markets in {r}?"],
}
CITY_POS, CITY_NEG = 50, 20  # righe di citta' nel test (con --dump-dir)
# Domande di storia e clima delle citta' (con --cities-db), non in WIKI_QUESTIONS del training e con una parola di
# historyClimateWords (TravelAssistant.kt), per lingua e categoria (il test usa i cities.db di una sola lingua)
PARA_WIKI = {
    "it": {"STORIA": ["Come e' nata {r} e come si e' sviluppata nei secoli?", "Che passato storico ha {r}?",
                      "Mi racconti le origini antiche di {r}?"],
           "CLIMA": ["Com'e' il meteo a {r} durante l'anno?", "Che tempo fa a {r} nelle diverse stagioni?",
                     "Mi descrivi il clima di {r}?"]},
    "en": {"STORIA": ["How did {r} develop through its history?", "What historical events took place in {r}?",
                      "Which centuries were important for {r}?"],
           "CLIMA": ["What is the weather like in {r} through the year?", "How would you describe the climate of {r}?",
                     "What are the seasons like in {r}?"]},
}
WIKI_POS, WIKI_NEG = 24, 8  # righe di storia e clima nel test per lingua (con --cities-db)
WIKI_SEED = 2611  # proprio, come NEAR_SEED: le righe precedenti non cambiano
OFF_TOPIC = ["Qual e' la capitale della Francia?", "Come si prepara la carbonara?", "Chi ha vinto i mondiali di calcio nel 2006?",
             "Quanto fa 17 per 23?", "Scrivimi una poesia sul mare.", "Chi ha scritto la Divina Commedia?",
             "Come si installa Python su Windows?", "Qual e' il senso della vita?",
             "What is the capital of Spain?", "How do I cook pasta?"]

# Domande su cosa c'e' qui vicino (per categoria dei POI, None = generica) e sui mezzi (generiche, per mezzo, per linea {l}),
# con le chiavi di ASK e TRANSIT_ASK di sft_nearby.py, da cui si prendono l'inizio della risposta e l'argomento del rifiuto.
# Scritte in altro modo rispetto al training (test_sft_nearby.py controlla che non abbiano 4 parole di fila in comune con
# quelle di training) e con le parole che in TravelAssistant.kt attivano lo stesso blocco (nearbyWords, transitWords).
NEAR_QUESTIONS = {
    "it": {
        None: ["Che posti ci sono qui attorno?", "Elencami i luoghi utili nelle vicinanze."],
        "FARMACIA": ["Sto cercando una farmacia nei dintorni, ce n'è una?"],
        "BANCOMAT": ["Devo prelevare contanti: c'è uno sportello nelle vicinanze?"],
        "CIBO_BEVANDE": ["Ho fame, dove trovo da mangiare qui attorno?"],
        "BAGNI_PUBBLICI": ["Dove trovo una toilette pubblica qui attorno?"],
        "OSPEDALE": ["Mi sono fatto male, quanto dista il pronto soccorso più vicino?"],
        "UFFICIO_POSTALE": ["Devo spedire una cartolina: ci sono le poste nei dintorni?"],
        "INFORMAZIONI": ["Mi servono delle mappe, c'è un punto informazioni nei dintorni?"],
        "PARCHEGGIO": ["Devo lasciare la macchina, c'è un posteggio qui attorno?"],
        "ALLOGGIO": ["Cerco un albergo nelle vicinanze, ne vedi qualcuno?"],
        "NEGOZI": ["Devo comprare qualcosa, ci sono botteghe qui attorno?"],
        "POLIZIA": ["Devo sporgere denuncia: dov'è il commissariato più vicino a me?"],
        "MUSEI_ARTE": ["Ci sono mostre o gallerie da visitare qui attorno?"],
        "ACQUA_POTABILE": ["Posso riempire la borraccia da qualche parte qui attorno?"],
        "CARBURANTE": ["Ho la riserva accesa: c'è un distributore nei dintorni?"],
    },
    "en": {
        None: ["Which places are close by?", "Give me a list of useful spots around here."],
        "FARMACIA": ["Can you find me a chemist close by?"],
        "BANCOMAT": ["I need cash, is there a cash machine close by?"],
        "CIBO_BEVANDE": ["I'm hungry, where can I grab a bite close by?"],
        "BAGNI_PUBBLICI": ["Is there a restroom I can use around here?"],
        "OSPEDALE": ["How far away is the closest hospital?"],
        "UFFICIO_POSTALE": ["I have to mail a postcard, which post office is closest?"],
        "INFORMAZIONI": ["I need a map, is there a visitor information point close by?"],
        "PARCHEGGIO": ["I have to leave the car somewhere, any car park close by?"],
        "ALLOGGIO": ["Can you find me a place to stay close by?"],
        "NEGOZI": ["I need to buy a few things, any stores close by?"],
        "POLIZIA": ["I have to report a theft, which police station is closest?"],
        "MUSEI_ARTE": ["Are there any galleries or exhibitions around here?"],
        "ACQUA_POTABILE": ["Where can I refill my water bottle close by?"],
        "CARBURANTE": ["I'm low on fuel, is there a petrol station close by?"],
    },
}
# Per le righe in piu' di pos_near e neg_near (NEAR_EXTRA_QUOTAS): altre domande per categoria, con sinonimi e bisogni
# che non sono ne' nel training (ASK di sft_nearby.py) ne' in NEAR_QUESTIONS, e le categorie che il training chiede solo
# con l'etichetta (TAXI, BANCA, LUOGHI_DI_CULTO, LUOGHI_STORICI). Stessi controlli di NEAR_QUESTIONS in test_sft_nearby.py.
NEAR_QUESTIONS_EXTRA = {
    "it": {
        None: ["Fammi un elenco di quello che si trova qui attorno.", "Cosa offre la zona nei dintorni?"],
        "FARMACIA": ["Ho bisogno di un antidolorifico: dove lo prendo qui attorno?", "C'è un farmacista nelle vicinanze?"],
        "BANCOMAT": ["Sono senza contanti, c'è una cassa automatica qui attorno?", "C'è un postamat nelle vicinanze?"],
        "CIBO_BEVANDE": ["Dove faccio uno spuntino qui attorno?", "C'è un'osteria nei dintorni?", "Dove bevo un aperitivo qui attorno?"],
        "BAGNI_PUBBLICI": ["Mi scappa, c'è un gabinetto qui attorno?", "Devo andare in bagno: ce n'è uno nei dintorni?"],
        "OSPEDALE": ["Mi sono slogato una caviglia, dove mi curano qui attorno?", "C'è una clinica nelle vicinanze?"],
        "UFFICIO_POSTALE": ["Devo mandare una raccomandata, dove vado qui attorno?", "Dove trovo le poste nelle vicinanze?"],
        "INFORMAZIONI": ["Cerco una cartina della città: dove la danno qui attorno?", "C'è un punto IAT nei dintorni?"],
        "PARCHEGGIO": ["Dove metto l'auto qui attorno?", "C'è un garage o un'autorimessa nei dintorni?"],
        "ALLOGGIO": ["C'è una locanda qui attorno?", "Cerco un ostello nelle vicinanze, ne trovi?"],
        "NEGOZI": ["C'è un minimarket qui attorno?", "Dove compro il pane nelle vicinanze?"],
        "POLIZIA": ["Mi hanno rubato il telefono, a chi mi rivolgo qui attorno?", "Ci sono agenti nelle vicinanze?"],
        "MUSEI_ARTE": ["C'è un'esposizione da vedere qui attorno?", "Dove trovo una collezione d'arte nelle vicinanze?"],
        "ACQUA_POTABILE": ["Ho sete, dove mi disseto qui attorno?", "C'è un rubinetto pubblico nelle vicinanze?"],
        "CARBURANTE": ["Devo fare il pieno, dove vado qui attorno?", "C'è una pompa di benzina nelle vicinanze?"],
        "TAXI": ["Mi serve una corsa in taxi, dove lo trovo qui attorno?", "C'è una stazione dei taxi nelle vicinanze?"],
        "BANCA": ["Devo parlare con la mia banca: c'è una filiale qui attorno?", "C'è un istituto di credito nelle vicinanze?"],
        "LUOGHI_DI_CULTO": ["Vorrei pregare, c'è una parrocchia qui attorno?", "C'è una cappella nelle vicinanze?"],
        "LUOGHI_STORICI": ["Ci sono rovine da vedere qui attorno?", "C'è qualcosa di antico nelle vicinanze?"],
    },
    "en": {
        None: ["Tell me everything that is close by.", "What does this area offer around here?"],
        "FARMACIA": ["I need painkillers, anywhere close by that sells them?", "Is there a pharmacist around here?"],
        "BANCOMAT": ["I want to withdraw cash, any bank machine close by?", "Where can I take out money close by?"],
        "CIBO_BEVANDE": ["Anywhere to grab a snack close by?", "Is there a pub around here?", "Any takeaway close by?"],
        "BAGNI_PUBBLICI": ["Is there a bathroom I can use close by?", "Which washroom is closest to me?"],
        "OSPEDALE": ["I twisted my ankle, who can treat me close by?", "Is there an urgent care centre around here?"],
        "UFFICIO_POSTALE": ["I need to post a letter, where can I do that close by?", "Is there a mail office around here?"],
        "INFORMAZIONI": ["Any tourist info desk close by?", "Where can I pick up a city map close by?"],
        "PARCHEGGIO": ["Any free parking spot close by?", "Any garage to leave the car in around here?"],
        "ALLOGGIO": ["Is there an inn close by?", "Any motel or hostel around here?"],
        "NEGOZI": ["Is there a corner shop close by?", "Any bakery around here?"],
        "POLIZIA": ["My bag was stolen, who can I go to close by?", "Are there any cops around here?"],
        "MUSEI_ARTE": ["Is there an exhibit to see close by?", "Any art space around here?"],
        "ACQUA_POTABILE": ["I'm thirsty, is there a water tap close by?", "Any public tap around here?"],
        "CARBURANTE": ["Which gas pump is closest?", "I need diesel, anything close by?"],
        "TAXI": ["Where can I catch a cab close by?", "Any taxi stand around here?"],
        "BANCA": ["Any bank branch close by?", "I need to see my bank, is there one around here?"],
        "LUOGHI_DI_CULTO": ["I would like to pray, is there a mosque or a chapel close by?", "Which temple is closest to me?"],
        "LUOGHI_STORICI": ["Are there any ruins close by?", "Any old landmarks around here?"],
    },
}
DEP_QUESTIONS = {
    "it": {
        "any": ["Che partenze ci sono a breve?", "Fra quanto c'è la prima partenza utile?"],
        "mode": {"BUS": ["Fra quanto arriva un autobus?"], "TRAM": ["Tra quanti minuti arriva il tram?"],
                 "METRO": ["Fra quanto arriva una metropolitana?"], "TRAIN": ["C'è un treno in partenza a breve?"],
                 "TROLLEYBUS": ["Fra quanto arriva il filobus?"], "FERRY": ["Fra quanto salpa un traghetto?"]},
        "line": {"BUS": ["Tra quanto arriva l'autobus {l}?"], "TRAM": ["Il tram {l} fra quanto arriva?"],
                 "METRO": ["Fra quanto arriva la metro {l}?"], "TRAIN": ["Fra quanto si parte col treno {l}?"],
                 "FERRY": ["Il traghetto {l} fra quanto salpa?"]},
    },
    "en": {
        "any": ["Which departures are coming up soon?", "Show me the upcoming departures."],
        "mode": {"BUS": ["How long until a bus arrives?"], "TRAM": ["How many minutes until a tram comes?"],
                 "METRO": ["How long must I wait for the metro?"], "TRAIN": ["Is there a train leaving soon?"],
                 "TROLLEYBUS": ["Is a trolleybus departure coming up soon?"], "FERRY": ["How long until a ferry sails?"]},
        "line": {"BUS": ["How long until bus {l} gets here?"], "TRAM": ["How soon will tram {l} arrive?"],
                 "METRO": ["How long until metro {l} arrives?"], "TRAIN": ["Is train {l} leaving soon?"],
                 "FERRY": ["How soon does ferry {l} sail?"]},
    },
}
# Nomi e direzioni dei dati sintetici, per lingua locale, diversi da SURNAMES, PLACES e HEADSIGNS di sft_nearby.py
NEAR_SURNAMES = {"it": ["Romano", "Costa", "Giordano", "Mancini", "Lombardi", "Barbieri", "Moretti"],
                 "es": ["Fernández", "González", "Díaz", "Moreno", "Jiménez", "Álvarez"],
                 "fr": ["Petit", "Durand", "Leroy", "Girard", "Bonnet", "Mercier"],
                 "de": ["Meyer", "Koch", "Richter", "Klein", "Wolf", "Neumann"],
                 "en": ["Johnson", "Roberts", "Davies", "Thompson", "Green", "Clarke"]}
NEAR_PLACES = {"it": ["Cavour", "Verdi", "Dante", "Sant'Anna", "Porta Romana", "Belvedere", "Marina"],
               "es": ["de la Paz", "San Miguel", "del Sol", "la Merced", "Santa Cruz", "del Río"],
               "fr": ["de la Poste", "Saint-Jean", "du Pont", "des Lilas", "de l'Église", "du Moulin"],
               "de": ["Anger", "Kloster", "Mühlen", "Wiesen", "Burg", "Garten"],
               "en": ["Mill", "Abbey", "Quay", "Meadow", "Chapel", "Victoria"]}
DEP_HEADSIGNS = {"it": ["Fiera", "Policlinico", "Lungomare", "Piazza Grande", "Zona Industriale", "Parco Nord"],
                 "es": ["Ciudad Universitaria", "Feria", "Polígono", "Barrio Alto", "Parque Norte", "Muelle"],
                 "fr": ["Gare Routière", "Zone Industrielle", "Parc des Expositions", "Mairie", "Lycée", "Quai Sud"],
                 "de": ["Messe", "Zoo", "Westfriedhof", "Gewerbegebiet", "Nordbad", "Kliniken"],
                 "en": ["Showground", "Industrial Estate", "Seafront", "Park and Ride", "College", "Riverside"]}
NEAR_SEED = 2610  # diverso dal --seed di generate_sft.py (42)
# Righe per lingua, per tipo e caso: "blocco" con la risposta (tabellone con il mezzo o la linea, per pos_dep), "scaduti"
# e "vuoto" (orari scaduti, nessuna partenza), "manca" (blocco senza la categoria o il mezzo chiesti), "assente" (nessun blocco)
NEAR_QUOTAS = {("pos_near", "blocco"): 14, ("neg_near", "manca"): 6, ("neg_near", "assente"): 4,
               ("pos_dep", "blocco"): 9, ("pos_dep", "scaduti"): 3, ("pos_dep", "vuoto"): 2,
               ("neg_dep", "manca"): 6, ("neg_dep", "assente"): 4}
# Righe in piu' di pos_near e neg_near, in coda al test con un seme proprio e le domande di NEAR_QUESTIONS_EXTRA: con 14
# righe un rifiuto in piu' sposta pos_near di 7 punti, con 120 di meno di uno.
NEAR_EXTRA_QUOTAS = {("pos_near", "blocco"): 106, ("neg_near", "manca"): 18, ("neg_near", "assente"): 12}
NEAR_EXTRA_SEED = 2612


def nearby_rows(lang, guides, row, refusal, quotas=NEAR_QUOTAS, questions=NEAR_QUESTIONS, seed=NEAR_SEED):
    """Righe pos_near/neg_near e pos_dep/neg_dep fino a [quotas]: blocchi e risposte di sft_nearby.py con [questions] e i
    nomi qui sopra, poi le sezioni della guida di una regione di test come nel training (generate_sft.nearby_context).
    [guides]: [(regionId, nome, [corpi delle sezioni])]; [refusal]: argomento -> rifiuto."""
    if not guides:
        return []
    rng, L = random.Random(seed), generate_sft.LANGS[lang]
    asks = {c: (qs, *sft_nearby.ASK[lang][c][1:]) for c, qs in questions[lang].items()}
    transit_asks = {**sft_nearby.TRANSIT_ASK[lang], **DEP_QUESTIONS[lang]}
    no_departures = sft_nearby.transit_context(("departures", []), lang)
    done, out, seen = Counter(), [], set()
    for i in range(5000):
        if sum(done.values()) == sum(quotas.values()):
            break
        transit = i % 2 == 1
        if transit:
            block, q, answer, kind = sft_nearby.transit_example(rng, lang, refusal, transit_asks, DEP_HEADSIGNS)
        else:
            block, q, answer, kind = sft_nearby.poi_example(rng, lang, refusal, asks, NEAR_SURNAMES, NEAR_PLACES)
        kind += "_dep" if transit else "_near"
        case = ("assente" if block is None else "manca" if kind.startswith("neg") else "blocco" if "\n" in block
                else "vuoto" if block == no_departures else "scaduti")
        if done[kind, case] >= quotas.get((kind, case), 0):
            continue
        rid, name, bodies = guides[len(out) % len(guides)]
        context = generate_sft.nearby_context(rng, block, q, name, bodies, L, transit)
        if (context, q) in seen:
            continue
        seen.add((context, q))
        done[kind, case] += 1
        out.append(row(kind, rid, "PARTENZE" if transit else "VICINO", context, q, answer))
    rng.shuffle(out)
    return out


def wikipedia_rows(lang, specs, row, refuse):
    """Righe pos_wiki e neg_wiki sulle citta' delle regioni di test dei cities.db di [specs] (generate_sft.load_city_sections):
    una domanda per sezione Storia o Clima, al massimo WIKI_POS positivi e WIKI_NEG rifiuti in tutto, a rotazione tra le citta'.
    Contesti e risposte come nel training (wikipedia_positive e wikipedia_refusal di generate_sft.py), con le domande di
    PARA_WIKI. [refuse]: argomento -> rifiuto."""
    rng, L = random.Random(WIKI_SEED), generate_sft.LANGS[lang]
    answer_for, keywords = generate_sft.make_answer_for(L, legacy=True), generate_sft.all_keywords(L)
    ask = lambda cat, name, city=False, wiki=False: rng.choice(PARA_WIKI[lang][cat]).format(r=name)
    cities = [(city, secs) for region, by_city in sorted(generate_sft.load_city_sections(specs).items()) if region in TEST_REGIONS
              for city, secs in sorted(by_city.items()) if any(s[0] in generate_sft.WIKI_CATS for s in secs)]
    rng.shuffle(cities)
    pos, neg = [], []
    for city, secs in cities:
        name, rid = city.split(" (")[0], f"citta:{city}"  # "Salem (Oregon)" -> "Salem"
        for sec in (s for s in secs if s[0] in generate_sft.WIKI_CATS):
            if len(pos) < WIKI_POS and covers(sec[0], sec[1], keywords):
                if example := generate_sft.wikipedia_positive(rng, lang, name, sec, secs, ask, answer_for):
                    pos.append(row("pos_wiki", rid, *example[:4]))
        if len(neg) < WIKI_NEG and (example := generate_sft.wikipedia_refusal(rng, lang, name, secs, ask, refuse)):
            neg.append(row("neg_wiki", rid, *example[:4]))
    print(f"citta' delle regioni di test con Storia o Clima: {len(cities)}, righe pos {len(pos)} neg {len(neg)}")
    return pos + neg


CITY_FIX_SEED = 2613  # contesti dei rifiuti rifatti (refusal_context): le altre righe delle citta' non cambiano
# Righe in piu' di neg_city, in coda al test con un seme proprio (city_extra_refusals): con 20 righe l'intervallo al 95%
# di un rifiuto all'85% va dal 64 al 95%.
CITY_EXTRA_NEG = 100
CITY_EXTRA_SEED = 2614
# Domande delle righe in piu', diverse da PARA_CITY e dalle domande delle citta' del training
PARA_CITY_EXTRA = {
    "it": {"ARRIVARE": ["In che modo si arriva a {r} da fuori?", "Quali collegamenti portano a {r}?"],
           "TRASPORTI": ["Com'e' muoversi dentro {r}?", "Come raggiungo i vari quartieri di {r}?"],
           "COSA_VEDERE": ["Che monumenti o musei offre {r}?", "Cosa merita di essere visto a {r}?"],
           "CIBO_BEVANDE": ["Dove trovo una buona trattoria a {r}?", "Che locali ci sono per mangiare la sera a {r}?"],
           "ALLOGGIO": ["Dove trovo una camera a {r}?", "Ci sono pensioni o B&B a {r}?"],
           "SICUREZZA": ["Ci sono pericoli per i turisti a {r}?", "Quali quartieri di {r} sono rischiosi?"],
           "CONNETTIVITA": ["Come navigo in rete a {r}?", "Dove trovo una rete wifi gratuita a {r}?"],
           "SHOPPING": ["Quali negozi ci sono a {r}?", "Dove si comprano prodotti locali a {r}?"]},
    "en": {"ARRIVARE": ["How do travellers reach {r} from outside?", "What are the routes into {r}?"],
           "TRASPORTI": ["What is moving around inside {r} like?", "How do I get between neighbourhoods in {r}?"],
           "COSA_VEDERE": ["What is worth seeing in {r}?", "Which museums or monuments does {r} have?"],
           "CIBO_BEVANDE": ["Where can I find a good place for supper in {r}?", "Which eateries are there in {r}?"],
           "ALLOGGIO": ["Where can I book a room in {r}?", "Are there guesthouses or B&Bs in {r}?"],
           "SICUREZZA": ["Are there dangers for tourists in {r}?", "Which parts of {r} are risky?"],
           "CONNETTIVITA": ["Where can I get on the web in {r}?", "How can I check my email in {r}?"],
           "SHOPPING": ["Which shops are there in {r}?", "Where can I buy local products in {r}?"]},
}
# Casi difficili delle righe in piu': nel contesto, se la citta' le ha, le sezioni di una categoria vicina a quella
# chiesta (Come arrivare per gli spostamenti in citta', Dove dormire per Dove mangiare), purche' non la trattino.
NEAR_CATEGORY = {"TRASPORTI": "ARRIVARE", "ARRIVARE": "TRASPORTI", "CIBO_BEVANDE": "ALLOGGIO", "ALLOGGIO": "CIBO_BEVANDE",
                 "SHOPPING": "COSA_VEDERE", "COSA_VEDERE": "SHOPPING", "SICUREZZA": "TRASPORTI", "CONNETTIVITA": "ALLOGGIO"}


def treats(cat, text):
    """True se [text] tratta la categoria con le radici italiane o inglesi: come i rifiuti del training, che tolgono dal
    contesto le sezioni sulla categoria in tutte e due le lingue."""
    return covers(cat, text) or covers(cat, text, generate_sft.en.KEYWORDS)


def refusal_context(rng, fix_rng, secs, cat, q, name):
    """Contesto di un rifiuto neg_city: 1-3 sezioni della citta' scelte con [rng] come prima; se il contesto tratta la
    categoria chiesta, rifatto con [fix_rng] dalle sole sezioni che non la trattano (None se non ce ne sono). Con [rng]
    usato sempre allo stesso modo, le altre righe delle citta' restano quelle di prima."""
    context = make_context(rng, rng.sample([b for _, b in secs], min(len(secs), rng.randint(1, 3))), q, name)
    if not treats(cat, context):
        return context
    bodies = [b for _, b in secs if not treats(cat, b)]
    if not bodies:
        return None
    context = make_context(fix_rng, fix_rng.sample(bodies, min(len(bodies), fix_rng.randint(1, 3))), q, name)
    return None if treats(cat, context) else context


def city_extra_refusals(cities, pools, name_of, row, refusal, n=CITY_EXTRA_NEG, seed=CITY_EXTRA_SEED):
    """[n] righe neg_city in piu' sulle citta' [cities] ([(titolo, [(categoria, corpo)])]): una categoria che la citta' non
    ha (a rotazione, la meno usata finora), una domanda di [pools] ([(lingua, {categoria: domande})], a rotazione: con
    [("it", ...), ("it", ...), ("en", ...)] due italiane e una inglese), contesto di 1-3 sezioni che non la trattano, con la
    sezione di NEAR_CATEGORY se la citta' la ha. Ogni riga ha "set": "v11" e la lingua della domanda ("question_lang")."""
    rng, used, out, seen = random.Random(seed), Counter(), [], set()
    pool = list(cities)
    rng.shuffle(pool)
    for title, secs in pool * 3:  # piu' giri: una citta' puo' mancare di piu' categorie
        if len(out) >= n:
            break
        q_lang, questions = pools[len(out) % len(pools)]
        missing = sorted((c for c in questions if c not in {c for c, _ in secs}), key=lambda c: (used[c], rng.random()))
        if not missing:
            continue
        cat, name = missing[0], name_of(title)
        q = rng.choice(questions[cat]).format(r=name)
        bodies = [(c, b) for c, b in secs if not treats(cat, b)]
        hard = [b for c, b in bodies if c == NEAR_CATEGORY.get(cat)]
        rest = [b for c, b in bodies if c != NEAR_CATEGORY.get(cat)]
        chosen = hard[:1] + rng.sample(rest, min(len(rest), rng.randint(0 if hard else 1, 2)))
        if not chosen:
            continue
        context = make_context(rng, chosen, q, name)
        if treats(cat, context) or (context, q) in seen:
            continue
        seen.add((context, q))
        used[cat] += 1
        out.append(row("neg_city", f"citta:{title}", cat, context, q, refusal(cat)) | {"set": "v11", "question_lang": q_lang})
    print(f"neg_city in piu': {len(out)} righe, per categoria {dict(used)}")
    return out


def expected_answer(answer, context, body, q, cat, name):
    """La risposta attesa di una riga gia' nel test ([answer], scelta come nel dataset v10, pick_answer con legacy): la
    stessa, salvo quando comincia con una frase che annuncia un elenco assente (intro_sentences): allora quella di
    pick_answer di oggi, oppure None se non resta una risposta: la riga si toglie dopo il mescolamento, cosi' le altre
    non cambiano."""
    if not any(answer.startswith(x) for _, x in intro_sentences(body)):
        return answer
    fixed = pick_answer(context, body, q, cat, name)
    return fixed if len(fixed) >= 40 else None


def load_test_cities(dump_dir, dump_date):
    """[(titolo, [(categoria, corpo)])] delle citta' delle regioni di test (Stato/Regione/Territorio del QuickbarCity =
    pagina di una regione di test) dal dump IT, in ordine di titolo."""
    sources = load_sources()
    test_titles = {wiki_dump.norm_title(sources[(r, "it")]) for r in TEST_REGIONS if sources.get((r, "it"), "-") != "-"}
    dump = wiki_dump.dump_files(dump_dir, DUMP_WIKIS["it"], dump_date)
    cities = []
    for title, text, redirect in wiki_dump.iter_pages(dump):
        parents = None if redirect else city_parents(text)
        if not parents or not parents & test_titles:
            continue
        secs = [(c, b) for c, b in parse_sections(text, CITY_HEADING_TO_CATEGORY) if covers(c, b) and len(b) >= CITY_MIN_SECTION]
        if secs:
            cities.append((title, secs))
    return sorted(cities)


def city_rows(rng, cities, row, refusal):
    """Righe sulle citta' delle regioni di test ([cities] di load_test_cities), dallo stesso dump IT del training: al
    massimo 2 domande per citta', CITY_POS positivi e CITY_NEG negativi in tutto, a rotazione tra le citta' per non
    pescarle tutte da un solo paese."""
    cities = list(cities)
    rng.shuffle(cities)
    fix_rng, pos, neg, slots, lost = random.Random(CITY_FIX_SEED), [], [], 0, 0
    for title, secs in cities:
        name = title.split(" (")[0]  # "Salem (Oregon)" -> "Salem"
        rid = f"citta:{title}"
        for cat, body in rng.sample(secs, min(2, len(secs))):
            if len(pos) >= CITY_POS:
                break
            others = [b for c, b in secs if c != cat and not covers(cat, b)]
            q = rng.choice(PARA_CITY[cat]).format(r=name)
            context = make_context(rng, [body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
            answer = pick_answer(context, body, q, cat, name, legacy=True)
            if len(answer) >= 40:
                pos.append(row("pos_city", rid, cat, context, q, answer))
        missing = [c for c in PARA_CITY if c not in {c for c, _ in secs}]
        if missing and slots < CITY_NEG:
            slots += 1  # conta i rifiuti tentati, non quelli tenuti: rng resta allineato alle righe di prima
            cat = rng.choice(missing)
            q = rng.choice(PARA_CITY[cat]).format(r=name)
            if context := refusal_context(rng, fix_rng, secs, cat, q, name):
                neg.append(row("neg_city", rid, cat, context, q, refusal(cat)))
            else:
                lost += 1
        if len(pos) >= CITY_POS and slots >= CITY_NEG:
            break
    print(f"citta' delle regioni di test: {len(cities)} con sezioni utili, righe pos {len(pos)} neg {len(neg)} "
          f"(rifiuti senza un contesto che non tratta la categoria: {lost})")
    return pos + neg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump-dir", type=Path, help="cartella dei dump (come generate_sft_dataset.py): aggiunge le citta'")
    ap.add_argument("--dump-date", help="data dei dump (MM-AAAA, AAAA-MM o AAAA-MM-GG), di default il nome della cartella")
    ap.add_argument("--cities-db", nargs="+", metavar="DB",
                    help="cities.db pubblicati (<regionId>--<versione>--cities.db o <regionId>=<file>): aggiunge storia e clima "
                         "delle citta' delle regioni di test")
    args = ap.parse_args()
    rng = random.Random(42)
    held_out = sorted(TEST_REGIONS)  # come train_lora.py
    # le regioni sostituite da sottoregioni (es. canada) non sono in regions.sh: nome dall'id
    names = {rid: rid.replace("-", " ").title() for rid in held_out} | {rid: name for rid, name, _ in load_regions()}
    langs = {}  # rid -> {"it": [(cat, corpo)], "en": [...]}
    for rid in held_out:
        langs[rid] = {}
        for lang, headings, suffix in (("it", HEADING_TO_CATEGORY, ""), ("en", EN_HEADING_TO_CATEGORY, ".en")):
            f = OUT / "raw" / f"{rid}{suffix}.txt"
            secs = parse_sections(f.read_text(encoding="utf-8"), headings) if f.exists() else []
            if secs:
                langs[rid][lang] = secs

    def refusal_on(topic):
        return f"Il contesto non contiene informazioni {topic}: per dettagli affidabili consulta una fonte ufficiale."

    def refusal(cat):
        return refusal_on(TOPIC[cat])

    def question(cat, name, i):  # a rotazione: 2 IT e 1 EN ogni 3, cosi' il test e' stabile
        pool = PARA_EN[cat] if i % 3 == 2 else PARA[cat]
        return pool[i % len(pool)].format(r=name)

    def row(kind, rid, cat, context, question_, answer):
        return {"messages": [{"role": "user", "content": on_device_prompt(context, question_)},
                             {"role": "assistant", "content": answer}],
                "kind": kind, "region": rid, "category": cat}

    out, guides = [], []
    for rid in held_out:
        if not langs[rid]:  # un test senza una regione non e' confrontabile con i precedenti: meglio fermarsi
            sys.exit(f"{rid}: nessuna sezione in {OUT / 'raw'} (ne' IT ne' EN): riempi la cache con generate_sft_dataset.py")
        name = names[rid]
        secs_it = langs[rid].get("it", [])
        for cat, body in secs_it:
            if not covers(cat, body):
                continue
            others = [b for c, b in secs_it if c != cat and not covers(cat, b)]
            for i in range(3):
                q = question(cat, name, i)
                context = make_context(rng, [body] + rng.sample(others, min(len(others), i)), q, name)  # 0, 1 o 2 distrattori
                answer = pick_answer(context, body, q, cat, name, legacy=True)
                if len(answer) >= 40:
                    out.append(row("pos_para", rid, cat, context, q, expected_answer(answer, context, body, q, cat, name)))
        for lang, secs in langs[rid].items():
            present = {c for c, _ in secs}
            for mc in (c for c in QUESTIONS if c not in present):
                bodies = [b for _, b in secs if not covers(mc, b)]
                if not bodies:
                    continue
                q = question(mc, name, rng.randint(0, 2))
                context = make_context(rng, rng.sample(bodies, min(len(bodies), rng.randint(1, 3))), q, name)
                # un contesto inglese che tratta la categoria con le radici inglesi (l'Antartide: acqua e alcol, cioe'
                # salute) non e' un rifiuto: la riga si toglie dopo il mescolamento, cosi' le altre non cambiano
                english = lang == "en" and covers(mc, context, generate_sft.en.KEYWORDS)
                out.append(row("neg_para", rid, mc, context, q, refusal(mc)) | ({"drop": True} if english else {}))
                out.append(row("neg_empty", rid, mc, FALLBACK_CONTEXT, question(mc, name, rng.randint(0, 2)), refusal(mc)))
        pool = [b for _, b in (secs_it or next(iter(langs[rid].values())))]
        guides.append((rid, name, pool))
        for q in OFF_TOPIC:
            out.append(row("neg_off", rid, "OFF", make_context(rng, rng.sample(pool, min(len(pool), rng.randint(1, 3))), q, name),
                           q, refusal("VITA_QUOTIDIANA")))
    rng.shuffle(out)
    kept = [r for r in out if not r.pop("drop", False) and r["messages"][1]["content"] is not None]
    print(f"neg_para con un contesto inglese che tratta la categoria e pos_para senza risposta: tolte {len(out) - len(kept)}")
    out, cities = kept, []
    if args.dump_dir:  # in coda, dopo il mescolamento: le righe dei paesi restano quelle di prima
        date = wiki_dump.normalize_date(args.dump_date or args.dump_dir.name)
        cities = load_test_cities(args.dump_dir, date)
        # le righe delle citta' dicono da quale dump vengono: un test e un training di mesi diversi si vedono
        out += [{**r, "dump": date} for r in city_rows(rng, cities, row, refusal)]
    out += nearby_rows("it", guides, row, refusal_on)
    if args.cities_db:
        out += wikipedia_rows("it", args.cities_db, row, refusal_on)
    # Righe in piu' del v11, ciascuna con un seme proprio: le precedenti restano quelle di prima
    if cities:
        pools = [("it", PARA_CITY_EXTRA["it"]), ("it", PARA_CITY_EXTRA["it"]), ("en", PARA_CITY_EXTRA["en"])]
        out += [{**r, "dump": date} for r in city_extra_refusals(cities, pools, lambda t: t.split(" (")[0], row, refusal)]
    out += [r | {"set": "v11"} for r in nearby_rows("it", guides, row, refusal_on, NEAR_EXTRA_QUOTAS, NEAR_QUESTIONS_EXTRA,
                                                     NEAR_EXTRA_SEED)]
    with open(OUT / "eval_extended.jsonl", "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"regioni di test: {len(held_out)}; righe: {len(out)}", dict(Counter(r["kind"] for r in out)))


if __name__ == "__main__":
    main()
