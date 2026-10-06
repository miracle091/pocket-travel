#!/usr/bin/env python3
"""Test degli esempi --cities-db di generate_sft.py (Storia e Clima delle citta' da Wikipedia) e delle righe pos_wiki e
neg_wiki di generate_eval_set.py: ogni domanda ha una parola di historyClimateWords (TravelAssistant.kt, di cui
HISTORY_CLIMATE_WORDS e' la copia), le domande del test non sono in quelle del training, la regione di un cities.db si ricava
dal nome del file, positivi, rifiuti e distrattori hanno il contesto e la risposta che l'app darebbe.

Uso: python test_sft_wikipedia_cities.py
"""
import random
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path

import generate_eval_set
import generate_sft
import generate_sft_dataset as it
import generate_sft_dataset_en as en
from eval_common import TEST_REGIONS
from generate_sft import (HISTORY_CLIMATE_WORDS, WIKI_CATS, city_db_region, load_city_sections, make_answer_for,
                          wikipedia_distractor, wikipedia_positive, wikipedia_refusal)

KOTLIN = Path(__file__).resolve().parents[3] / "feature" / "ai" / "src" / "main" / "kotlin" / "com" / "pockettravel" / "feature" / "ai" / "TravelAssistant.kt"
WV, WP = "https://it.wikivoyage.org/wiki/Torino", "https://it.wikipedia.org/wiki/Torino"

TORINO = [
    ("COSA_VEDERE", "La Mole Antonelliana ospita il Museo del Cinema e domina lo skyline della citta'. Il Museo Egizio e' tra "
                    "i piu' importanti al mondo. Palazzo Madama e Palazzo Reale sono aperti tutti i giorni tranne il lunedi'. Il parco del "
                    "Valentino si affaccia sul Po ed e' il posto giusto per una passeggiata nel pomeriggio.", WV, False),
    ("ALLOGGIO", "Gli alberghi del centro hanno prezzi medi e le camere si prenotano con anticipo. Vicino alla stazione ci sono "
                 "ostelli e pensioni economiche. Gli hotel di lusso si trovano lungo la via principale e hanno camere con "
                 "colazione inclusa, mentre i campeggi sono fuori citta'.", WV, False),
    ("STORIA", "Torino fu fondata dai Taurini e divenne colonia romana con il nome di Augusta Taurinorum. Nel Medioevo passo' "
               "ai Savoia, che ne fecero la capitale del ducato.\nFu la prima capitale d'Italia nel 1861.", WP, False),
    ("CLIMA", "Il clima e' continentale, con inverni freddi ed estati calde e afose. Le precipitazioni sono piu' frequenti in "
              "primavera e in autunno.", WP, False),
]


def question(cat, name, city=False, wiki=False):
    if wiki:
        return {"STORIA": "Qual e' la storia di {r}?", "CLIMA": "Che clima c'e' a {r}?"}[cat].format(r=name)
    return {"COSA_VEDERE": "Cosa vedere a {r}?", "ALLOGGIO": "Dove dormire a {r}?"}[cat].format(r=name)


def refuse(topic):
    return generate_sft.LANGS["it"]["refusal"](topic, "verifica.")


class QuestionsTest(unittest.TestCase):
    pools = {"it": [it.WIKI_QUESTIONS, it.WIKI_QUESTIONS_EN], "en": [en.WIKI_QUESTIONS, it.WIKI_QUESTIONS]}

    def test_ogni_domanda_ha_una_parola_di_storia_o_clima(self):
        for questions in [*self.pools["it"], *self.pools["en"], *generate_eval_set.PARA_WIKI.values()]:
            for cat, qs in questions.items():
                self.assertEqual(set(questions), set(WIKI_CATS))
                for q in qs:
                    self.assertIn("{r}", q)
                    self.assertTrue(HISTORY_CLIMATE_WORDS.search(q.format(r="Torino")), q)

    def test_le_lingue_delle_domande(self):
        for lang, (own, other) in self.pools.items():
            for cat in WIKI_CATS:
                self.assertTrue(set(own[cat]).isdisjoint(other[cat]), lang)
                self.assertEqual(generate_sft.LANGS[lang]["wiki_questions"][cat], own[cat])

    def test_domande_del_test_fuori_dal_training(self):
        train = {q for pools in self.pools.values() for qs in pools for v in qs.values() for q in v}
        test = {q for by_cat in generate_eval_set.PARA_WIKI.values() for qs in by_cat.values() for q in qs}
        self.assertFalse(train & test)

    def test_parole_chiave_e_argomenti_per_ogni_categoria(self):
        for lang in ("it", "en"):
            L = generate_sft.LANGS[lang]
            for cat in WIKI_CATS:
                self.assertTrue(L["wiki_keywords"][cat])
                self.assertIn(cat, L["topic"])
                self.assertNotIn(cat, L["keywords"])  # KEYWORDS filtra anche le domande fuori tema: non va toccato

    @unittest.skipUnless(KOTLIN.exists(), "TravelAssistant.kt non trovato")
    def test_regex_come_quella_dell_app(self):
        source = KOTLIN.read_text(encoding="utf-8")
        block = source.split("private val historyClimateWords = Regex(", 1)[1].split("RegexOption", 1)[0]
        self.assertEqual("".join(re.findall(r'"""(.*?)"""', block, re.S)), HISTORY_CLIMATE_WORDS.pattern)
        self.assertEqual(HISTORY_CLIMATE_WORDS.flags & re.I, re.I)


