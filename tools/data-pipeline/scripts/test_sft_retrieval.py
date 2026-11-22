#!/usr/bin/env python3
"""Test di sft_retrieval.py: domande diverse da quelle delle misure, regioni del training, risposte e righe su guide
finte, senza rete.

Uso: python test_sft_retrieval.py
"""
import contextlib
import io
import json
import random
import re
import shutil
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import eval_retrieval as er
import generate_eval_set as ges
import generate_eval_set_en as ges_en
import generate_sft as gs
import sft_retrieval as sr


def normalized(template):
    """Una domanda senza segnaposto, maiuscole, accenti e punteggiatura."""
    return er.normalized_words(re.sub(r"\{\w+\}", "x", template)).strip()


def measured_questions():
    """Le domande delle serie di eval_retrieval.py e dei test di generate_eval_set*.py, normalizzate."""
    out = set()

    def add(value):
        if isinstance(value, str):
            out.add(normalized(value))
        elif isinstance(value, dict):
            for v in value.values():
                add(v)
        elif isinstance(value, (list, tuple)):
            for v in value:
                add(v)
    for lang in er.LANGS:
        for series in ("direct", "reworded", "dev", "dev2", "control", "other-lang"):
            add(er.questions_of(lang, series))
        add([q for q, _ in er.GENERAL_Q[lang]])
    add([er.OTHER_COUNTRY_Q, ges.PARA, ges.PARA_EN, ges.PARA_CITY, ges.PARA_WIKI, ges_en.PARA, ges_en.PARA_CITY_EN])
    return out


class QuestionsTest(unittest.TestCase):
    def test_nessuna_domanda_uguale_a_quelle_delle_misure(self):
        measured = measured_questions()
        self.assertIn(normalized("Cosa fare in una giornata a {c}?"), measured)
        ours = {normalized(q) for qs in sr.QUESTIONS.values() for t in qs.values() for q in t}
        self.assertEqual(ours & measured, set())

    def test_da_6_a_10_domande_per_categoria_e_lingua(self):
        for lang, qs in sr.QUESTIONS.items():
            self.assertEqual(set(qs), set(sr.CATEGORIES))
            for cat, templates in qs.items():
                self.assertTrue(6 <= len(set(templates)) <= 10, (lang, cat))
                self.assertTrue(all("{c}" in t for t in templates), (lang, cat))

    def test_schemi_nell_altra_lingua_allo_stesso_posto(self):
        # le parole di ricerca vengono dallo schema allo stesso posto nella lingua della guida (task_question)
        for cat in sr.CATEGORIES:
            self.assertEqual(len(sr.QUESTIONS["it"][cat]), len(sr.QUESTIONS["en"][cat]), cat)
        for lang, stop in sr.SEARCH_STOPWORDS.items():  # parole delle domande, scritte giuste
            words = {w for ts in sr.QUESTIONS[lang].values() for t in ts for w in sr.SEARCH_WORD.findall(t.lower())}
            self.assertLessEqual(stop, words, lang)

    def test_parole_richieste_solo_per_domande_del_modulo(self):
        templates = {t for qs in sr.QUESTIONS.values() for ts in qs.values() for t in ts}
        self.assertLessEqual(set(sr.ANSWER_NEEDS), templates)

    def test_risposta_con_le_parole_della_domanda(self):
        self.assertTrue(sr.answer_has_needs("{c} ha origini romane?", "Fu una colonia romana."))
        self.assertFalse(sr.answer_has_needs("{c} ha origini romane?", "Il porto crebbe nel Seicento."))
        self.assertFalse(sr.answer_has_needs("{c} ha origini romane?", "Ottone I, imperatore del Sacro Romano Impero, la difese."))
        self.assertFalse(sr.answer_has_needs("Does {c} have Roman origins?", "It joined the Holy Roman Empire. The church is Romanesque."))
        founded = "In what year was {c} founded?"
        self.assertTrue(sr.answer_has_needs(founded, "The town was founded in 1250 by the Moors."))
        self.assertFalse(sr.answer_has_needs(founded, "The town was founded by the Moors."))
        self.assertFalse(sr.answer_has_needs(founded, "It has 12.000 inhabitants and was founded later."))
        self.assertFalse(sr.answer_has_needs("Da quanti secoli esiste {c}?", "Nel 1888 fu fondato il birrificio."))
        self.assertTrue(sr.answer_has_needs("Da quanti secoli esiste {c}?", "I primi insediamenti risalgono all'epoca vichinga."))
        self.assertTrue(sr.answer_has_needs("Da chi è stata fondata {c}?", "Fu fondata dai Fenici."))
        self.assertTrue(sr.answer_has_needs("Dove cenare a {c}?", "Qualunque frase."))


