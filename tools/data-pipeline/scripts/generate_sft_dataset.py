#!/usr/bin/env python3
"""Genera il dataset SFT (formato onDevicePrompt) dalle guide Wikivoyage IT dell'elenco delle regioni.

Metodo "template + negativi sintetici":
- positivi: domanda per categoria (template), risposta = prime frasi della sezione (estrattivo);
- negativi: domanda su una categoria che il contesto NON copre -> "il contesto non basta".

Uso: python generate_sft_dataset.py [--limit N] [--negatives 0.2] [--seed 42] [--vs]
     [--dump-dir <cartella dei dump> --cities 3000]
Con --dump-dir i testi vengono dai dump di dumps.wikimedia.org (Wikivoyage IT/EN, Wikipedia IT: vedi
DUMP_WIKIS e wiki_dump.py) invece che dall'API, i titoli da sft-sources.tsv: stesso dump e stesso seed
danno lo stesso dataset. --cities aggiunge le pagine delle citta' di Wikivoyage IT (escluse quelle delle
regioni di test). Le domande fuori tema includono quelle di truthful_qa_italian e alpaca-cleaned-italian
(solo le domande, la risposta e' sempre il rifiuto).
--recheck-missing cerca di nuovo le pagine date per inesistenti ("-" in sft-sources.tsv, cache vuote in raw/).
Output (in tools/data-pipeline/data/sft/, git-ignored): pocket_travel_sft.jsonl + ATTRIBUTION.tsv di default
(pubblicabile, senza VS) oppure pocket_travel_sft.with-vs.jsonl + ATTRIBUTION.with-vs.tsv (con --vs), piu'
EXCLUDED[.with-vs].tsv, le fonti e le sezioni rimaste fuori e perche' (vedi write_excluded) — nomi
distinti apposta, cosi' le due varianti convivono sul disco senza sovrascriversi; raw/<regionId>[.en].txt
(cache, condivisa tra le due varianti). Positivi solo dalle pagine IT (la risposta estrattiva EN sarebbe in
inglese, contro "rispondi in italiano"); le pagine EN servono da contesto per una quota minore dei negativi
(NEG_EN_SHARE): nell'app il contesto e' sempre Wikivoyage IT, quindi la maggior parte dei negativi usa quello.
Il testo Wikivoyage e' CC BY-SA 4.0: ATTRIBUTION.tsv elenca le pagine sorgente per la model card.
Viaggiare Sicuri (Farnesina, in italiano) alimenta anche i positivi, ma la sua licenza non e' verificata
(il sito non concede un riuso esplicito) e il training e' estrattivo (le risposte sono frasi letterali
della fonte): un modello addestrato con VS puo' rigenerare testo Farnesina non licenziato se interrogato,
un rischio di redistribuzione, non solo di attribuzione mancante.
POLICY: il default (senza --vs) e' l'UNICA variante che puo' finire
su un repo HuggingFace pubblico (upload_hf.py rifiuta --public se rileva righe VS in ATTRIBUTION.tsv). Con
--vs, VS viene incluso per un dataset/modello di uso locale o personale: mai per la pubblicazione.
Wikipedia IT (CC BY-SA 4.0, via langlink dall'articolo tematico EN: "Cuisine of X", "Culture of X", ecc.)
alimenta i positivi di CIBO_BEVANDE, CONNETTIVITA, USI_COSTUMI, VITA_QUOTIDIANA: sono le categorie che
Wikivoyage spesso non tratta a fondo. L'articolo si divide in paragrafi e
si tengono solo i piu' pertinenti (WP_MAX_PARAGRAPHS): lunghi come una sezione Wikivoyage, non l'intro
enciclopedica troncata a 2000 caratteri.
"""
import argparse, functools, html, json, os, random, re, subprocess, sys, time, unicodedata, urllib.error, urllib.parse, urllib.request
from collections import Counter
from pathlib import Path

from eval_common import TEST_REGIONS
from status import Progress, phase
from travel_questions import require_revision
import wiki_dump

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "data" / "sft"
UA = {"User-Agent": "pocket-travel-sft/0.10 (https://github.com/miracle091/pocket-travel)"}
# Con --dump-dir: titolo della pagina di ogni fonte per regione (regionId, fonte, titolo), versionato cosi'
# che dump + questo file bastino a rifare lo stesso dataset. Le regioni nuove si risolvono via API e si
# aggiungono qui.
SOURCES_TSV = HERE.parent / "sft-sources.tsv"
# Wiki dei dump per fonte: le parti in --dump-dir le trova wiki_dump.dump_files (MediaWiki Content File Exports del
# 1° del mese, https://dumps.wikimedia.org/other/mediawiki_content_current/, download-wikimedia-dumps.sh). wp_en: gli
# articoli tematici originali del dataset inglese (generate_sft.py --lang en).
DUMP_WIKIS = {"it": "itwikivoyage", "en": "enwikivoyage", "wp": "itwiki", "wp_en": "enwiki"}
SOURCE_URL = {"it": "https://it.wikivoyage.org/wiki/", "en": "https://en.wikivoyage.org/wiki/",
              "wp": "https://it.wikipedia.org/wiki/", "wp_en": "https://en.wikipedia.org/wiki/"}

# Stessa mappa (voci IT) di GenerateGuideContent.kt
HEADING_TO_CATEGORY = {
    "rispettare le usanze": "USI_COSTUMI", "come arrivare": "DOGANE",
    "situazione sanitaria": "SALUTE", "sicurezza": "SICUREZZA",
    "come spostarsi": "TRASPORTI", "a tavola": "CIBO_BEVANDE",
    "valuta e acquisti": "ACQUISTI", "come restare in contatto": "CONNETTIVITA",
    "tenersi informati": "VITA_QUOTIDIANA",
}
# Voci EN, stessa mappa di GenerateGuideContent.kt (solo le categorie con domande in QUESTIONS)
EN_HEADING_TO_CATEGORY = {
    "respect": "USI_COSTUMI", "get in": "DOGANE", "stay healthy": "SALUTE", "stay safe": "SICUREZZA",
    "get around": "TRASPORTI", "eat": "CIBO_BEVANDE", "drink": "CIBO_BEVANDE", "buy": "ACQUISTI",
    "connect": "CONNETTIVITA", "cope": "VITA_QUOTIDIANA",
}

# Sezioni delle pagine Viaggiare Sicuri (Farnesina, in italiano) -> categoria. Licenza non verificata.
VS_BASE = "https://www.viaggiaresicuri.it"
VS_SECTION_TO_CATEGORY = {"infoSicurezza": "SICUREZZA", "infoSituazioneSanitaria": "SALUTE",
                          "infoRequisitiIngresso": "DOGANE", "infoMobilita": "TRASPORTI"}

QUESTIONS = {
    "USI_COSTUMI": ["Quali usanze devo rispettare in {r}?", "Ci sono regole di comportamento da conoscere in {r}?", "Come mi comporto con la gente del posto in {r}?"],
    "DOGANE": ["Come si arriva in {r}?", "Cosa devo sapere per entrare in {r}?", "Quali sono i modi per raggiungere {r}?"],
    "SALUTE": ["Quali sono le condizioni sanitarie in {r}?", "Devo preoccuparmi della salute viaggiando in {r}?", "Servono precauzioni sanitarie in {r}?"],
    "SICUREZZA": ["{r} e' sicuro per un turista?", "Ci sono rischi per la sicurezza in {r}?", "A cosa devo fare attenzione in {r}?"],
    "TRASPORTI": ["Come mi sposto in {r}?", "Quali mezzi di trasporto posso usare in {r}?", "Come si gira in {r}?"],
    "CIBO_BEVANDE": ["Cosa si mangia in {r}?", "Com'e' la cucina in {r}?", "Cosa devo sapere su cibo e bevande in {r}?"],
    "ACQUISTI": ["Come funzionano soldi e acquisti in {r}?", "Che valuta si usa in {r}?", "Cosa devo sapere per pagare in {r}?"],
    "CONNETTIVITA": ["Come resto in contatto in {r}?", "Come funzionano telefono e internet in {r}?", "Posso usare il telefono in {r}?"],
    "VITA_QUOTIDIANA": ["Come mi tengo informato in {r}?", "Dove trovo notizie e informazioni utili in {r}?", "Quali informazioni pratiche mi servono in {r}?"],
}
# Altre formulazioni (registro colloquiale, breve, esplicito): ampliano QUESTIONS. Disgiunte da PARA in
# generate_eval_set.py, che resta il test di generalizzazione.
QUESTIONS_EXTRA = {
    "USI_COSTUMI": ["Cosa e' meglio non fare in {r}?", "Quali sono le buone maniere in {r}?", "Tradizioni e usanze di {r}: cosa sapere?", "Come ci si comporta con la gente del posto in {r}?", "Quali sono le regole non scritte in {r}?", "Cosa e' considerato maleducato in {r}?"],
    "DOGANE": ["Come raggiungo {r} e cosa serve per entrare?", "Quali sono le vie d'accesso a {r}?", "Info per arrivare in {r}.", "Quali sono i modi per arrivare a {r}?", "Come ci si arriva, a {r}?", "Con quali mezzi ci si arriva a {r}?"],
    "SALUTE": ["Devo fare vaccinazioni per {r}?", "Come sono i servizi sanitari in {r}?", "Quali rischi per la salute ci sono in {r}?"],
    "SICUREZZA": ["Quali pericoli ci sono in {r}?", "Devo temere furti o criminalita' in {r}?", "Consigli di sicurezza per {r}.", "Posso passeggiare senza problemi in {r}?", "Ci sono zone da evitare in {r}?", "Posso stare tranquillo a {r}?", "E' pericoloso girare in {r}?"],
    "TRASPORTI": ["Che mezzi ci sono per spostarsi in {r}?", "Meglio auto o mezzi pubblici in {r}?", "Come si visita {r} senza perdersi?"],
    "CIBO_BEVANDE": ["Cosa mangiano e bevono in {r}?", "Specialita' gastronomiche di {r}?", "Consigli su ristoranti e cibo in {r}."],
    "ACQUISTI": ["Posso pagare con carta in {r}?", "Serve cambiare valuta per {r}?", "Come si fanno gli acquisti in {r}?"],
    "CONNETTIVITA": ["C'e' internet in {r}?", "Serve una SIM locale in {r}?", "Come funziona il wifi in {r}?"],
    "VITA_QUOTIDIANA": ["Dove trovo informazioni aggiornate su {r}?", "Quali siti o giornali consultare per {r}?", "Consigli pratici per la vita di tutti i giorni in {r}."],
}
# Domande fuori tema per i negativi di training (diverse da OFF_TOPIC di generate_eval_set.py)
OFF_TOPIC_TRAIN = ["Chi e' il presidente degli Stati Uniti?", "Come si fa il pane in casa?", "Che tempo fara' domani a Roma?",
                   "Spiegami come funziona un motore a scoppio.", "Quanto fa 45 diviso 9?", "Racconta una barzelletta.",
                   "Chi ha dipinto la Gioconda?", "Come si scrive un curriculum?", "Qual e' il pianeta piu' grande?",
                   "Come si impara la chitarra?", "Consigliami un film da vedere.", "Che cos'e' l'inflazione?"]
