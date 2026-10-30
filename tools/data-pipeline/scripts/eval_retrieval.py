#!/usr/bin/env python3
"""Misura la scelta del contesto dell'assistente sul dispositivo (RAG sulle guide) su dati pubblicati, senza modello
e senza app.

Replica in Python TravelAssistant.kt (buildFtsQuery, namedCities, rankSections, focusStems, selectContext) e
FtsRanking.kt (BM25 su matchinfo 'pcxnal' con le statistiche delle due tabelle sommate): se cambiano li', va cambiato
anche qui. Per ogni regione scarica guides.db e il cities.db pubblicati (`manifest.json`), aggiunge Storia e Clima da
Wikipedia (city_wikipedia.py + generateCities, come build-cities.sh) se il cities.db pubblicato non li ha ancora,
e fa domande costruite da schemi di domanda fissi:
- citta', domande pratiche ("Dove dormire a {citta'}?", "Cosa si mangia a {citta'}?"): attesa la sezione della
  categoria di quella citta';
- distanze ("Quanto dista {citta'} da {altra}?"): attesa la sezione TRASPORTI della prima citta' e nel contesto il
  suo paragrafo che nomina l'altra con km o tempi di viaggio;
- citta', storia e clima ("Com'e' il clima a {citta'}?"): attesa la sezione STORIA o CLIMA di quella citta';
- paese ("Serve il passaporto per entrare?"): attesa la sezione della categoria nella guida del paese.
Metriche: sezione attesa tra le 3 del contesto ("trovata") e al primo posto, Storia/Clima tra le 3 quando la domanda
non ne parla, e il paragrafo della sezione attesa con piu' parole della domanda presente nel contesto (questa
favorisce la scelta dei paragrafi dell'app, che usa lo stesso criterio). Con --lang en le guide inglesi (guidesEn,
citiesEn, Storia e Clima da Wikipedia EN) e le domande in inglese, come l'app in inglese.
Con --questions reworded le stesse domande dette con altre parole (sinonimi, giri di frase), con --questions other-lang
le domande nell'altra lingua sulle guide di --lang (chi scrive in inglese con l'app in italiano, e viceversa): servono
a vedere quanto la ricerca a testo pieno dipende dalle parole esatte delle guide. Citta' e coppie delle distanze sono
le stesse in tutti e tre i casi. Con --questions control le domande di controllo, scritte dopo le liste di parole e mai
usate per costruirle: dicono se le liste valgono anche su domande nuove; --questions dev e dev2 sono controlli di prima,
diventati serie di sviluppo. Con --questions general le domande generali (jet lag, mance, passaporto smarrito) con
aperta ogni regione: attesa la sezione della pagina tematica della guida generale (regionId _general) che risponde.
Le sezioni della guida generale concorrono in tutte le serie, come nell'app. Con --questions other-country le domande sul paese
che nominano un'altra nazione ("Serve il passaporto per entrare in Grecia?" con aperto il Portogallo): attesa la sezione
della guida della nazione nominata. Con --guides-db un guides.db locale al posto di quello
pubblicato, per misurare un cambio delle guide prima di pubblicarlo. Con --word-lists la query ha anche le parole delle
guide per la categoria della domanda (CategoryWords.kt, accese nell'app; senza l'opzione si misura la ricerca senza liste).
Con --before-rag la ricerca senza guida generale e senza nazione nominata: senza --word-lists e' quella di prima del RAG.
Con --model-categories FILE (scritto da model_categories.py) le domande in cui le liste non riconoscono una categoria usano
quella scelta dal modello, come l'app sul dispositivo (ModelQuestionCategory.kt).
Con --contexts FILE ogni domanda (distanze escluse) va in JSONL con il contesto che l'app mette nel prompt e la presenza
della sezione attesa, per far rispondere il modello con eval_gguf.py --contexts (ricerca e modello insieme).

Solo misura, non ancora nell'app: con --rerank GGUF il riordino delle candidate col modello della chat (llama-server,
punteggio yesno o refusal, varianti replace, add e blend); con --rrf la fusione dei ranghi tra la query della domanda e
quella con le parole della categoria; con --paragraphs la ricerca per paragrafi (rank) o i paragrafi scelti col BM25
dentro le sezioni di oggi (within, within-country). Ogni misura stampa l'impronta dei dati e la configurazione, il
margine al 95% di "trovata" e Hit@5 per tipo; con --history FILE la aggiunge a uno storico JSONL e avvisa se una misura
con la stessa configurazione era stata fatta su dati diversi; con --explain FILE scrive le domande senza la sezione
attesa, con le sezioni scelte e il posto di quella attesa.

Uso: python eval_retrieval.py [--lang it|en] [--questions direct|reworded|dev|dev2|other-lang|control|general|other-country] [--regions italia ...]
[--cache DIR] [--no-wikipedia] [--guides-db FILE] [--word-lists [--model-categories FILE]] [--before-rag] [--contexts FILE [--contexts-sample N]]
[--rerank GGUF --llama-cpp DIR [--rerank-scoring yesno|refusal] [--rerank-mode replace|add|blend] [--gpu-layers 99 --device Vulkan0]]
[--rrf [--rrf-weight W]] [--paragraphs rank|within|within-country [--paragraph-chars N]] [--history FILE] [--explain FILE]
(la cache dei file scaricati sta di default nella cartella temporanea di sistema)
Richiede rete (`manifest.json`, release, API di Wikipedia) e, per Storia e Clima, il gradle wrapper dalla root del repo.
"""
import argparse
import atexit
import hashlib
import json
import lzma
import math
import os
import random
import re
import sqlite3
import struct
import subprocess
import sys
import tempfile
import time
import threading
import unicodedata
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from pathlib import Path

import city_wikipedia

MANIFEST_URL = "https://miracle091.github.io/pocket-travel/manifest.json"
REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_REGIONS = ["italia", "portogallo", "grecia", "svizzera", "croazia"]
CITIES_PER_REGION = {"italia": 40}
DEFAULT_CITIES = 15

# --- costanti dell'app ---------------------------------------------------------------------------------------------
GENERAL_GUIDE_REGION_ID = "_general"       # GuideRepository.GENERAL_GUIDE_REGION_ID
MAX_SECTIONS = 3                 # TravelAssistant.MAX_SECTIONS
CANDIDATE_CAP = 100              # GuideRepository/CityRepository.CANDIDATE_CAP
CITY_CANDIDATE_CAP = 100         # CityRepository.CITY_CANDIDATE_CAP
MAX_CONTEXT = 2000               # buildOnDeviceContext(maxChars)
MRR_DEPTH = 10                   # sezioni della classifica per Hit@5 e MRR@10 (solo misura)
MIN_SECTION_CHARS = 50           # TravelAssistant.MIN_SECTION_CHARS
MIN_CITY_NAME_CHARS = 3          # TravelAssistant.MIN_CITY_NAME_CHARS
STEM_CHARS = 5                   # TravelAssistant.STEM_CHARS
LONG_WORD_CHARS = 8              # TravelAssistant.LONG_WORD_CHARS
SHORT_QUERY_TERMS = ["jet lag", "wi fi", "tip", "tips", "sim", "atm", "first aid", "pronto soccorso"]  # TravelAssistant.shortQueryTerms
# TravelAssistant.questionStopwords
STOPWORDS = {
    "quale", "quali", "quanto", "quanta", "quanti", "quante", "quando", "perche", "sono", "della", "delle", "dello",
    "degli", "dell", "nella", "nelle", "nello", "negli", "nell", "alla", "alle", "allo", "agli", "dalla", "dalle", "dallo",
    "dagli", "sulla", "sulle", "sullo", "sugli", "questo", "questa", "questi", "queste", "quello", "quella", "quelli",
    "quelle", "anche", "molto", "molti", "molte", "posso", "puoi", "possono", "devo", "deve", "devono", "serve", "servono",
    "essere", "fatto", "avere", "hanno", "ogni", "tutto", "tutti", "tutte", "altro", "altri", "loro", "dire", "cosi",
    "ancora", "oppure", "mentre",
    "what", "which", "where", "when", "does", "there", "with", "from", "that", "this", "have", "should", "about", "much",
    "many", "could", "would", "your", "some", "into", "they", "them", "were", "been", "will", "also", "very", "need",
}
TITLE_WEIGHT, BODY_WEIGHT = 3.0, 1.0
BM25_K1, BM25_B, BM25_IDF_SMOOTHING = 1.2, 0.75, 0.5
WIKI = {"STORIA", "CLIMA"}
WIKIPEDIA_OFF_TOPIC_WEIGHT = 0.1  # TravelAssistant.WIKIPEDIA_OFF_TOPIC_WEIGHT
GENERAL_GUIDE_FIRST_RATIO = 2.0          # TravelAssistant.GENERAL_GUIDE_FIRST_RATIO (guide inglesi)
# TravelAssistant.generalTravelWords: con queste parole la soglia della guida generale scende a 1
GENERAL_TRAVEL_WORDS = re.compile(r"\b(ester[oi]|in viaggio|viaggiando|abroad|overseas|travell?ing)\b", re.IGNORECASE)
# TravelAssistant.historyClimateWords
HISTORY_CLIMATE_WORDS = re.compile(
    r"\b((?<!centr[oi] )stori|fondat|fondò|secol|guerr|antic(?:[oa]\b|h)|roman[oaie]?\b|mediev|clima|temperatur|piov|piogg|neve|nevic|cald|fredd|estat|"
    r"invern|meteo|stagion|histor|found|centur|wars?\b|ancient|medieval|weather|rain|snow|hot\b|cold\b|summer|winter|season|"
    r"passat|origin|govern|medioev|nascit|nata\b|nato\b|period|quando andare|best time|when to go|establish|ruled|"
    r"costru|mura\b|built|walls?\b|ottocent|novecent|settecent|seicent|cinquecent|quattrocent|trecent|gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre|mesi\b|january|february|march|april|june|july|august|september|october|november|december|months?\b|middle ages|past\b|origins?\b|natale\b|natalizi|capodanno|pasqua|ferragosto|christmas|xmas|easter|new year|che tempo|"
    r"popol|abitat|inhabit|settl|peoples\b|how old)",
    re.IGNORECASE)

CITY_Q = {
    "COSA_VEDERE": ["Cosa vedere a {c}?", "Quali monumenti visitare a {c}?", "Quali musei ci sono a {c}?"],
    "TRASPORTI": ["Come arrivare a {c} in treno?", "Come muoversi a {c} con l'autobus?"],
    "ALLOGGIO": ["Dove dormire a {c}?", "Quali alberghi ci sono a {c}?"],
    "CIBO_BEVANDE": ["Dove mangiare a {c}?", "Quali ristoranti consigli a {c}?", "Cosa si mangia a {c}?",
                     "Quali sono i piatti tipici di {c}?"],
    "ACQUISTI": ["Dove fare acquisti a {c}?"],
}
DISTANCE_Q = ["Quanto dista {c} da {o}?", "Quanti km ci sono tra {o} e {c}?", "Quanto ci vuole da {o} a {c}?"]
WIKI_Q = {
    "STORIA": ["Qual è la storia di {c}?", "Chi ha fondato {c}?", "Cosa è successo a {c} durante la seconda guerra mondiale?",
               "{c} era una città romana?"],
    "CLIMA": ["Com'è il clima a {c}?", "Fa caldo a {c} d'estate?", "Quanto piove a {c}?", "Nevica a {c} in inverno?"],
}
COUNTRY_Q = {
    "DOGANE": ["Serve il passaporto per entrare?", "Cosa si può portare in dogana?"],
    "SALUTE": ["Quali vaccinazioni servono?", "Com'è l'assistenza sanitaria per i turisti?"],
    "SICUREZZA": ["È pericoloso girare di notte?", "Ci sono borseggiatori?"],
    "ACQUISTI": ["Si può pagare con la carta di credito?", "Quanto si lascia di mancia?"],
    "CONNETTIVITA": ["Come comprare una SIM per internet?"],
    "TRASPORTI": ["Come funzionano i treni nel paese?", "Conviene noleggiare un'auto?"],
    "CIBO_BEVANDE": ["Quali piatti tipici assaggiare?"],
}
CITY_Q_EN = {
    "COSA_VEDERE": ["What to see in {c}?", "Which monuments should I visit in {c}?", "What museums are there in {c}?"],
    "TRASPORTI": ["How do I get to {c} by train?", "How do I get around {c} by bus?"],
    "ALLOGGIO": ["Where to stay in {c}?", "Which hotels are there in {c}?"],
    "CIBO_BEVANDE": ["Where to eat in {c}?", "Which restaurants do you recommend in {c}?", "What is the local food in {c}?",
                     "What are the typical dishes of {c}?"],
    "ACQUISTI": ["Where to go shopping in {c}?"],
}
DISTANCE_Q_EN = ["How far is {c} from {o}?", "How many km from {o} to {c}?", "How long does it take from {o} to {c}?"]
WIKI_Q_EN = {
    "STORIA": ["What is the history of {c}?", "Who founded {c}?", "What happened in {c} during the Second World War?",
               "Was {c} a Roman town?"],
    "CLIMA": ["What is the climate like in {c}?", "Is it hot in {c} in summer?", "How much does it rain in {c}?",
              "Does it snow in {c} in winter?"],
}
COUNTRY_Q_EN = {
    "DOGANE": ["Do I need a passport to enter?", "What can I bring through customs?"],
    "SALUTE": ["Which vaccinations do I need?", "What is healthcare like for tourists?"],
    "SICUREZZA": ["Is it dangerous to walk around at night?", "Are there pickpockets?"],
    "ACQUISTI": ["Can I pay by credit card?", "How much should I tip?"],
    "CONNETTIVITA": ["How do I buy a SIM card for mobile internet?"],
    "TRASPORTI": ["How do trains work in the country?", "Is it worth renting a car?"],
    "CIBO_BEVANDE": ["Which typical dishes should I try?"],
}
# Le stesse richieste con altre parole: sinonimi e giri di frase che le guide usano poco o in altra forma.
CITY_Q_REWORDED = {
    "COSA_VEDERE": ["Cosa non perdere a {c}?", "Quali sono le attrazioni principali di {c}?",
                    "Cosa merita una visita a {c}?"],
    "TRASPORTI": ["Come ci si arriva a {c}?", "Come mi sposto a {c} senza macchina?"],
    "ALLOGGIO": ["Dove posso passare la notte a {c}?", "Cerco un posto economico per pernottare a {c}"],
    "CIBO_BEVANDE": ["Che cosa assaggiare a {c}?", "Dove si cena bene a {c}?", "Quali specialità locali provare a {c}?",
                     "Un buon locale per pranzo a {c}?"],
    "ACQUISTI": ["Dove comprare souvenir a {c}?"],
}
DISTANCE_Q_REWORDED = ["Quanta strada c'è da {o} a {c}?", "Quanto è lontana {c} da {o}?",
                       "In quanto tempo si va da {o} a {c}?"]