class RegionsAndLicensesTest(unittest.TestCase):
    def test_regioni_fuori_dal_test_e_dalla_misura(self):
        cities = {"cities": {"fileXz": {"url": "u"}}}
        manifest = {"regions": [{"regionId": r, **cities} for r in
                                ("spagna", "germania", "canada-ontario", "figi-occidentali", "italia", "portogallo")]
                    + [{"regionId": "austria"}]}
        self.assertEqual(sr.training_regions(manifest, "it"), ["spagna"])
        self.assertEqual(sr.training_regions(manifest, "en"), [])

    def test_licenze_come_generate_sft(self):
        self.assertEqual(sr.section_license("https://it.wikivoyage.org/wiki/Siviglia", False, "it"), "CC BY-SA 4.0")
        self.assertEqual(sr.section_license("https://en.wikipedia.org/wiki/Seville", False, "en"), gs.WIKI_LICENSE)
        self.assertEqual(sr.section_license("https://travel.gc.ca/destinations/spain", False, "en"), gs.GUIDE_LICENSES["travel.gc.ca"])
        opus = "Helsinki-NLP/opus-mt-tc-big-en-it"
        self.assertEqual(sr.section_license("https://en.wikivoyage.org/wiki/Spain", opus, "it"),
                         f"CC BY-SA 4.0 (tradotta automaticamente dall'inglese, {opus}, {gs.translation_license(opus)})")
        self.assertIn("index-translate-9b-q4_k_m, Apache 2.0",
                      sr.section_license("https://en.wikivoyage.org/wiki/Spain", "index-translate-9b-q4_k_m", "it"))
        # senza modello o con un modello senza licenza verificata la sezione non entra
        self.assertIsNone(sr.section_license("https://en.wikivoyage.org/wiki/Spain", "", "it"))
        self.assertIsNone(sr.section_license("https://en.wikivoyage.org/wiki/Spain", "translategemma-4b", "it"))
        self.assertIsNone(sr.section_license("https://example.com/x", False, "it"))


SPAIN_GUIDE = [("spagna", "TRASPORTI", "Come spostarsi", "I treni collegano le principali città del paese.", "https://it.wikivoyage.org/wiki/Spagna")]
SEVILLE = [
    ("Siviglia", "ALLOGGIO", "Dove alloggiare",
     "L'Hotel Sole ha camere doppie a 40 euro vicino alla cattedrale.\nL'ostello del centro costa poco ed è vicino alla stazione.",
     "https://it.wikivoyage.org/wiki/Siviglia"),
    ("Siviglia", "STORIA", "Storia",
     "Siviglia fu fondata dai Romani con il nome di Hispalis nel II secolo a.C. e divenne un porto importante.",
     "https://it.wikipedia.org/wiki/Siviglia"),
    ("Siviglia", "COSA_VEDERE", "Cosa vedere", "La cattedrale e il palazzo dell'Alcázar sono i monumenti principali della città.",
     "https://it.wikivoyage.org/wiki/Siviglia"),
]


class RowsTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.cache = Path(tmp.name)
        with contextlib.closing(sqlite3.connect(self.cache / "guides.db")) as db:
            db.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
            db.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", SPAIN_GUIDE)
            db.commit()
        with contextlib.closing(sqlite3.connect(self.cache / "spagna.cities.db")) as db:
            db.execute("CREATE TABLE city_sections (city TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT, translated INTEGER)")
            db.executemany("INSERT INTO city_sections VALUES (?,?,?,?,?,0)", SEVILLE)
            db.commit()
        manifest = {"guides": {"fileXz": {"url": "g"}},
                    "regions": [{"regionId": "spagna", "displayName": "Spagna", "cities": {"fileXz": {"url": "c"}}}]}
        for patch in (mock.patch.object(er, "fetch", return_value=json.dumps(manifest).encode()),
                      mock.patch.object(er, "download", side_effect=lambda url, out: out),
                      mock.patch.dict(sr.QUESTIONS["it"], {"ALLOGGIO": ["Dove passo la notte a {c}?"],
                                                           "STORIA": ["{c} ha origini romane?"],
                                                           "COSA_VEDERE": ["Cosa fare a {c} in poche ore?"]})):
            patch.start()
            self.addCleanup(patch.stop)

    def rows(self, **kw):
        with contextlib.redirect_stdout(io.StringIO()):
            return sr.retrieval_rows("it", 9, random.Random(1), self.cache, regions=["spagna"], **kw)

    def test_righe_con_il_contesto_della_ricerca_e_risposta_dalla_sezione(self):
        rows, attribution = self.rows()
        by_cat = {r["category"]: r for r in rows}
        self.assertEqual(set(by_cat), {"ALLOGGIO", "STORIA", "COSA_VEDERE"})
        for cat, r in by_cat.items():
            user, assistant = r["messages"]
            self.assertEqual((user["role"], assistant["role"]), ("user", "assistant"))
            self.assertEqual((r["kind"], r["region"], r["translated"]), ("pos", "citta:Siviglia", False))
            body = next(b for _, c, _, b, _ in SEVILLE if c == cat)
            q = user["content"].split("DOMANDA: ")[1]
            with contextlib.closing(er.build_db(self.cache / "guides.db", "spagna", SEVILLE)) as db:
                top, stems = er.search(db, "spagna", q, ["Siviglia"], "it", True)
            self.assertEqual(user["content"], gs.LANGS["it"]["prompt"](er.select_context([s["body"] for s in top], stems), q))
            self.assertGreaterEqual(len(assistant["content"]), sr.MIN_ANSWER)
            self.assertTrue(all(line in body for line in assistant["content"].split("\n")))
        self.assertIn("Romani", by_cat["STORIA"]["messages"][1]["content"])
        self.assertIn(("citta:Siviglia", "Siviglia", "https://it.wikipedia.org/wiki/Siviglia", gs.WIKI_LICENSE), attribution)
        self.assertIn(("citta:Siviglia", "Siviglia", "https://it.wikivoyage.org/wiki/Siviglia", "CC BY-SA 4.0"), attribution)
        self.assertEqual(len(attribution), len(set(attribution)))

    def test_storia_senza_le_parole_della_domanda_resta_fuori(self):
        with mock.patch.dict(sr.QUESTIONS["it"], {"STORIA": ["{c} è una città medievale?"]}):
            rows, _ = self.rows()
        self.assertNotIn("STORIA", {r["category"] for r in rows})

    def test_rifiuto_solo_se_il_contesto_non_ha_la_categoria(self):
        other = {"city": "Siviglia", "category": "COSA_VEDERE", "body": SEVILLE[2][3]}
        hotel = {"city": None, "region": "spagna", "category": "ALLOGGIO", "title": "Dormire", "body": "Hotel ovunque nel paese."}
        refusal = lambda topic: f"Il contesto non contiene informazioni {topic}"
        with mock.patch.object(er, "search", return_value=([other], set())):
            rows, _ = self.rows(refusal=refusal, negatives=5)
        neg = [r for r in rows if r["kind"] == "neg"]
        self.assertEqual({r["category"] for r in neg}, {"ALLOGGIO", "STORIA"})
        self.assertEqual(next(r for r in neg if r["category"] == "ALLOGGIO")["messages"][1]["content"],
                         "Il contesto non contiene informazioni su dove dormire")
        with mock.patch.object(er, "search", return_value=([hotel], set())):
            rows, _ = self.rows(refusal=refusal, negatives=5)
        self.assertEqual([r for r in rows if r["category"] == "ALLOGGIO"], [])


GENERAL = [("_general", "ALLOGGIO", "Dormire", "Passare la notte in ostello costa poco in quasi tutte le città.",
            "https://it.wikivoyage.org/wiki/Dormire"),
           ("_general", "TRASPORTI", "Spostarsi in treno",
            "Spostarsi in treno è comodo. Per spostarsi i biglietti si comprano in stazione o online prima di partire.",
            "https://it.wikivoyage.org/wiki/Spostarsi_in_treno")]
SPAIN_TRANSPORT = "Il treno collega le principali città del paese, l'autobus arriva anche nei villaggi."
CORDOBA = [("Cordova", "ALLOGGIO", "Dove alloggiare", "La pensione del quartiere ebraico ha camere semplici a 30 euro.",
            "https://it.wikivoyage.org/wiki/Cordova")]


def context_of(row):
    """Il contesto del prompt della chat di una riga (italiano)."""
    return row["messages"][0]["content"].split("CONTESTO: ", 1)[1].rsplit("\n\nDOMANDA: ", 1)[0]


