#!/usr/bin/env python3
"""Misura la scelta del contesto dell'assistente sul dispositivo (RAG sulle guide) su dati pubblicati, senza modello
e senza app.

Replica in Python TravelAssistant.kt (buildFtsQuery, namedCities, rankSections, focusStems, selectContext) e
FtsRanking.kt (BM25 su matchinfo 'pcxnal' con le statistiche delle due tabelle sommate): se cambiano li', va cambiato
anche qui. Per ogni regione scarica guides.db e il cities.db pubblicati (manifest), aggiunge Storia e Clima da
Wikipedia (city_wikipedia.py + generateCities, come build-cities.sh) se il cities.db pubblicato non li ha ancora,
e fa domande costruite da modelli fissi:
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
usate per costruirle: dicono se le liste valgono anche su domande nuove. Con --questions general le domande generali (jet lag, mance, passaporto smarrito) con
aperta ogni regione: attesa la sezione della pagina tematica della pseudo-regione mondo (regionId _world) che risponde.
Le sezioni del mondo concorrono in tutte le serie, come nell'app. Con --questions other-country le domande sul paese
che nominano un'altra nazione ("Serve il passaporto per entrare in Grecia?" con aperto il Portogallo): attesa la sezione
della guida della nazione nominata. Con --guides-db un guides.db locale al posto di quello
pubblicato, per misurare un cambio delle guide prima di pubblicarlo. Con --word-lists la query ha anche le parole delle
guide per la categoria della domanda (CategoryWords.kt, accese nell'app; senza l'opzione si misura la ricerca senza liste).

Uso: python eval_retrieval.py [--lang it|en] [--questions direct|reworded|other-lang|control|general|other-country] [--regions italia ...]
[--cache DIR] [--no-wikipedia] [--guides-db FILE] [--word-lists]
(la cache dei file scaricati sta di default nella cartella temporanea di sistema)
Richiede rete (manifest, release, API di Wikipedia) e, per Storia e Clima, il gradle wrapper dalla root del repo.
"""
import argparse
import json
import lzma
import math
import random
import re
import sqlite3
import struct
import subprocess
import sys
import tempfile
import unicodedata
import urllib.request
from collections import defaultdict
from contextlib import closing
from pathlib import Path

import city_wikipedia

MANIFEST_URL = "https://miracle091.github.io/pocket-travel/manifest.json"
REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_REGIONS = ["italia", "portogallo", "grecia", "svizzera", "croazia"]
CITIES_PER_REGION = {"italia": 40}
DEFAULT_CITIES = 15

