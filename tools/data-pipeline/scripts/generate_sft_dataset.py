#!/usr/bin/env python3
"""Genera il dataset SFT (formato onDevicePrompt) dalle guide Wikivoyage IT delle regioni pilota.

Metodo "template + negativi sintetici":
- positivi: domanda per categoria (template), risposta = prime frasi della sezione (estrattivo);
- negativi: domanda su una categoria che il contesto NON copre -> "il contesto non basta".

Uso: python generate_sft_dataset.py [--limit N] [--negatives 0.2] [--seed 42] [--vs]
Output (in tools/data-pipeline/data/sft/, git-ignored): pocket_travel_sft.jsonl + ATTRIBUTION.tsv di default
(pubblicabile, senza VS) oppure pocket_travel_sft.with-vs.jsonl + ATTRIBUTION.with-vs.tsv (con --vs) — nomi
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
Wikivoyage spesso non tratta a fondo (vedi bilanciamento nel dev doc). L'articolo si divide in paragrafi e
si tengono solo i piu' pertinenti (WP_MAX_PARAGRAPHS): lunghi come una sezione Wikivoyage, non l'intro
enciclopedica troncata a 2000 caratteri.
"""
import argparse, html, json, random, re, sys, time, urllib.error, urllib.parse, urllib.request
from collections import Counter
from pathlib import Path

from eval_common import TEST_REGIONS
from status import Progress, phase

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "data" / "sft"
UA = {"User-Agent": "pocket-travel-sft/0.4 (https://github.com/miracle091/pocket-travel)"}

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

# Sezioni delle schede Viaggiare Sicuri (Farnesina, in italiano) -> categoria. Licenza non verificata.
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
# Come TravelAssistant.kt: fino a 3 sezioni unite da riga vuota, contesto troncato a 2000 caratteri, oppure
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
}

# Copia di PromptTemplates.onDevicePrompt (trimIndent)
def on_device_prompt(context, question):
    return ("Sei una guida turistica offline.\n"
            "Rispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.\n"
            "Se il contesto non basta, dillo esplicitamente.\n\n"
            f"CONTESTO: {context}\n\nDOMANDA: {question}")

# --- pulizia wikitext (port ridotto di cleanBody in GenerateGuideContent.kt) ---
RX = [(re.compile(p, re.S | re.I), r) for p, r in [
    (r"\{\|.*?\|\}", ""), (r"<!--.*?-->", ""), (r"<ref\b[^>]*?/>|<ref\b[^>]*?>.*?</ref>", ""),
    (r"\[\[(?:File|Image|Immagine):.*?\]\]", ""), (r"\[https?://\S+\s+([^\]]+)\]", r"\1"),
    (r"\[https?://\S+\]", ""), (r"\[\[(?:[^|\]]*\|)?([^\]]+)\]\]", r"\1"), (r"'{2,3}", ""),
    (r"\{\{[^}]*\}\}", ""), (r"<[^>]+>", "")]]
# Markup wiki sopravvissuto alla pulizia (tabelle/template spezzati): la sezione si scarta intera
LEFTOVER = re.compile(r"\{\||\|\}|\|-|\{\{|\}\}|valign")
HEADING = re.compile(r"^==(?!=)\s*(.+?)\s*(?<!=)==$")
SUBHEADING = re.compile(r"^={3,}.*={3,}$")

def clean(raw):
    for rx, rep in RX:
        raw = rx.sub(rep, raw)
    lines = []
    for l in raw.splitlines():
        l = l.strip()
        if not l or SUBHEADING.match(l):
            continue
        lines.append(re.sub(r"^[*#:]+\s*", "", l))
    return re.sub(r"\s+", " ", re.sub(r"\]\]|\[\[", "", " ".join(lines))).strip()

def parse_sections(text, headings=HEADING_TO_CATEGORY):
    out, cur, body = [], None, []
    def flush():
        cat = headings.get((cur or "").lower())
        c = html.unescape(clean("\n".join(body)))
        if cat and c and not LEFTOVER.search(c):
            out.append((cat, c))
    for line in text.splitlines():
        m = HEADING.match(line.strip())
        if m:
            flush(); cur, body = m.group(1), []
        else:
            body.append(line)
    flush()
    return out

def sentences(text):
    return [s.strip() for s in re.split(r"(?<=[.!?])\s+", text) if len(s.strip()) > 1]