WIKI_Q_REWORDED = {
    "STORIA": ["Quali sono le origini di {c}?", "Quando è nata {c}?", "Cosa sappiamo del passato di {c}?",
               "Chi governava {c} nel Medioevo?"],
    "CLIMA": ["Che tempo fa a {c} in primavera?", "Qual è il periodo migliore per andare a {c}?",
              "Serve l'ombrello a {c} in autunno?", "Che temperature ci sono a {c} ad agosto?"],
}
COUNTRY_Q_REWORDED = {
    "DOGANE": ["Che documenti mi servono alla frontiera?", "Quali limiti ci sono per alcol e sigarette all'arrivo?"],
    "SALUTE": ["Devo fare qualche vaccino prima di partire?", "Se mi ammalo dove vado?"],
    "SICUREZZA": ["È un paese sicuro per chi viaggia?", "Rischio di essere derubato?"],
    "ACQUISTI": ["Accettano il bancomat nei negozi?", "Si usa lasciare qualcosa al cameriere?"],
    "CONNETTIVITA": ["Come mi collego a internet col telefono?"],
    "TRASPORTI": ["Come ci si sposta tra le città?", "Si può girare in macchina?"],
    "CIBO_BEVANDE": ["Cosa si mangia di solito?"],
}
CITY_Q_REWORDED_EN = {
    "COSA_VEDERE": ["What shouldn't I miss in {c}?", "What are the main attractions of {c}?",
                    "What is worth a visit in {c}?"],
    "TRASPORTI": ["How can I reach {c}?", "How do I move around {c} without a car?"],
    "ALLOGGIO": ["Where can I spend the night in {c}?", "I need a cheap place to sleep in {c}"],
    "CIBO_BEVANDE": ["What should I taste in {c}?", "Where can I have a good dinner in {c}?",
                     "Which local specialities should I try in {c}?", "A good place for lunch in {c}?"],
    "ACQUISTI": ["Where can I buy souvenirs in {c}?"],
}
DISTANCE_Q_REWORDED_EN = ["How much road is there from {o} to {c}?", "How far away is {c} from {o}?",
                          "How quickly can I get from {o} to {c}?"]
WIKI_Q_REWORDED_EN = {
    "STORIA": ["What are the origins of {c}?", "When was {c} established?", "What do we know about the past of {c}?",
               "Who ruled {c} in the Middle Ages?"],
    "CLIMA": ["What is the weather like in {c} in spring?", "When is the best time to go to {c}?",
              "Do I need an umbrella in {c} in autumn?", "What temperatures are there in {c} in August?"],
}
COUNTRY_Q_REWORDED_EN = {
    "DOGANE": ["What documents do I need at the border?", "What are the limits on alcohol and cigarettes on arrival?"],
    "SALUTE": ["Should I get any jabs before leaving?", "Where do I go if I get sick?"],
    "SICUREZZA": ["Is the country safe for travellers?", "Am I likely to get robbed?"],
    "ACQUISTI": ["Do shops accept debit cards?", "Do people leave something for the waiter?"],
    "CONNETTIVITA": ["How do I get online with my phone?"],
    "TRASPORTI": ["How do people travel between cities?", "Can I get around by car?"],
    "CIBO_BEVANDE": ["What do people usually eat?"],
}
# Liste di parole (CategoryWords.kt, solo con --word-lists): radici che nella domanda indicano la
# categoria e parole dei titoli delle guide da aggiungere alla query. test_eval_retrieval.py le confronta con l'app.
CATEGORY_TRIGGERS = {
    "ALLOGGIO": "allogg dormi pernot notte alberg hotel ostell campeg stay sleep accomm lodg hostel night= camp= camping campsite camera prenot room rooms",
    "CIBO_BEVANDE": "mangi cibo cucin piatt assag ristor pranz cena cenar colaz specia bere vino birra "
                    "food eat dish cuisin restaurant lunch dinner breakf tast drink wine beer beve bevi bevan "
                    "dolci= dolce= dessert sweets= pastic pastry trattor osteri tavern",
    "COSA_VEDERE": "veder visit attraz monum muse perder merit see sight attract worth miss to+do things+to+do "
                   "castell castle palazz palace chies church cattedr cathedr duomo",
    "ACQUISTI": "acqui compr souven negoz mercat manci camerier pagar contan bancom soldi valut "
                "shop buy market tip waiter pay cash card= money curren atm paga euro= dollar "
                "ricord regal gift cost= costs= costo= costi= expens costa+la+vita costo+della+vita cost+of+living",
    "TRASPORTI": "arriv spost muov treno treni autob macchin auto nolegg guidar raggiun "
                 "bus train car drive rent transp metro reach around stazion station aeroport airport "
                 "taxi tassi= tram= traghett ferry",
    "DOGANE": "passap docum front dogan visto alcol sigar passport border custom visa alcoh cigar identit id+card",
    "SALUTE": "vacci ammal malat medic osped salute farmac sanit sick ill doctor hospit health jab pharm pronto+soccorso first+aid "
              "rubinett tap+water",
    "SICUREZZA": "sicur peric derub furt borseg ladr crimin truffa truffe safe danger rob theft thief pickp crime scam",
    "CONNETTIVITA": "internet telef cellul sim wifi collegar roami online phone mobile connec",
    "STORIA": "stori origin nata= nato= fondat fondo= passat medioev govern antico antica antich roman guerr "
              "histor found establ past= ancien rule= ruled ruler middl war middle+ages costru mura= built wall= ottocent novecent settecent seicent cinquecent quattrocent trecent secol centur "
              "popol abitat inhabit settl peoples= how+old",
    "CLIMA": "clima meteo piog piov ombrel tempera primav estat invern autun nevic neve caldo fredd stagion "
             "climat weather rain umbrel spring summer winter= autumn snow hot cold season "
             "periodo+migliore quando+andare best+time when+to+go gennaio febbraio marzo aprile maggio giugno luglio agosto settembre ottobre novembre dicembre mesi january february march april june july august september october november december months",
    "CONSIGLI": "combat evitar preven curar rimed superar affront deal cope avoid prevent cure treat overcom",
    "EMERGENZE": "perdo perso= persa smarr rubat lose lost stolen",
    "PERIODI": "natale= natalizi capodann pasqua ferragost christmas xmas easter new+year che+tempo",
}
CATEGORY_GUIDE_WORDS = {
    "it": {"ALLOGGIO": "allogg*", "CIBO_BEVANDE": "mangi*, tavola", "COSA_VEDERE": "veder*", "ACQUISTI": "acqui*, valut*",
           "TRASPORTI": "arriv*, spost*", "DOGANE": "arriv*, dogan*", "SALUTE": "sanit*, salut*", "SICUREZZA": "sicur*",
           "CONNETTIVITA": "contat*", "STORIA": "stori*", "CLIMA": "clima",
           "CONSIGLI": "prepar*, recupe*, preven*, tratta*", "EMERGENZE": "emerg*", "PERIODI": "clima", "METEO": "clima"},
    "en": {"ALLOGGIO": "sleep", "CIBO_BEVANDE": "eat, drink", "COSA_VEDERE": "see", "ACQUISTI": "buy",
           "TRASPORTI": "get in, get around", "DOGANE": "get in, customs", "SALUTE": "health*", "SICUREZZA": "safe*",
           "CONNETTIVITA": "connect*", "STORIA": "history", "CLIMA": "climate",
           "CONSIGLI": "prepar*, cope, recover*, prevent*, treat*", "EMERGENZE": "lose, lost, stolen", "PERIODI": "climate", "METEO": "climate"},
}
SHORT_STEM_CHARS = 4             # CategoryWords.SHORT_STEM_CHARS
# Categoria scelta dal modello quando le liste non ne riconoscono nessuna (ModelQuestionCategory.kt): (nome, esempi, gruppo).
# Con --model-categories FILE le categorie gia' scelte dal modello per ogni domanda (model_categories.py).
MODEL_CATEGORY_LABELS = {
    "it": [("alloggio", "hotel, dormire, campeggio", "ALLOGGIO"), ("cibo", "ristoranti, piatti, bere", "CIBO_BEVANDE"),
           ("cosa vedere", "monumenti, musei, gite", "COSA_VEDERE"), ("acquisti", "negozi, prezzi, soldi", "ACQUISTI"),
           ("trasporti", "treni, autobus, auto, aerei", "TRASPORTI"), ("documenti e dogana", "passaporto, visto", "DOGANE"),
           ("salute", "medici, ospedali, vaccini", "SALUTE"), ("sicurezza", "criminalita', zone pericolose", "SICUREZZA"),
           ("telefono e internet", None, "CONNETTIVITA"), ("storia", "passato, fondazione, guerre", "STORIA"),
           ("clima", "meteo, stagioni, temperature", "CLIMA"), ("viaggio in generale", "jet lag, bagagli, oggetti persi", None)],
    "en": [("accommodation", "hotels, sleeping, camping", "ALLOGGIO"), ("food", "restaurants, dishes, drinks", "CIBO_BEVANDE"),
           ("sightseeing", "monuments, museums, trips", "COSA_VEDERE"), ("shopping", "shops, prices, money", "ACQUISTI"),
           ("transport", "trains, buses, cars, flights", "TRASPORTI"), ("documents and customs", "passport, visa", "DOGANE"),
           ("health", "doctors, hospitals, vaccines", "SALUTE"), ("safety", "crime, dangerous areas", "SICUREZZA"),
           ("phone and internet", None, "CONNETTIVITA"), ("history", "past, foundation, wars", "STORIA"),
           ("climate", "weather, seasons, temperatures", "CLIMA"), ("travel in general", "jet lag, luggage, lost items", None)],
}
HISTORY_CLIMATE_GROUPS = {"STORIA", "CLIMA", "PERIODI", "METEO"}  # ModelQuestionCategory.HISTORY_CLIMATE_GROUPS
# Previsioni del tempo (CategoryWords.forecastStems e nearTimeStems): una parola di previsione, o una del clima con un
# momento vicino; METEO prende il posto di CLIMA e PERIODI.
FORECAST_STEMS = "previs forecast"
NEAR_TIME_STEMS = ("oggi domani dopodomani stasera stanotte stamattina weekend= fine+settimana lunedi martedi "
                   "mercoledi giovedi venerdi sabato domenica prossimi+giorni settimana+prossima questa+settimana today tomorrow "
                   "tonight this+week next+week next+days monday tuesday wednesday thursday friday saturday sunday")
MODEL_CATEGORIES = {}  # domanda -> gruppo, da --model-categories
MODEL_CATEGORY_MISSES = set()  # domande senza categoria dalle liste e assenti dal file di --model-categories


def model_category_prompt(question, lang):
    """modelCategoryPrompt."""
    labels = "; ".join(n if e is None else f"{n} ({e})" for n, e, _ in MODEL_CATEGORY_LABELS[lang])
    if lang == "en":
        return f"Classify a traveller's question into exactly one category among: {labels}.\nQuestion: {question}\nCategory:"
    return f"Classifica la domanda di un viaggiatore in una sola categoria tra: {labels}.\nDomanda: {question}\nCategoria:"


def model_category_grammar(lang):
    """modelCategoryGrammar."""
    return "root ::= " + " | ".join(f'"{n}"' for n, _, _ in MODEL_CATEGORY_LABELS[lang])


def question_groups(question):
    """questionGroups: le categorie che le parole della domanda indicano."""
    tokens = [folded(t) for t in re.split(r"[^\w]+|_", question) if t]
    text = " " + " ".join(tokens) + " "

    def hit(stem):
        if "+" in stem:
            return " " + stem.replace("+", " ") in text
        if stem.endswith("="):
            return any(t in (stem[:-1], stem[:-1] + "s") for t in tokens)
        return any(t.startswith(stem) if len(stem) >= SHORT_STEM_CHARS else t in (stem, stem + "s") for t in tokens)
    groups = {cat for cat, stems in CATEGORY_TRIGGERS.items() if any(hit(s) for s in stems.split())}
    weather = "CLIMA" in groups or hit("che+tempo")
    if any(hit(s) for s in FORECAST_STEMS.split()) or weather and any(hit(s) for s in NEAR_TIME_STEMS.split()):
        return groups - {"CLIMA", "PERIODI"} | {"METEO"}
    return groups


def with_category_words(fts, question, lang, extra_groups=()):
    """withCategoryWords: [fts] con in OR le parole dei titoli delle guide in [lang] delle categorie della domanda e di
    [extra_groups] (la categoria scelta dal modello)."""
    words = CATEGORY_GUIDE_WORDS.get(lang, CATEGORY_GUIDE_WORDS["en"])
    groups = question_groups(question) | set(extra_groups)
    extra = [f'"{w}"' if " " in w else w for cat in sorted(groups) for w in words.get(cat, "").split(", ") if w]
    return " OR ".join(dict.fromkeys([t for t in fts.split(" OR ") if t] + extra))


