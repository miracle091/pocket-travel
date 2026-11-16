"""Controllo del registro con il lessico Morph-it (CC BY-SA 2.0 / LGPL, morph-it_048): trova in un testo italiano le
forme che si rivolgono al lettore con tu o voi.
- forme solo di seconda persona (tutte le analisi di Morph-it sono verbi alla 2a persona o pronomi e possessivi di 2a):
  sempre;
- forme ambigue (una analisi alla 2a persona e altre no: "evita", "portate", "sei"): a inizio frase o di voce
  (imperativo), oppure dopo se, quando, non, che, dove, come, mentre, perche', ne o un pronome atono;
- imperativi con pronome attaccato a inizio frase o di voce ("Informati", "Assicuratevi"): radice imperativa di 2a
  persona + ti, vi, ci, lo, la, li, le, ne, gli; dentro la frase non conta un participio o aggettivo plurale ("taxi
  ufficiali, dotati di tassametro", "farmaci contraffatti");
- "vi" pronome, salvo prima di essere e di "si" ("vi sono", "vi si trova") e in "che vi" + verbo di luogo ("le persone
  che vi vivono").
Non conta: il testo tra virgolette (citazioni in altre lingue), la forma di cortesia, "Non" + infinito, il rimando
"(vedi ...)", i nomi propri (una parola maiuscola anche dentro la frase, "Cuba"; le parole tutte maiuscole), "Sia ...
sia/che", "Data/Date" + articolo, i plurali di nomi ("spinaci").
Misurato il 2026-10-31 contro tre revisori su traduzioni dei consigli del Governo del Canada e di Wikivoyage: precisione
0,98, copertura 0,96. Il 2026-11-14, su tutti i consigli canadesi tradotti, la regola dei participi e del "vi" di luogo
ha tolto 24 segnalazioni, tutte false. Il 2026-11-16, sulle 44.316 traduzioni di Canada e Wikivoyage, le regole su
nomi propri, "sia", "Data", plurali e "che vi" hanno tolto 157 segnalazioni (righe segnalate da 1116 a 988); rilette
le 27 del "vi", le 10 dei nomi e plurali e 40 delle altre a caso: tutte false.

Il lessico non e' nel repository: si scarica da https://docs.sslmit.unibo.it/doku.php?id=resources:morph-it
(morph-it.tgz, file current_version/m.utf8, una riga "forma<TAB>lemma<TAB>analisi").
Uso come modulo: load(percorso di m.utf8), poi hits(testo) -> lista di (parola, motivo).
Prova: python register_check.py <m.utf8> "testo" ..."""
import re
import sys
from functools import lru_cache

CLITICS = ("ti", "vi", "ci", "lo", "la", "li", "le", "ne", "gli", "telo", "tela", "velo", "vela", "tene", "vene")
TRIGGERS = {"se", "quando", "non", "che", "dove", "come", "mentre", "perché", "perche", "ne", "ti", "vi", "ci",
            "tu", "voi"}
PRON2 = {"tu", "te", "ti", "tuo", "tua", "tuoi", "tue", "voi", "vostro", "vostra", "vostri", "vostre", "teco"}
ESSERE = {"è", "e'", "sono", "era", "erano", "sarà", "saranno", "sia", "siano", "fu", "furono", "si", "fosse", "fossero",
          "sarebbe", "sarebbero", "stato", "stata", "stati", "state"}
IMPERSONAL = {"bisogna", "occorre", "basta", "conviene", "serve", "servono"}
DET = {"un", "una", "uno", "il", "lo", "la", "i", "gli", "le", "l", "sempre",
       "subito", "bene", "questo", "questa", "questi", "queste", "tutti", "tutte", "ogni"}
# verbi dopo cui "-vi" indica il luogo ("e' sicuro accedervi"), non voi
PLACE_VI = {"accedere", "andare", "entrare", "arrivare", "tornare", "restare", "rimanere", "giungere", "soggiornare",
            "salire", "scendere", "circolare", "vivere", "lavorare"}