def covers(cat, text):
    """True se il testo tratta davvero la categoria (le sezioni Wikivoyage a volte coprono altro)."""
    t = text.lower()
    return any(k in t for k in KEYWORDS[cat])

def make_context(rng, bodies):
    """Come l'app: sezioni in ordine qualunque unite da riga vuota, troncate a MAX_CONTEXT."""
    parts = list(bodies)
    rng.shuffle(parts)
    return "\n\n".join(parts)[:MAX_CONTEXT]

def pick_answer(context, body, question, cat, name):
    """Fino a 3 frasi del corpo presenti per intero nel contesto: quelle piu' vicine alla domanda
    (parole in comune, escluso il nome della regione) o alla categoria; in ordine di testo. Solo frasi
    pertinenti (punteggio > 0) se ce ne sono, senza link, e al massimo MAX_ANSWER caratteri in tutto."""
    stems = {w[:5] for w in re.findall(r"\w{4,}", question.lower())} - {w[:5] for w in re.findall(r"\w{4,}", name.lower())}
    cand = [(i, x) for i, x in enumerate(sentences(body))
            if x in context and not URL.search(x) and len(x) <= MAX_ANSWER]  # frasi-elenco lunghissime: fuori
    score = lambda x: 2 * sum(st in x.lower() for st in stems) + any(k in x.lower() for k in KEYWORDS[cat])
    ranked = sorted(cand, key=lambda t: (-score(t[1]), t[0]))
    best = [t for t in ranked if score(t[1]) > 0][:3] or ranked[:1]
    while len(best) > 1 and sum(len(x) + 1 for _, x in best) > MAX_ANSWER:
        best.pop()  # toglie la meno pertinente
    return " ".join(x for _, x in sorted(best))

def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")

def fetch_it(title, lang="it"):
    """(testo_raw, url) della pagina in lingua `lang` (IT, DE, FR) via langlink EN, o None."""
    q = f"https://en.wikivoyage.org/w/api.php?action=query&titles={title}&prop=langlinks&lllang={lang}&format=json"
    pages = json.loads(get(q))["query"]["pages"]
    links = next(iter(pages.values())).get("langlinks")
    if not links:
        return None
    it_title = urllib.parse.quote(links[0]["*"].replace(" ", "_"))
    text = get(f"https://{lang}.wikivoyage.org/w/index.php?title={it_title}&action=raw")
    return (text, f"https://{lang}.wikivoyage.org/wiki/{it_title}") if text.strip() else None

def fetch_en(title):
    """(testo_raw, url) della pagina EN, o None."""
    text = get(f"https://en.wikivoyage.org/w/index.php?title={title}&action=raw")
    return (text, f"https://en.wikivoyage.org/wiki/{title}") if text.strip() else None

# Titoli Wikipedia EN per l'articolo tematico di un paese: alimentano i positivi delle categorie che
# Wikivoyage spesso non tratta a fondo (CIBO_BEVANDE, CONNETTIVITA, USI_COSTUMI, VITA_QUOTIDIANA).
# Piu' di un pattern per categoria: se il primo manca, MediaWiki spesso lo rinvia a un titolo piu' ampio
# (es. "Cuisine of Guyana" -> "Culture of Guyana"), quindi il secondo pattern raramente serve davvero.
WP_TITLE_PATTERNS = {
    "CIBO_BEVANDE": ["Cuisine of {n}"],
    "CONNETTIVITA": ["Telecommunications in {n}", "Communications in {n}"],
    "USI_COSTUMI": ["Culture of {n}"],
    "VITA_QUOTIDIANA": ["Mass media in {n}", "Media of {n}"],
}
WP_LANG_SUFFIX = {"CIBO_BEVANDE": "wp_cibo", "CONNETTIVITA": "wp_conn",
                  "USI_COSTUMI": "wp_usi", "VITA_QUOTIDIANA": "wp_vita"}

def fetch_wp_it(en_title, cat):
    """(testo_raw, url) della pagina Wikipedia IT sul tema `cat` del paese, via langlink dal titolo EN
    (stesso meccanismo di fetch_it, ma su Wikipedia: serve la versione IT per un positivo estrattivo,
    l'estratto EN sarebbe in inglese)."""
    n = en_title.replace("_", " ")
    for pattern in WP_TITLE_PATTERNS[cat]:
        title = pattern.format(n=n)
        q = (f"https://en.wikipedia.org/w/api.php?action=query&titles={urllib.parse.quote(title)}"
             "&prop=langlinks&lllang=it&redirects=1&format=json")
        page = next(iter(json.loads(get(q))["query"]["pages"].values()))
        links = page.get("langlinks")
        if "missing" in page or not links:
            continue
        it_title = urllib.parse.quote(links[0]["*"].replace(" ", "_"))
        text = get(f"https://it.wikipedia.org/w/index.php?title={it_title}&action=raw")
        return (text, f"https://it.wikipedia.org/wiki/{it_title}") if text.strip() else None
    return None