# Chiusure del rifiuto: l'apertura "Il contesto non contiene informazioni" resta fissa (e' il criterio del test)
REFUSAL_TAILS = ["per dettagli affidabili consulta una fonte ufficiale.", "ti consiglio di verificare su fonti ufficiali.",
                 "meglio controllare una fonte aggiornata prima di partire.", "non posso rispondere con certezza usando solo questo testo."]

# Domande in inglese (risposta comunque in italiano). Disgiunte da PARA_EN in generate_eval_set.py.
QUESTIONS_EN = {
    "USI_COSTUMI": ["What local customs should I respect in {r}?", "How should I behave with locals in {r}?", "Any etiquette tips for {r}?"],
    "DOGANE": ["How do I get to {r}?", "What do I need to enter {r}?", "What are the ways to reach {r}?"],
    "SALUTE": ["What are the health conditions in {r}?", "Do I need vaccinations for {r}?", "Any health precautions for {r}?"],
    "SICUREZZA": ["Is {r} safe for tourists?", "What are the safety risks in {r}?", "What should I watch out for in {r}?"],
    "TRASPORTI": ["How do I get around in {r}?", "What transport can I use in {r}?", "How do people travel within {r}?"],
    "CIBO_BEVANDE": ["What do people eat in {r}?", "What is the food like in {r}?", "What should I know about food and drink in {r}?"],
    "ACQUISTI": ["What currency is used in {r}?", "How do I pay for things in {r}?", "Can I use cards in {r}?"],
    "CONNETTIVITA": ["How do I stay in touch in {r}?", "Is there internet in {r}?", "Can I use my phone in {r}?"],
    "VITA_QUOTIDIANA": ["Where can I find news about {r}?", "How do I stay informed in {r}?", "What practical info do I need for {r}?"],
}
OFF_TOPIC_TRAIN_EN = ["Who is the president of France?", "How do I bake bread?", "What is the tallest mountain in the world?",
                      "Write me a short poem.", "How does a car engine work?", "What is 12 times 12?"]
KEYWORDS = {  # radici che il contesto deve contenere perche' la categoria sia davvero trattata (minuscolo)
    "USI_COSTUMI": ["usanz", "costum", "rispett", "educazion", "cultur", "tradizion", "comport", "vest", "mancia"],
    "DOGANE": ["arriv", "aere", "visto", "passaport", "frontier", "dogan", "traghett", "treno", "ingress"],
    "SALUTE": ["salut", "sanit", "vaccin", "malatt", "medic", "ospedal", "farmac", "acqua"],
    "SICUREZZA": ["sicur", "crimin", "furt", "peric", "polizia", "rischi", "attenzione"],
    "TRASPORTI": ["autobus", "treno", "metro", "auto", "aere", "traghett", "taxi", "biciclet", "trasport", "strad"],
    "CIBO_BEVANDE": ["cucina", "piatt", "cibo", "mangi", "ristorant", "bevand", "vino", "birra", "cibi"],
    "ACQUISTI": ["valuta", "moneta", "euro", "dollar", "carta", "bancomat", "prezz", "cambio", "negozi", "acquist"],
    "CONNETTIVITA": ["internet", "wifi", "wi-fi", "telefon", "cellular", "sim", "rete mobile", "4g", "5g", "roaming"],
    "VITA_QUOTIDIANA": ["giornal", "notizie", "informazion", "sito", "radio", "televisi", "stamp", "media"],
}
# Come TravelAssistant.kt: fino a 3 sezioni unite da riga vuota entro 2000 caratteri (make_context), oppure
# il testo di fallback quando la ricerca non trova nulla.
FALLBACK_CONTEXT = "Nessuna informazione disponibile per questa regione."
MAX_CONTEXT = 2000
# Risposta: "massimo 3 frasi" del prompt, ma le frasi Wikivoyage possono essere lunghissime
MAX_ANSWER = 450
URL = re.compile(r"https?://|www\.", re.I)
# Quota dei negativi con contesto Wikivoyage EN invece che IT (nell'app il contesto e' sempre IT)
NEG_EN_SHARE = 0.2

TOPIC = {  # per la risposta negativa: gia' con preposizione articolata
    "USI_COSTUMI": "sulle usanze locali", "DOGANE": "su come arrivare", "SALUTE": "sulla salute e sulle vaccinazioni",
    "SICUREZZA": "sulla sicurezza", "TRASPORTI": "sugli spostamenti", "CIBO_BEVANDE": "su cibo e bevande",
    "ACQUISTI": "su valuta e acquisti", "CONNETTIVITA": "su telefono e internet", "VITA_QUOTIDIANA": "sulle informazioni pratiche",
    "ARRIVARE": "su come arrivare", "COSA_VEDERE": "su cosa vedere", "ALLOGGIO": "su dove dormire", "SHOPPING": "sugli acquisti",
    "STORIA": "sulla storia", "CLIMA": "sul clima",
}

# Fatti rapidi del paese (sezione FATTI_RAPIDI di guides.db, "Campo: valore" per riga, come l'assistente li trova nel
# contesto): domande per campo, la risposta e' la riga del campo. Con --guides-db. Capitale, Valuta, Prefisso telefonico
# e Lato di guida vengono da Wikidata (GenerateCountryFacts.kt) e generate_sft.py li usa solo con --guide-sections
# (QUICK_FACT_WIKIDATA); le domande sulla valuta sono diverse da quelle di ACQUISTI.
QUICK_FACT_QUESTIONS = {
    "Lingua": ["Che lingua si parla in {r}?", "Quali lingue parlano in {r}?", "In {r} che lingua usano?"],
    "Elettricità": ["Che prese elettriche ci sono in {r}?", "Serve un adattatore per le prese in {r}?", "Che tensione ha la corrente in {r}?"],
    "Fuso orario": ["Che fuso orario ha {r}?", "Quante ore di differenza ci sono con {r}?", "Qual e' il fuso orario di {r}?"],
    "Numeri di emergenza": ["Qual e' il numero dell'ambulanza in {r}?", "Che numero chiamo per un'emergenza in {r}?", "Qual e' il numero della polizia in {r}?"],
    "Capitale": ["Qual e' la capitale di {r}?", "Come si chiama la capitale di {r}?", "Quale citta' e' la capitale di {r}?"],
    "Valuta": ["Qual e' la moneta di {r}?", "Come si chiama la valuta di {r}?", "Che moneta hanno in {r}?"],
    "Prefisso telefonico": ["Qual e' il prefisso telefonico di {r}?", "Che prefisso internazionale ha {r}?", "Che prefisso devo comporre per chiamare {r}?"],
    "Lato di guida": ["Da che lato si guida in {r}?", "In {r} si guida a destra o a sinistra?", "Su che lato della strada si guida in {r}?"],
}
QUICK_FACT_TOPIC = {"Lingua": "sulla lingua", "Elettricità": "sulle prese elettriche", "Fuso orario": "sul fuso orario",
                    "Numeri di emergenza": "sui numeri di emergenza", "Capitale": "sulla capitale", "Valuta": "sulla valuta",
                    "Prefisso telefonico": "sul prefisso telefonico", "Lato di guida": "sul lato di guida"}
# Radici che, se presenti in una sezione, ne fanno un contesto che risponde al campo: fuori dai negativi.
QUICK_FACT_KEYWORDS = {"Lingua": ("lingu",), "Elettricità": ("prese", "presa", "volt", "elettric"),
                       "Fuso orario": ("fuso", "utc", "gmt"), "Numeri di emergenza": ("emergenz", "ambulanz", "112", "polizia"),
                       "Capitale": ("capital",), "Valuta": ("valuta", "monet", "euro", "dollar"),
                       "Prefisso telefonico": ("prefiss",), "Lato di guida": ("guida a destra", "guida a sinistra", "si guida")}