# Nazione nominata nella domanda (CountryGuides.kt): l'app riconosce i nomi di tutte le nazioni (Locale del JDK), la misura
# solo quelli delle regioni di DEFAULT_REGIONS e della Francia (nazione divisa), le uniche che nomina. Le regioni di ogni nazione vengono da regions.sh, come
# la tabella countryRegions dell'app.
COUNTRY_NAMES = {"it": ("Italia", "Italy"), "pt": ("Portogallo", "Portugal"), "gr": ("Grecia", "Greece"),
                 "ch": ("Svizzera", "Switzerland"), "hr": ("Croazia", "Croatia"), "fr": ("Francia", "France")}
# Domande sul paese con la nazione nominata ({p}), da fare con aperta un'altra regione.
OTHER_COUNTRY_Q = {
    "it": {"DOGANE": ["Serve il passaporto per entrare in {p}?", "Cosa si può portare in dogana in {p}?"],
           "SALUTE": ["Quali vaccinazioni servono per andare in {p}?", "Com'è l'assistenza sanitaria per i turisti in {p}?"],
           "SICUREZZA": ["È pericoloso girare di notte in {p}?", "Ci sono borseggiatori in {p}?"],
           "ACQUISTI": ["Si può pagare con la carta di credito in {p}?", "Quanto si lascia di mancia in {p}?"],
           "CONNETTIVITA": ["Come comprare una SIM per internet in {p}?"],
           "TRASPORTI": ["Come funzionano i treni in {p}?", "Conviene noleggiare un'auto in {p}?"],
           "CIBO_BEVANDE": ["Quali piatti tipici assaggiare in {p}?"]},
    "en": {"DOGANE": ["Do I need a passport to enter {p}?", "What can I bring through customs in {p}?"],
           "SALUTE": ["Which vaccinations do I need for {p}?", "What is healthcare like for tourists in {p}?"],
           "SICUREZZA": ["Is it dangerous to walk around at night in {p}?", "Are there pickpockets in {p}?"],
           "ACQUISTI": ["Can I pay by credit card in {p}?", "How much should I tip in {p}?"],
           "CONNETTIVITA": ["How do I buy a SIM card for mobile internet in {p}?"],
           "TRASPORTI": ["How do trains work in {p}?", "Is it worth renting a car in {p}?"],
           "CIBO_BEVANDE": ["Which typical dishes should I try in {p}?"]},
}


def country_regions():
    """{codice ISO: [regionId]} dalle righe di regions.sh (flagCode e' l'ottavo campo), come countryRegions dell'app."""
    out = defaultdict(list)
    for line in (Path(__file__).resolve().parent / "regions.sh").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith('"') and line.count("|") >= 10:
            fields = line.strip('"').split("|")
            out[fields[7]].append(fields[0])
    return dict(out)


COUNTRY_REGIONS = country_regions()
REGION_COUNTRY = {r: iso for iso, regions in COUNTRY_REGIONS.items() for r in regions}


def named_place(question, open_region, lang="it"):
    """namedPlace: (codice, nome come nella domanda, regioni) della nazione nominata, se non e' quella di [open_region]."""
    by_name = {name: iso for iso, names in COUNTRY_NAMES.items() for name in names}
    open_country = REGION_COUNTRY.get(open_region)
    for name in named_cities(question, list(by_name), lang):
        if by_name[name] != open_country:
            return by_name[name], name, COUNTRY_REGIONS[by_name[name]]
    return None


# Domande generali, non su un paese, con la pagina tematica della guida generale che risponde (titolo nella guida
# di quella lingua). La guida italiana non ha la pagina sulle mance (manca su it.wikivoyage).
GENERAL_Q = {
    "it": [
        ("Come si combatte il jet lag?", "Jet lag"),
        ("La melatonina aiuta col fuso orario dopo un volo lungo?", "Jet lag"),
        ("Come si previene il mal di montagna?", "Mal di montagna"),
        ("Quali sono i sintomi del mal d'altitudine?", "Mal di montagna"),
        ("Conviene fare un'assicurazione di viaggio?", "Assicurazione di viaggio"),
        ("Cosa copre di solito un'assicurazione di viaggio?", "Assicurazione di viaggio"),
        ("Cosa mettere nel kit di pronto soccorso da viaggio?", "Salute"),
        ("Cosa faccio se perdo il passaporto all'estero?", "Documenti"),
        ("Quali documenti servono per guidare all'estero?", "Documenti"),
        ("Che differenza c'è tra il visto all'arrivo e il visto in anticipo?", "Visto d'ingresso"),
        ("Cosa succede se resto oltre la scadenza del visto?", "Visto d'ingresso"),
        ("Come proteggersi dai borseggiatori?", "Borseggiatori"),
        ("Quali sono le truffe più comuni con i taxi?", "Truffe comuni"),
        ("Come riconoscere i falsi poliziotti?", "Truffe comuni"),
        ("Conviene usare i traveller's cheque?", "Strumenti di pagamento"),
        ("Meglio i contanti o la carta di credito in viaggio?", "Strumenti di pagamento"),
        ("Come evitare i costi del roaming col telefono?", "Telefono cellulare"),
        ("Serve un telefono satellitare nei posti isolati?", "Telefono cellulare"),
        ("Come evitare il jet lag nei voli verso est?", "Jet lag"),
        ("Quanti giorni servono per riprendersi dal fuso orario?", "Jet lag"),
        ("Cosa fare se mi gira la testa in alta quota?", "Mal di montagna"),
        ("Come ci si acclimata in montagna?", "Mal di montagna"),
        ("L'assicurazione di viaggio copre le spese mediche?", "Assicurazione di viaggio"),
        ("Cosa faccio se mi rubano i documenti in viaggio?", "Documenti"),
        ("Mi hanno rubato la borsa, cosa devo fare?", "Borseggiatori"),
        ("Dove tenere il portafoglio sui mezzi affollati?", "Borseggiatori"),
        ("Come riconoscere un tassista disonesto?", "Truffe comuni"),
        ("Cosa fare se mi chiedono soldi per strada?", "Truffe comuni"),
        ("Conviene cambiare i soldi prima di partire?", "Strumenti di pagamento"),
        ("Come prelevare contanti all'estero senza commissioni?", "Strumenti di pagamento"),
        ("Come uso il cellulare all'estero senza spendere troppo?", "Telefono cellulare"),
        ("Cosa portare in valigia per restare in salute?", "Salute"),
    ],
    "en": [
        ("How do I deal with jet lag?", "Jet lag"),
        ("Does melatonin help with jet lag after a long flight?", "Jet lag"),
        ("How can I prevent altitude sickness?", "Altitude sickness"),
        ("What are the symptoms of altitude sickness?", "Altitude sickness"),
        ("Is travel insurance worth it?", "Travel insurance"),
        ("What does travel insurance usually cover?", "Travel insurance"),
        ("What should I pack in a travel first aid kit?", "Stay healthy"),
        ("What do I do if I lose my passport abroad?", "Passport"),
        ("How long must my passport be valid before it expires?", "Passport"),
        ("What is the difference between a visa on arrival and a visa in advance?", "Visa"),
        ("What happens if I overstay my visa?", "Visa"),
        ("How do I protect myself from pickpockets?", "Pickpockets"),
        ("What are the most common taxi scams?", "Common scams"),
        ("How do I spot fake police officers?", "Common scams"),
        ("Are traveller's cheques still worth using?", "Money"),
        ("Is cash or a credit card better when travelling?", "Money"),
        ("How do I avoid roaming charges on my phone?", "Mobile phones"),
        ("Do I need a satellite phone in remote places?", "Mobile phones"),
        ("How much should I tip in a restaurant?", "Tipping"),
        ("Which services usually expect a tip?", "Tipping"),
        ("How do I avoid jet lag when flying east?", "Jet lag"),
        ("How many days does it take to recover from the time difference?", "Jet lag"),
        ("What should I do if I feel dizzy at high altitude?", "Altitude sickness"),
        ("How do you acclimatize in the mountains?", "Altitude sickness"),
        ("Does travel insurance cover medical bills?", "Travel insurance"),
        ("What should I do if my passport is stolen while travelling?", "Passport"),
        ("My bag was stolen, what should I do?", "Pickpockets"),
        ("Where should I keep my wallet on crowded buses?", "Pickpockets"),
        ("How do I recognise a dishonest taxi driver?", "Common scams"),
        ("What should I do if strangers ask me for money?", "Common scams"),
        ("Should I exchange money before I leave?", "Money"),
        ("How do I withdraw cash abroad without fees?", "Money"),
        ("How can I use my phone abroad without spending too much?", "Mobile phones"),
        ("Is it customary to tip taxi drivers?", "Tipping"),
    ],
}
# Per lingua: domande dirette e riformulate (citta', storia e clima, paese, distanze), chiavi di `manifest.json` e suffisso
# dei file in cache.
LANGS = {
    "it": {"questions": (CITY_Q, WIKI_Q, COUNTRY_Q, DISTANCE_Q),
           "reworded": (CITY_Q_REWORDED, WIKI_Q_REWORDED, COUNTRY_Q_REWORDED, DISTANCE_Q_REWORDED),
           "guides": "guides", "cities": "cities", "suffix": "", "other": "en"},
    "en": {"questions": (CITY_Q_EN, WIKI_Q_EN, COUNTRY_Q_EN, DISTANCE_Q_EN),
           "reworded": (CITY_Q_REWORDED_EN, WIKI_Q_REWORDED_EN, COUNTRY_Q_REWORDED_EN, DISTANCE_Q_REWORDED_EN),
           "guides": "guidesEn", "cities": "citiesEn", "suffix": "-en", "other": "it"},
}
# Seconda serie di sviluppo (--questions dev): erano le prime domande di controllo; dal 2026-10-11 le liste di parole
# si correggono anche sui loro errori, quindi non sono piu' un controllo.
CITY_Q_DEV = {
    "COSA_VEDERE": ["Che cosa c'è di bello a {c}?", "Quali chiese meritano a {c}?"],
    "TRASPORTI": ["C'è la metropolitana a {c}?", "Da quale stazione si parte per {c}?"],
    "ALLOGGIO": ["C'è un ostello a {c}?", "Dove prenotare una camera a {c}?"],
    "CIBO_BEVANDE": ["Dove fare colazione a {c}?", "Quale vino bere a {c}?"],
    "ACQUISTI": ["Ci sono mercatini a {c}?"],
}
WIKI_Q_DEV = {"STORIA": ["Com'era {c} nell'Ottocento?", "Chi ha costruito le mura di {c}?"],
                  "CLIMA": ["Che clima c'è a {c} a novembre?", "Fa freddo a {c} di sera?"]}
COUNTRY_Q_DEV = {"DOGANE": ["Basta la carta d'identità?"], "SALUTE": ["Dove trovo una farmacia aperta?"],
                     "SICUREZZA": ["Ci sono zone da evitare?"], "ACQUISTI": ["Si paga in euro?"],
                     "CONNETTIVITA": ["Il wifi è diffuso?"], "TRASPORTI": ["Come sono le strade?"],
                     "CIBO_BEVANDE": ["Cosa si beve?"]}
CITY_Q_DEV_EN = {
    "COSA_VEDERE": ["What is there to do in {c}?", "Which churches are worth it in {c}?"],
    "TRASPORTI": ["Is there a metro in {c}?", "Which station do trains to {c} leave from?"],
    "ALLOGGIO": ["Is there a hostel in {c}?", "Where can I book a room in {c}?"],
    "CIBO_BEVANDE": ["Where can I have breakfast in {c}?", "Which wine should I drink in {c}?"],
    "ACQUISTI": ["Are there markets in {c}?"],
}
WIKI_Q_DEV_EN = {"STORIA": ["What was {c} like in the 19th century?", "Who built the walls of {c}?"],
                     "CLIMA": ["What is the climate in {c} in November?", "Is it cold in {c} in the evening?"]}
COUNTRY_Q_DEV_EN = {"DOGANE": ["Is an ID card enough?"], "SALUTE": ["Where can I find an open pharmacy?"],
                        "SICUREZZA": ["Are there areas to avoid?"], "ACQUISTI": ["Do they use the euro?"],
                        "CONNETTIVITA": ["Is wifi common?"], "TRASPORTI": ["What are the roads like?"],
                        "CIBO_BEVANDE": ["What do people drink?"]}
# Terza serie di sviluppo (--questions dev2): erano le domande di controllo scritte il 2026-10-11 sera; dal 2026-10-13
# le liste di parole si correggono anche sui loro errori ("Ci sono taxi a X?", "C'è una trattoria tipica a X?").
CITY_Q_DEV2 = {
    "COSA_VEDERE": ["Quali palazzi storici ci sono a {c}?", "Cosa fare in una giornata a {c}?", "C'è un castello a {c}?"],
    "TRASPORTI": ["Ci sono taxi a {c}?", "Come si raggiunge {c} dall'aeroporto?"],
    "ALLOGGIO": ["Dove soggiornare a {c} con la famiglia?", "Ci sono campeggi vicino a {c}?"],
    "CIBO_BEVANDE": ["C'è una trattoria tipica a {c}?", "Dove bere un aperitivo a {c}?", "Quali dolci provare a {c}?"],
    "ACQUISTI": ["Cosa portare a casa come ricordo da {c}?"],
}
WIKI_Q_DEV2 = {
    "STORIA": ["Che cosa è successo a {c} nel Novecento?", "Chi ha dominato {c} nei secoli?", "Quanto è antica {c}?",
               "Quali popoli hanno abitato {c}?", "Com'è cambiata {c} dopo la guerra?"],
    "CLIMA": ["Piove molto a {c} in ottobre?", "Che tempo trovo a {c} a Natale?", "Quali sono i mesi più caldi a {c}?",
              "C'è umidità a {c} d'estate?", "Fa freddo a {c} in febbraio?"],
}
COUNTRY_Q_DEV2 = {"DOGANE": ["Serve un visto per entrare?", "Quanti contanti posso portare alla frontiera?"],
                  "SALUTE": ["L'acqua del rubinetto si può bere?", "Ci sono ospedali per stranieri?"],
                  "SICUREZZA": ["Ci sono quartieri pericolosi?", "È sicuro viaggiare da sola?"],
                  "ACQUISTI": ["Quanto costa la vita?", "Si contratta nei negozi?"],
                  "CONNETTIVITA": ["Il telefono prende ovunque?"],
                  "TRASPORTI": ["Come funzionano gli autobus tra le città?", "Si guida a destra o a sinistra?"],
                  "CIBO_BEVANDE": ["Qual è il piatto nazionale?"]}