WP_MAX_PARAGRAPHS = 2
WP_MIN_PARAGRAPH = 150
WP_SKIP_SECTIONS = {"note", "bibliografia", "voci correlate", "collegamenti esterni", "altri progetti", "galleria d'immagini"}

def parse_wp_it(raw, cat):
    """[(cat, paragrafo)]: i WP_MAX_PARAGRAPHS paragrafi dell'articolo che toccano piu' parole chiave della
    categoria (puliti come le sezioni Wikivoyage), nell'ordine dell'articolo; niente incipit enciclopedico
    (tutto cio' che precede il primo titolo), note e bibliografia."""
    paras, skip = [], True
    for block in re.split(r"\n\s*\n", raw):
        if (m := HEADING.match(block.strip()) or re.match(r"^={2,}\s*(.+?)\s*={2,}", block.strip())):
            skip = m.group(1).lower() in WP_SKIP_SECTIONS
            block = block.split("\n", 1)[1] if "\n" in block else ""
        if skip:
            continue
        body = html.unescape(clean(block))
        if len(body) >= WP_MIN_PARAGRAPH and not LEFTOVER.search(body) and covers(cat, body):
            paras.append(body)
    hits = lambda p: sum(k in p.lower() for k in KEYWORDS[cat])
    best = sorted(sorted(range(len(paras)), key=lambda i: -hits(paras[i]))[:WP_MAX_PARAGRAPHS])
    return [(cat, paras[i]) for i in best]

def fetch_vs(iso3):
    """(json_raw, url) della scheda paese di Viaggiare Sicuri (JSON statico del sito), o None."""
    try:
        return get(f"{VS_BASE}/schede_paese/{iso3}.json"), f"{VS_BASE}/find-country/country/{iso3}"
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise

def parse_vs(raw):
    """[(categoria, corpo)] dalle sezioni della scheda (HTML -> testo), nodi nell'ordine del sito."""
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
    """regionId -> codice ISO3 della scheda VS. Solo regioni con bandiera propria (non condivisa con altre
    regioni, non in un gruppo): altrimenti la scheda del paese non descriverebbe la regione."""
    rows = [r.split("|") for r in re.findall(r'^\s*"([^"]+\|[^"]+)"\s*$', (HERE / "pilot-regions.sh").read_text(encoding="utf-8"), re.M)]
    flags = Counter(f[7] for f in rows if len(f) > 8)
    iso3 = {n["Codice-2"].lower(): n["Codice-3"] for n in json.loads(get(f"{VS_BASE}/schede_paese/lista_nazioni.json"))}
    return {f[0]: iso3[f[7]] for f in rows if len(f) > 8 and flags[f[7]] == 1 and not f[8] and f[7] in iso3}