# --- costanti dell'app ---------------------------------------------------------------------------------------------
WORLD_REGION_ID = "_world"       # GuideRepository.WORLD_REGION_ID
MAX_SECTIONS = 3                 # TravelAssistant.MAX_SECTIONS
CANDIDATE_CAP = 100              # GuideRepository/CityRepository.CANDIDATE_CAP
CITY_CANDIDATE_CAP = 100         # CityRepository.CITY_CANDIDATE_CAP
MAX_CONTEXT = 2000               # buildOnDeviceContext(maxChars)
MIN_SECTION_CHARS = 50           # TravelAssistant.MIN_SECTION_CHARS
MIN_CITY_NAME_CHARS = 3          # TravelAssistant.MIN_CITY_NAME_CHARS
STEM_CHARS = 5                   # TravelAssistant.STEM_CHARS
LONG_WORD_CHARS = 8              # TravelAssistant.LONG_WORD_CHARS
SHORT_WORD_PHRASES = ["jet lag", "wi fi", "tip", "tips", "sim", "atm", "first aid", "pronto soccorso"]  # TravelAssistant.shortWordPhrases
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
WORLD_FIRST_RATIO = 2.0          # TravelAssistant.WORLD_FIRST_RATIO
# TravelAssistant.generalTravelWords: con queste parole la soglia del mondo scende a 1
GENERAL_TRAVEL_WORDS = re.compile(r"\b(ester[oi]|in viaggio|viaggiando|abroad|overseas|travell?ing)\b", re.IGNORECASE)
# TravelAssistant.historyClimateWords
HISTORY_CLIMATE_WORDS = re.compile(
    r"\b(stori|fondat|fondò|secol|guerr|antic|roman[oaie]?\b|mediev|clima|temperatur|piov|piogg|neve|nevic|cald|fredd|estat|"
    r"invern|meteo|stagion|histor|found|centur|wars?\b|ancient|medieval|weather|rain|snow|hot\b|cold\b|summer|winter|season|"
    r"passat|origin|govern|medioev|nascit|nata\b|nato\b|period|quando andare|best time|when to go|establish|ruled|"
    r"middle ages|past\b|origins?\b)",
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
# Liste di parole per categoria (CategoryWords.kt, solo con --word-lists): radici che nella domanda indicano la
# categoria e parole dei titoli delle guide da aggiungere alla query. test_eval_retrieval.py le confronta con l'app.
CATEGORY_TRIGGERS = {
    "ALLOGGIO": "allogg dormi pernot notte alberg hotel ostell campeg stay sleep accomm lodg hostel night camp",
    "CIBO_BEVANDE": "mangi cibo cucin piatt assag ristor pranz cena cenar colaz specia bere vino birra "
                    "food eat dish cuisin restau lunch dinner breakf tast drink wine beer",
    "COSA_VEDERE": "veder visit attraz monum muse perder merit see sight attract worth miss",
    "ACQUISTI": "acqui compr souven negoz mercat manci camer pagar contan bancom soldi valut "
                "shop buy market tip waiter pay cash card money curren atm",
    "TRASPORTI": "arriv spost muov treno treni autob macchin auto nolegg guidar raggiun "
                 "bus train car drive rent transp metro reach around",
    "DOGANE": "passap docum front dogan visto alcol sigar passport border custom visa alcoh cigar",
    "SALUTE": "vacci ammal malat medic osped salut farmac sanit sick ill doctor hospit health jab pharm pronto+soccorso first+aid",
    "SICUREZZA": "sicur peric derub furt borseg ladr crimin truff safe danger rob theft thief pickp crime scam",
    "CONNETTIVITA": "intern telef cellul sim wifi colleg roami online phone mobile connec",
    "STORIA": "stori origin nata nato fond passat medioev govern antic roman guerr "
              "histor found establ past ancien rule middl war middle+ages",
    "CLIMA": "clima meteo piog piov ombrel tempera primav estat invern autun nevic neve caldo fredd stagion "
             "climat weather rain umbrel spring summer winter autumn snow hot cold season "
             "periodo+migliore quando+andare best+time when+to+go",
    "CONSIGLI": "combat evitar preven curar rimed superar affront deal cope avoid prevent cure treat overcom",
    "EMERGENZE": "perdo perso persa smarr rubat lose lost stolen",
}
CATEGORY_GUIDE_WORDS = {
    "it": {"ALLOGGIO": "allogg*", "CIBO_BEVANDE": "mangi*, tavola", "COSA_VEDERE": "veder*", "ACQUISTI": "acqui*, valut*",
           "TRASPORTI": "arriv*, spost*", "DOGANE": "arriv*, dogan*", "SALUTE": "sanit*, salut*", "SICUREZZA": "sicur*",
           "CONNETTIVITA": "contat*", "STORIA": "stori*", "CLIMA": "clima",
           "CONSIGLI": "prepar*, recupe*, preven*, tratta*", "EMERGENZE": "emerg*"},
    "en": {"ALLOGGIO": "sleep", "CIBO_BEVANDE": "eat, drink", "COSA_VEDERE": "see", "ACQUISTI": "buy",
           "TRASPORTI": "get in, get around", "DOGANE": "get in, customs", "SALUTE": "health*", "SICUREZZA": "safe*",
           "CONNETTIVITA": "connect*", "STORIA": "history", "CLIMA": "climate",
           "CONSIGLI": "prepar*, cope, recover*, prevent*, treat*", "EMERGENZE": "lose, lost, stolen"},
}
SHORT_STEM_CHARS = 4             # CategoryWords.SHORT_STEM_CHARS


def question_categories(question):
    """questionCategories: le categorie che le parole della domanda indicano."""
    tokens = [folded(t) for t in re.split(r"[^\w]+|_", question) if t]
    text = " " + " ".join(tokens) + " "

    def hit(stem):
        if "+" in stem:
            return " " + stem.replace("+", " ") in text
        return any(t.startswith(stem) if len(stem) >= SHORT_STEM_CHARS else t in (stem, stem + "s") for t in tokens)
    return {cat for cat, stems in CATEGORY_TRIGGERS.items() if any(hit(s) for s in stems.split())}


def with_category_words(fts, question, lang):
    """withCategoryWords: [fts] con in OR le parole dei titoli delle guide in [lang] delle categorie della domanda."""
    words = CATEGORY_GUIDE_WORDS.get(lang, CATEGORY_GUIDE_WORDS["en"])
    extra = [f'"{w}"' if " " in w else w for cat in sorted(question_categories(question)) for w in words.get(cat, "").split(", ") if w]
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


def named_country(question, open_region, lang="it"):
    """namedCountry: (codice, nome come nella domanda, regioni) della nazione nominata, se non e' quella di [open_region]."""
    by_name = {name: iso for iso, names in COUNTRY_NAMES.items() for name in names}
    open_country = REGION_COUNTRY.get(open_region)
    for name in named_cities(question, list(by_name), lang):
        if by_name[name] != open_country:
            return by_name[name], name, COUNTRY_REGIONS[by_name[name]]
    return None


# Domande generali, non su un paese, con la pagina tematica della pseudo-regione mondo che risponde (titolo nella guida
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
# Per lingua: domande dirette e riformulate (citta', storia e clima, paese, distanze), chiavi del manifest e suffisso
# dei file in cache.
LANGS = {
    "it": {"questions": (CITY_Q, WIKI_Q, COUNTRY_Q, DISTANCE_Q),
           "reworded": (CITY_Q_REWORDED, WIKI_Q_REWORDED, COUNTRY_Q_REWORDED, DISTANCE_Q_REWORDED),
           "guides": "guides", "cities": "cities", "suffix": "", "other": "en"},
    "en": {"questions": (CITY_Q_EN, WIKI_Q_EN, COUNTRY_Q_EN, DISTANCE_Q_EN),
           "reworded": (CITY_Q_REWORDED_EN, WIKI_Q_REWORDED_EN, COUNTRY_Q_REWORDED_EN, DISTANCE_Q_REWORDED_EN),
           "guides": "guidesEn", "cities": "citiesEn", "suffix": "-en", "other": "it"},
}
# Domande di controllo: stesse categorie, parole scelte dopo aver scritto le liste di parole (CATEGORY_TRIGGERS) e mai
# usate per costruirle o correggerle. Dicono se le liste valgono anche fuori dalle domande da cui sono nate: le correzioni
# delle liste si fanno sugli errori delle altre serie, non su queste.
CITY_Q_CONTROL = {
    "COSA_VEDERE": ["Che cosa c'è di bello a {c}?", "Quali chiese meritano a {c}?"],
    "TRASPORTI": ["C'è la metropolitana a {c}?", "Da quale stazione si parte per {c}?"],
    "ALLOGGIO": ["C'è un ostello a {c}?", "Dove prenotare una camera a {c}?"],
    "CIBO_BEVANDE": ["Dove fare colazione a {c}?", "Quale vino bere a {c}?"],
    "ACQUISTI": ["Ci sono mercatini a {c}?"],
}
WIKI_Q_CONTROL = {"STORIA": ["Com'era {c} nell'Ottocento?", "Chi ha costruito le mura di {c}?"],
                  "CLIMA": ["Che clima c'è a {c} a novembre?", "Fa freddo a {c} di sera?"]}
COUNTRY_Q_CONTROL = {"DOGANE": ["Basta la carta d'identità?"], "SALUTE": ["Dove trovo una farmacia aperta?"],
                     "SICUREZZA": ["Ci sono zone da evitare?"], "ACQUISTI": ["Si paga in euro?"],
                     "CONNETTIVITA": ["Il wifi è diffuso?"], "TRASPORTI": ["Come sono le strade?"],
                     "CIBO_BEVANDE": ["Cosa si beve?"]}
CITY_Q_CONTROL_EN = {
    "COSA_VEDERE": ["What is there to do in {c}?", "Which churches are worth it in {c}?"],
    "TRASPORTI": ["Is there a metro in {c}?", "Which station do trains to {c} leave from?"],
    "ALLOGGIO": ["Is there a hostel in {c}?", "Where can I book a room in {c}?"],
    "CIBO_BEVANDE": ["Where can I have breakfast in {c}?", "Which wine should I drink in {c}?"],
    "ACQUISTI": ["Are there markets in {c}?"],
}
WIKI_Q_CONTROL_EN = {"STORIA": ["What was {c} like in the 19th century?", "Who built the walls of {c}?"],
                     "CLIMA": ["What is the climate in {c} in November?", "Is it cold in {c} in the evening?"]}
COUNTRY_Q_CONTROL_EN = {"DOGANE": ["Is an ID card enough?"], "SALUTE": ["Where can I find an open pharmacy?"],
                        "SICUREZZA": ["Are there areas to avoid?"], "ACQUISTI": ["Do they use the euro?"],
                        "CONNETTIVITA": ["Is wifi common?"], "TRASPORTI": ["What are the roads like?"],
                        "CIBO_BEVANDE": ["What do people drink?"]}
LANGS["it"]["control"] = (CITY_Q_CONTROL, WIKI_Q_CONTROL, COUNTRY_Q_CONTROL, DISTANCE_Q)
LANGS["en"]["control"] = (CITY_Q_CONTROL_EN, WIKI_Q_CONTROL_EN, COUNTRY_Q_CONTROL_EN, DISTANCE_Q_EN)
QUESTION_SETS = ["direct", "reworded", "other-lang", "control", "general", "other-country"]
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
    phrases = [f'"{p}"' for p in SHORT_WORD_PHRASES if f" {p} " in normalized_words(question)]
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


def named_cities(question, cities, lang="it"):
    """namedCities: le citta' nominate nella domanda (a parole intere), dal nome piu' lungo, senza quelle comprese in un
    nome piu' lungo gia' trovato. Un nome di una sola parola conta solo con l'iniziale maiuscola o dopo una
    preposizione di luogo (PLACE_PREPOSITIONS)."""
    words = normalized_words(question)
    tokens = [w for w in re.split(r"[^\w]+|_", question) if w]
    folded_tokens = [folded(t) for t in tokens]
    prepositions = PLACE_PREPOSITIONS.get(lang, PLACE_PREPOSITIONS["en"])

    def named_as_place(name):
        return any(t == name and (tokens[i][0].isupper() or (i > 0 and folded_tokens[i - 1] in prepositions))
                   for i, t in enumerate(folded_tokens))

    found = []
    for c in cities:
        name = normalized_words(spoken_city_name(c)).strip()
        if len(spoken_city_name(c)) >= MIN_CITY_NAME_CHARS and f" {name} " in words and (" " in name or named_as_place(name)):
            found.append(c)
    kept = []
    for c in sorted(found, key=lambda c: -len(spoken_city_name(c))):
        if not any(normalized_words(spoken_city_name(c)) in normalized_words(spoken_city_name(k)) for k in kept):
            kept.append(c)
    return kept


def focus_stems(fts, city):
    """focusStems: prime 5 lettere delle parole della query, senza quelle della citta' nominata."""
    city_stems = {w[:STEM_CHARS] for w in normalized_words(spoken_city_name(city)).split() if len(w) >= 4} if city else set()
    return {folded(t.rstrip("*").strip('"'))[:STEM_CHARS] for t in fts.split(" OR ") if t} - city_stems


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


def bm25(info, stats):
    """bm25Score."""
    score = 0.0
    for ph in range(info["p"]):
        for col in range(info["c"]):
            tf = info["hits"][ph][col]
            if not tf:
                continue
            docs = max(stats["docs"][ph][col], 1)
            idf = math.log(1 + (stats["n"] - docs + BM25_IDF_SMOOTHING) / (docs + BM25_IDF_SMOOTHING))
            norm = 1 - BM25_B + BM25_B * info["len"][col] / max(stats["avg"][col], 1.0)
            score += (TITLE_WEIGHT if col == 0 else BODY_WEIGHT) * idf * tf * (BM25_K1 + 1) / (tf + BM25_K1 * norm)
    return score


def rank_sections(guide, city, country_first, history_or_climate=True, world=(), general=False):
    """rankSections: [(sezione, matchinfo)] della guida del paese e delle citta' -> le MAX_SECTIONS migliori; prima
    quelle del paese con [country_first] True, quelle delle citta' con False, solo per punteggio con None. Della
    pseudo-regione mondo [world] conta solo la migliore: in testa, in piu' alle altre, se supera di WORLD_FIRST_RATIO
    volte la migliore delle altre e la domanda non nomina una citta'; altrimenti solo in un posto libero, in fondo."""
    tables = [rows[0][1] for rows in (list(guide) + list(world), city) if rows]
    if not tables:
        return []
    stats = corpus_stats(tables)
    weight = lambda s: WIKIPEDIA_OFF_TOPIC_WEIGHT if not history_or_climate and s["category"] in WIKI else 1.0
    ranked = [(bm25(info, stats), True, s) for s, info in guide] + [(bm25(info, stats) * weight(s), False, s) for s, info in city]
    ranked.sort(key=lambda r: (country_first is not None and r[1] != country_first, -r[0]))
    top = ranked[:MAX_SECTIONS]
    best = max(((bm25(info, stats), s) for s, info in world), key=lambda r: r[0], default=None)
    sections = [s for _, _, s in top]
    if best is None:
        return sections
    if not top or (country_first is not False and best[0] > (1.0 if general else WORLD_FIRST_RATIO) * max(r[0] for r in top)):
        return [best[1]] + sections
    return sections + [best[1]] if len(top) < MAX_SECTIONS else sections


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


# --- database come sul telefono --------------------------------------------------------------------------------------
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
        # righe in piu' (quelle della pseudo-regione mondo). La ricerca resta su [region] e sul mondo (guide_candidates).
        for row in guides.execute("SELECT regionId, category, title, body, sourceUrl FROM guide_sections"):
            db.execute("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES (?,?,?,?,?)", row)
    for row in city_rows:
        db.execute("INSERT INTO city_sections (regionId, city, category, title, body, sourceUrl) VALUES (?,?,?,?,?,?)", (region, *row))
    for t in ("guide_sections", "city_sections"):
        db.execute(f"INSERT INTO {t}_fts({t}_fts) VALUES('rebuild')")
    return db


def city_candidates(db, region, fts, named):
    """CityRepository.searchCandidates per ogni citta' di [named] ("quanto dista X da Y"), o per tutte se e' vuota."""
    return [({"city": c, "category": k, "body": b}, parse_matchinfo(mi)) for name in (named or [None])
            for c, k, b, mi in db.execute(
        "SELECT s.city, s.category, s.body, matchinfo(city_sections_fts, 'pcxnal') FROM city_sections s "
        "JOIN city_sections_fts ON s.id = city_sections_fts.rowid "
        "WHERE city_sections_fts MATCH ? AND s.regionId = ? AND (? IS NULL OR s.city = ?) LIMIT ?",
        (fts, region, name, name, CITY_CANDIDATE_CAP if name else CANDIDATE_CAP))]


def search(db, region, question, cities, lang="it", word_lists=False):
    """TravelAssistant.searchSections: (sezioni, radici della domanda). Con [word_lists] la query ha anche le parole delle
    guide per la categoria della domanda; le radici restano quelle della domanda. Una nazione diversa da quella di
    [region] nominata senza citta' (named_country) fa cercare nelle sue regioni, senza il suo nome nella query."""
    named = named_cities(question, cities, lang)
    city = named[0] if len(named) == 1 else None
    country = None if named else named_country(question, region, lang)
    with_words = (lambda q: with_category_words(q, question, lang)) if word_lists else (lambda q: q)
    base = fts_query(question, region, city or (country and country[1]),
                     keep_city_words=not (word_lists and question_categories(question)))
    fts = with_words(base)
    if not fts:
        return [], set()
    city_rows = [] if country else city_candidates(db, region, fts, named)
    if city and not city_rows:
        # Nessuna sezione della citta' con le parole della domanda: si cerca anche il suo nome.
        base = fts_query(question, region)
        fts = with_words(base)
        city_rows = city_candidates(db, region, fts, named)
    history_or_climate = bool(HISTORY_CLIMATE_WORDS.search(question))
    guide_regions = country[2] if country else [region]
    return (rank_sections(guide_candidates(db, guide_regions, fts), city_rows, country_first=not named,
                          history_or_climate=history_or_climate, world=guide_candidates(db, [WORLD_REGION_ID], fts),
                          general=bool(GENERAL_TRAVEL_WORDS.search(question))),
            focus_stems(base, city))


def guide_candidates(db, regions, fts):
    """GuideRepository.searchCandidates: [(sezione, matchinfo)] delle guide di [regions] (o della pseudo-regione mondo),
    senza le sezioni "#for-nationality=XX" come l'app senza nazionalita' scelta (isGuideSectionFor): restano quelle
    "#not-for-nationality=XX", il rimando per tutti gli altri. CANDIDATE_CAP candidati per regione, come l'app."""
    marks = ",".join("?" * len(regions))
    return [({"city": None, "region": r, "category": k, "title": t, "body": b, "world": r == WORLD_REGION_ID}, parse_matchinfo(mi))
            for r, k, t, b, url, mi in db.execute(
                "SELECT s.regionId, s.category, s.title, s.body, s.sourceUrl, matchinfo(guide_sections_fts, 'pcxnal') FROM guide_sections s "
                "JOIN guide_sections_fts ON s.id = guide_sections_fts.rowid "
                f"WHERE guide_sections_fts MATCH ? AND s.regionId IN ({marks}) LIMIT {CANDIDATE_CAP * len(regions)}", (fts, *regions))
            if "#for-nationality=" not in url]


def answer_paragraph(body, stems):
    """Il paragrafo della sezione attesa con piu' radici della domanda (il primo se nessuno ne ha)."""
    paragraphs = [p for p in body.split("\n") if p.strip()]
    return max(paragraphs, key=lambda p: sum(s in p.lower() for s in stems))


# --- dati --------------------------------------------------------------------------------------------------------------
def download(url, out):
    if not out.exists():
        data = urllib.request.urlopen(url).read()
        out.write_bytes(lzma.decompress(data) if url.endswith(".xz") else data)
    return out


def fetch_data(regions, cache, lang):
    """guides<suffisso>.db e <regione>.cities<suffisso>.db pubblicati, piu' <regione>.wp<suffisso>.db con Storia e
    Clima se i cities.db pubblicati non li hanno ancora."""
    conf = LANGS[lang]
    sfx = conf["suffix"]
    cache.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(urllib.request.urlopen(MANIFEST_URL).read())
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
    for row in sqlite3.connect(cache / f"{region}.cities{sfx}.db").execute("SELECT city, category, title, body, sourceUrl FROM city_sections"):
        if with_wikipedia or row[1] not in WIKI:
            rows[row[0]].append(row)
    wp_db = cache / f"{region}.wp{sfx}.db"
    if with_wikipedia and wp_db.exists():
        for row in sqlite3.connect(wp_db).execute("SELECT city, category, title, body, sourceUrl FROM city_sections"):
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
    """I modelli di domanda (citta', storia e clima, paese, distanze) di [question_set] per le guide in [lang]."""
    if question_set == "other-lang":
        return LANGS[LANGS[lang]["other"]]["questions"]
    return LANGS[lang][question_set if question_set in ("reworded", "control") else "questions"]


def make_plan(region, city_rows, rng, pick, questions):
    """(tipo, categoria attesa, citta' attesa o None, domanda, paragrafo atteso o None) per la regione: citta' e coppie
    scelte con [rng], modelli di [questions] con [pick], cosi' citta' e coppie non cambiano con i modelli."""
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


def general(guides_db, regions, lang, word_lists=False):
    """Le domande generali (GENERAL_Q) con aperta ogni regione di [regions], senza le citta' (non ne nominano): pagina
    del mondo attesa nel contesto, al primo posto, e il suo paragrafo con piu' parole della domanda nel contesto."""
    found = first = in_context = n = 0
    for region in regions:
        db = build_db(guides_db, region, [])
        for question, page in GENERAL_Q[lang]:
            top, stems = search(db, region, question, [], lang, word_lists)
            match = [s for s in top if s.get("world") and s["title"].split(": ")[0] == page]
            n += 1
            found += bool(match)
            first += bool(top) and top[0] in match
            if match:
                in_context += answer_paragraph(match[0]["body"], stems)[:150] in select_context([s["body"] for s in top], stems)
        db.close()
    print(f"domande generali ({n} domande): trovata {100 * found / max(n, 1):.1f}%, prima {100 * first / max(n, 1):.1f}%, "
          f"risposta nel contesto {100 * in_context / max(found, 1):.1f}% delle trovate")


def other_country(guides_db, regions, lang, word_lists=False):
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
                top, _ = search(db, region, q.format(p=name), [], lang, word_lists)
                match = [s for s in top if s["category"] == cat and s.get("region") in COUNTRY_REGIONS[target]]
                n += 1
                found += bool(match)
                first += bool(top) and top[0] in match
        db.close()
    print(f"paese nominato ({n} domande): trovata {100 * found / max(n, 1):.1f}%, prima {100 * first / max(n, 1):.1f}%")


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
    a = ap.parse_args()
    fetch_data(a.regions, a.cache, a.lang)
    guides_db = a.guides_db or a.cache / f"guides{LANGS[a.lang]['suffix']}.db"
    if a.questions == "general":
        general(guides_db, a.regions, a.lang, a.word_lists)
        return
    if a.questions == "other-country":
        other_country(guides_db, a.regions, a.lang, a.word_lists)
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
            top, stems = search(db, region, q, cities, a.lang, a.word_lists)
            r = res[kind]
            r["n"] += 1
            # Una sezione del mondo con la stessa categoria non e' quella del paese attesa.
            match = [s for s in top if s["category"] == cat and (city is None or s["city"] == city) and not s.get("world")]
            r["trovata"] += bool(match)
            r["prima"] += bool(top) and top[0] in match
            r["Storia/Clima di troppo"] += kind != KINDS[2] and any(s["category"] in WIKI for s in top)
            if match:
                expected = paragraph or answer_paragraph(match[0]["body"], stems)
                r["risposta nel contesto"] += expected[:150] in select_context([s["body"] for s in top], stems)
    for kind in KINDS:
        r = res[kind]
        n = max(r["n"], 1)
        print(f"{kind} ({r['n']} domande): trovata {100 * r['trovata'] / n:.1f}%, prima {100 * r['prima'] / n:.1f}%, "
              f"Storia/Clima di troppo {100 * r['Storia/Clima di troppo'] / n:.1f}%, "
              f"risposta nel contesto {100 * r['risposta nel contesto'] / max(r['trovata'], 1):.1f}% delle trovate")


if __name__ == "__main__":
    main()