# Note personali inventate (nessun dato vero): l'app mette la nota piu' pertinente in fondo al contesto come
# "Nota personale: <titolo>\n<testo>" (buildOnDeviceContext). (titolo, testo, domande, risposta: una frase del testo).
NOTE_SAMPLES = [
    ("Albergo", "Prenotazione all'Hotel Aurora, camera 214. Il check-in e' dalle 14 alle 22. La colazione e' inclusa.",
     ["A che ora posso fare il check-in?", "Da che ora entro in albergo?"], "Il check-in e' dalle 14 alle 22."),
    ("Albergo", "Prenotazione all'Hotel Aurora, camera 214. Il check-in e' dalle 14 alle 22. La colazione e' inclusa.",
     ["La colazione e' compresa?", "In albergo ho la colazione?"], "La colazione e' inclusa."),
    ("Volo di ritorno", "Volo AZ 611 del 14 maggio, partenza alle 18:40 dal terminal 3. Bagaglio da stiva di 23 kg.",
     ["A che ora parte il mio volo di ritorno?", "Quando riparto?"], "Volo AZ 611 del 14 maggio, partenza alle 18:40 dal terminal 3."),
    ("Volo di ritorno", "Volo AZ 611 del 14 maggio, partenza alle 18:40 dal terminal 3. Bagaglio da stiva di 23 kg.",
     ["Quanto bagaglio da stiva posso portare?", "Che peso ha il bagaglio incluso?"], "Bagaglio da stiva di 23 kg."),
    ("Auto a noleggio", "Ritiro dell'auto al banco Rent Easy dell'aeroporto. Restituzione con il pieno entro le 10 di domenica.",
     ["Dove ritiro l'auto a noleggio?", "Dove prendo la macchina?"], "Ritiro dell'auto al banco Rent Easy dell'aeroporto."),
    ("Auto a noleggio", "Ritiro dell'auto al banco Rent Easy dell'aeroporto. Restituzione con il pieno entro le 10 di domenica.",
     ["Quando devo restituire l'auto?", "Entro quando riporto la macchina?"], "Restituzione con il pieno entro le 10 di domenica."),
    ("Museo", "Biglietti per il museo nazionale prenotati per giovedi' alle 11. Il codice della prenotazione e' K7Q2.",
     ["A che ora e' la visita al museo?", "Quando vado al museo?"], "Biglietti per il museo nazionale prenotati per giovedi' alle 11."),
    ("Museo", "Biglietti per il museo nazionale prenotati per giovedi' alle 11. Il codice della prenotazione e' K7Q2.",
     ["Qual e' il codice della prenotazione del museo?", "Che codice devo mostrare al museo?"], "Il codice della prenotazione e' K7Q2."),
    ("Farmaci", "Portare sempre le pastiglie per la pressione, una al mattino. La ricetta e' nella tasca interna dello zaino.",
     ["Dove ho messo la ricetta?", "Dov'e' la ricetta dei farmaci?"], "La ricetta e' nella tasca interna dello zaino."),
    ("Treno", "Treno per la costa il 9 giugno alle 7:55, carrozza 6, posto 42. Il biglietto e' nell'app delle ferrovie.",
     ["Che posto ho sul treno?", "In che carrozza sono sul treno?"], "Treno per la costa il 9 giugno alle 7:55, carrozza 6, posto 42."),
    ("Ristorante", "Tavolo prenotato da Trattoria del Porto sabato alle 20:30 per quattro persone.",
     ["A che ora e' la cena di sabato?", "Per quando ho prenotato al ristorante?"], "Tavolo prenotato da Trattoria del Porto sabato alle 20:30 per quattro persone."),
    ("Assicurazione", "Assicurazione di viaggio con numero di assistenza +39 02 1234 5678, attivo giorno e notte. Polizza n. 55-0192.",
     ["Che numero chiamo per l'assicurazione?", "Qual e' il numero di assistenza dell'assicurazione?"], "Assicurazione di viaggio con numero di assistenza +39 02 1234 5678, attivo giorno e notte."),
]

def load_quick_facts(guides_db, questions=QUICK_FACT_QUESTIONS):
    """{regionId: {campo: riga}} dalla sezione FATTI_RAPIDI di guides.db (solo i campi di `questions`)."""
    import sqlite3
    from contextlib import closing
    out = {}
    with closing(sqlite3.connect(guides_db)) as db:  # "with" sulla sola connessione non la chiude
        for rid, body in db.execute("SELECT regionId, body FROM guide_sections WHERE category = 'FATTI_RAPIDI'"):
            fields = {line.split(":", 1)[0]: line for line in body.splitlines() if line.split(":", 1)[0] in questions}
            if fields:
                out[rid] = (body, fields)
    return out

# Pagine delle citta' di Wikivoyage IT (solo con --dump-dir): sezioni diverse da quelle dei paesi, domande
# pensate per una citta'. Allargano i contesti oltre le ~330 guide dei paesi.
CITY_HEADING_TO_CATEGORY = {
    "come arrivare": "ARRIVARE", "come spostarsi": "TRASPORTI", "cosa vedere": "COSA_VEDERE",
    "dove mangiare": "CIBO_BEVANDE", "dove alloggiare": "ALLOGGIO", "sicurezza": "SICUREZZA",
    "come restare in contatto": "CONNETTIVITA", "acquisti": "SHOPPING",
}
CITY_QUESTIONS = {
    "ARRIVARE": ["Come arrivo a {r}?", "Come si raggiunge {r}?", "Qual e' il modo migliore per arrivare a {r}?", "C'e' un aeroporto vicino a {r}?"],
    "TRASPORTI": ["Come mi sposto a {r}?", "Come funzionano i mezzi pubblici a {r}?", "Conviene girare {r} a piedi?"],
    "COSA_VEDERE": ["Cosa vedere a {r}?", "Quali sono le attrazioni principali di {r}?", "Cosa non perdere a {r}?"],
    "CIBO_BEVANDE": ["Dove si mangia a {r}?", "Dove posso mangiare bene a {r}?", "Consigli per mangiare a {r}?"],
    "ALLOGGIO": ["Dove dormire a {r}?", "Dove posso alloggiare a {r}?", "Ci sono alberghi o ostelli a {r}?"],
    "SICUREZZA": ["{r} e' una citta' sicura?", "Ci sono zone da evitare a {r}?", "A cosa fare attenzione a {r}?"],
    "CONNETTIVITA": ["C'e' il wifi a {r}?", "Come trovo internet a {r}?", "Dove trovo una connessione a {r}?"],
    "SHOPPING": ["Dove fare shopping a {r}?", "Cosa comprare a {r}?", "Ci sono mercati a {r}?"],
}
CITY_QUESTIONS_EN = {
    "ARRIVARE": ["How do I get to {r}?", "Is there an airport near {r}?"],
    "TRASPORTI": ["How do I get around {r}?", "How does public transport work in {r}?"],
    "COSA_VEDERE": ["What should I see in {r}?", "What are the main sights in {r}?"],
    "CIBO_BEVANDE": ["Where can I eat in {r}?", "Any food tips for {r}?"],
    "ALLOGGIO": ["Where should I stay in {r}?", "Are there hostels in {r}?"],
    "SICUREZZA": ["Is {r} safe?", "Are there areas to avoid in {r}?"],
    "CONNETTIVITA": ["Is there wifi in {r}?", "How do I get online in {r}?"],
    "SHOPPING": ["Where can I shop in {r}?", "What should I buy in {r}?"],
}
KEYWORDS.update({
    "ARRIVARE": ["aeroport", "stazion", "treno", "autobus", "aere", "volo", "voli", "autostrad", "porto", "traghett"],
    "COSA_VEDERE": ["muse", "chies", "palazz", "castell", "piazz", "monument", "cattedral", "ponte", "parco", "galleri"],
    "ALLOGGIO": ["hotel", "albergh", "ostell", "campegg", "b&b", "pension", "alloggi", "camere", "agriturism"],
    "SHOPPING": ["negozi", "mercat", "acquist", "centro commerciale", "souvenir", "boutique", "compr"],
})
# Storia e Clima delle citta' (sezioni di Wikipedia di cities.db, generate_sft.py --cities-db). Radici a parte e non in
# KEYWORDS, che filtra anche le domande fuori tema. Ogni domanda ha una parola di historyClimateWords (TravelAssistant.kt):
# solo cosi' l'app porta in alto queste sezioni, che altrimenti pesano poco nella classifica.
WIKI_QUESTIONS = {
    # domande generiche, come quelle delle altre categorie: la risposta estrattiva e' un riassunto della sezione, e una
    # domanda puntuale ("Chi ha fondato X?") insegnerebbe a rispondere anche quando la sezione non ne parla
    "STORIA": ["Qual e' la storia di {r}?", "Raccontami la storia di {r}.",
               "Quali eventi storici hanno segnato {r}?", "Cosa e' successo a {r} nel corso dei secoli?"],
    "CLIMA": ["Che clima c'e' a {r}?", "Com'e' il clima di {r}?",
              "Che temperature ci sono a {r} durante l'anno?", "Com'e' il meteo a {r} nelle varie stagioni?"],
}
WIKI_QUESTIONS_EN = {
    "STORIA": ["What is the history of {r}?", "Tell me about the history of {r}."],
    "CLIMA": ["What is the climate like in {r}?", "What is the weather like in {r} across the seasons?"],
}
WIKI_KEYWORDS = {
    # radici abbastanza lunghe da non trovarsi nelle sezioni pratiche ("centro storico", "imperdibile", "antico")
    "STORIA": ["storia", "fondat", "fondaz", "secol", "guerr", "antich", "romani", "mediev", "impero", "imperator", "regno",
               "dominazion"],
    "CLIMA": ["clima", "climat", "temperatur", "piogg", "piov", "neve", "nevic", "inverno", "estate", "estati", "gradi c",
              "°c", "stagion", "precipitazion"],
}
# Quante domande (una per sezione trattata) prendere da ogni citta' e da quante citta' al massimo: le
# citta' sono ~5.000, senza tetto soffocherebbero le guide dei paesi (il contesto reale dell'app).
CITY_MAX_QUESTIONS = 3
# Sezioni di citta' piu' corte (spesso un solo nome d'albergo) darebbero una "risposta" uguale a tutto il
# contesto: non insegnano a scegliere le frasi giuste.
CITY_MIN_SECTION = 250
# Domande fuori tema da dataset italiani pubblicati (vedi fetch_off_topic): ognuna al massimo due volte,
# per non far imparare al modello poche frasi a memoria invece del concetto di "fuori tema".
# (dataset, config, split, colonna della domanda, licenza, quante righe tenere)
# Le copie "cleaned" di Alpaca si dichiarano CC BY 4.0, ma i dati originali di Stanford Alpaca sono CC BY-NC 4.0 (solo
# ricerca): la riga di ATTRIBUTION riporta la licenza d'origine. Se ne usano solo le domande, con il rifiuto come risposta.
ALPACA_LICENSE = "CC BY-NC 4.0 (dati originali di Stanford Alpaca; la copia dichiara CC BY 4.0)"
OFF_TOPIC_SOURCES = {
    "truthful_qa_italian": ("sapienzanlp/truthful_qa_italian", "default", "validation", "input_translation", "Apache 2.0", 800),
    "alpaca_cleaned_italian": ("DanielSc4/alpaca-cleaned-italian", "it", "train", "instruction", ALPACA_LICENSE, 1500),
}
OFF_TOPIC_MAX_USES = 2
# Revisioni dei dataset delle domande fuori tema (italiani e inglesi), controllate da fetch_off_topic prima di
# riempire la cache (travel_questions.require_revision)
OFF_TOPIC_REVISIONS = {
    "sapienzanlp/truthful_qa_italian": "b4b40c1dfd28c48ed5f3094de9172e56a7b222ca",
    "DanielSc4/alpaca-cleaned-italian": "87525b0178ba16266de4fee61dc39525354a1ab7",
    "truthfulqa/truthful_qa": "741b8276f2d1982aa3d5b832d3ee81ed3b896490",
    "yahma/alpaca-cleaned": "12567cabf869d7c92e573c7c783905fc160e9639",
}
# Una domanda "fuori tema" che tocca i temi di viaggio (es. "consigli per restare in salute") non lo e'
# davvero: si scarta se contiene una radice di KEYWORDS o una di queste.
TRAVEL_STEMS = ("viagg", "turis", "vacanz", "citta'", "città", "paese", "paesi", "nazion")