CITY_Q_DEV2_EN = {
    "COSA_VEDERE": ["Which historic palaces are there in {c}?", "What to do for a day in {c}?", "Is there a castle in {c}?"],
    "TRASPORTI": ["Are there taxis in {c}?", "How do I get to {c} from the airport?"],
    "ALLOGGIO": ["Where to stay in {c} with kids?", "Are there campsites near {c}?"],
    "CIBO_BEVANDE": ["Is there a traditional tavern in {c}?", "Where can I get a drink in {c}?", "Which desserts should I try in {c}?"],
    "ACQUISTI": ["What should I bring home as a souvenir from {c}?"],
}
WIKI_Q_DEV2_EN = {
    "STORIA": ["What happened in {c} in the 20th century?", "Who ruled {c} over the centuries?", "How old is {c}?",
               "Which peoples lived in {c}?", "How did {c} change after the war?"],
    "CLIMA": ["Does it rain a lot in {c} in October?", "What is the weather like in {c} at Christmas?",
              "Which are the hottest months in {c}?", "Is it humid in {c} in summer?", "Is it cold in {c} in February?"],
}
COUNTRY_Q_DEV2_EN = {"DOGANE": ["Do I need a visa to enter?", "How much cash can I bring across the border?"],
                     "SALUTE": ["Is tap water safe to drink?", "Are there hospitals for foreigners?"],
                     "SICUREZZA": ["Are there dangerous neighbourhoods?", "Is it safe to travel alone as a woman?"],
                     "ACQUISTI": ["How expensive is it?", "Can you haggle in shops?"],
                     "CONNETTIVITA": ["Is there phone coverage everywhere?"],
                     "TRASPORTI": ["How do buses between cities work?", "Do they drive on the left or the right?"],
                     "CIBO_BEVANDE": ["What is the national dish?"]}
# Domande di controllo: stesse categorie, scritte il 2026-10-13 prima delle correzioni delle liste di parole sugli
# errori di dev2 e mai usate per costruirle o correggerle. Dicono se le liste valgono anche su domande nuove: le
# correzioni si fanno sugli errori delle altre serie, mai su queste (se ne guardano solo i totali).
CITY_Q_CONTROL = {
    "COSA_VEDERE": ["Quali sono i posti più belli di {c}?", "Cosa visitare a {c} con i bambini?", "Che passeggiata fare a {c}?"],
    "TRASPORTI": ["Dove si parcheggia a {c}?", "Come raggiungo il centro di {c}?"],
    "ALLOGGIO": ["C'è un agriturismo a {c}?", "Dove trovo un B&B a {c}?"],
    "CIBO_BEVANDE": ["C'è una buona pizzeria a {c}?", "Quali prodotti tipici ci sono a {c}?", "Dove prendere un gelato a {c}?"],
    "ACQUISTI": ["Ci sono negozi di artigianato a {c}?"],
}
WIKI_Q_CONTROL = {
    "STORIA": ["Quando è stata fondata {c}?", "Cosa c'era a {c} ai tempi dei Romani?", "Che importanza aveva {c} nel Rinascimento?",
               "Quali battaglie ci sono state a {c}?", "Da dove viene il nome di {c}?"],
    "CLIMA": ["Che vestiti portare a {c} a marzo?", "C'è tanto sole a {c}?", "Tira vento a {c}?",
              "Qual è la stagione migliore per visitare {c}?", "Quanti gradi ci sono a {c} d'inverno?"],
}
COUNTRY_Q_CONTROL = {"DOGANE": ["Posso portare medicine in valigia?", "Cosa serve a un minore per entrare?"],
                     "SALUTE": ["Ci sono malattie da cui proteggersi?", "Come chiamo un'ambulanza?"],
                     "SICUREZZA": ["Ci sono truffe per turisti?", "Si può uscire tranquilli la sera?"],
                     "ACQUISTI": ["I prezzi sono alti?", "Dove cambio i soldi?"],
                     "CONNETTIVITA": ["C'è copertura 4G?"],
                     "TRASPORTI": ["Ci sono voli interni?", "Conviene viaggiare in pullman?"],
                     "CIBO_BEVANDE": ["A che ora si cena di solito?"]}
CITY_Q_CONTROL_EN = {
    "COSA_VEDERE": ["What are the most beautiful places in {c}?", "What can I visit in {c} with children?", "Is there a nice walk in {c}?"],
    "TRASPORTI": ["Where can I park in {c}?", "How do I get to the centre of {c}?"],
    "ALLOGGIO": ["Is there a farm stay in {c}?", "Where can I find a B&B in {c}?"],
    "CIBO_BEVANDE": ["Is there a good pizzeria in {c}?", "What are the local products of {c}?", "Where can I get an ice cream in {c}?"],
    "ACQUISTI": ["Are there craft shops in {c}?"],
}
WIKI_Q_CONTROL_EN = {
    "STORIA": ["When was {c} founded?", "What was in {c} in Roman times?", "How important was {c} during the Renaissance?",
               "Which battles took place in {c}?", "Where does the name {c} come from?"],
    "CLIMA": ["What clothes should I pack for {c} in March?", "Is it sunny in {c}?", "Is it windy in {c}?",
              "Which season is best to visit {c}?", "How many degrees is it in {c} in winter?"],
}
COUNTRY_Q_CONTROL_EN = {"DOGANE": ["Can I bring medicines in my luggage?", "What does a minor need to enter?"],
                        "SALUTE": ["Are there diseases I should protect myself from?", "How do I call an ambulance?"],
                        "SICUREZZA": ["Are there scams targeting tourists?", "Is it safe to go out in the evening?"],
                        "ACQUISTI": ["Are prices high?", "Where can I change money?"],
                        "CONNETTIVITA": ["Is there 4G coverage?"],
                        "TRASPORTI": ["Are there domestic flights?", "Is it worth travelling by coach?"],
                        "CIBO_BEVANDE": ["What time do people usually have dinner?"]}
LANGS["it"]["dev"] = (CITY_Q_DEV, WIKI_Q_DEV, COUNTRY_Q_DEV, DISTANCE_Q)
LANGS["en"]["dev"] = (CITY_Q_DEV_EN, WIKI_Q_DEV_EN, COUNTRY_Q_DEV_EN, DISTANCE_Q_EN)
LANGS["it"]["dev2"] = (CITY_Q_DEV2, WIKI_Q_DEV2, COUNTRY_Q_DEV2, DISTANCE_Q)
LANGS["en"]["dev2"] = (CITY_Q_DEV2_EN, WIKI_Q_DEV2_EN, COUNTRY_Q_DEV2_EN, DISTANCE_Q_EN)
LANGS["it"]["control"] = (CITY_Q_CONTROL, WIKI_Q_CONTROL, COUNTRY_Q_CONTROL, DISTANCE_Q)
LANGS["en"]["control"] = (CITY_Q_CONTROL_EN, WIKI_Q_CONTROL_EN, COUNTRY_Q_CONTROL_EN, DISTANCE_Q_EN)
QUESTION_SETS = ["direct", "reworded", "dev", "dev2", "other-lang", "control", "general", "other-country"]
KINDS = ["citta', domande pratiche", "paese", "citta', storia e clima", "distanze"]
# Un paragrafo con una distanza o un tempo di viaggio ("72 km", "3 h 30", "20 minuti", "2 hours").
TRAVEL_FIGURE = re.compile(r"\d+\s*(km|h|ore|minuti|min|hours?|minutes?)\b")
DISTANCE_PAIRS = 20


# --- replica dell'app ------------------------------------------------------------------------------------------------
def fts_prefix(word):
    """ftsPrefix: le prime 5 lettere con l'asterisco, 6 dalle parole di 8; intera sotto le 5."""
    if len(word) < STEM_CHARS:
        return word
    return word[:STEM_CHARS if len(word) < LONG_WORD_CHARS else STEM_CHARS + 1] + "*"


def fts_query(question, region_id, city=None, keep_city_words=True):
    """buildFtsQuery: parole (divise su tutto cio' che non e' lettera o cifra, come l'indice) di almeno 4 caratteri in
    minuscolo, senza quelle del nome della regione e senza STOPWORDS; senza quelle della citta' [city] salvo che
    restino solo quelle; per prefisso (fts_prefix), senza doppioni, in OR."""
    region_tokens = {t.lower() for t in re.split(r"[^\w]+", region_id) if t}
    tokens = [t.lower() for t in re.split(r"[^\w]+|_", question) if t]
    words = [t for t in tokens if len(t) >= 4 and t not in region_tokens and folded(t) not in STOPWORDS]
    city_words = set(normalized_words(spoken_city_name(city)).split()) if city else set()
    topic = [t for t in words if folded(t) not in city_words] or (words if keep_city_words else [])
    phrases = [f'"{p}"' for p in SHORT_QUERY_TERMS if f" {p} " in normalized_words(question)]
    return " OR ".join(dict.fromkeys([fts_prefix(t) for t in topic] + phrases))


def spoken_city_name(city):
    """spokenCityName: senza il disambiguatore di Wikivoyage ("Porto (Portogallo)" -> "Porto")."""
    return city.split(" (")[0].strip()


def folded(text):
    """folded: minuscolo e senza accenti."""
    return "".join(ch for ch in unicodedata.normalize("NFD", text.lower()) if unicodedata.category(ch) != "Mn")


def normalized_words(text):
    """normalizedWords: minuscolo, senza accenti, parole separate da un solo spazio anche agli estremi."""
    return " " + " ".join(w for w in re.split(r"[^\w]+|_", folded(text)) if w) + " "


PLACE_PREPOSITIONS = {
    "it": {"a", "ad", "di", "da", "in", "per", "verso", "vicino"},
    "en": {"in", "to", "from", "near", "around", "at", "of", "visit", "visiting"},
}


ITALIAN_ARTICLES = {"il", "lo", "la", "i", "gli", "le", "l"}

ARTICULATED_PREPOSITIONS = {
    "del", "dello", "della", "dei", "degli", "delle", "dell",
    "al", "allo", "alla", "ai", "agli", "alle", "all",
    "dal", "dallo", "dalla", "dai", "dagli", "dalle", "dall",
    "nel", "nello", "nella", "nei", "negli", "nelle", "nell",
    "sul", "sullo", "sulla", "sui", "sugli", "sulle", "sull",
}


def named_cities(question, cities, lang="it"):
    """namedCities: le citta' nominate nella domanda (a parole intere), dal nome piu' lungo, senza quelle comprese in un
    nome piu' lungo gia' trovato. Un nome di una sola parola conta solo con l'iniziale maiuscola o dopo una
    preposizione di luogo (PLACE_PREPOSITIONS). In italiano un nome con l'articolo ("La Valletta") vale anche senza,
    dopo una preposizione articolata ("della Valletta", "dell'Aquila")."""
    words = normalized_words(question)
    tokens = [w for w in re.split(r"[^\w]+|_", question) if w]
    folded_tokens = [folded(t) for t in tokens]
    prepositions = PLACE_PREPOSITIONS.get(lang, PLACE_PREPOSITIONS["en"])

    def named_as_place(name):
        return any(t == name and (tokens[i][0].isupper() or (i > 0 and folded_tokens[i - 1] in prepositions))
                   for i, t in enumerate(folded_tokens))

    def named_without_article(name):
        first, _, rest = name.partition(" ")
        if lang != "it" or first not in ITALIAN_ARTICLES or not rest:
            return False
        rest_words = rest.split(" ")
        return any(folded_tokens[i:i + len(rest_words)] == rest_words and folded_tokens[i - 1] in ARTICULATED_PREPOSITIONS
                   for i in range(1, len(folded_tokens)))

    found = []
    for c in cities:
        name = normalized_words(spoken_city_name(c)).strip()
        if len(spoken_city_name(c)) >= MIN_CITY_NAME_CHARS and (
                (f" {name} " in words and (" " in name or named_as_place(name))) or named_without_article(name)):
            found.append(c)
    kept = []
    for c in sorted(found, key=lambda c: -len(spoken_city_name(c))):
        if not any(normalized_words(spoken_city_name(c)) in normalized_words(spoken_city_name(k)) for k in kept):
            kept.append(c)
    return kept


def focus_stems(fts, city):
    """focusStems: prime 5 lettere delle parole della query, senza quelle della citta' nominata."""
    city_stems = {w[:STEM_CHARS] for w in normalized_words(spoken_city_name(city)).split() if len(w) >= 4} if city else set()
    return {folded(t.rstrip("*").strip('"'))[:STEM_CHARS] for t in fts.split(" OR ")
            if t and not (t.startswith('"') and len(t) - 2 < STEM_CHARS)} - city_stems