class CityDbTest(unittest.TestCase):
    def test_regione_dal_nome_del_file(self):
        self.assertEqual(city_db_region("/tmp/italia--r12--cities.db"), ("italia", Path("/tmp/italia--r12--cities.db")))
        self.assertEqual(city_db_region("emilia-romagna--r3--cities-en.db")[0], "emilia-romagna")

    def test_regione_scritta_a_mano(self):
        self.assertEqual(city_db_region("figi-lau=/tmp/cities.db"), ("figi-lau", Path("/tmp/cities.db")))

    def test_file_senza_regione(self):
        with self.assertRaises(ValueError):
            city_db_region("/tmp/cities.db")

    def test_lettura_delle_sezioni(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = make_db(Path(tmp) / "italia--r1--cities.db", {"Torino": TORINO})
            self.assertEqual(load_city_sections([str(path)]), {"italia": {"Torino": TORINO}})

    def test_cities_db_pubblicato_prima_della_colonna_translated(self):
        # i cities.db di fine settembre hanno solo city, category, title, body, sourceUrl: sezioni non tradotte
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "afghanistan--r1--cities.db"
            db = sqlite3.connect(path)
            db.execute("CREATE TABLE city_sections (city TEXT NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, "
                       "body TEXT NOT NULL, sourceUrl TEXT NOT NULL)")
            db.execute("INSERT INTO city_sections VALUES ('Kabul', 'TRASPORTI', 'Trasporti', 'Bus.', 'u')")
            db.commit()
            db.close()
            self.assertEqual(load_city_sections([str(path)]), {"afghanistan": {"Kabul": [("TRASPORTI", "Bus.", "u", False)]}})


def make_db(path, cities):
    """cities.db di prova, con le colonne di GenerateCities.kt."""
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE city_sections (city TEXT NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, "
               "sourceUrl TEXT NOT NULL, population INTEGER, capital INTEGER NOT NULL DEFAULT 0, latitude REAL, longitude REAL, "
               "translated INTEGER NOT NULL DEFAULT 0)")
    for city, secs in cities.items():
        db.executemany("INSERT INTO city_sections (city, category, title, body, sourceUrl, translated) VALUES (?, ?, ?, ?, ?, ?)",
                       [(city, cat, cat.title(), body, url, int(tr)) for cat, body, url, tr in secs])
    db.commit()
    db.close()
    return path


class ExamplesTest(unittest.TestCase):
    answer_for = staticmethod(make_answer_for(generate_sft.LANGS["it"]))

    def sec(self, cat):
        return next(s for s in TORINO if s[0] == cat)

    def test_positivo_con_la_sezione_nel_contesto(self):
        for cat in WIKI_CATS:
            for seed in range(20):
                cat_, context, q, answer, used = wikipedia_positive(random.Random(seed), "it", "Torino", self.sec(cat), TORINO,
                                                                    question, self.answer_for)
                self.assertEqual(cat_, cat)
                self.assertTrue(HISTORY_CLIMATE_WORDS.search(q))
                for sentence in it.sentences(answer):  # estrattiva: frasi della sezione, presenti nel contesto
                    self.assertIn(sentence, self.sec(cat)[1])
                    self.assertIn(sentence, context)
                self.assertIn(self.sec(cat), used)
                # le altre sezioni nel contesto non trattano la categoria
                keywords = generate_sft.all_keywords(generate_sft.LANGS["it"])
                for s in used[1:]:
                    self.assertFalse(it.covers(cat, s[1], keywords))

    def test_senza_risposta_niente_esempio(self):
        short = ("STORIA", "Fondata nel 1200.", WP, False)
        self.assertIsNone(wikipedia_positive(random.Random(1), "it", "Torino", short, [short], question, self.answer_for))

    def test_rifiuto_per_la_categoria_che_manca(self):
        secs = [s for s in TORINO if s[0] != "STORIA"]
        for seed in range(20):
            cat, context, q, answer, used = wikipedia_refusal(random.Random(seed), "it", "Torino", secs, question, refuse)
            self.assertEqual(cat, "STORIA")
            self.assertEqual(q, "Qual e' la storia di Torino?")
            self.assertTrue(answer.startswith("Il contesto non contiene informazioni sulla storia:"))
            self.assertTrue(1 <= len(used) <= 3)
            keywords = generate_sft.all_keywords(generate_sft.LANGS["it"])
            self.assertFalse(any(it.covers("STORIA", s[1], keywords) for s in used))
            for body in (s[1] for s in TORINO if s[0] == "STORIA"):
                self.assertNotIn(body.split("\n")[0], context)

    def test_niente_rifiuto_se_la_citta_ha_storia_e_clima(self):
        self.assertIsNone(wikipedia_refusal(random.Random(1), "it", "Torino", TORINO, question, refuse))

    def test_rifiuto_nell_altra_lingua_non_usa_sezioni_che_trattano_la_categoria(self):
        secs = [("CLIMA", "Il clima e' mite.", WP, False),
                ("COSA_VEDERE", "The ancient castle stands in the main square of the old town and the church is next to it.", WV, False),
                ("ALLOGGIO", "Gli alberghi del centro hanno prezzi medi.", WV, False)]
        for seed in range(20):
            _, _, _, _, used = wikipedia_refusal(random.Random(seed), "it", "Torino", secs, question, refuse)
            self.assertNotIn(secs[1], used)  # "ancient": storia nell'altra lingua

    def test_distrattore_con_storia_o_clima_dopo_la_sezione_giusta(self):
        seen = set()
        for seed in range(30):
            example = wikipedia_distractor(random.Random(seed), "it", "Torino", TORINO, question, self.answer_for)
            if example is None:
                continue
            cat, context, q, answer, used = example
            seen.add(used[1][0])
            self.assertIn(cat, ("COSA_VEDERE", "ALLOGGIO"))
            self.assertFalse(HISTORY_CLIMATE_WORDS.search(q))
            self.assertIn(used[1][0], WIKI_CATS)
            right = used[0][1]
            for sentence in it.sentences(answer):
                self.assertIn(sentence, right)
            self.assertLess(context.index(right[:30]), context.index(used[1][1].split("\n")[0][:30]))
        self.assertEqual(seen, set(WIKI_CATS))

    def test_distrattore_senza_storia_ne_clima(self):
        self.assertIsNone(wikipedia_distractor(random.Random(1), "it", "Torino", TORINO[:2], question, self.answer_for))

    def test_distrattore_scartato_se_la_domanda_parla_di_storia(self):
        ask = lambda cat, name, city=False, wiki=False: "Cosa vedere a Torino della sua storia?"
        self.assertIsNone(wikipedia_distractor(random.Random(1), "it", "Torino", TORINO, ask, self.answer_for))

    def test_inglese(self):
        secs = [("COSA_VEDERE", "The Royal Palace and the Egyptian Museum are the main sights of the city, open every day but Monday.", WV, False),
                ("CLIMA", "The climate is humid subtropical, with cold winters and hot summers. Rain falls mostly in spring and autumn.", WP, False)]
        ask = lambda cat, name, city=False, wiki=False: "What is the climate like in Turin?" if wiki else "What should I see in Turin?"
        L = generate_sft.LANGS["en"]
        answer_for = make_answer_for(L)
        cat, context, q, answer, used = wikipedia_positive(random.Random(1), "en", "Turin", secs[1], secs, ask, answer_for)
        self.assertEqual(cat, "CLIMA")
        self.assertIn("climate", answer)
        cat, context, q, answer, used = wikipedia_refusal(random.Random(1), "en", "Turin", secs, ask, lambda t: L["refusal"](t, "check."))
        self.assertEqual((cat, answer), ("STORIA", "The context does not contain information about history: check."))


class EvalRowsTest(unittest.TestCase):
    def test_solo_regioni_di_test_con_domande_proprie(self):
        test_region = sorted(TEST_REGIONS)[0]
        with tempfile.TemporaryDirectory() as tmp:
            no_history = [s for s in TORINO if s[0] != "STORIA"]
            specs = [str(make_db(Path(tmp) / f"{test_region}--r1--cities.db", {"Torino": TORINO, "Asti": no_history})),
                     str(make_db(Path(tmp) / "monaco--r1--cities.db", {"Nizza": TORINO}))]
            row = lambda kind, rid, cat, context, q, answer: {"kind": kind, "region": rid, "category": cat, "q": q, "a": answer}
            refuse_on = lambda topic: f"Il contesto non contiene informazioni {topic}: verifica."
            rows = generate_eval_set.wikipedia_rows("it", specs, row, refuse_on)
        self.assertEqual({r["region"] for r in rows}, {"citta:Torino", "citta:Asti"})
        self.assertEqual({r["kind"] for r in rows}, {"pos_wiki", "neg_wiki"})
        templates = [q for qs in generate_eval_set.PARA_WIKI["it"].values() for q in qs]
        for r in rows:
            self.assertIn(r["q"].replace(r["region"].split(":")[1], "{r}"), templates)
            self.assertEqual(r["a"].startswith("Il contesto non"), r["kind"] == "neg_wiki")
        self.assertEqual([r["kind"] for r in rows].count("neg_wiki"), 1)  # solo Asti non ha la Storia

    def test_righe_ripetibili(self):
        test_region = sorted(TEST_REGIONS)[0]
        with tempfile.TemporaryDirectory() as tmp:
            specs = [str(make_db(Path(tmp) / f"{test_region}--r1--cities.db", {"Torino": TORINO}))]
            row = lambda kind, rid, cat, context, q, answer: (kind, rid, cat, context, q, answer)
            refuse_on = lambda topic: topic
            self.assertEqual(generate_eval_set.wikipedia_rows("it", specs, row, refuse_on),
                             generate_eval_set.wikipedia_rows("it", specs, row, refuse_on))


if __name__ == "__main__":
    unittest.main()