def load_regions():
    src = (HERE / "pilot-regions.sh").read_text(encoding="utf-8")
    rows = re.findall(r'^\s*"([^"]+\|[^"]+)"\s*$', src, re.M)
    return [(f[0], f[1], f[6]) for f in (r.split("|") for r in rows) if len(f) >= 7]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="solo le prime N regioni")
    ap.add_argument("--negatives", type=float, default=0.33, help="negativi per positivo (0.33 = ~25%% del totale)")
    ap.add_argument("--off-topic", type=float, default=0.25, help="quota di negativi con domanda fuori tema")
    ap.add_argument("--empty", type=float, default=0.1, help="quota di negativi con il contesto di fallback")
    ap.add_argument("--english", type=float, default=0.2, help="quota di domande in inglese")
    ap.add_argument("--vs", action="store_true",
                    help="include Viaggiare Sicuri (Farnesina): licenza non verificata, SOLO uso locale/personale, mai pubblicare")
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    (OUT / "raw").mkdir(parents=True, exist_ok=True)

    # niente sottoregioni delle regioni di test (es. canada-*): il test resterebbe dentro il training
    regions = [r for r in load_regions() if r[0] in TEST_REGIONS or not any(r[0].startswith(f"{t}-") for t in TEST_REGIONS)]
    regions = regions[: a.limit or None]
    def load_page(rid, lang, title):
        """(testo, url) dalla cache o da Wikivoyage, o None."""
        suffix = "" if lang == "it" else f".{lang}"
        cache, meta = OUT / "raw" / f"{rid}{suffix}.txt", OUT / "raw" / f"{rid}{suffix}.url"
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

    # data: regionId -> (nome, {"it": [(cat, corpo)], "en": [...]}). I positivi usano solo "it":
    # la risposta estrattiva di una pagina EN sarebbe in inglese, contro "rispondi in italiano".
    data, attribution = {}, []
    vs = vs_codes(regions) if a.vs else {}
    phase("fonti", f"{len(regions)} regioni (pagine dalla cache in raw/, le mancanti dalla rete)")
    progress = Progress("fonti", len(regions), "regione", every=20)
    for n, (rid, name, title) in enumerate(regions, 1):
        progress.update(n - 1, rid)
        by_lang = {}
        for lang, headings in (("it", HEADING_TO_CATEGORY), ("en", EN_HEADING_TO_CATEGORY)):
            page = load_page(rid, lang, title)
            if page and (secs := parse_sections(page[0], headings)):
                by_lang[lang] = secs
                attribution.append((rid, name, page[1], "CC BY-SA 4.0"))
        page = load_page(rid, "vs", vs[rid]) if rid in vs else None
        if page and (secs := parse_vs(page[0])):  # italiano: alimenta anche i positivi
            by_lang["it"] = by_lang.get("it", []) + secs
            attribution.append((rid, name, page[1], "licenza non verificata (Farnesina)"))
        for cat, suffix in WP_LANG_SUFFIX.items():  # italiano: alimenta anche i positivi (categorie deboli)
            page = load_page(rid, suffix, title)
            if page and (secs := parse_wp_it(page[0], cat)):
                by_lang["it"] = by_lang.get("it", []) + secs
                attribution.append((rid, name, page[1], "CC BY-SA 4.0 (Wikipedia)"))
        if by_lang:
            data[rid] = (name, by_lang)

    def question(cat, name):
        pool = QUESTIONS_EN[cat] if rng.random() < a.english else QUESTIONS[cat] + QUESTIONS_EXTRA[cat]
        return rng.choice(pool).format(r=name)

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
                continue
            others = [b for c, b in secs_it if c != cat and not covers(cat, b)]
            for _ in range(3):
                q = question(cat, name)
                extra = rng.sample(others, min(len(others), rng.choice([0, 0, 1, 1, 2])))
                context = make_context(rng, [body] + extra)
                answer = pick_answer(context, body, q, cat, name)
                if len(answer) < 40:  # la sezione giusta e' stata troncata: riprova da sola
                    context = make_context(rng, [body])
                    answer = pick_answer(context, body, q, cat, name)
                if len(answer) < 40 or (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, cat, context, q, answer)); pos += 1
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
            q = rng.choice(OFF_TOPIC_TRAIN + OFF_TOPIC_TRAIN_EN)
            cat, ans = "OFF", f"Il contesto non contiene informazioni utili a rispondere: {tail}"
            context = make_context(rng, rng.sample([b for _, b in secs], min(len(secs), rng.randint(1, 3))))
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
                context = make_context(rng, rng.sample(bodies, min(len(bodies), rng.randint(1, 3))))
        if (context, q) in seen:
            continue
        seen.add((context, q))
        rows.append(row("neg", rid, cat, context, q, ans)); neg += 1
    rng.shuffle(rows)

    # nomi distinti per --vs: il default (pubblicabile, senza VS) resta pocket_travel_sft.jsonl/ATTRIBUTION.tsv
    # (stesso nome atteso di default da train_lora.py); --vs (locale) scrive su file .with-vs a parte, cosi'
    # le due varianti convivono sul disco senza sovrascriversi a vicenda
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
          f"positivi={pos} negativi={neg} totale={len(rows)}")
    print("per categoria:", dict(Counter(r["category"] for r in rows)))
    print(f"scritto {data_out}")
    print("SOLO USO LOCALE (--vs: include Viaggiare Sicuri, licenza non verificata, non pubblicare su HuggingFace)" if a.vs
          else "PUBBLICABILE (nessuna riga Viaggiare Sicuri)")

if __name__ == "__main__":
    main()