# Copia di PromptTemplates.onDevicePrompt (trimIndent)
def on_device_prompt(context, question):
    return ("Sei una guida turistica offline.\n"
            "Rispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.\n"
            "Se il contesto non basta, dillo esplicitamente.\n\n"
            f"CONTESTO: {context}\n\nDOMANDA: {question}")

# Markup wiki sopravvissuto alla pulizia (tabelle/template spezzati): la sezione si scarta intera
LEFTOVER = re.compile(r"\{\||\|\}|\|-|\{\{|\}\}|valign")
HEADING = re.compile(r"^==(?!=)\s*(.+?)\s*(?<!=)==$")

_app_cleaner = None

def app_clean(raw):
    """La pulizia delle guide dell'app (cleanBody di GenerateGuideContent.kt, via CleanWikitext.kt): stesso testo,
    con sottotitoli "▸", elenchi "•" e a capo, che l'assistente si trova nel contesto. Un solo processo JVM per tutta
    la generazione, una sezione per riga."""
    global _app_cleaner
    if _app_cleaner is None:
        root = HERE.parents[2]
        gradlew = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
        lines = subprocess.run([str(gradlew), "-q", ":tools:data-pipeline:content:cleanWikitextCommand"], cwd=root,
                               capture_output=True, text=True, check=True).stdout.strip().splitlines()
        java, classpath = lines[-2], lines[-1]
        _app_cleaner = subprocess.Popen([java, "-cp", classpath, "com.pockettravel.pipeline.CleanWikitextKt"],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8")
    _app_cleaner.stdin.write(json.dumps({"raw": raw}) + "\n")
    _app_cleaner.stdin.flush()
    return json.loads(_app_cleaner.stdout.readline())["text"]

def parse_sections(text, headings=HEADING_TO_CATEGORY, dropped=None):
    """[(categoria, corpo)] delle sezioni di [headings]; le categorie delle sezioni scartate per markup residuo
    finiscono in [dropped], se data."""
    out, cur, body = [], None, []
    def flush():
        cat = headings.get((cur or "").lower())
        c = app_clean("\n".join(body))
        if cat and c and not LEFTOVER.search(c):
            out.append((cat, c))
        elif cat and c and dropped is not None:
            dropped.append(cat)
    for line in text.splitlines():
        m = HEADING.match(line.strip())
        if m:
            flush(); cur, body = m.group(1), []
        else:
            body.append(line)
    flush()
    return out

SENTENCE_END = r"(?<=[.!?])\s+"

def line_sentences(text, split=SENTENCE_END):
    """[(numero di riga, frase)] del testo riga per riga (le guide dell'app vanno a capo): i sottotitoli "▸" non sono
    frasi, il segno "•" delle voci di elenco si toglie."""
    out = []
    for n, line in enumerate(text.splitlines()):
        line = line.strip()
        if not line or line.startswith("▸ "):
            continue
        out += [(n, x.strip()) for x in re.split(split, line.removeprefix("• ")) if len(x.strip()) > 1]
    return out

def sentences(text, split=SENTENCE_END):
    """Le frasi di line_sentences, senza il numero di riga."""
    return [x for _, x in line_sentences(text, split)]

@functools.lru_cache(maxsize=None)
def keyword_rx(kws):
    """Le radici di [kws] (tupla) all'inizio di una parola, come travel_questions.py: "media" non vale in
    "immediately", "cultur" non in "agriculture", "dress" non in "address". Una radice che comincia con uno spazio
    (" bus") resta com'e'."""
    return re.compile("|".join(re.escape(k) if k[0] == " " else r"(?<![^\W\d_])" + re.escape(k) for k in kws))

def keyword_hits(cat, text, keywords=KEYWORDS):
    """Le radici di keywords[cat] nel testo (keyword_rx); "respectively" non conta come "respect"."""
    return set(keyword_rx(tuple(keywords[cat])).findall(text.lower().replace("respectively", "")))

def covers(cat, text, keywords=KEYWORDS):
    """True se il testo tratta davvero la categoria (le sezioni Wikivoyage a volte coprono altro)."""
    return bool(keyword_hits(cat, text, keywords))

# Come TravelAssistant.kt (focusStems, selectContext, relevantParagraphs): radici di 5 lettere, e sotto questo spazio
# residuo una sezione in piu' sarebbe solo un frammento.
STEM_CHARS = 5
MIN_SECTION_CHARS = 50
# Come questionStopwords in TravelAssistant.kt: parole della domanda che l'app non cerca.
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

def _folded(text):
    """Minuscolo e senza accenti, come folded in TravelAssistant.kt."""
    return "".join(ch for ch in unicodedata.normalize("NFD", text.lower()) if unicodedata.category(ch) != "Mn")

def question_stems(question, name=""):
    """Come buildFtsQuery + focusStems dell'app: parole divise su tutto cio' che non e' lettera o cifra ("dell'isola" ->
    "dell", "isola"), di almeno 4 caratteri e non in STOPWORDS, prime 5 lettere senza accenti; senza quelle di [name]
    (la regione, che l'app toglie dalla query, o la citta' nominata)."""
    words = lambda text: (w for w in re.split(r"[^\w]+|_", text) if w)
    name_stems = {_folded(w)[:STEM_CHARS] for w in words(name) if len(w) >= 4}
    return {_folded(w)[:STEM_CHARS] for w in words(question) if len(w) >= 4 and _folded(w) not in STOPWORDS} - name_stems

def relevant_paragraphs(body, stems, budget):
    """Come relevantParagraphs dell'app: [body] se sta in [budget]; altrimenti i paragrafi con piu' radici [stems]
    (a parita' i primi) finche' ci stanno, nell'ordine del testo; un solo paragrafo troppo lungo, il suo inizio."""
    if len(body) <= budget:
        return body
    paragraphs = [p for p in body.split("\n") if p.strip()]
    hits = [sum(s in _folded(p) for s in stems) for p in paragraphs]
    kept, used = [], 0
    for i in sorted(range(len(paragraphs)), key=lambda i: (-hits[i], i)):
        if used + len(paragraphs[i]) + 1 <= budget:
            kept.append(i)
            used += len(paragraphs[i]) + 1
    return "\n".join(paragraphs[i] for i in sorted(kept)) if kept else body[:budget]

def make_context(rng, bodies, question="", name="", max_chars=MAX_CONTEXT, ordered=False):
    """Come l'app (selectContext): sezioni unite da riga vuota entro [max_chars], ognuna intera se ci sta nello spazio
    rimasto, altrimenti i suoi paragrafi con piu' parole di [question]. In ordine qualunque: l'app le mette per
    rilevanza, ma la sezione giusta non e' sempre la prima; con [ordered] nell'ordine di [bodies]."""
    parts = list(bodies)
    if not ordered:
        rng.shuffle(parts)
    stems = question_stems(question, name)
    out, remaining = [], max_chars
    for body in parts:
        if remaining < MIN_SECTION_CHARS:
            break
        part = relevant_paragraphs(body, stems, remaining)
        if part.strip():
            out.append(part)
            remaining -= len(part) + 2
    return "\n\n".join(out)[:max_chars]

def pick_answer(context, body, question, cat, name, keywords=KEYWORDS, split=SENTENCE_END):
    """Fino a 3 frasi del corpo presenti per intero nel contesto: quelle piu' vicine alla domanda
    (parole in comune, escluso il nome della regione) o alla categoria; in ordine di testo. Solo frasi
    pertinenti (punteggio > 0) se ce ne sono, senza link, e al massimo MAX_ANSWER caratteri in tutto. Frasi di righe
    diverse (voci di elenco, sottosezioni) separate da un a capo come nella guida, quelle della stessa riga da uno spazio."""
    stems = {w[:5] for w in re.findall(r"\w{4,}", question.lower())} - {w[:5] for w in re.findall(r"\w{4,}", name.lower())}
    cand = [(i, n, x) for i, (n, x) in enumerate(line_sentences(body, split))
            if x in context and not URL.search(x) and len(x) <= MAX_ANSWER]  # frasi-elenco lunghissime: fuori
    score = lambda x: 2 * sum(st in x.lower() for st in stems) + covers(cat, x, keywords)
    ranked = sorted(cand, key=lambda t: (-score(t[2]), t[0]))
    best = [t for t in ranked if score(t[2]) > 0][:3] or ranked[:1]
    while len(best) > 1 and sum(len(x) + 1 for _, _, x in best) > MAX_ANSWER:
        best.pop()  # toglie la meno pertinente
    best.sort()
    return "".join(("" if k == 0 else " " if n == best[k - 1][1] else "\n") + x for k, (_, n, x) in enumerate(best))

def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")

def fetch_it(title, lang="it"):
    """(testo_raw, url) della pagina in lingua `lang` (IT, DE, FR) via langlink EN, o None."""
    q = (f"https://en.wikivoyage.org/w/api.php?action=query&titles={urllib.parse.quote(title)}"
         f"&prop=langlinks&lllang={lang}&redirects=1&format=json")
    pages = json.loads(get(q))["query"]["pages"]
    links = next(iter(pages.values())).get("langlinks")
    if not links:
        return None
    it_title = urllib.parse.quote(links[0]["*"].replace(" ", "_"))
    text = get(f"https://{lang}.wikivoyage.org/w/index.php?title={it_title}&action=raw")
    return (text, f"https://{lang}.wikivoyage.org/wiki/{it_title}") if text.strip() else None

def fetch_en(title):
    """(testo_raw, url) della pagina EN, o None."""
    en_title = urllib.parse.quote(title)
    text = get(f"https://en.wikivoyage.org/w/index.php?title={en_title}&action=raw")
    return (text, f"https://en.wikivoyage.org/wiki/{en_title}") if text.strip() else None

# Titoli Wikipedia EN per l'articolo tematico di un paese: alimentano i positivi delle categorie che
# Wikivoyage spesso non tratta a fondo (CIBO_BEVANDE, CONNETTIVITA, USI_COSTUMI, VITA_QUOTIDIANA).
# Piu' di un pattern per categoria: se il primo manca, MediaWiki spesso lo rinvia a un titolo piu' ampio
# (es. "Cuisine of Guyana" -> "Culture of Guyana"), quindi il secondo pattern raramente serve davvero.
# "{n} cuisine" serve alle cucine regionali, che su Wikipedia EN hanno solo quel titolo ("Fujian cuisine").
WP_TITLE_PATTERNS = {
    "CIBO_BEVANDE": ["Cuisine of {n}", "{n} cuisine"],
    "CONNETTIVITA": ["Telecommunications in {n}", "Communications in {n}"],
    "USI_COSTUMI": ["Culture of {n}"],
    "VITA_QUOTIDIANA": ["Mass media in {n}", "Media of {n}"],
}
WP_LANG_SUFFIX = {"CIBO_BEVANDE": "wp_cibo", "CONNETTIVITA": "wp_conn",
                  "USI_COSTUMI": "wp_usi", "VITA_QUOTIDIANA": "wp_vita"}

def wp_candidate_titles(en_title, cat):
    """Titoli Wikipedia EN da provare per il tema `cat` del paese, nell'ordine di WP_TITLE_PATTERNS. L'articolo
    iniziale va minuscolo a meta' titolo ("Cuisine of the Bahamas", non "of The Bahamas", che non esiste)."""
    n = en_title.replace("_", " ")
    mid = "the " + n[4:] if n.startswith("The ") else n
    return [p.format(n=n if p.startswith("{n}") else mid) for p in WP_TITLE_PATTERNS[cat]]

def topic_word(title, en_title):
    """La parola del tema in un titolo di wp_candidate_titles, minuscola: "cuisine" per "Cuisine of the Bahamas"."""
    place = set(en_title.replace("_", " ").lower().split())
    return next(w for w in title.lower().split() if w not in place | {"of", "in", "the"})

def fetch_wp_it(en_title, cat):
    """(testo_raw, url) della pagina Wikipedia IT sul tema `cat` del paese, via langlink dal titolo EN
    (stesso meccanismo di fetch_it, ma su Wikipedia: serve la versione IT per un positivo estrattivo,
    l'estratto EN sarebbe in inglese). Un titolo che rinvia alla voce del paese ("Vatican City cuisine" -> "Vatican
    City") non vale: la pagina finale deve avere nel titolo la parola del tema (topic_word)."""
    for title in wp_candidate_titles(en_title, cat):
        q = (f"https://en.wikipedia.org/w/api.php?action=query&titles={urllib.parse.quote(title)}"
             "&prop=langlinks&lllang=it&redirects=1&format=json")
        page = next(iter(json.loads(get(q))["query"]["pages"].values()))
        links = page.get("langlinks")
        if "missing" in page or not links or topic_word(title, en_title) not in page["title"].lower():
            continue
        it_title = urllib.parse.quote(links[0]["*"].replace(" ", "_"))
        text = get(f"https://it.wikipedia.org/w/index.php?title={it_title}&action=raw")
        return (text, f"https://it.wikipedia.org/wiki/{it_title}") if text.strip() else None
    return None

WP_MAX_PARAGRAPHS = 2
WP_MIN_PARAGRAPH = 150
WP_SKIP_SECTIONS = {"note", "bibliografia", "voci correlate", "collegamenti esterni", "altri progetti", "galleria d'immagini"}
WP_SKIP_SECTIONS_EN = {"references", "see also", "external links", "further reading", "notes", "bibliography",
                       "sources", "works cited", "citations", "footnotes", "gallery"}
# Negli articoli di Wikipedia "cultur", "tradizion" e "religio" prendono paragrafi su monumenti, statistiche religiose
# o letteratura: per USI_COSTUMI solo parole di comportamento ("rispettare", non "rispett": prenderebbe "rispettivamente"
# e "rispetto alla media")
WP_KEYWORDS = {**KEYWORDS, "USI_COSTUMI": ["usanz", "rispettare", "rispettos", "comport", "mancia", "mance", "galateo", "buone maniere", "abbigliament", "tabù", "saluta", "costum"]}
# radici diverse che un paragrafo deve avere: una sola parola su usanze o abbigliamento capita anche nella storia
WP_MIN_HITS = {"USI_COSTUMI": 2}
WP_HEADING = re.compile(r"(={2,})\s*(.+?)\s*\1\s*(?:\n|$)")

def parse_wp(raw, cat, keywords=WP_KEYWORDS, skip_sections=WP_SKIP_SECTIONS):
    """[(cat, paragrafo)]: i WP_MAX_PARAGRAPHS paragrafi dell'articolo di Wikipedia che toccano piu' [keywords] della
    categoria (puliti come le sezioni Wikivoyage, con app_clean), nell'ordine dell'articolo; niente incipit
    enciclopedico (tutto cio' che precede il primo titolo) ne' [skip_sections] (note, bibliografia): le apre o le chiude
    solo un titolo di livello 2, non i sottotitoli. Per Wikipedia EN: WP_KEYWORDS_EN di generate_sft_dataset_en.py e
    WP_SKIP_SECTIONS_EN."""
    paras, skip, lead = [], True, True
    for block in re.split(r"\n\s*\n|\n(?==)", raw):  # anche un titolo senza riga vuota prima apre un blocco
        block = block.strip()
        if m := WP_HEADING.match(block):
            if len(m.group(1)) == 2 or lead:
                skip = m.group(2).lower() in skip_sections
            lead = False
            block = block[m.end():]
        if skip or not block:
            continue
        body = app_clean(block)
        if (len(body) >= WP_MIN_PARAGRAPH and not LEFTOVER.search(body)
                and len(keyword_hits(cat, body, keywords)) >= WP_MIN_HITS.get(cat, 1)):
            paras.append(body)
    hits = lambda p: len(keyword_hits(cat, p, keywords))
    best = sorted(sorted(range(len(paras)), key=lambda i: -hits(paras[i]))[:WP_MAX_PARAGRAPHS])
    return [(cat, paras[i]) for i in best]

def fetch_vs(iso3):
    """(json_raw, url) della pagina del paese di Viaggiare Sicuri (JSON statico del sito), o None."""
    try:
        return get(f"{VS_BASE}/schede_paese/{iso3}.json"), f"{VS_BASE}/find-country/country/{iso3}"
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise

def parse_vs(raw):
    """[(categoria, corpo)] dalle sezioni della pagina (HTML -> testo), nodi nell'ordine del sito."""
    d = json.loads(raw)
    out = []
    for key, cat in VS_SECTION_TO_CATEGORY.items():
        nodes = sorted((d.get(key) or {}).get("nodi", {}).values(), key=lambda n: n.get("ordinamento", 0))
        # <span> nel CMS di VS spezza spesso una parola a meta' (es. "Poliz<span...>ia"): va tolto senza
        # spazio, non con lo spazio usato per gli altri tag (che separano invece parole/frasi distinte)
        clean = lambda t: re.sub(r"<[^>]+>", " ", re.sub(r"</?span[^>]*>", "", t))
        body = " ".join(html.unescape(clean(n.get("contenuto") or "")) for n in nodes)
        body = re.sub(r"\s+", " ", body).strip()
        if body:
            out.append((cat, body))
    return out

def vs_codes(regions):
    """regionId -> codice ISO3 della pagina VS. Solo regioni con bandiera propria (non condivisa con altre
    regioni, non in un gruppo): altrimenti la pagina del paese non descriverebbe la regione."""
    rows = [r.split("|") for r in re.findall(r'^\s*"([^"]+\|[^"]+)"\s*$', (HERE / "regions.sh").read_text(encoding="utf-8"), re.M)]
    flags = Counter(f[7] for f in rows if len(f) > 8)
    iso3 = {n["Codice-2"].lower(): n["Codice-3"] for n in json.loads(get(f"{VS_BASE}/schede_paese/lista_nazioni.json"))}
    return {f[0]: iso3[f[7]] for f in rows if len(f) > 8 and flags[f[7]] == 1 and not f[8] and f[7] in iso3}

def load_regions():
    src = (HERE / "regions.sh").read_text(encoding="utf-8")
    rows = re.findall(r'^\s*"([^"]+\|[^"]+)"\s*$', src, re.M)
    return [(f[0], f[1], f[6]) for f in (r.split("|") for r in rows) if len(f) >= 7]

def fetch_off_topic(sources=OFF_TOPIC_SOURCES, keywords=KEYWORDS, travel_stems=TRAVEL_STEMS):
    """{fonte: [domande]} dai dataset di `sources`, in cache in raw/offtopic_<fonte>.txt (una per
    riga). Solo domande brevi, a riga singola e senza temi di viaggio."""
    out = {}
    for name, (dataset, config, split, column, _, keep) in sources.items():
        cache = OUT / "raw" / f"offtopic_{name}.txt"
        if not cache.exists():
            require_revision(dataset, OFF_TOPIC_REVISIONS[dataset])
            qs, offset = [], 0
            while len(qs) < keep:
                url = ("https://datasets-server.huggingface.co/rows?" + urllib.parse.urlencode(
                    {"dataset": dataset, "config": config, "split": split, "offset": offset, "length": 100}))
                for attempt in range(1, 6):  # datasets-server risponde 429 se le richieste sono fitte, a volte 502
                    try:
                        rows = json.loads(get(url))["rows"]
                        break
                    except urllib.error.HTTPError as e:
                        if (e.code != 429 and e.code < 500) or attempt == 5:
                            raise
                        time.sleep(15 * attempt)
                if not rows:
                    break
                for r in rows:
                    q = (r["row"].get(column) or "").strip()
                    low = q.lower()
                    if column == "instruction" and r["row"].get("input"):
                        continue  # alpaca: niente istruzioni con un testo di input a parte
                    if not 10 <= len(q) <= 160 or "\n" in q:
                        continue
                    if any(s in low for s in travel_stems) or any(k in low for ks in keywords.values() for k in ks):
                        continue
                    qs.append(q)
                offset += 100; time.sleep(2)
            cache.write_text("\n".join(qs[:keep]) + "\n", encoding="utf-8")
        out[name] = [l for l in cache.read_text(encoding="utf-8").splitlines() if l.strip()]
    return out

def load_sources():
    """{(regionId, fonte): titolo} da SOURCES_TSV (fonte: it, en, wp_cibo, wp_conn, wp_usi, wp_vita)."""
    if not SOURCES_TSV.exists():
        return {}
    rows = [l.split("\t") for l in SOURCES_TSV.read_text(encoding="utf-8").splitlines()[1:] if l.strip()]
    return {(r[0], r[1]): r[2] for r in rows}

def save_sources(sources):
    with open(SOURCES_TSV, "w", encoding="utf-8", newline="\n") as f:
        f.write("regionId\tsource\ttitle\n")
        for (rid, src), title in sorted(sources.items()):
            f.write(f"{rid}\t{src}\t{title}\n")

def raw_cache(rid, src):
    """(testo, url) della cache in raw/ di una fonte della regione: testo vuoto = pagina inesistente."""
    suffix = "" if src == "it" else f".{src}"
    return OUT / "raw" / f"{rid}{suffix}.txt", OUT / "raw" / f"{rid}{suffix}.url"

def forget_missing(regions, srcs, sources):
    """Toglie i "-" di [sources] e le cache vuote delle fonti [srcs] delle regioni: le pagine date per inesistenti si
    cercano di nuovo (un langlink mancante puo' essere stato aggiunto, o il "-" veniva da un vecchio errore di rete)."""
    for rid, _, _ in regions:
        for src in srcs:
            if sources.get((rid, src)) == "-":
                del sources[(rid, src)]
            cache, meta = raw_cache(rid, src)
            if cache.exists() and not cache.read_text(encoding="utf-8"):
                cache.unlink()
                meta.unlink(missing_ok=True)

def resolve_titles(regions, srcs, sources, load_page):
    """Completa [sources] con il titolo di ogni (regione, fonte) di [srcs] che manca, via [load_page]. "-" solo se la
    pagina non esiste (load_page ne ha scritto la cache vuota): dopo un errore di rete la coppia resta senza titolo,
    cosi' non finisce in SOURCES_TSV come assente e la prossima esecuzione la riprova."""
    for rid, _, title in regions:
        for src in srcs:
            if (rid, src) in sources:
                continue
            if res := load_page(rid, src, title):
                sources[(rid, src)] = urllib.parse.unquote(res[1].rsplit("/wiki/", 1)[1]).replace("_", " ")
            elif raw_cache(rid, src)[0].exists():
                sources[(rid, src)] = "-"

def dump_text(pages, redirects, title):
    """Testo della pagina [title] di un dump ({titolo: testo}), anche se [title] e' un redirect ({titolo: destinazione}):
    in sft-sources.tsv ci sono titoli come "The Bahamas" o "Curacao", redirect di "Bahamas" e "Curaçao"."""
    t = wiki_dump.norm_title(title)
    return pages.get(redirects.get(t, t))

def write_excluded(path, excluded):
    """Scrive le fonti escluse, [(regionId, fonte, categoria, motivo)], in un TSV e ne stampa il conteggio per fonte e
    motivo. Motivi: regione-di-test (sottoregione o stessa pagina di una regione di test, voluto), pagina-condivisa
    (pagina gia' usata da un'altra regione, generate_sft.drop_shared_pages), pagina-assente,
    nessuna-sezione (pagina senza sezioni utili), markup-residuo (sezione con tabelle o template rimasti dopo la
    pulizia), fuori-categoria (la sezione non tratta la sua categoria), nessuna-risposta (nessuna frase scelta). Fonte "tradotta":
    sezione tradotta dall'altra lingua; "wp-citta": Storia o Clima di Wikipedia di una citta' di cities.db (generate_sft.py)."""
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("regionId\tsource\tcategory\treason\n")
        for row in excluded:
            f.write("\t".join(row) + "\n")
    by_source = Counter((src, reason) for _, src, _, reason in excluded)
    print("escluse (fonte/motivo: quante):", ", ".join(f"{s}/{r}: {n}" for (s, r), n in sorted(by_source.items())))
    print(f"elenco delle esclusioni in {path}")

QUICKBAR_FIELD = re.compile(r"^\|\s*(Stato|Stato federato|Regione|Territorio)\s*=\s*\[\[([^\]|#]+)", re.M)

def city_parents(text):
    """Titoli dei luoghi che contengono una citta' (Stato, Stato federato, Regione, Territorio) dal suo
    QuickbarCity, o None se la pagina non e' una citta'."""
    if not re.match(r"\s*\{\{\s*QuickbarCity", text, re.I):
        return None
    return {wiki_dump.norm_title(m.group(2)) for m in QUICKBAR_FIELD.finditer(text[:4000])}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="solo le prime N regioni")
    ap.add_argument("--negatives", type=float, default=0.33, help="negativi per positivo (0.33 = ~25%% del totale)")
    ap.add_argument("--off-topic", type=float, default=0.25, help="quota di negativi con domanda fuori tema")
    ap.add_argument("--empty", type=float, default=0.1, help="quota di negativi con il contesto di fallback")
    ap.add_argument("--english", type=float, default=0.2, help="quota di domande in inglese")
    ap.add_argument("--vs", action="store_true",
                    help="include Viaggiare Sicuri (Farnesina): licenza non verificata, SOLO uso locale/personale, mai pubblicare")
    ap.add_argument("--dump-dir", type=Path,
                    help="cartella con i dump di dumps.wikimedia.org (vedi DUMP_WIKIS): testi dai dump invece che dall'API")
    ap.add_argument("--dump-date", help="data dei dump (MM-AAAA, AAAA-MM o AAAA-MM-GG), di default il nome della cartella")
    ap.add_argument("--cities", type=int, default=0,
                    help="con --dump-dir: quante citta' di Wikivoyage IT usare (0 = nessuna)")
    ap.add_argument("--guides-db", type=Path,
                    help="guides.db pubblicato: domande sui fatti rapidi (lingua, prese, fuso, numeri di emergenza)")
    ap.add_argument("--recheck-missing", action="store_true",
                    help="cerca di nuovo le pagine date per inesistenti (\"-\" in sft-sources.tsv, cache vuote in raw/)")
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    (OUT / "raw").mkdir(parents=True, exist_ok=True)

    # niente sottoregioni delle regioni di test (es. canada-*) ne' regioni con la stessa pagina (figi-occidentali e
    # figi-lau sono entrambe "Figi"): il test resterebbe dentro il training
    all_regions = load_regions()
    test_titles = {r[2] for r in all_regions if r[0] in TEST_REGIONS}
    regions = [r for r in all_regions if r[0] in TEST_REGIONS
               or not (any(r[0].startswith(f"{t}-") for t in TEST_REGIONS) or r[2] in test_titles)]
    excluded = [(r[0], "-", "-", "regione-di-test") for r in all_regions if r not in regions]
    regions = regions[: a.limit or None]
    def load_page(rid, lang, title):
        """(testo, url) dalla cache o da Wikivoyage, o None."""
        cache, meta = raw_cache(rid, lang)
        try:
            if cache.exists():  # file vuoto = pagina inesistente (per riprovare basta cancellarlo)
                text = cache.read_text(encoding="utf-8")
                return (text, meta.read_text(encoding="utf-8")) if text else None
            res = {"it": fetch_it, "en": fetch_en, "vs": fetch_vs,
                   **{s: (lambda t, c=cat: fetch_wp_it(t, c)) for cat, s in WP_LANG_SUFFIX.items()},
                   }[lang](title); time.sleep(0.5)
            if not res:
                print(f"-- {rid}: nessuna pagina {lang.upper()}")
                cache.write_text("", encoding="utf-8"); meta.write_text("", encoding="utf-8"); return None
            cache.write_text(res[0], encoding="utf-8"); meta.write_text(res[1], encoding="utf-8")
            return res
        except Exception as e:
            print(f"-- {rid}: errore {lang.upper()} {e}", file=sys.stderr); return None

    # Con --dump-dir: titoli da SOURCES_TSV (i mancanti via API, poi salvati), testi dai dump.
    sources, texts, redirects = load_sources(), {}, {"wp": {}}  # wp: load_titles segue gia' i redirect
    wiki_sources = ("it", "en", *WP_LANG_SUFFIX.values())
    if a.recheck_missing:
        forget_missing(regions, wiki_sources, sources)
    if a.dump_dir:
        date = wiki_dump.normalize_date(a.dump_date or a.dump_dir.name)
        phase("titoli", f"{len(regions)} regioni (da {SOURCES_TSV.name}, i mancanti via API)")
        resolve_titles(regions, wiki_sources, sources, load_page)
        save_sources(sources)
        phase("dump", f"Wikivoyage IT/EN e Wikipedia IT del {date}")
        for lang in ("it", "en"):
            texts[lang], redirects[lang] = {}, {}
            for t, x, redirect in wiki_dump.iter_pages(wiki_dump.dump_files(a.dump_dir, DUMP_WIKIS[lang], date)):
                if redirect:
                    redirects[lang][t] = redirect
                else:
                    texts[lang][t] = x
        wp_titles = {t for (_, src), t in sources.items() if src.startswith("wp_") and t != "-"}
        texts["wp"] = {t: x for t, (_, x) in wiki_dump.load_titles(a.dump_dir, DUMP_WIKIS["wp"], date, wp_titles).items()}

    def load_source(rid, lang, title):
        """(testo, url): dal dump con --dump-dir, altrimenti dalla cache in raw/ o dalla rete."""
        if not a.dump_dir or lang == "vs":
            return load_page(rid, lang, title)
        page = sources.get((rid, lang), "-")
        group = "wp" if lang.startswith("wp_") else lang
        text = dump_text(texts[group], redirects[group], page) if page != "-" else None
        return (text, SOURCE_URL[group] + urllib.parse.quote(page.replace(" ", "_"))) if text else None

    # data: regionId -> (nome, {"it": [(cat, corpo)], "en": [...]}). I positivi usano solo "it":
    # la risposta estrattiva di una pagina EN sarebbe in inglese, contro "rispondi in italiano".
    data, attribution = {}, []
    vs = vs_codes(regions) if a.vs else {}
    phase("fonti", f"{len(regions)} regioni" + ("" if a.dump_dir else " (pagine dalla cache in raw/, le mancanti dalla rete)"))
    progress = Progress("fonti", len(regions), "regione", every=20)
    origin = {}  # (regionId, corpo) -> fonte della sezione, per l'elenco delle esclusioni

    def no_text(rid, src, cat, page):
        excluded.append((rid, src, cat, "nessuna-sezione" if page else "pagina-assente"))

    for n, (rid, name, title) in enumerate(regions, 1):
        progress.update(n - 1, rid)
        by_lang = {}
        for lang, headings in (("it", HEADING_TO_CATEGORY), ("en", EN_HEADING_TO_CATEGORY)):
            page, dropped = load_source(rid, lang, title), []
            if page and (secs := parse_sections(page[0], headings, dropped)):
                by_lang[lang] = secs
                origin.update(((rid, body), lang) for _, body in secs)
                attribution.append((rid, name, page[1], "CC BY-SA 4.0"))
            else:
                no_text(rid, lang, "-", page)
            excluded.extend((rid, lang, cat, "markup-residuo") for cat in dropped)
        page = load_page(rid, "vs", vs[rid]) if rid in vs else None
        if page and (secs := parse_vs(page[0])):  # italiano: alimenta anche i positivi
            by_lang["it"] = by_lang.get("it", []) + secs
            origin.update(((rid, body), "vs") for _, body in secs)
            attribution.append((rid, name, page[1], "licenza non verificata (Farnesina)"))
        elif rid in vs:
            no_text(rid, "vs", "-", page)
        for cat, suffix in WP_LANG_SUFFIX.items():  # italiano: alimenta anche i positivi (categorie deboli)
            page = load_source(rid, suffix, title)
            if page and (secs := parse_wp(page[0], cat)):
                by_lang["it"] = by_lang.get("it", []) + secs
                origin.update(((rid, body), suffix) for _, body in secs)
                attribution.append((rid, name, page[1], "CC BY-SA 4.0 (Wikipedia)"))
            else:
                no_text(rid, suffix, cat, page)
        if by_lang:
            data[rid] = (name, by_lang)

    def question(cat, name, city=False):
        if city:
            pool = CITY_QUESTIONS_EN[cat] if rng.random() < a.english else CITY_QUESTIONS[cat]
        else:
            pool = QUESTIONS_EN[cat] if rng.random() < a.english else QUESTIONS[cat] + QUESTIONS_EXTRA[cat]
        return rng.choice(pool).format(r=name)

    # Fuori tema: le liste scritte a mano piu' le domande dei dataset italiani, ognuna al massimo
    # OFF_TOPIC_MAX_USES volte.
    extra_off_topic = fetch_off_topic()
    off_topic_pool = OFF_TOPIC_TRAIN + OFF_TOPIC_TRAIN_EN + [q for qs in extra_off_topic.values() for q in qs]
    off_topic_uses = Counter()

    def off_topic_question():
        free = [q for q in off_topic_pool if off_topic_uses[q] < OFF_TOPIC_MAX_USES]
        q = rng.choice(free or off_topic_pool)
        off_topic_uses[q] += 1
        return q

    def row(kind, rid, cat, context, q, ans):
        return {"messages": [{"role": "user", "content": on_device_prompt(context, q)},
                             {"role": "assistant", "content": ans}], "kind": kind, "region": rid, "category": cat}

    progress.update(len(regions), f"{len(data)} regioni con testo")
    phase("righe", "positivi, poi negativi")
    # positivi: sezione giusta + 0-2 sezioni distraenti (categorie diverse, che non trattano la domanda)
    rows, pos, seen = [], 0, set()
    for rid, (name, langs) in data.items():
        secs_it = langs.get("it", [])
        for cat, body in secs_it:
            if not covers(cat, body):
                excluded.append((rid, origin[(rid, body)], cat, "fuori-categoria"))
                continue
            pos_before = pos
            others = [b for c, b in secs_it if c != cat and not covers(cat, b)]
            for _ in range(3):
                q = question(cat, name)
                extra = rng.sample(others, min(len(others), rng.choice([0, 0, 1, 1, 2])))
                context = make_context(rng, [body] + extra, q, name)
                answer = pick_answer(context, body, q, cat, name)
                if len(answer) < 40:  # la sezione giusta e' stata troncata: riprova da sola
                    context = make_context(rng, [body], q, name)
                    answer = pick_answer(context, body, q, cat, name)
                if len(answer) < 40 or (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, cat, context, q, answer)); pos += 1
            if pos == pos_before:
                excluded.append((rid, origin[(rid, body)], cat, "nessuna-risposta"))
    # negativi: categoria assente dal contesto (1-3 sezioni che non la trattano), domanda fuori tema,
    # oppure contesto di fallback
    n_neg = int(pos * a.negatives)
    ids, tries, neg = list(data), 0, 0
    while neg < n_neg and tries < n_neg * 20:
        tries += 1
        rid = rng.choice(ids); name, langs = data[rid]
        # contesto IT come nell'app, EN solo per una quota minore; la risposta e' sempre IT
        lang = "en" if "en" in langs and ("it" not in langs or rng.random() < NEG_EN_SHARE) else "it"
        secs = langs[lang]
        tail = rng.choice(REFUSAL_TAILS)
        x = rng.random()
        if x < a.off_topic:
            q = off_topic_question()
            cat, ans = "OFF", f"Il contesto non contiene informazioni utili a rispondere: {tail}"
            context = make_context(rng, rng.sample([b for _, b in secs], min(len(secs), rng.randint(1, 3))), q, name)
        else:
            present = {c for c, _ in secs}
            missing = [c for c in QUESTIONS if c not in present]
            if not missing:
                continue
            cat = rng.choice(missing)
            q, ans = question(cat, name), f"Il contesto non contiene informazioni {TOPIC[cat]}: {tail}"
            if x < a.off_topic + a.empty:
                context = FALLBACK_CONTEXT
            else:
                bodies = [b for _, b in secs if not covers(cat, b)]
                if not bodies:
                    continue
                context = make_context(rng, rng.sample(bodies, min(len(bodies), rng.randint(1, 3))), q, name)
        if (context, q) in seen:
            continue
        seen.add((context, q))
        rows.append(row("neg", rid, cat, context, q, ans)); neg += 1

    # Citta' di Wikivoyage IT (--dump-dir e --cities): fino a CITY_MAX_QUESTIONS sezioni per citta', una
    # domanda ciascuna, e un rifiuto ogni 1/negatives positivi circa. Fuori le citta' delle regioni di test
    # (Stato/Regione/Territorio del QuickbarCity) e le pagine gia' usate come guida di una regione.
    city_pos = city_neg = 0
    if a.dump_dir and a.cities:
        phase("citta'", f"al massimo {a.cities} citta' di Wikivoyage IT")
        page_title = lambda rid: wiki_dump.norm_title(sources.get((rid, "it"), "-"))
        test_titles = {page_title(r) for r in TEST_REGIONS}
        region_titles = {page_title(rid) for rid, _, _ in regions}
        cities = []
        for title, text in sorted(texts["it"].items()):
            parents = city_parents(text)
            if parents is None or title in region_titles or parents & test_titles:
                continue
            secs = [(c, b) for c, b in parse_sections(text, CITY_HEADING_TO_CATEGORY)
                    if covers(c, b) and len(b) >= CITY_MIN_SECTION]
            if secs:
                cities.append((title, secs))
        rng.shuffle(cities)
        # "Come arrivare" c'e' in quasi ogni citta': da ognuna si prendono le sezioni delle categorie finora
        # meno usate, cosi' le categorie restano bilanciate
        city_cats = Counter()
        for title, secs in cities[: a.cities]:
            chosen = sorted(secs, key=lambda s: (city_cats[s[0]], rng.random()))[:CITY_MAX_QUESTIONS]
            city_cats.update(c for c, _ in chosen)
            rid = f"citta:{title}"
            name = re.sub(r"\s*\(.*\)$", "", title)  # "Salem (Oregon)" -> "Salem"
            attribution.append((rid, name, SOURCE_URL["it"] + urllib.parse.quote(title.replace(" ", "_")), "CC BY-SA 4.0"))
            for cat, body in chosen:
                q = question(cat, name, city=True)
                others = [b for c, b in secs if c != cat and not covers(cat, b)]
                context = make_context(rng, [body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
                answer = pick_answer(context, body, q, cat, name)
                if len(answer) < 40 or (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, cat, context, q, answer)); city_pos += 1
            missing = [c for c in CITY_QUESTIONS if c not in {c for c, _ in secs}]
            if missing and rng.random() < a.negatives * 2:  # ~1 rifiuto ogni 2-3 domande della citta'
                cat = rng.choice(missing)
                q = question(cat, name, city=True)
                context = make_context(rng, rng.sample([b for _, b in secs], min(len(secs), rng.randint(1, 3))), q, name)
                if (context, q) not in seen:
                    seen.add((context, q))
                    ans = f"Il contesto non contiene informazioni {TOPIC[cat]}: {rng.choice(REFUSAL_TAILS)}"
                    rows.append(row("neg", rid, cat, context, q, ans)); city_neg += 1
    # Fatti rapidi (--guides-db): due domande per campo con la sezione nel contesto (tra 0 e 2 sezioni del paese), e
    # un rifiuto ogni 1/negatives circa con le sole altre sezioni, se nessuna tratta il campo. Fuori le regioni di test,
    # che restano per misurare queste domande su paesi mai visti.
    quick_pos = quick_neg = 0
    if a.guides_db:
        phase("fatti rapidi", str(a.guides_db))
        for rid, (qf_body, fields) in sorted(load_quick_facts(a.guides_db).items()):
            if rid not in data or rid in TEST_REGIONS:
                continue
            name, langs = data[rid]
            others = [b for _, b in langs.get("it", [])]
            for field, line in fields.items():
                for q in rng.sample(QUICK_FACT_QUESTIONS[field], 2):
                    q = q.format(r=name)
                    context = make_context(rng, [qf_body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
                    if line not in context or (context, q) in seen:
                        continue
                    seen.add((context, q))
                    rows.append(row("pos", rid, "FATTI_RAPIDI", context, q, line)); quick_pos += 1
                unrelated = [b for b in others if not any(k in b.lower() for k in QUICK_FACT_KEYWORDS[field])]
                if unrelated and rng.random() < a.negatives * 2:
                    q = rng.choice(QUICK_FACT_QUESTIONS[field]).format(r=name)
                    context = make_context(rng, rng.sample(unrelated, min(len(unrelated), rng.randint(1, 3))), q, name)
                    if (context, q) not in seen:
                        seen.add((context, q))
                        ans = f"Il contesto non contiene informazioni {QUICK_FACT_TOPIC[field]}: {rng.choice(REFUSAL_TAILS)}"
                        rows.append(row("neg", rid, "FATTI_RAPIDI", context, q, ans)); quick_neg += 1
    # Note personali (con --guides-db, insieme ai fatti rapidi): ogni nota in contesti di qualche regione, dopo 0-2
    # sezioni del paese come nell'app. Nessun rifiuto in piu': i negativi senza nota ci sono gia'.
    note_pos = 0
    if a.guides_db:
        note_regions = [rid for rid in data if rid not in TEST_REGIONS and data[rid][1].get("it")]
        for title, body, questions, answer in NOTE_SAMPLES:
            for rid in rng.sample(note_regions, min(len(note_regions), 8)):
                secs = [b for _, b in data[rid][1]["it"]]
                q = rng.choice(questions)
                note = f"Nota personale: {title}\n{body}"
                sections = make_context(rng, rng.sample(secs, min(len(secs), rng.choice([0, 1, 2]))), q, data[rid][0],
                                        max_chars=MAX_CONTEXT - len(note) - 2)
                context = "\n\n".join(x for x in (sections, note) if x)
                if (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, "NOTE", context, q, answer)); note_pos += 1
    rng.shuffle(rows)

    # nomi distinti per --vs: il default (pubblicabile, senza VS) resta pocket_travel_sft.jsonl/ATTRIBUTION.tsv
    # (stesso nome atteso di default da train_lora.py); --vs (locale) scrive su file .with-vs a parte, cosi'
    # le due varianti convivono sul disco senza sovrascriversi a vicenda
    for name, (dataset, *_, lic, _) in OFF_TOPIC_SOURCES.items():  # solo domande, con rifiuto come risposta
        attribution.append(("-", f"domande fuori tema ({name})", f"https://huggingface.co/datasets/{dataset}/tree/{OFF_TOPIC_REVISIONS[dataset]}", lic))
    if a.dump_dir:  # la data del dump resta accanto al dataset
        for wiki in ("itwikivoyage", "enwikivoyage", "itwiki"):
            attribution.append(("-", f"export {wiki} del {date}", wiki_dump.export_url(wiki, date),
                                "CC BY-SA 4.0 (testo delle pagine elencate sopra)"))
    suffix = ".with-vs" if a.vs else ""
    data_out, attr_out = OUT / f"pocket_travel_sft{suffix}.jsonl", OUT / f"ATTRIBUTION{suffix}.tsv"
    with open(data_out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open(attr_out, "w", encoding="utf-8") as f:
        f.write("regionId\tdisplayName\tsourceUrl\tlicense\n")
        for rid, name, url, lic in attribution:
            f.write(f"{rid}\t{name}\t{url}\t{lic}\n")
    n_it = sum("it" in langs for _, langs in data.values())
    print(f"regioni con guida: {len(data)}/{len(regions)} (IT: {n_it}, EN: {sum('en' in l for _, l in data.values())}, "
          f"VS: {sum(r[3] == 'licenza non verificata (Farnesina)' for r in attribution)}); "
          f"positivi={pos} negativi={neg} citta' positivi={city_pos} negativi={city_neg} totale={len(rows)}")
    print("per categoria:", dict(Counter(r["category"] for r in rows)))
    print(f"domande fuori tema distinte usate: {len(off_topic_uses)} (max {max(off_topic_uses.values(), default=0)} volte l'una)")
    if a.dump_dir:
        print(f"fonti: dump del {date}, titoli in {SOURCES_TSV}")
    if a.guides_db:
        print(f"fatti rapidi: {quick_pos} positivi, {quick_neg} rifiuti; note personali: {note_pos} positivi")
    print(f"scritto {data_out}")
    write_excluded(OUT / f"EXCLUDED{suffix}.tsv", excluded)
    print("SOLO USO LOCALE (--vs: include Viaggiare Sicuri, licenza non verificata, non pubblicare su HuggingFace)" if a.vs
          else "PUBBLICABILE (nessuna riga Viaggiare Sicuri)")

if __name__ == "__main__":
    main()