def parse_matchinfo(blob):
    """FtsMatchInfo.parse: matchinfo(..., 'pcxnal')."""
    ints = struct.unpack(f"<{len(blob) // 4}I", blob)
    p, c = ints[0], ints[1]
    tail = 2 + p * c * 3
    return {
        "p": p, "c": c,
        "hits": [[ints[2 + (ph * c + col) * 3] for col in range(c)] for ph in range(p)],
        "docs": [[ints[2 + (ph * c + col) * 3 + 2] for col in range(c)] for ph in range(p)],
        "n": ints[tail], "avg": list(ints[tail + 1:tail + 1 + c]), "len": list(ints[tail + 1 + c:tail + 1 + 2 * c]),
    }


def corpus_stats(tables):
    """FtsCorpusStats.of: righe, lunghezza media e righe con ogni frase sommate su un matchinfo per tabella."""
    n = sum(t["n"] for t in tables)
    c = tables[0]["c"]
    return {
        "n": n,
        "avg": [sum(t["n"] * t["avg"][col] for t in tables) / max(n, 1) for col in range(c)],
        "docs": [[sum(t["docs"][ph][col] for t in tables) for col in range(c)] for ph in range(tables[0]["p"])],
    }


def bm25(info, stats, phrases=None):
    """bm25Score; con [phrases] solo le prime [phrases] frasi della query."""
    score = 0.0
    for ph in range(info["p"] if phrases is None else min(phrases, info["p"])):
        for col in range(info["c"]):
            tf = info["hits"][ph][col]
            if not tf:
                continue
            docs = max(stats["docs"][ph][col], 1)
            idf = math.log(1 + (stats["n"] - docs + BM25_IDF_SMOOTHING) / (docs + BM25_IDF_SMOOTHING))
            norm = 1 - BM25_B + BM25_B * info["len"][col] / max(stats["avg"][col], 1.0)
            score += (TITLE_WEIGHT if col == 0 else BODY_WEIGHT) * idf * tf * (BM25_K1 + 1) / (tf + BM25_K1 * norm)
    return score


def rank_sections(guide, city, country_first, history_or_climate=True, general_guide=(), general_question=False, lang="it",
                  compare_phrases=None, limit=MAX_SECTIONS, general_extra=0):
    """rankSections: [(sezione, matchinfo)] della guida del paese e delle citta' -> le MAX_SECTIONS migliori; prima
    quelle del paese con [country_first] True, quelle delle citta' con False, solo per punteggio con None. Della
    guida generale [general_guide] conta solo la migliore: in testa, in piu' alle altre, se supera di GENERAL_GUIDE_FIRST_RATIO
    volte (1 con [general_question] e nelle guide italiane: generalGuideFirstRatio) la migliore delle altre e la
    domanda non nomina una citta', o da sola se le altre non trovano nulla; altrimenti non entra (mai in fondo come riempitivo).
    Con [compare_phrases] il confronto con la guida generale conta solo le prime frasi della query (senza le parole della
    categoria scelta dal modello, aggiunte in fondo): quelle parole scelgono le sezioni della regione, non se la guida
    generale va in testa.
    Solo per il riordino (--rerank): [limit] sezioni invece di MAX_SECTIONS, e in coda le [general_extra] pagine della guida
    generale con il BM25 piu' alto che non ci sono gia'."""
    tables = [rows[0][1] for rows in (list(guide) + list(general_guide), city) if rows]
    if not tables:
        return []
    stats = corpus_stats(tables)
    weight = lambda s: WIKIPEDIA_OFF_TOPIC_WEIGHT if not history_or_climate and s["category"] in WIKI else 1.0
    ranked = [(bm25(info, stats), True, s) for s, info in guide] + [(bm25(info, stats) * weight(s), False, s) for s, info in city]
    ranked.sort(key=lambda r: (country_first is not None and r[1] != country_first, -r[0]))
    top = ranked[:limit]
    best = max(((bm25(info, stats, compare_phrases), s) for s, info in general_guide), key=lambda r: r[0], default=None)
    sections = [s for _, _, s in top]
    if general_extra:
        ranked_general = [s for s, _ in sorted(general_guide, key=lambda g: -bm25(g[1], stats, compare_phrases))]
        with_general = rank_sections(guide, city, country_first, history_or_climate, general_guide, general_question, lang,
                                     compare_phrases, limit)
        return with_general + [s for s in ranked_general if all(s is not t for t in with_general)][:general_extra]
    if best is None:
        return sections
    infos = {id(s): (info, w) for s, info, w in [(s, i, 1.0) for s, i in guide] + [(s, i, weight(s)) for s, i in city]}
    if compare_phrases is None:
        top_score = max((r[0] for r in top), default=0.0)
    else:
        top_score = max((bm25(infos[id(s)][0], stats, compare_phrases) * infos[id(s)][1] for _, _, s in top), default=0.0)
    if not top or (country_first is not False and best[0] > (1.0 if general_question or lang == "it" else GENERAL_GUIDE_FIRST_RATIO) * top_score):
        return [best[1]] + sections
    return sections


def relevant_paragraphs(body, stems, budget):
    """relevantParagraphs."""
    if len(body) <= budget:
        return body
    paragraphs = [p for p in body.split("\n") if p.strip()]
    hits = [sum(s in folded(p) for s in stems) for p in paragraphs]
    kept, used = [], 0
    for i in sorted(range(len(paragraphs)), key=lambda i: (-hits[i], i)):
        if used + len(paragraphs[i]) + 1 <= budget:
            kept.append(i)
            used += len(paragraphs[i]) + 1
    return "\n".join(paragraphs[i] for i in sorted(kept)) if kept else body[:budget]


def select_context(bodies, stems, max_chars=MAX_CONTEXT):
    """selectContext."""
    parts, remaining = [], max_chars
    for body in bodies:
        if remaining < MIN_SECTION_CHARS:
            break
        part = relevant_paragraphs(body, stems, remaining)
        if part.strip():
            parts.append(part)
            remaining -= len(part) + 2
    return "\n\n".join(parts)[:max_chars]