# altri verbi dopo cui "che vi" + 3a persona indica il luogo ("le strade che vi conducono")
PLACE_VI_THIRD = {"condurre", "risiedere", "crescere", "transitare", "operare", "abitare"}
SUBORD = {"se","quando", "dove", "come", "mentre", "perché", "perche"}  # dopo queste il congiuntivo di 3a e' raro
QUOTED = re.compile(r"\"[^\"]*\"|“[^”]*”|«[^»]*»")
CROSS_REF = re.compile(r"\(vedi\b", re.IGNORECASE)  # "(vedi Livelli di rischio)": forma scelta per i rimandi
CLAUSE = re.compile(r"(?<=[.!?;:])\s+|\n|^\s*[•▸\-–]\s*|\(|\)")
WORD = re.compile(r"[A-Za-zÀ-ÖØ-öø-ÿ']+")


LEX = None
LEMMAS = None


def load(path):
    """Carica il lessico Morph-it da [path] (m.utf8); va chiamata prima di hits()."""
    global LEX, LEMMAS
    lex, lemmas = {}, {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3:
                lex.setdefault(parts[0], []).append(parts[2])
                lemmas.setdefault(parts[0], set()).add(parts[1])
    LEX, LEMMAS = lex, lemmas
    kind.cache_clear()
    clitic_second.cache_clear()


def _second(tag):
    return bool(re.match(r"(VER|AUX|MOD):(ind|sub|cond|impr)\+[a-z]+\+2\+", tag)) or "PRO-PERS-CLI-2" in tag \
        or "PRO-PERS-2" in tag or "-2-" in tag and tag.startswith(("PRO-POSS", "DET-POSS"))


@lru_cache(maxsize=None)
def kind(word):
    """(tipo, altre letture) per una parola minuscola: tipo 'solo2', 'ambigua' o None; altre letture: insieme fra
    'nome' (nome o aggettivo), 'terza' (verbo alla 3a persona), 'participio'."""
    tags = LEX.get(word, [])
    two = [t for t in tags if _second(t)]
    if not two:
        return None, frozenset()
    other = set()
    for t in tags:
        if t.startswith("NOUN"):
            other.add("nome")
        elif t.startswith("ADJ"):
            other.add("aggettivo")
        elif re.match(r"(VER|AUX|MOD):(ind|cond)\+[a-z]+\+3\+", t):
            other.add("terza")
        elif re.match(r"(VER|AUX|MOD):sub\+[a-z]+\+3\+", t):
            other.add("congiuntivo terza")
        elif re.match(r"(VER|AUX|MOD):part", t):
            other.add("participio")
    return ("solo2" if len(two) == len(tags) else "ambigua"), frozenset(other)


@lru_cache(maxsize=None)
def clitic_second(word):
    """'imperativo' per radice imperativa di 2a persona + pronome ("informati", "assicuratevi"); 'infinito' per
    infinito + ti o vi ("impedirti"). None altrimenti."""
    for c in sorted(CLITICS, key=len, reverse=True):
        if word.endswith(c) and len(word) > len(c) + 2:
            stem = word[: -len(c)]
            for st in (stem, stem + "e") + ((stem + "re",) if stem.endswith(("or", "ur", "ar")) else ()):  # sottoporvi
                tags = LEX.get(st, [])
                if any(re.match(r"(VER|AUX|MOD):impr\+pres\+2\+", t) for t in tags):
                    return "imperativo"
                if c in ("ti", "vi") and any(re.match(r"(VER|AUX|MOD):inf", t) for t in tags):
                    return "infinito"
    return None


def participle(word):
    """Participio passato o aggettivo per Morph-it ("dotati"); per una parola che il lessico non ha, anche la sua parte
    finale ("contraffatti" da "fatti")."""
    def part(w):
        return any(re.match(r"(VER|AUX|MOD):part|ADJ", t) for t in LEX.get(w, []))
    return part(word) or word not in LEX and any(part(word[i:]) for i in range(2, len(word) - 3))


def place_verb(word):
    """Verbo di luogo alla 3a persona e mai alla 2a per Morph-it ("vivono", "conducono"): in "che vi vivono" il "vi"
    e' di luogo ("che vi interessano", "vi chiede" restano voi)."""
    tags = LEX.get(word, [])
    return bool(LEMMAS.get(word, set()) & (PLACE_VI | PLACE_VI_THIRD)) and not any(map(_second, tags)) \
        and any(re.match(r"(VER|AUX|MOD):(ind|sub|cond)\+[a-z]+\+3\+", t) for t in tags)


def plural_noun(word):
    """Plurale di un nome di Morph-it ("piatti", "spinaci"), anche se il lessico ha solo il singolare."""
    return any(t.startswith("NOUN") for w in (word, word[:-1] + "o", word[:-1] + "e", word[:-1] + "a", word[:-1] + "io")
               for t in LEX.get(w, []))


def hits(text):
    if LEX is None:
        raise RuntimeError("lessico Morph-it non caricato: chiamare load() prima di hits()")
    text = CROSS_REF.sub("(", QUOTED.sub(" ", text))
    # nomi propri: una parola maiuscola dentro una frase lo e' anche a inizio frase ("Cuba ... a Cuba")
    proper = {m.group(0) for clause in CLAUSE.split(text) if clause for m in list(WORD.finditer(clause))[1:]
              if m.group(0)[0].isupper()}
    out = []
    for clause in CLAUSE.split(text):
        if not clause or not clause.strip():
            continue
        tokens = list(WORD.finditer(clause))
        words = [m.group(0) for m in tokens]
        for i, raw in enumerate(words):
            w = raw.lower().strip("'")
            prev = words[i - 1].lower() if i else ""
            nxt = words[i + 1].lower() if i + 1 < len(words) else ""
            start = i == 0 and len(words) > 2  # le voci di una o due parole sono titoli ("▸ Guida")
            soft = start or i > 0 and clause[tokens[i - 1].end():tokens[i].start()].strip() == ","
            if i > 0 and raw[0].isupper() or raw in proper or len(raw) > 1 and raw.isupper():
                continue  # nomi propri ("Haiti", "HAITI")
            if w == "sia" and re.search(r"\b(sia|che)\b", clause[tokens[i].end():], re.IGNORECASE) \
                    or w in ("data", "date", "dato", "dati") and nxt in DET:
                continue  # "Sia i treni sia gli autobus", "Data la distanza"
            if w in PRON2:
                if w == "te" and nxt in ("verde", "nero"):
                    continue
                out.append((raw, "pronome"))
            elif w == "vi":
                if nxt not in ESSERE and not (prev == "che" and place_verb(nxt)):
                    out.append((raw, "vi"))
            elif w == "sei":
                if prev in TRIGGERS:
                    out.append((raw, "sei"))
            elif w in IMPERSONAL:
                continue
            else:
                k, other = kind(w)
                after = prev in TRIGGERS and not other & ({"terza", "participio"} | (set() if prev in SUBORD else {"congiuntivo terza"})
                                                         | ({"nome"} if prev == "come" else set()))
                if k == "solo2":
                    out.append((raw, "verbo 2a"))
                elif k == "ambigua" and start and ("nome" not in other or nxt.strip("'") in DET):
                    out.append((raw, "verbo ambiguo a inizio frase"))
                elif k == "ambigua" and after:
                    out.append((raw, "verbo ambiguo dopo " + prev))
                elif k is None or "participio" in other:
                    c = clitic_second(w)
                    noun = plural_noun(w)
                    place = w.endswith("vi") and w[:-2] + "e" in PLACE_VI
                    # fuori dall'inizio della frase un participio plurale ("taxi ufficiali, dotati di") non e' un imperativo;
                    # un nome plurale ("piatti", "spinaci") mai, se non e' anche un participio ("Assicurati di")
                    if c == "infinito" and not noun and not place or c == "imperativo" and not (
                            noun and not participle(w)) and (start or not participle(w) and (soft or w not in LEX)):
                        out.append((raw, c + " con pronome"))
    return out


if __name__ == "__main__":
    load(sys.argv[1])
    for t in sys.argv[2:]:
        print(t, "->", hits(t))