class V12Test(unittest.TestCase):
    """Righe del v12: ordine dei contesti dell'app con domande sul paese, pagina generale in testa, riordino, parole di
    ricerca e istruzioni nascoste."""

    def setUp(self):
        RowsTest.setUp(self)
        with contextlib.closing(sqlite3.connect(self.cache / "guides.db")) as db:
            db.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", GENERAL)
            db.execute("UPDATE guide_sections SET title = 'Trasporti', body = ? WHERE regionId = 'spagna'", (SPAIN_TRANSPORT,))
            db.commit()
        with contextlib.closing(sqlite3.connect(self.cache / "spagna.cities.db")) as db:
            db.executemany("INSERT INTO city_sections VALUES (?,?,?,?,?,0)", CORDOBA)
            db.commit()
        for patch in (mock.patch.dict(gs.LANGS["it"]["questions"], {"TRASPORTI": ["Come mi sposto in {r}?"]}),
                      mock.patch.dict(sr.QUESTIONS["en"], {"ALLOGGIO": ["Where can I sleep cheaply in {c}?"],
                                                           "STORIA": ["Does {c} have Roman origins?"],
                                                           "COSA_VEDERE": ["What to do in {c} with only a few hours?"]})):
            patch.start()
            self.addCleanup(patch.stop)

    def search(self, q, city_rows=SEVILLE + CORDOBA, **kw):
        with contextlib.closing(er.build_db(self.cache / "guides.db", "spagna", city_rows)) as db:
            return er.search(db, "spagna", q, ["Cordova", "Siviglia"], "it", True, **kw)

    def rows(self, general_first):
        with contextlib.redirect_stdout(io.StringIO()):
            return sr.retrieval_rows("it", 9, random.Random(1), self.cache, regions=["spagna"], general_first=general_first,
                                     country_names={"spagna": "Spagna"})[0]

    def test_ordine_dell_app_con_le_domande_sul_paese(self):
        rows = self.rows(0.0)
        country = [r for r in rows if r["region"] == "spagna"]
        self.assertEqual([r["category"] for r in country], ["TRASPORTI"])
        q = "Come mi sposto in Spagna?"
        top, stems = self.search(q)
        self.assertTrue(top[0]["general_guide"])  # la ricerca mette la guida generale in testa
        self.assertEqual(context_of(country[0]), er.on_device_context(top, stems))
        self.assertTrue(context_of(country[0]).startswith("Il treno collega"))
        self.assertIn("Spostarsi in treno è comodo", context_of(country[0]))
        self.assertIn("Il treno collega", country[0]["messages"][1]["content"])
        # Le domande sulle citta' non portano la guida generale: contesto come prima
        city = next(r for r in rows if r["category"] == "ALLOGGIO" and r["region"] == "citta:Siviglia")
        top, stems = self.search("Dove passo la notte a Siviglia?")
        self.assertEqual(context_of(city), er.select_context([s["body"] for s in top], stems))

    def test_domande_sul_paese_col_nome_della_lingua_del_dataset(self):
        # i nomi dei paesi vengono da generate_sft.py (country_names), non dal displayName italiano del manifest: in
        # inglese "Spain", e per la regione principale di una nazione con le isole il nome della nazione, non la parte
        # dopo " - "
        shutil.copy(self.cache / "guides.db", self.cache / "guides-en.db")
        shutil.copy(self.cache / "spagna.cities.db", self.cache / "spagna.cities-en.db")
        manifest = {"guides": {"fileXz": {"url": "g"}}, "guidesEn": {"fileXz": {"url": "g"}},
                    "regions": [{"regionId": "spagna", "displayName": "Spagna - Penisola", "cities": {"fileXz": {"url": "c"}},
                                 "citiesEn": {"fileXz": {"url": "c"}}}]}
        for lang, name in (("en", "Spain"), ("it", "Spagna")):
            with mock.patch.object(er, "fetch", return_value=json.dumps(manifest).encode()), \
                    mock.patch.object(sr, "city_examples", return_value=[]) as examples, \
                    contextlib.redirect_stdout(io.StringIO()):
                sr.retrieval_rows(lang, 9, random.Random(1), self.cache, regions=["spagna"], general_first=0.0,
                                  country_names={"spagna": name})
            self.assertEqual([c.kwargs["country"] for c in examples.call_args_list if c.kwargs.get("country")], [name], lang)

    def test_pagina_generale_in_testa_e_risposta_dalla_sezione(self):
        by_cat = {r["category"]: r for r in self.rows(1.0) if r["region"] in ("spagna", "citta:Siviglia")}
        for cat, start in (("TRASPORTI", "Spostarsi in treno"), ("ALLOGGIO", "Passare la notte in ostello")):
            self.assertTrue(context_of(by_cat[cat]).startswith(start), cat)
        self.assertIn("L'ostello del centro", context_of(by_cat["ALLOGGIO"]))
        self.assertTrue(by_cat["ALLOGGIO"]["messages"][1]["content"] in SEVILLE[0][3])
        # Senza pagine della guida generale con le parole della domanda, l'ordine dell'app
        self.assertFalse(context_of(by_cat["STORIA"]).startswith("Passare"))

    def task_rows(self, kind, n_rerank, n_words):
        with mock.patch.object(sr, "QUESTION_KINDS", (kind,)), contextlib.redirect_stdout(io.StringIO()):
            return sr.task_rows("it", n_rerank, n_words, random.Random(1), self.cache, regions=["spagna"])

    def test_righe_del_riordino(self):
        rows, attribution = self.task_rows("riformulata", 100, 0)
        self.assertTrue(rows)
        import audit_sft
        for r in rows:
            self.assertEqual((r["task"], r["category"]), ("riordino", "RIORDINO"))
            self.assertEqual(r["messages"][1]["content"], "sì" if r["kind"] == "pos" else "no")
            self.assertEqual(audit_sft.hard_errors(r, "it"), [])
        by_q = {}
        for r in rows:
            q = audit_sft.parse_row(r)[1]
            place = r["messages"][0]["content"].split("Sezione della guida (", 1)[1].split("):", 1)[0]
            by_q.setdefault(q, {})[place] = r["messages"][1]["content"]
        self.assertEqual(by_q["Dove passo la notte a Siviglia?"],
                         {"Siviglia, Dove alloggiare": "sì", "Cordova, Dove alloggiare": "no", "guida generale, Dormire": "no"})
        self.assertEqual(by_q["Siviglia ha origini romane?"]["Siviglia, Storia"], "sì")
        self.assertIn(("citta:Cordova", "Cordova", "https://it.wikivoyage.org/wiki/Cordova", "CC BY-SA 4.0"), attribution)

    def test_parole_di_ricerca_nella_lingua_della_guida(self):
        rows, _ = self.task_rows("altra-lingua", 0, 10)
        row = next(r for r in rows if "sleep" in r["messages"][0]["content"])
        self.assertEqual(row["messages"][0]["content"], er.SEARCH_WORDS_PROMPT["it"].format(q="Where can I sleep cheaply in Siviglia?"))
        self.assertEqual((row["task"], row["category"], row["kind"]), ("parole-di-ricerca", "PAROLE_DI_RICERCA", "pos"))
        self.assertEqual(set(row["messages"][1]["content"].split(", ")), {"hotel", "camere", "ostello"})
        self.assertTrue(all(r["task"] == "parole-di-ricerca" for r in rows))

    def test_parole_dalla_domanda_equivalente_e_dalla_sezione(self):
        body = "Gli alberghi del centro hanno camere doppie. Un albergo economico e due ostelli."
        words = sr.search_words("Dove trovo una camera economica a {c}?", body, ["albergh", "ostell", "camere"], gs.IT_STOPWORDS, "Siviglia")
        self.assertEqual(words, ["camera", "economica", "alberghi", "ostelli"])
        self.assertEqual(sr.search_words("Dove dormire a {c}?", "Solo un albergo.", ["albergh"], gs.IT_STOPWORDS, "Siviglia"), [])

    def test_etichette_delle_candidate(self):
        keywords = [gs.all_keywords(gs.LANGS["it"])]
        section = lambda **kw: {"city": None, "region": "spagna", "category": "ALLOGGIO", "body": "x", **kw}
        self.assertEqual(sr.candidate_label(section(general_guide=True), "Siviglia", "ALLOGGIO", keywords), "guida-generale")
        self.assertEqual(sr.candidate_label(section(city="Siviglia", category="COSA_VEDERE", body="La cattedrale."),
                                            "Siviglia", "ALLOGGIO", keywords), "stesso-luogo")
        self.assertIsNone(sr.candidate_label(section(city="Siviglia", category="COSA_VEDERE", body="Un hotel in piazza."),
                                             "Siviglia", "ALLOGGIO", keywords))
        self.assertEqual(sr.candidate_label(section(city="Cordova"), "Siviglia", "ALLOGGIO", keywords), "altra-citta")
        self.assertIsNone(sr.candidate_label(section(city="Cordova", category="STORIA"), "Siviglia", "ALLOGGIO", keywords))
        self.assertIsNone(sr.candidate_label(section(), "Siviglia", "ALLOGGIO", keywords))

    def test_istruzioni_nascoste_con_la_stessa_risposta(self):
        import audit_sft
        import eval_common
        context = "▸ Cosa vedere\nLa cattedrale è il monumento principale.\nIl palazzo dell'Alcázar ha giardini."
        row = {"messages": [{"role": "user", "content": gs.LANGS["it"]["prompt"](context, "Cosa vedere a Siviglia?")},
                            {"role": "assistant", "content": "La cattedrale è il monumento principale."}],
               "kind": "pos", "region": "spagna", "category": "COSA_VEDERE", "translated": False}
        test_region = dict(row, region=next(iter(sr.TEST_REGIONS)))
        # il testo selezionato e' tutto il contesto dell'app: un'istruzione fuori dalle «» non avrebbe la forma dell'app
        selected = dict(row, region="frasario:Francese", category="TRADUZIONE", messages=[
            {"role": "user", "content": gs.LANGS["it"]["prompt"]("Testo selezionato dall'utente: «Merci»", "Traduci in italiano il testo selezionato")},
            {"role": "assistant", "content": "Grazie"}])
        out = gs.injected_rows([row, test_region, dict(row, category="FATTI_RAPIDI"), selected], 5, random.Random(0), "it",
                               gs.LANGS["it"]["prompt"])
        self.assertEqual(len(out), 1)
        injected = audit_sft.parse_row(out[0])[0]
        self.assertTrue(out[0]["injected"])
        self.assertEqual(out[0]["messages"][1], row["messages"][1])
        self.assertTrue(any(text in injected for texts in gs.INJECTIONS.values() for text in texts))
        self.assertIn("La cattedrale è il monumento principale.\n", injected)
        self.assertEqual(audit_sft.hard_errors(out[0], "it"), [])
        # Diverse dalle righe del test pos_injection, che misurano il modello addestrato
        for lang in ("it", "en"):
            tests = [r["messages"][0]["content"] for r in eval_common.injection_rows(lang)]
            self.assertFalse(any(text in t for t in tests for texts in gs.INJECTIONS.values() for text in texts))

    def test_istruzioni_nascoste_varie_e_con_parole_diverse_dal_test(self):
        import eval_common
        tests = [injection for rows in eval_common._INJECTIONS.values() for *_, injection, _, _ in rows]
        marks = re.compile(r"«[^»]+»|\"[^\"]+\"|\b[\w-]+\.example\b")
        test_marks = {m.lower() for t in tests for m in marks.findall(t)}
        for lang, texts in gs.INJECTIONS.items():
            self.assertEqual(len(texts), len(set(texts)))
            self.assertGreaterEqual(len(texts), 200)
            for text in texts:
                self.assertTrue(text[0].isupper() or text[0] in "[P", text)
                self.assertFalse({m.lower() for m in marks.findall(text)} & test_marks, text)
                self.assertFalse(any(text.lower() in t.lower() or t.lower() in text.lower() for t in tests), text)

    def test_istruzione_nascosta_anche_in_testa_alla_riga(self):
        import audit_sft
        context = "▸ Cosa vedere\nLa cattedrale è il monumento principale.\nIl palazzo dell'Alcázar ha giardini."
        row = {"messages": [{"role": "user", "content": gs.LANGS["it"]["prompt"](context, "Cosa vedere a Siviglia?")},
                            {"role": "assistant", "content": "La cattedrale è il monumento principale."}],
               "kind": "pos", "region": "spagna", "category": "COSA_VEDERE", "translated": False}
        where = set()
        for seed in range(60):
            out = gs.injected_rows([row], 1, random.Random(seed), "it", gs.LANGS["it"]["prompt"])
            line = next(line for line in audit_sft.parse_row(out[0])[0].split("\n") if "Alcázar" in line)
            where.add("testa" if not line.startswith("Il palazzo") else "coda" if line != "Il palazzo dell'Alcázar ha giardini." else "")
        self.assertEqual({"testa", "coda"}, where - {""})


if __name__ == "__main__":
    unittest.main()