# --- database come sul dispositivo -----------------------------------------------------------------------------------
def build_db(guides_db, region, city_rows):
    """region.db in memoria con le tabelle FTS4 come le crea Room (content esterno, unicode61). [city_rows]:
    (citta', categoria, titolo, corpo, url) nell'ordine di import."""
    db = sqlite3.connect(":memory:")
    for t, extra in (("guide_sections", ""), ("city_sections", "city TEXT,")):
        db.execute(f"CREATE TABLE {t} (id INTEGER PRIMARY KEY, regionId TEXT, {extra} category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        db.execute(f"CREATE VIRTUAL TABLE {t}_fts USING fts4(title, body, content='{t}', tokenize=unicode61)")
    with closing(sqlite3.connect(guides_db)) as guides:
        # Tutte le guide, come le importa l'app (GuidesImporter): le statistiche di BM25 (righe, lunghezza media, righe
        # con ogni parola) valgono per tutta la tabella, e con le sole righe di una regione cambierebbero molto per poche
        # righe in piu' (quelle della guida generale). La ricerca resta su [region] e sulla guida generale (guide_candidates).
        for row in guides.execute("SELECT regionId, category, title, body, sourceUrl FROM guide_sections"):
            db.execute("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES (?,?,?,?,?)", row)
    for row in city_rows:
        db.execute("INSERT INTO city_sections (regionId, city, category, title, body, sourceUrl) VALUES (?,?,?,?,?,?)", (region, *row))
    # Paragrafi delle sezioni (solo per --paragraphs): una riga per paragrafo non vuoto, con il titolo della sezione.
    for t, extra in (("guide", ""), ("city", "city,")):
        db.execute(f"CREATE TABLE {t}_paragraphs (id INTEGER PRIMARY KEY, sectionId INTEGER, title TEXT, body TEXT)")
        db.execute(f"CREATE VIRTUAL TABLE {t}_paragraphs_fts USING fts4(title, body, content='{t}_paragraphs', tokenize=unicode61)")
        for sid, title, body in db.execute(f"SELECT id, title, body FROM {t}_sections").fetchall():
            db.executemany(f"INSERT INTO {t}_paragraphs (sectionId, title, body) VALUES (?,?,?)",
                           [(sid, title, para) for para in paragraph_chunks(body, PARAGRAPHS["chars"])])
    for t in ("guide_sections", "city_sections", "guide_paragraphs", "city_paragraphs"):
        db.execute(f"INSERT INTO {t}_fts({t}_fts) VALUES('rebuild')")
    return db


def city_candidates(db, region, fts, named):
    """CityRepository.searchCandidates per ogni citta' di [named] ("quanto dista X da Y"), o per tutte se e' vuota."""
    if PARAGRAPHS["on"] == "rank":
        return [({"city": c, "category": k, "title": t, "body": p, "section_body": b}, parse_matchinfo(mi))
                for name in (named or [None]) for c, k, t, p, b, mi in db.execute(
            "SELECT s.city, s.category, s.title, p.body, s.body, matchinfo(city_paragraphs_fts, 'pcxnal') FROM city_paragraphs p "
            "JOIN city_paragraphs_fts ON p.id = city_paragraphs_fts.rowid JOIN city_sections s ON s.id = p.sectionId "
            "WHERE city_paragraphs_fts MATCH ? AND s.regionId = ? AND (? IS NULL OR s.city = ?) LIMIT ?",
            (fts, region, name, name, PARAGRAPHS["cap"]))]
    return [({"city": c, "category": k, "title": t, "body": b}, parse_matchinfo(mi)) for name in (named or [None])
            for c, k, t, b, mi in db.execute(
        "SELECT s.city, s.category, s.title, s.body, matchinfo(city_sections_fts, 'pcxnal') FROM city_sections s "
        "JOIN city_sections_fts ON s.id = city_sections_fts.rowid "
        "WHERE city_sections_fts MATCH ? AND s.regionId = ? AND (? IS NULL OR s.city = ?) LIMIT ?",
        (fts, region, name, name, CITY_CANDIDATE_CAP if name else CANDIDATE_CAP))]


def search(db, region, question, cities, lang="it", word_lists=False, before_rag=False, pool=None):
    """TravelAssistant.searchSections: (sezioni, radici della domanda). Con [word_lists] la query ha anche le parole delle
    guide per la categoria della domanda; le radici restano quelle della domanda. Una nazione diversa da quella di
    [region] nominata senza citta' (named_place) fa cercare nelle sue regioni, senza il suo nome nella query. Con
    [before_rag] la ricerca di prima della guida generale e della nazione nominata: solo la regione aperta e le sue citta'.
    Con [pool] le prime [pool] sezioni della classifica invece del contesto, senza riordino (Hit@5 e MRR@10)."""
    named = named_cities(question, cities, lang)
    city = named[0] if len(named) == 1 else None
    country = None if named or before_rag else named_place(question, region, lang)
    groups = question_groups(question) if word_lists else set()
    # Senza categoria dalle liste, quella scelta dal modello (MODEL_CATEGORIES, come l'app sul dispositivo).
    model = {MODEL_CATEGORIES[question]} if word_lists and not groups and MODEL_CATEGORIES.get(question) else set()
    if MODEL_CATEGORIES and word_lists and not groups and question not in MODEL_CATEGORIES:
        MODEL_CATEGORY_MISSES.add(question)
    with_words = (lambda q: with_category_words(q, question, lang, model)) if word_lists else (lambda q: q)
    base = fts_query(question, region, city or (country and country[1]),
                     keep_city_words=not (groups or model))
    fts = with_words(base)
    if not fts:
        return [], set()
    city_rows = [] if country else city_candidates(db, region, fts, named)
    if city and not city_rows:
        # Nessuna sezione della citta' con le parole della domanda: si cerca anche il suo nome.
        base = fts_query(question, region)
        fts = with_words(base)
        city_rows = city_candidates(db, region, fts, named)
    history_or_climate = bool(HISTORY_CLIMATE_WORDS.search(question)) or bool((groups | model) & HISTORY_CLIMATE_GROUPS)
    guide_regions = country[2] if country else [region]
    rank_for = lambda query, rows, compare, **extra: rank_sections(
        guide_candidates(db, guide_regions, query), rows, country_first=not named, history_or_climate=history_or_climate,
        general_guide=() if before_rag else guide_candidates(db, [GENERAL_GUIDE_REGION_ID], query),
        general_question=bool(GENERAL_TRAVEL_WORDS.search(question)), lang=lang, compare_phrases=compare, **extra)
    rank = lambda **extra: rank_for(fts, city_rows, len([t for t in base.split(" OR ") if t]) if model else None, **extra)
    if RRF["on"] and base and base != fts:
        # Fusione dei ranghi tra la query della domanda e quella con le parole della categoria (solo misura).
        base_rows = [] if country else city_candidates(db, region, base, named)
        fused = rrf([rank_for(base, base_rows, None, limit=MRR_DEPTH), rank(limit=MRR_DEPTH)],
                    [RRF["weight_original"], 1 - RRF["weight_original"]])
        rank = lambda limit=MAX_SECTIONS, general_extra=0: fused[:limit]
    if PARAGRAPHS["on"] == "rank":
        ranked_paragraphs = rank(limit=PARAGRAPHS["pool"])
        rank = lambda limit=MAX_SECTIONS, general_extra=0: by_section(ranked_paragraphs)[:limit]
    elif PARAGRAPHS["on"] in ("within", "within-country") and not pool:
        # Sezioni come oggi; dentro di loro i paragrafi col BM25 piu' alto riempiono il contesto.
        today, mode = rank(), PARAGRAPHS["on"]
        PARAGRAPHS["on"] = "rank"
        try:
            paragraph_rows = [] if country else city_candidates(db, region, fts, named)
            ranked_paragraphs = rank_for(fts, paragraph_rows, len([t for t in base.split(" OR ") if t]) if model else None,
                                         limit=PARAGRAPHS["pool"])
        finally:
            PARAGRAPHS["on"] = mode
        within = paragraphs_within(today, ranked_paragraphs)
        rank = lambda limit=None, general_extra=0: within[:limit]  # le sezioni di oggi, anche la quarta della guida generale
    if pool:
        return rank(limit=pool), focus_stems(base, city)
    sections, stems = rank(), focus_stems(base, city)
    if RERANK["scorer"]:
        sections = reranked(question, sections, rank(limit=RERANK["candidates"], general_extra=RERANK["general"]), stems, lang)
    return sections, stems


# --- riordino col modello della chat (--rerank) ------------------------------------------------------------------------
# Misura senza training: il modello della chat da' un punteggio a ogni candidata, le prime RERANK["candidates"] della
# ricerca di oggi piu' le RERANK["general"] pagine migliori della guida generale. Due punteggi da confrontare:
# - "yesno": "La sezione contiene la risposta? sì o no", con una grammatica che ammette solo le due risposte:
#   P(sì) / (P(sì) + P(no)) sul primo token, come Qwen3-Reranker;
# - "refusal": il prompt dell'app con la candidata come contesto, 1 - P(inizio del rifiuto addestrato), cioe' quanto il
#   modello si aspetta di poter rispondere con quella sezione (ha senso per i modelli addestrati sulle guide).
# Della candidata entrano i paragrafi con piu' radici della domanda, fino a RERANK["snippet"] caratteri (relevant_paragraphs).
RERANK = {"scorer": None, "mode": "replace", "candidates": 10, "general": 2, "snippet": 600, "scoring": "yesno", "blend": 0.5,
          "add_first": None}
RERANK_PROMPT = {
    "it": "Domanda di un viaggiatore: {q}\n\nSezione della guida ({place}):\n{text}\n\n"
          "La sezione contiene la risposta alla domanda? Rispondi solo sì o no.",
    "en": "A traveller's question: {q}\n\nGuide section ({place}):\n{text}\n\n"
          "Does the section contain the answer to the question? Answer only yes or no.",
}
RERANK_ANSWERS = {"it": ("sì", "no"), "en": ("yes", "no")}
RERANK_SLOTS = 4  # richieste in parallelo a llama-server (-np), contesto di 4096 token ciascuna
REFUSAL_START = {"it": "Il contesto", "en": "The context"}  # inizio di REFUSAL e REFUSAL_EN di eval_common.py


def rerank_place(section):
    """Il luogo della candidata nel prompt del riordino: citta' e titolo, guida generale e titolo, o il titolo."""
    title = section.get("title") or section["category"]
    if section.get("city"):
        return f"{spoken_city_name(section['city'])}, {title}"
    return f"guida generale, {title}" if section.get("general_guide") else title


def rerank_text(section, stems):
    return relevant_paragraphs(section["body"], stems, RERANK["snippet"])


def rerank_prompt(question, section, stems, lang):
    return RERANK_PROMPT[lang].format(q=question, place=rerank_place(section), text=rerank_text(section, stems))


def reranked(question, app, candidates, stems, lang):
    """Le sezioni del contesto con il riordino: "replace" le MAX_SECTIONS candidate col punteggio piu' alto (a parita',
    l'ordine della ricerca); "add" quelle dell'app piu' la migliore candidata se manca, in coda come fts+emb1 o in testa se
    il suo punteggio arriva a RERANK["add_first"]; "blend" le
    prime MAX_SECTIONS della fusione dei ranghi (rrf) tra l'ordine della ricerca, con peso RERANK["blend"], e quello del
    riordino, che regge meglio un giudice rumoroso."""
    if not candidates:
        return app
    # In parallelo: llama-server ha RERANK_SLOTS slot, uno per richiesta.
    with ThreadPoolExecutor(RERANK_SLOTS) as pool:
        scores = list(pool.map(lambda s: RERANK["scorer"](question, s, stems, lang), candidates))
    order = sorted(range(len(candidates)), key=lambda i: (-scores[i], i))
    if RERANK["mode"] == "replace":
        return [candidates[i] for i in order[:MAX_SECTIONS]]
    if RERANK["mode"] == "blend":
        return rrf([candidates, [candidates[i] for i in order]], [RERANK["blend"], 1 - RERANK["blend"]])[:MAX_SECTIONS]
    best = candidates[order[0]]
    if any(best is s for s in app):
        return app
    # In coda la quarta sezione viene spesso tagliata dal limite del contesto: con un punteggio sicuro va in testa.
    first = RERANK["add_first"] is not None and scores[order[0]] >= RERANK["add_first"]
    return [best] + app if first else app + [best]


class RerankScorer:
    """llama-server con il modello della chat, prompt ChatML a turno singolo come ai_chat.cpp (blocco <think> vuoto solo
    con enable_thinking), punteggio RERANK["scoring"]; i punteggi restano in [cache_file] (JSON) tra un'esecuzione e
    l'altra, cosi' le varianti (add/replace, candidate) non rifanno le richieste."""

    def __init__(self, model, llama_cpp, gpu_layers, device, cache_file):
        import socket
        import time
        from gguf import GGUFReader
        from eval_common import chatml_prompt, find_llama_bin, llama_env, on_device_prompt, uses_empty_think
        self.chatml_prompt, self.on_device_prompt = chatml_prompt, on_device_prompt
        template = GGUFReader(model).fields.get("tokenizer.chat_template")
        self.think = uses_empty_think(bytes(template.parts[template.data[0]]).decode("utf-8") if template is not None else None)
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        exe = find_llama_bin("llama-server", llama_cpp)
        self.server = subprocess.Popen([exe, "-m", str(model), "--host", "127.0.0.1", "--port", str(port),
                                        "-c", str(4096 * RERANK_SLOTS), "-np", str(RERANK_SLOTS),
                                        "--no-webui", "-ngl", str(gpu_layers), *(["--device", device] if device else [])],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=llama_env(exe))
        atexit.register(self.close)
        self.url = f"http://127.0.0.1:{port}"
        for _ in range(600):
            try:
                if urllib.request.urlopen(f"{self.url}/health", timeout=5).status == 200:
                    break
            except OSError:
                time.sleep(0.5)
        self.cache_file = cache_file
        self.cache = json.loads(cache_file.read_text(encoding="utf-8")) if cache_file.exists() else {}
        self.calls = 0
        self.lock = threading.Lock()

    def post(self, path, payload):
        request = urllib.request.Request(self.url + path, json.dumps(payload).encode("utf-8"), {"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=300) as r:
            return json.load(r)

    def next_probs(self, prompt, grammar=None):
        """({id: probabilita'}, {id: testo}) dei 20 primi token dopo [prompt] (testo, o lista di testo e id): con la
        grammatica normalizzate sui token ammessi, senza grammatica la softmax dei logit."""
        payload = {"prompt": prompt, "n_predict": 1, "n_probs": 20, "cache_prompt": True}
        if grammar:
            payload.update(grammar=grammar, temperature=1.0, samplers=["temperature"], post_sampling_probs=True)
            top = self.post("/completion", payload)["completion_probabilities"][0]["top_probs"]
            return {t["id"]: t["prob"] for t in top}, {t["id"]: t["token"] for t in top}
        payload.update(temperature=0)
        top = self.post("/completion", payload)["completion_probabilities"][0]["top_logprobs"]
        return {t["id"]: math.exp(t["logprob"]) for t in top}, {t["id"]: t["token"] for t in top}

    def yesno(self, prompt, lang):
        yes, no = RERANK_ANSWERS[lang]
        probs, pieces = self.next_probs(prompt, f'root ::= "{yes}" | "{no}"')
        # Con la grammatica il primo token e' un prefisso di una delle due risposte ("s" di "sì", "n" o "no").
        p_yes = sum(p for i, p in probs.items() if yes.startswith(pieces[i]))
        p_no = sum(p for i, p in probs.items() if no.startswith(pieces[i]) and not yes.startswith(pieces[i]))
        return p_yes / (p_yes + p_no) if p_yes + p_no > 0 else 0.0

    def refusal(self, prompt, lang):
        """1 - P(REFUSAL_START), con i token del rifiuto forzati uno alla volta in coda al prompt; un token fuori dai 20
        piu' probabili conta 0."""
        tokens = self.post("/tokenize", {"content": REFUSAL_START[lang]})["tokens"]
        p = 1.0
        for i, token in enumerate(tokens):
            probs, _ = self.next_probs([prompt, *tokens[:i]])
            p *= probs.get(token, 0.0)
        return 1.0 - p

    def __call__(self, question, section, stems, lang):
        if RERANK["scoring"] == "yesno":
            user = rerank_prompt(question, section, stems, lang)
        else:
            user = self.on_device_prompt(rerank_text(section, stems), question, lang)
        prompt = self.chatml_prompt(user, self.think)
        key = f"{RERANK['scoring']}\n{prompt}"
        if key not in self.cache:
            score = getattr(self, RERANK["scoring"])(prompt, lang)
            with self.lock:
                self.cache[key] = score
                self.calls += 1
                if self.calls % 500 == 0:
                    self.save()
        return self.cache[key]

    def save(self):
        part = self.cache_file.with_name(self.cache_file.name + ".part")
        part.write_text(json.dumps(dict(self.cache), ensure_ascii=False), encoding="utf-8")
        os.replace(part, self.cache_file)

    def close(self):
        if self.server.poll() is None:
            self.save()
            self.server.kill()
            self.server.wait()


# --- ricerca per paragrafi (--paragraphs, solo misura) ------------------------------------------------------------------
# Ogni paragrafo e' una riga FTS con il titolo della sezione. "rank": la classifica dei paragrafi (stesso BM25) da' quella
# delle sezioni, nell'ordine del loro paragrafo migliore, e nel contesto di ogni sezione entrano al piu'
# PARAGRAPHS["per_section"] paragrafi, i migliori, nell'ordine del testo. "within": sezioni scelte come oggi, paragrafi
# scelti col BM25 al posto delle radici della domanda (relevant_paragraphs).
PARAGRAPHS = {"on": False, "pool": 60, "cap": 400, "per_section": 2, "chars": 0}
SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-ZÀ-Ý0-9«\"(])")


def paragraph_chunks(body, chars):
    """I paragrafi indicizzati di una sezione: con [chars] 0 una riga non vuota per paragrafo; altrimenti righe
    consecutive unite fino a [chars] caratteri e righe piu' lunghe di 2,5 [chars] divise a fine frase."""
    lines = [line for line in body.split("\n") if line.strip()]
    if not chars:
        return lines
    pieces = []
    for line in lines:
        if len(line) <= 2.5 * chars:
            pieces.append(line)
            continue
        current = ""
        for sentence in SENTENCE_END.split(line):
            if current and len(current) + 1 + len(sentence) > chars:
                pieces.append(current)
                current = sentence
            else:
                current = f"{current} {sentence}" if current else sentence
        if current:
            pieces.append(current)
    chunks, current = [], ""
    for piece in pieces:
        if current and len(current) + 1 + len(piece) > chars:
            chunks.append(current)
            current = piece
        else:
            current = f"{current}\n{piece}" if current else piece
    if current:
        chunks.append(current)
    return chunks


def in_text_order(section_body, chunks):
    """[chunks] (pezzi di [section_body]) nell'ordine in cui compaiono nel testo, uniti da un a capo."""
    return "\n".join(sorted(chunks, key=lambda c: (section_body.find(c), c)))


def paragraphs_within(sections, paragraphs, budget=MAX_CONTEXT):
    """[sections] nel loro ordine, ognuna con i suoi paragrafi presi da [paragraphs] (classifica BM25) finche' il totale
    sta in [budget] caratteri, nell'ordine del testo; una sezione senza paragrafi in classifica tiene il corpo intero
    (select_context lo taglia come oggi)."""
    key = lambda s: (s.get("region"), s.get("city"), s["category"], s.get("title"))
    wanted = {key(s): s["body"] for s in sections}
    chosen, used = {}, 0
    for para in paragraphs:
        k = key(para)
        if (k in wanted and para["section_body"] == wanted[k] and used + len(para["body"]) + 1 <= budget
                and (PARAGRAPHS["on"] == "within" or not para.get("city"))):
            chosen.setdefault(k, set()).add(para["body"])
            used += len(para["body"]) + 1
    out = []
    for s in sections:
        lines = chosen.get(key(s))
        body = in_text_order(s["body"], lines) if lines else s["body"]
        out.append({**s, "body": body, "section_body": s["body"]})
    return out


def by_section(paragraphs):
    groups, order = {}, []
    for para in paragraphs:
        key = (para.get("region"), para.get("city"), para["category"], para.get("title"), para["section_body"][:80])
        if key not in groups:
            groups[key] = []
            order.append(key)
        if len(groups[key]) < PARAGRAPHS["per_section"]:
            groups[key].append(para)
    out = []
    for key in order:
        first = groups[key][0]
        body = in_text_order(first["section_body"], {p["body"] for p in groups[key]})
        out.append({**{k: v for k, v in first.items() if k != "section_body"}, "body": body, "section_body": first["section_body"]})
    return out


# --- fusione dei ranghi (--rrf, solo misura) ---------------------------------------------------------------------------
# RRF tra la classifica della query della domanda e quella della query con le parole della categoria (liste o modello):
# punteggio = somma dei pesi / (K + posto). K = 20 come il RAG di riferimento del piano; peso della query originale 0,5.
RRF = {"on": False, "k": 20, "weight_original": 0.5}


def section_key(section):
    return section.get("region"), section.get("city"), section["category"], section.get("title"), section["body"][:80]


def rrf(rankings, weights):
    """Le sezioni di [rankings] (liste ordinate) per punteggio RRF decrescente; a parita', l'ordine di comparsa."""
    scores, first = {}, {}
    for ranking, weight in zip(rankings, weights):
        for position, section in enumerate(ranking, 1):
            key = section_key(section)
            scores[key] = scores.get(key, 0.0) + weight / (RRF["k"] + position)
            first.setdefault(key, (len(first), section))
    return [first[k][1] for k in sorted(scores, key=lambda k: (-scores[k], first[k][0]))]


def guide_candidates(db, regions, fts):
    """GuideRepository.searchCandidates: [(sezione, matchinfo)] delle guide di [regions] (o della guida generale),
    senza le sezioni "#for-nationality=XX" come l'app senza nazionalita' scelta (isGuideSectionFor): restano quelle
    "#not-for-nationality=XX", il rimando per tutti gli altri. CANDIDATE_CAP candidati per regione, come l'app."""
    marks = ",".join("?" * len(regions))
    if PARAGRAPHS["on"] == "rank":
        return [({"city": None, "region": r, "category": k, "title": t, "body": p, "section_body": b,
                  "general_guide": r == GENERAL_GUIDE_REGION_ID}, parse_matchinfo(mi))
                for r, k, t, p, b, url, mi in db.execute(
                    "SELECT s.regionId, s.category, s.title, p.body, s.body, s.sourceUrl, matchinfo(guide_paragraphs_fts, 'pcxnal') "
                    "FROM guide_paragraphs p JOIN guide_paragraphs_fts ON p.id = guide_paragraphs_fts.rowid "
                    "JOIN guide_sections s ON s.id = p.sectionId "
                    f"WHERE guide_paragraphs_fts MATCH ? AND s.regionId IN ({marks}) LIMIT {PARAGRAPHS['cap'] * len(regions)}",
                    (fts, *regions))
                if "#for-nationality=" not in url]
    return [({"city": None, "region": r, "category": k, "title": t, "body": b, "general_guide": r == GENERAL_GUIDE_REGION_ID}, parse_matchinfo(mi))
            for r, k, t, b, url, mi in db.execute(
                "SELECT s.regionId, s.category, s.title, s.body, s.sourceUrl, matchinfo(guide_sections_fts, 'pcxnal') FROM guide_sections s "
                "JOIN guide_sections_fts ON s.id = guide_sections_fts.rowid "
                f"WHERE guide_sections_fts MATCH ? AND s.regionId IN ({marks}) LIMIT {CANDIDATE_CAP * len(regions)}", (fts, *regions))
            if "#for-nationality=" not in url]


def answer_paragraph(body, stems):
    """Il paragrafo della sezione attesa con piu' radici della domanda (il primo se nessuno ne ha)."""
    paragraphs = [p for p in body.split("\n") if p.strip()]
    return max(paragraphs, key=lambda p: sum(s in p.lower() for s in stems))


def context_row(top, stems, match):
    """Il contesto che l'app mette nel prompt (buildOnDeviceContext senza nota: selectContext dei corpi delle sezioni) e
    se la sezione attesa vi e' entrata: "section_in_context" se almeno un suo paragrafo e' nel contesto (selectContext
    puo' lasciarla fuori o tagliarla), "answer_in_context" se c'e' il suo paragrafo con piu' radici della domanda, che
    resta in "answer_paragraph" (per spostarlo nel contesto con eval_gguf.py --answer-position)."""
    context = select_context([s["body"] for s in top], stems)
    paragraphs = [p for s in match for p in s["body"].split("\n") if p.strip()]
    answer = answer_paragraph(match[0].get("section_body", match[0]["body"]), stems) if match else None
    in_context = bool(answer) and answer[:150] in context
    return {"context": context, "found": bool(match),
            "section_in_context": any(p[:150] in context for p in paragraphs),
            "answer_in_context": in_context, "answer_paragraph": answer if in_context else None}


def write_contexts(path, rows, sample):
    """Le righe [rows] in JSONL in [path]; con [sample] al massimo [sample] domande per tipo, scelte con un seme fisso
    sulle posizioni: le domande non dipendono dalla ricerca, quindi due varianti della ricerca hanno le stesse."""
    by_kind = defaultdict(list)
    for r in rows:
        by_kind[r["kind"]].append(r)
    kept = []
    for kind, kind_rows in by_kind.items():
        picked = sorted(random.Random(9).sample(range(len(kind_rows)), sample)) if 0 < sample < len(kind_rows) else range(len(kind_rows))
        kept += [kind_rows[i] for i in picked]
    with open(path, "w", encoding="utf-8") as out:
        out.writelines(json.dumps(r, ensure_ascii=False) + "\n" for r in kept)
    print(f"contesti: {len(kept)} domande in {path}")


# --- dati --------------------------------------------------------------------------------------------------------------
UA = {"User-Agent": "pocket-travel-eval-retrieval/1.0 (https://github.com/miracle091/pocket-travel)"}


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300).read()


def download(url, out):
    """[out] scaricato da [url], riusato finche' l'URL (che ha la versione pubblicata) resta lo stesso: <out>.url lo
    ricorda. Scritto in un file temporaneo e poi rinominato: un download interrotto non lascia un file troncato."""
    seen = out.with_name(out.name + ".url")
    if not out.exists() or not seen.exists() or seen.read_text(encoding="utf-8") != url:
        data = fetch(url)
        part = out.with_name(out.name + ".part")
        part.write_bytes(lzma.decompress(data) if url.endswith(".xz") else data)
        os.replace(part, out)
        seen.write_text(url, encoding="utf-8")
    return out


def fetch_data(regions, cache, lang):
    """guides<suffisso>.db e <regione>.cities<suffisso>.db pubblicati, piu' <regione>.wp<suffisso>.db con Storia e
    Clima se i cities.db pubblicati non li hanno ancora."""
    conf = LANGS[lang]
    sfx = conf["suffix"]
    cache.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(fetch(MANIFEST_URL))
    download(manifest[conf["guides"]]["fileXz"]["url"], cache / f"guides{sfx}.db")
    by_id = {r["regionId"]: r for r in manifest["regions"]}
    for region in regions:
        cities_db = download(by_id[region][conf["cities"]]["fileXz"]["url"], cache / f"{region}.cities{sfx}.db")
        wp_db = cache / f"{region}.wp{sfx}.db"
        with closing(sqlite3.connect(cities_db)) as db:
            published = {k for (k,) in db.execute("SELECT DISTINCT category FROM city_sections")}
            names = [c for (c,) in db.execute("SELECT DISTINCT city FROM city_sections")]
        if wp_db.exists() or published & WIKI:
            continue
        jsonl = cache / f"{region}.wp{sfx}.jsonl"
        jsonl.write_text("".join(json.dumps({"city": c, "text": ""}, ensure_ascii=False) + "\n" for c in names), encoding="utf-8")
        city_wikipedia.annotate([jsonl], lang)
        gradlew = REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
        lang_arg = "--lang en " if lang == "en" else ""
        subprocess.run([str(gradlew), "-q", ":tools:data-pipeline:content:generateCities", f"--args={lang_arg}{jsonl} {wp_db}"],
                       cwd=REPO_ROOT, check=True)


def city_rows_of(cache, region, with_wikipedia, lang="it"):
    """Sezioni delle citta' nell'ordine di import di un cities.db della pipeline: per citta', Wikivoyage poi Wikipedia."""
    sfx = LANGS[lang]["suffix"]
    rows = defaultdict(list)
    with closing(sqlite3.connect(cache / f"{region}.cities{sfx}.db")) as db:
        for row in db.execute("SELECT city, category, title, body, sourceUrl FROM city_sections"):
            if with_wikipedia or row[1] not in WIKI:
                rows[row[0]].append(row)
    wp_db = cache / f"{region}.wp{sfx}.db"
    if with_wikipedia and wp_db.exists():
        with closing(sqlite3.connect(wp_db)) as db:
            for row in db.execute("SELECT city, category, title, body, sourceUrl FROM city_sections"):
                if row[0] in rows:
                    rows[row[0]].append(row)
    return [r for city_rows in rows.values() for r in city_rows]


def distance_pairs(city_rows):
    """(citta', altra citta', paragrafo): paragrafi delle sezioni TRASPORTI che nominano un'altra citta' della regione
    (nome di una parola, almeno 5 lettere) con km o tempi di viaggio."""
    names = {c for c, *_ in city_rows if " " not in c and len(c) >= 5}
    pairs = {}
    for city, cat, _, body, _ in city_rows:
        if cat != "TRASPORTI" or city not in names:
            continue
        for paragraph in body.split("\n"):
            if TRAVEL_FIGURE.search(paragraph):
                for other in names & set(re.findall(r"\w+", paragraph)) - {city}:
                    pairs.setdefault((city, other), paragraph)
    return [(c, o, p) for (c, o), p in sorted(pairs.items())]


def questions_of(lang, question_set):
    """Gli schemi di domanda (citta', storia e clima, paese, distanze) di [question_set] per le guide in [lang]."""
    if question_set == "other-lang":
        return LANGS[LANGS[lang]["other"]]["questions"]
    return LANGS[lang][question_set if question_set in ("reworded", "dev", "dev2", "control") else "questions"]


def make_plan(region, city_rows, rng, pick, questions):
    """(tipo, categoria attesa, citta' attesa o None, domanda, paragrafo atteso o None) per la regione: citta' e coppie
    scelte con [rng], schemi di domanda di [questions] con [pick], cosi' citta' e coppie non cambiano con gli schemi."""
    city_q, wiki_q, country_q, distance_q = questions
    cats = defaultdict(set)
    for city, cat, *_ in city_rows:
        cats[city].add(cat)
    eligible = sorted(c for c in cats if {"COSA_VEDERE", "TRASPORTI"} <= cats[c] and cats[c] & WIKI)
    plan = []
    for c in rng.sample(eligible, min(len(eligible), CITIES_PER_REGION.get(region, DEFAULT_CITIES))):
        for cat, qs in city_q.items():
            if cat in cats[c]:
                plan.append((KINDS[0], cat, c, pick.choice(qs).format(c=spoken_city_name(c)), None))
        for cat, qs in wiki_q.items():
            if cat in cats[c]:
                plan.append((KINDS[2], cat, c, pick.choice(qs).format(c=spoken_city_name(c)), None))
    for cat, qs in country_q.items():
        plan += [(KINDS[1], cat, None, q, None) for q in qs]
    pairs = distance_pairs(city_rows)
    for c, o, paragraph in rng.sample(pairs, min(len(pairs), DISTANCE_PAIRS)):
        plan.append((KINDS[3], "TRASPORTI", c, pick.choice(distance_q).format(c=c, o=o), paragraph))
    return plan


def general(guides_db, regions, lang, word_lists=False, before_rag=False, rows=None, results=None):
    """Le domande generali (GENERAL_Q) con aperta ogni regione di [regions], senza le citta' (non ne nominano): pagina
    della guida generale attesa nel contesto, al primo posto, e il suo paragrafo con piu' parole della domanda nel contesto.
    In [rows], se c'e', una riga per domanda con il contesto (context_row)."""
    found = first = in_context = n = 0
    for region in regions:
        db = build_db(guides_db, region, [])
        for question, page in GENERAL_Q[lang]:
            top, stems = search(db, region, question, [], lang, word_lists, before_rag)
            match = [s for s in top if s.get("general_guide") and s["title"].split(": ")[0] == page]
            n += 1
            found += bool(match)
            first += bool(top) and top[0] in match
            if match:
                in_context += answer_paragraph(match[0].get("section_body", match[0]["body"]), stems)[:150] in select_context([s["body"] for s in top], stems)
            if rows is not None:
                rows.append({"region": region, "kind": "domande generali", "category": page, "city": None,
                             "question": question, **context_row(top, stems, match)})
        db.close()
    print(f"domande generali ({n} domande): trovata {100 * found / max(n, 1):.1f}%, prima {100 * first / max(n, 1):.1f}%, "
          f"risposta nel contesto {100 * in_context / max(found, 1):.1f}% delle trovate; margine al 95% di trovata "
          f"±{margin(found, n):.1f} punti")
    if results is not None:
        results["domande generali"] = {"n": n, "trovata": found, "prima": first, "risposta nel contesto": in_context}


def other_country(guides_db, regions, lang, word_lists=False, before_rag=False, results=None):
    """Le domande sul paese con nominata un'altra nazione (OTHER_COUNTRY_Q): con aperta ogni regione di [regions] la
    nazione della regione successiva della lista, e con aperta la prima la Francia (nazione divisa in 13 regioni). Attesa
    la sezione della categoria in una regione della nazione nominata."""
    pairs = [(r, REGION_COUNTRY[regions[(i + 1) % len(regions)]]) for i, r in enumerate(regions)] + [(regions[0], "fr")]
    found = first = n = 0
    for region, target in pairs:
        if target == REGION_COUNTRY.get(region) or target not in COUNTRY_NAMES:
            sys.exit(f"other-country: {target} e' la nazione di {region} o non ha un nome in COUNTRY_NAMES")
        name = COUNTRY_NAMES[target][0 if lang == "it" else 1]
        db = build_db(guides_db, region, [])
        for cat, qs in OTHER_COUNTRY_Q[lang].items():
            for q in qs:
                top, _ = search(db, region, q.format(p=name), [], lang, word_lists, before_rag)
                match = [s for s in top if s["category"] == cat and s.get("region") in COUNTRY_REGIONS[target]]
                n += 1
                found += bool(match)
                first += bool(top) and top[0] in match
        db.close()
    print(f"paese nominato ({n} domande): trovata {100 * found / max(n, 1):.1f}%, prima {100 * first / max(n, 1):.1f}%; "
          f"margine al 95% di trovata ±{margin(found, n):.1f} punti")
    if results is not None:
        results["paese nominato"] = {"n": n, "trovata": found, "prima": first}


# --- affidabilita' della misura --------------------------------------------------------------------------------------
def margin(k, n, z=1.96):
    """Meta' dell'intervallo di Wilson al 95% di k/n, in punti percentuali (il lato piu' largo)."""
    if not n:
        return 0.0
    p = k / n
    centre = (p + z * z / (2 * n)) / (1 + z * z / n)
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return 100 * max(centre + half - p, p - (centre - half))


def fingerprint(paths):
    """{nome del file: prime 12 cifre dello sha256} dei file che esistono tra [paths]."""
    out = {}
    for path in paths:
        if path.exists():
            digest = hashlib.sha256()
            with open(path, "rb") as f:
                for block in iter(lambda: f.read(1 << 20), b""):
                    digest.update(block)
            out[path.name] = digest.hexdigest()[:12]
    return out


def measure_config(a):
    """La configurazione che cambia i numeri: serie, regioni, ricerca, categorie del modello, riordino, fusione, paragrafi."""
    config = {"lang": a.lang, "series": a.questions, "regions": a.regions, "word_lists": a.word_lists,
              "before_rag": a.before_rag, "no_wikipedia": a.no_wikipedia,
              "model_categories": fingerprint([a.model_categories]) if a.model_categories else None,
              "rrf": dict(RRF) if RRF["on"] else None,
              "paragraphs": {k: v for k, v in PARAGRAPHS.items()} if PARAGRAPHS["on"] else None}
    if RERANK["scorer"]:
        config["rerank"] = {"model": a.rerank.name, **{k: v for k, v in RERANK.items() if k != "scorer"}}
    return config


def record_history(history, config, data, results):
    """Una riga JSON in [history] (se c'e') con data, impronta dei dati, configurazione e risultati della misura."""
    if history:
        history.parent.mkdir(parents=True, exist_ok=True)
        with open(history, "a", encoding="utf-8") as out:
            out.write(json.dumps({"date": time.strftime("%Y-%m-%dT%H:%M:%S"), "data": data, "config": config,
                                  "results": results}, ensure_ascii=False, sort_keys=True) + "\n")


def check_history(history, config, data):
    """Avviso se lo storico ha gia' una misura con la stessa configurazione ma dati diversi (guide ripubblicate)."""
    if not history.exists():
        return
    for line in history.read_text(encoding="utf-8").splitlines():
        old = json.loads(line)
        if old["config"] == config and old["data"] != data:
            changed = sorted(k for k in set(old["data"]) | set(data) if old["data"].get(k) != data.get(k))
            print(f"attenzione: stessa configurazione della misura del {old['date']} con dati diversi ({', '.join(changed)}): "
                  "i numeri non sono confrontabili con quella", file=sys.stderr)
            return


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--lang", choices=sorted(LANGS), default="it")
    ap.add_argument("--questions", choices=QUESTION_SETS, default="direct",
                    help="domande dirette, riformulate o nell'altra lingua")
    ap.add_argument("--regions", nargs="+", default=DEFAULT_REGIONS)
    ap.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "pocket-travel-eval-retrieval")
    ap.add_argument("--no-wikipedia", action="store_true", help="senza Storia e Clima (per confronto)")
    ap.add_argument("--guides-db", type=Path, help="un guides.db locale al posto di quello pubblicato (per misurare prima di pubblicare)")
    ap.add_argument("--word-lists", action="store_true", help="con le liste di parole per categoria (CategoryWords.kt)")
    ap.add_argument("--before-rag", action="store_true",
                    help="ricerca senza guida generale e senza nazione nominata (con le liste spente: com'era prima del RAG)")
    ap.add_argument("--contexts", type=Path,
                    help="scrive in JSONL domanda, contesto come nel prompt dell'app e presenza della sezione attesa, per "
                         "eval_gguf.py --contexts (senza le distanze: l'app aggiunge la sua sezione calcolata dalle coordinate)")
    ap.add_argument("--model-categories", type=Path,
                    help="JSON {domanda: gruppo} scritto da model_categories.py: con --word-lists, la categoria scelta dal "
                         "modello per le domande in cui le liste non ne riconoscono nessuna (come l'app sul dispositivo)")
    ap.add_argument("--contexts-sample", type=int, default=0, help="con --contexts: al massimo N domande per tipo (0 = tutte)")
    ap.add_argument("--history", type=Path,
                    help="storico JSONL: aggiunge data, impronta dei dati, configurazione e risultati, e avvisa se una misura "
                         "con la stessa configurazione e' stata fatta su dati diversi")
    ap.add_argument("--explain", type=Path,
                    help="JSONL con le domande senza la sezione attesa nel contesto: sezioni scelte e posto di quella attesa")
    ap.add_argument("--paragraph-chars", type=int, default=PARAGRAPHS["chars"],
                    help="con --paragraphs: righe consecutive della stessa sezione unite fino a N caratteri (0 = una riga per "
                         "paragrafo); le righe piu' lunghe di 2,5 N divise a fine frase")
    ap.add_argument("--rerank", type=Path, metavar="GGUF",
                    help="riordino delle candidate col modello della chat (punteggio sì/no da llama-server): misura senza training")
    ap.add_argument("--rerank-mode", choices=["replace", "add", "blend"], default="replace",
                    help="replace: le 3 col punteggio piu' alto; add: le sezioni di oggi piu' la migliore se manca; blend: "
                         "fusione dei ranghi tra la ricerca e il riordino")
    ap.add_argument("--rerank-blend", type=float, default=RERANK["blend"], help="con --rerank-mode blend: peso della ricerca")
    ap.add_argument("--rerank-add-first", type=float, metavar="SOGLIA",
                    help="con --rerank-mode add: la candidata aggiunta va in testa invece che in coda se il punteggio arriva a SOGLIA")
    ap.add_argument("--rerank-snippet", type=int, default=RERANK["snippet"], help="caratteri della candidata nel prompt del riordino")
    ap.add_argument("--rerank-candidates", type=int, default=RERANK["candidates"], help="candidate dalla ricerca di oggi")
    ap.add_argument("--rerank-general", type=int, default=RERANK["general"], help="pagine della guida generale in piu' tra le candidate")
    ap.add_argument("--rerank-scoring", choices=["yesno", "refusal"], default=RERANK["scoring"],
                    help="yesno: P(sì) con la grammatica sì/no; refusal: 1 - P(rifiuto) col prompt dell'app")
    ap.add_argument("--paragraphs", choices=["rank", "within", "within-country"],
                    help="rank: classifica dei paragrafi (al piu' 2 per sezione); within: sezioni come oggi, dentro di loro i "
                         "paragrafi col BM25 piu' alto riempiono il contesto; within-country: lo stesso solo per le sezioni del paese e della guida generale")
    ap.add_argument("--rrf", action="store_true",
                    help="con --word-lists: fusione dei ranghi tra la query della domanda e quella con le parole della categoria")
    ap.add_argument("--rrf-weight", type=float, default=RRF["weight_original"], help="con --rrf: peso della query della domanda")
    ap.add_argument("--llama-cpp", type=Path, help="con --rerank: cartella dei binari di llama.cpp (get_llama_tools.py)")
    ap.add_argument("--gpu-layers", type=int, default=0, help="con --rerank: layer su GPU (99 = tutti)")
    ap.add_argument("--device", help="con --rerank: GPU da usare, come in `llama-server --list-devices` (es. Vulkan0)")
    a = ap.parse_args()
    if a.rrf and not a.word_lists:
        ap.error("--rrf fonde la query con e senza le parole della categoria: serve --word-lists")
    RRF.update(on=a.rrf, weight_original=a.rrf_weight)
    PARAGRAPHS.update(on=a.paragraphs, chars=a.paragraph_chars)
    if a.rerank:
        if not a.llama_cpp:
            ap.error("--rerank richiede --llama-cpp")
        import gpu_lock
        gpu_lock.gpu_job("eval_retrieval.py", gpu=a.gpu_layers > 0)
        RERANK.update(mode=a.rerank_mode, candidates=a.rerank_candidates, general=a.rerank_general,
                      scoring=a.rerank_scoring, blend=a.rerank_blend, add_first=a.rerank_add_first, snippet=a.rerank_snippet)
        a.cache.mkdir(parents=True, exist_ok=True)
        RERANK["scorer"] = RerankScorer(a.rerank, a.llama_cpp, a.gpu_layers, a.device,
                                        a.cache / f"rerank-{a.rerank.stem}-{a.lang}-{RERANK['snippet']}.json")
    if a.before_rag and a.word_lists:
        ap.error("--before-rag misura la ricerca senza le liste di parole")
    if a.model_categories and not a.word_lists:
        ap.error("--model-categories vale solo con --word-lists, come nell'app")
    if a.model_categories:
        MODEL_CATEGORIES.update(json.loads(a.model_categories.read_text(encoding="utf-8")))
        atexit.register(lambda: MODEL_CATEGORY_MISSES and print(
            f"attenzione: {len(MODEL_CATEGORY_MISSES)} domande senza categoria nel file di --model-categories (altre regioni o "
            "cache? rigeneralo con model_categories.py)", file=sys.stderr))
    fetch_data(a.regions, a.cache, a.lang)
    guides_db = a.guides_db or a.cache / f"guides{LANGS[a.lang]['suffix']}.db"
    sfx = LANGS[a.lang]["suffix"]
    data = fingerprint([guides_db] + [a.cache / f"{r}.{kind}{sfx}.db" for r in a.regions for kind in ("cities", "wp")])
    config = measure_config(a)
    print(f"dati: {json.dumps(data, sort_keys=True)}")
    print(f"configurazione: {json.dumps(config, sort_keys=True, ensure_ascii=False)}")
    if a.history:
        check_history(a.history, config, data)
    results = {}
    explained = [] if a.explain else None
    context_rows = [] if a.contexts else None
    labels = {"lang": a.lang, "series": a.questions,
              "search": "before-rag" if a.before_rag else "word-lists" if a.word_lists else "no-word-lists"}
    if a.questions == "general":
        general(guides_db, a.regions, a.lang, a.word_lists, a.before_rag, context_rows, results)
        if a.contexts:
            write_contexts(a.contexts, [{**labels, **r} for r in context_rows], a.contexts_sample)
        record_history(a.history, config, data, results)
        return
    if a.questions == "other-country":
        other_country(guides_db, a.regions, a.lang, a.word_lists, a.before_rag, results)
        record_history(a.history, config, data, results)
        return
    rng, pick = random.Random(7), random.Random(8)
    questions = questions_of(a.lang, a.questions)
    res = defaultdict(lambda: defaultdict(int))
    for region in a.regions:
        # Le domande si scelgono sempre con Storia e Clima, cosi' con e senza --no-wikipedia sono le stesse.
        plan = make_plan(region, city_rows_of(a.cache, region, True, a.lang), rng, pick, questions)
        rows = city_rows_of(a.cache, region, not a.no_wikipedia, a.lang)
        db = build_db(guides_db, region, rows)
        cities = sorted({r[0] for r in rows})
        for kind, cat, city, q, paragraph in plan:
            top, stems = search(db, region, q, cities, a.lang, a.word_lists, a.before_rag)
            r = res[kind]
            r["n"] += 1
            # Una sezione della guida generale con la stessa categoria non e' quella del paese attesa.
            match = [s for s in top if s["category"] == cat and (city is None or s["city"] == city) and not s.get("general_guide")]
            r["trovata"] += bool(match)
            r["prima"] += bool(top) and top[0] in match
            r["Storia/Clima di troppo"] += kind != KINDS[2] and any(s["category"] in WIKI for s in top)
            # Posto della sezione attesa nella classifica della ricerca (senza riordino): Hit@1, Hit@5, MRR@10.
            ranking, _ = search(db, region, q, cities, a.lang, a.word_lists, a.before_rag, pool=MRR_DEPTH)
            rank_of = next((i + 1 for i, s in enumerate(ranking) if s["category"] == cat and (city is None or s["city"] == city)
                            and not s.get("general_guide")), None)
            r["hit1"] += rank_of == 1
            r["hit5"] += rank_of is not None and rank_of <= 5
            r["rr"] += 1 / rank_of if rank_of else 0.0
            if match:
                expected = paragraph or answer_paragraph(match[0].get("section_body", match[0]["body"]), stems)
                r["risposta nel contesto"] += expected[:150] in select_context([s["body"] for s in top], stems)
            elif explained is not None:
                explained.append({"region": region, "kind": kind, "question": q, "expected": {"category": cat, "city": city},
                                  "rank_in_search": rank_of,
                                  "chosen": [{"category": s["category"], "city": s.get("city"), "title": s.get("title"),
                                              "general_guide": bool(s.get("general_guide"))} for s in top]})
            if context_rows is not None and kind != KINDS[3]:
                context_rows.append({**labels, "region": region, "kind": kind, "category": cat, "city": city, "question": q,
                                     **context_row(top, stems, match)})
    if a.contexts:
        write_contexts(a.contexts, context_rows, a.contexts_sample)
    if explained is not None:
        with open(a.explain, "w", encoding="utf-8") as out:
            out.writelines(json.dumps(e, ensure_ascii=False) + "\n" for e in explained)
        print(f"spiegazioni: {len(explained)} domande senza la sezione attesa in {a.explain}")
    for kind in KINDS:
        r = res[kind]
        n = max(r["n"], 1)
        print(f"{kind} ({r['n']} domande): trovata {100 * r['trovata'] / n:.1f}%, prima {100 * r['prima'] / n:.1f}%, "
              f"Storia/Clima di troppo {100 * r['Storia/Clima di troppo'] / n:.1f}%, "
              f"risposta nel contesto {100 * r['risposta nel contesto'] / max(r['trovata'], 1):.1f}% delle trovate, "
              f"Hit@1 {100 * r['hit1'] / n:.1f}%, Hit@5 {100 * r['hit5'] / n:.1f}%, MRR@{MRR_DEPTH} {r['rr'] / n:.3f}; "
              f"margine al 95% di trovata ±{margin(r['trovata'], r['n']):.1f} e di Hit@5 ±{margin(r['hit5'], r['n']):.1f} punti")
        results[kind] = {k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()}
    record_history(a.history, config, data, results)


if __name__ == "__main__":
    main()
