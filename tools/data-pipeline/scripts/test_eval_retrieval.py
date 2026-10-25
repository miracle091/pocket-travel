#!/usr/bin/env python3
"""Test di eval_retrieval.py: la replica della scelta del contesto dell'assistente (TravelAssistant.kt,
FtsRanking.kt) su un database FTS4 in memoria, senza rete.

Uso: python test_eval_retrieval.py
"""
import contextlib
import gc
import io
import json
import lzma
import random
import re
import sqlite3
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest import mock

import eval_retrieval as ev


class ReplicaTest(unittest.TestCase):
    def test_fts_query_come_build_fts_query(self):
        self.assertEqual(ev.fts_query("Quale valuta si usa a San Marino?", "san-marino"), "valut*")
        self.assertEqual(ev.fts_query("hi to a in", "italia"), "")
        self.assertEqual(ev.fts_query("Quali sono i piatti tipici della Puglia?", "italia"), "piatt* OR tipic* OR pugli*")
        self.assertEqual(ev.fts_query("Serve il passaporto per entrare?", "italia"), "passap* OR entra*")
        self.assertEqual(ev.fts_query("What are the typical dishes?", "italia"), "typic* OR dishe*")

    def test_espressioni_di_parole_corte_come_frase(self):
        self.assertEqual(ev.fts_query("Come si combatte il jet lag?", "san-marino"), 'come OR combat* OR "jet lag"')
        self.assertEqual(ev.focus_stems('come OR combat* OR "jet lag"', None), {"come", "comba", "jet l"})

    def test_fts_query_senza_la_citta_nominata(self):
        self.assertEqual(ev.fts_query("Cosa si mangia a Napoli?", "italia", "Napoli"), "cosa OR mangi*")
        self.assertEqual(ev.fts_query("Cosa vedere a Forli?", "italia", "Forlì"), "cosa OR veder*")
        self.assertEqual(ev.fts_query("Napoli?", "italia", "Napoli"), "napol*")

    def test_stopwords_come_l_app(self):
        kotlin = (Path(__file__).resolve().parents[3] / "feature/ai/src/main/kotlin/com/pockettravel/feature/ai/TravelAssistant.kt")
        block = kotlin.read_text(encoding="utf-8").split("private val questionStopwords = setOf(")[1].split(")")[0]
        self.assertEqual(set(re.findall(r'"(\w+)"', block)), ev.STOPWORDS)

    def test_liste_di_parole_come_l_app(self):
        kotlin = (Path(__file__).resolve().parents[3] / "feature/ai/src/main/kotlin/com/pockettravel/feature/ai/CategoryWords.kt")
        text = kotlin.read_text(encoding="utf-8")
        pairs = lambda block: {cat: "".join(re.findall(r'"([^"]*)"', value))
                               for cat, value in re.findall(r'"([A-Z_]+)" to ((?:"[^"]*"(?:\s*\+\s*)?)+)', block)}
        triggers = text.split("private val categoryTriggers = mapOf(")[1].split(").mapValues")[0]
        self.assertEqual(pairs(triggers), ev.CATEGORY_TRIGGERS)
        for lang in ("it", "en"):
            block = text.split(f'"{lang}" to mapOf(')[1].split("),")[0]
            self.assertEqual(pairs(block), ev.CATEGORY_GUIDE_WORDS[lang])

    def test_categorie_del_modello_come_l_app(self):
        kotlin = (Path(__file__).resolve().parents[3] / "feature/ai/src/main/kotlin/com/pockettravel/feature/ai/ModelQuestionCategory.kt")
        text = kotlin.read_text(encoding="utf-8")
        for lang, block in (("it", "modelCategoryLabelsIt = listOf("), ("en", "modelCategoryLabelsEn = listOf(")):
            rows = re.findall(r'ModelCategoryLabel\("([^"]*)", (?:"([^"]*)"|null), (?:"([^"]*)"|null)\)', text.split(block)[1].split("\n)")[0])
            self.assertEqual([(n, e or None, g or None) for n, e, g in rows], ev.MODEL_CATEGORY_LABELS[lang])
        self.assertIn('"Classifica la domanda di un viaggiatore in una sola categoria tra: $labels.\\nDomanda: $question\\nCategoria:"', text)
        self.assertIn('"Classify a traveller\'s question into exactly one category among: $labels.\\nQuestion: $question\\nCategory:"', text)
        self.assertTrue(ev.model_category_prompt("Tira vento a Zara?", "it").endswith("oggetti persi).\nDomanda: Tira vento a Zara?\nCategoria:"))
        self.assertEqual(ev.model_category_grammar("it").split(" | ")[0], 'root ::= "alloggio"')

    def test_categoria_del_modello_solo_senza_categoria_dalle_liste(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        guides = Path(tmp.name) / "modello.db"
        g = sqlite3.connect(guides)
        g.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        g.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", [
            ("italia", "ALLOGGIO", "Dove alloggiare", "Agriturismi e alberghi in tutta la regione.", "u"),
            ("italia", "CIBO_BEVANDE", "Mangiare", "Agriturismo con cucina tipica: ogni agriturismo ha una tavola ricca.", "u"),
        ])
        g.commit()
        g.close()
        db = ev.build_db(guides, "italia", [])
        q = "Agriturismo consigliato?"
        try:
            ev.MODEL_CATEGORIES.update({q: "ALLOGGIO", "Dove mangiare bene?": "ALLOGGIO"})
            top, _ = ev.search(db, "italia", q, [], "it", word_lists=True)
            self.assertEqual(top[0]["title"], "Dove alloggiare")
            # Le liste riconoscono gia' il cibo: la categoria del modello non conta.
            self.assertEqual(ev.with_category_words("mangi*", "Dove mangiare bene?", "it"), "mangi* OR tavola")
            top, _ = ev.search(db, "italia", "Dove mangiare bene?", [], "it", word_lists=True)
            self.assertEqual(top[0]["title"], "Mangiare")
            # Senza le liste nemmeno la categoria del modello.
            top, _ = ev.search(db, "italia", q, [], "it", word_lists=False)
            self.assertEqual(top[0]["title"], "Mangiare")
        finally:
            ev.MODEL_CATEGORIES.clear()
            db.close()

    def test_liste_di_parole_nella_query(self):
        self.assertEqual(ev.question_groups("Cerco un posto per pernottare a Rimini"), {"ALLOGGIO"})
        self.assertEqual(ev.question_groups("Che cosa e' tipico della zona?"), set())
        self.assertEqual(ev.with_category_words("pernot* OR rimini", "Dove pernottare a Rimini?", "it"), "pernot* OR rimini OR allogg*")
        self.assertEqual(ev.with_category_words("", "Come si arriva in treno?", "en"), '"get in" OR "get around"')

    def test_named_cities_senza_disambiguatore_e_accenti(self):
        cities = ["Porto (Portogallo)", "Porto Santo", "Forlì", "Bra", "Braga", "Ne"]
        self.assertEqual(ev.named_cities("Cosa vedere a Porto?", cities), ["Porto (Portogallo)"])
        self.assertEqual(ev.named_cities("Come arrivare a Porto Santo?", cities), ["Porto Santo"])
        self.assertEqual(ev.named_cities("Dove dormire a forli?", cities), ["Forlì"])
        self.assertEqual(ev.named_cities("Musei di Braga", cities), ["Braga"])
        self.assertEqual(ev.named_cities("Quanti ne servono?", cities), [])
        homonyms = ["Nice", "Mobile", "Split", "Malé"]
        self.assertEqual(ev.named_cities("Is there a nice beach?", homonyms, "en"), [])
        self.assertEqual(ev.named_cities("Mi sento male, dove trovo un medico?", homonyms), [])
        self.assertEqual(ev.named_cities("Beaches in nice?", homonyms, "en"), ["Nice"])
        self.assertEqual(ev.named_cities("Cosa vedere a male?", homonyms), ["Malé"])
        self.assertEqual(ev.named_cities("Quanto dista Siena da Firenze?", ["Firenze", "Roma", "Siena"]), ["Firenze", "Siena"])

    def test_focus_stems_senza_la_citta(self):
        self.assertEqual(ev.focus_stems("estate OR piove OR rimini", "Rimini"), {"estat", "piove"})
        self.assertEqual(ev.focus_stems("cosa OR mangi* OR passap*", "Napoli"), {"cosa", "mangi", "passa"})

    def test_relevant_paragraphs_e_select_context(self):
        clima = "Il clima e' temperato.\nIn inverno nevica spesso in collina.\nLe estati sono calde e afose."
        self.assertEqual(ev.relevant_paragraphs(clima, {"estat"}, 60), "Il clima e' temperato.\nLe estati sono calde e afose.")
        storia = "Fondata dai Romani.\n" + "B" * 300 + "\nNel Medioevo fu libero comune."
        self.assertEqual(ev.select_context(["A" * 100, storia], {"roman", "medio"}, max_chars=160),
                         "A" * 100 + "\n\nFondata dai Romani.\nNel Medioevo fu libero comune.")


class SearchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        guides = Path(self.tmp.name) / "guides.db"
        g = sqlite3.connect(guides)
        g.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        g.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", [
            ("italia", "DOGANE", "Dogane", "Per entrare serve il passaporto o la carta d'identita'.", "u"),
            ("italia", "TRASPORTI", "Come muoversi", "I treni collegano le citta' principali.", "u"),
        ])
        g.commit()
        g.close()
        rows = [
            ("Rimini", "ALLOGGIO", "Dove alloggiare", "Alberghi sul lungomare per dormire vicino al mare.", "u"),
            ("Rimini", "STORIA", "Storia", "Colonia romana.\n" + "Alberghi e treni del Novecento. " * 40, "u"),
            ("Bologna", "ALLOGGIO", "Dove alloggiare", "Alberghi in centro per dormire vicino alla stazione.", "u"),
        ]
        self.db = ev.build_db(guides, "italia", rows)
        self.cities = ["Bologna", "Rimini"]

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def test_la_citta_nominata_limita_le_sezioni_delle_citta(self):
        top, _ = ev.search(self.db, "italia", "Dove dormire a Rimini?", self.cities)
        self.assertEqual(top[0]["city"], "Rimini")
        self.assertEqual(top[0]["category"], "ALLOGGIO")
        self.assertNotIn("Bologna", [s["city"] for s in top])

    def test_senza_citta_viene_prima_la_guida_del_paese(self):
        top, _ = ev.search(self.db, "italia", "Serve il passaporto per entrare?", self.cities)
        self.assertEqual(top[0]["category"], "DOGANE")
        self.assertIsNone(top[0]["city"])

    def test_storia_pesa_poco_se_la_domanda_non_ne_parla(self):
        # La Storia di Rimini ripete "alberghi" e "treni" 40 volte: senza parole di storia finisce dopo l'alloggio.
        top, _ = ev.search(self.db, "italia", "Treni e alberghi a Rimini?", self.cities)
        categories = [s["category"] for s in top]
        self.assertLess(categories.index("ALLOGGIO"), categories.index("STORIA"))
        top, _ = ev.search(self.db, "italia", "Storia di treni e alberghi a Rimini?", self.cities)
        self.assertEqual(top[0]["category"], "STORIA")

    def test_cibo_della_citta_nominata_prima_delle_altre_sezioni(self):
        # Il nome "Rimini" non conta nella classifica: vince la sezione con "dormire", non quella che nomina di piu' Rimini.
        top, stems = ev.search(self.db, "italia", "Dove dormire a Rimini?", self.cities)
        self.assertEqual([(s["city"], s["category"]) for s in top][:1], [("Rimini", "ALLOGGIO")])
        self.assertEqual(stems, {"dove", "dormi"})

    def test_parole_di_storia_e_clima(self):
        self.assertTrue(ev.HISTORY_CLIMATE_WORDS.search("Does it rain a lot in Porto?"))
        self.assertFalse(ev.HISTORY_CLIMATE_WORDS.search("How do I get to Porto by train?"))
        self.assertTrue(ev.HISTORY_CLIMATE_WORDS.search("How old is Lagos?"))

    def test_parole_di_storia_e_clima_come_l_app(self):
        kotlin = (Path(__file__).resolve().parents[3] / "feature/ai/src/main/kotlin/com/pockettravel/feature/ai/TravelAssistant.kt")
        block = kotlin.read_text(encoding="utf-8").split("private val historyClimateWords = Regex(")[1].split("RegexOption")[0]
        self.assertEqual("".join(re.findall(r'"""(.*?)"""', block)), ev.HISTORY_CLIMATE_WORDS.pattern)

    def test_due_citta_nominate_e_ripiego_sul_nome(self):
        rows = [
            ("Siena", "TRASPORTI", "Come arrivare", "Da Firenze (72 km) con la Chiantigiana.", "u"),
            ("Siena", "COSA_VEDERE", "Cosa vedere", "Piazza del Campo, cuore di Siena.", "u"),
            ("Firenze", "TRASPORTI", "Come arrivare", "Aeroporto a 5 km dal centro di Firenze.", "u"),
        ]
        db = ev.build_db(Path(self.tmp.name) / "guides.db", "italia", rows)
        top, _ = ev.search(db, "italia", "Quanto dista Siena da Firenze?", ["Firenze", "Siena"])
        self.assertIn(rows[0][3], [s["body"] for s in top])
        self.assertEqual({s["city"] for s in top}, {"Firenze", "Siena"})
        # Nessuna sezione di Siena parla di musei: si cerca anche "siena", e le sue sezioni vengono prima del paese.
        top, _ = ev.search(db, "italia", "Quali musei ci sono a Siena?", ["Firenze", "Siena"])
        self.assertEqual([(s["city"], s["category"]) for s in top], [("Siena", "COSA_VEDERE")])
        db.close()

    def test_sezioni_per_una_nazionalita_come_nell_app_senza_nazionalita(self):
        guides = Path(self.tmp.name) / "palestina.db"
        g = sqlite3.connect(guides)
        g.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        g.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", [
            ("palestina", "SICUREZZA", "Aree a rischio", "Evitare Gaza e le aree a rischio.", "u#for-nationality=IL"),
            ("palestina", "SICUREZZA", "Israele", "Vedi la guida di Israele per le aree a rischio.", "u#not-for-nationality=IL"),
            ("palestina", "SALUTE", "Salute", "Bere acqua.", "u"),
            ("palestina", "DOGANE", "Ingresso", "Serve il passaporto britannico per entrare.", "u#for-nationality=GB"),
            ("palestina", "DOGANE", "Ingresso e uscita", "Serve il passaporto canadese per entrare.", "u#for-nationality=CA"),
        ])
        g.commit()
        g.close()
        db = ev.build_db(guides, "palestina", [])
        # Nella tabella tutte le righe, come nell'app (contano per BM25); tra i candidati non quelle per una nazionalita'.
        self.assertEqual(db.execute("SELECT COUNT(*) FROM guide_sections").fetchone()[0], 5)
        top, _ = ev.search(db, "palestina", "Quali sono le aree a rischio?", [])
        self.assertEqual([s["title"] for s in top], ["Israele"])
        # Il passaporto britannico o canadese non e' un candidato senza nazionalita'.
        top, _ = ev.search(db, "palestina", "Serve il passaporto per entrare?", [])
        self.assertEqual(top, [])
        db.close()

    def test_guida_generale_come_nell_app(self):
        guides = Path(self.tmp.name) / "generale.db"
        g = sqlite3.connect(guides)
        g.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        g.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", [
            ("italia", "DOGANE", "Dogane", "Per entrare serve il passaporto.", "u"),
            ("_general", "SALUTE", "Jet lag: Recupero", "Il recupero dal jet lag richiede pochi giorni.", "u"),
            ("_general", "DOGANE", "Documenti", "Il passaporto smarrito va denunciato " + "e poi rifatto. " * 30, "u"),
            ("grecia", "SALUTE", "Salute", "Il jet lag in Grecia.", "u"),
        ])
        g.commit()
        g.close()
        db = ev.build_db(guides, "italia", [])
        # Tutte le guide nella tabella, come nell'app, ma la ricerca solo nella regione aperta e nella guida generale.
        self.assertEqual([r for (r,) in db.execute("SELECT DISTINCT regionId FROM guide_sections ORDER BY 1")], ["_general", "grecia", "italia"])
        top, _ = ev.search(db, "italia", "Quanti giorni per il recupero dal jet lag?", [])
        self.assertEqual([s["title"] for s in top], ["Jet lag: Recupero"])
        # La sezione della guida generale, piu' lunga e con meno occorrenze di quella del paese, non entra: in fondo
        # come riempitivo porterebbe il modello fuori tema.
        top, _ = ev.search(db, "italia", "Serve il passaporto?", [])
        self.assertEqual([s["title"] for s in top], ["Dogane"])
        # Con la ricerca di prima del RAG la guida generale non si cerca.
        top, _ = ev.search(db, "italia", "Quanti giorni per il recupero dal jet lag?", [], before_rag=True)
        self.assertEqual(top, [])
        db.close()

    def rerank_with(self, scores, **conf):
        """search con il riordino: punteggio dal testo della sezione ([scores]: parola -> punteggio)."""
        scorer = lambda question, section, stems, lang: max((v for k, v in scores.items() if k in section["body"]), default=0.0)
        return mock.patch.dict(ev.RERANK, {"scorer": scorer, **conf})

    def test_riordino_sostituzione_sceglie_le_candidate_col_punteggio_piu_alto(self):
        with self.rerank_with({"Colonia romana": 0.9, "lungomare": 0.1}, mode="replace", candidates=10, general=0):
            top, _ = ev.search(self.db, "italia", "Treni e alberghi a Rimini?", self.cities)
        self.assertEqual(top[0]["category"], "STORIA")
        self.assertLessEqual(len(top), ev.MAX_SECTIONS)

    def test_riordino_aggiunta_tiene_le_sezioni_di_oggi(self):
        today, _ = ev.search(self.db, "italia", "Dove dormire a Bologna?", self.cities)
        with self.rerank_with({"Per entrare": 0.9}, mode="add", candidates=10, general=0):
            top, _ = ev.search(self.db, "italia", "Dove dormire a Bologna?", self.cities)
        self.assertEqual(top[:len(today)], today)
        # La migliore candidata e' gia' tra quelle di oggi o in coda, mai al posto di una di oggi.
        self.assertLessEqual(len(top), len(today) + 1)

    def test_riordino_aggiunta_in_testa_con_punteggio_sicuro(self):
        guides = Path(self.tmp.name) / "treni.db"
        g = sqlite3.connect(guides)
        g.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        # Quattro sezioni con "treni": oggi entrano le tre che lo ripetono di piu', non quella dei biglietti.
        g.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", [
            ("italia", "TRASPORTI", "Come muoversi", "Treni treni treni regionali.", "u"),
            ("italia", "ARRIVARE", "Come arrivare", "Treni treni internazionali.", "u"),
            ("italia", "COSA_VEDERE", "Cosa vedere", "Treni treni storici e musei.", "u"),
            ("italia", "ACQUISTI", "Valuta e acquisti", "I biglietti dei treni si pagano con la carta.", "u"),
        ])
        g.commit()
        g.close()
        db = ev.build_db(guides, "italia", [])
        question = "Come pago i treni?"
        runs = {}
        for name, score, threshold in (("coda", 0.9, None), ("testa", 0.9, 0.8), ("incerta", 0.7, 0.8)):
            with self.rerank_with({"biglietti": score}, mode="add", candidates=10, general=0, add_first=threshold):
                runs[name] = [s["category"] for s in ev.search(db, "italia", question, [])[0]]
        db.close()
        self.assertEqual(runs["coda"][-1], "ACQUISTI")
        self.assertEqual(runs["testa"], ["ACQUISTI"] + runs["coda"][:-1])
        self.assertEqual(runs["incerta"], runs["coda"])

    def test_riordino_fuso_con_la_ricerca(self):
        # Peso 1 alla ricerca: l'ordine di oggi; peso 0: quello del riordino, come "replace".
        question = "Treni e alberghi a Rimini?"
        with self.rerank_with({"Colonia romana": 0.9}, mode="replace", candidates=10, general=0):
            replaced, _ = ev.search(self.db, "italia", question, self.cities)
        with self.rerank_with({"Colonia romana": 0.9}, mode="blend", blend=0.0, candidates=10, general=0):
            only_rerank, _ = ev.search(self.db, "italia", question, self.cities)
        with self.rerank_with({"Colonia romana": 0.9}, mode="blend", blend=1.0, candidates=10, general=0):
            only_search, _ = ev.search(self.db, "italia", question, self.cities)
        with self.rerank_with({}, mode="replace", candidates=10, general=0):
            search_order, _ = ev.search(self.db, "italia", question, self.cities)
        self.assertEqual([s["category"] for s in only_rerank], [s["category"] for s in replaced])
        self.assertEqual([s["category"] for s in only_search], [s["category"] for s in search_order])

    def test_prompt_del_riordino_con_luogo_e_paragrafi_della_domanda(self):
        body = "\n".join(["Il centro storico e' pieno di chiese. " * 5] * 6 + ["Si dorme in ostelli vicino alla stazione."])
        section = {"city": "Porto (Portogallo)", "category": "ALLOGGIO", "title": "Dove alloggiare", "body": body}
        prompt = ev.rerank_prompt("Dove dormire a Porto?", section, {"dorm", "ostel"}, "it")
        self.assertIn("Porto, Dove alloggiare", prompt)
        self.assertIn("Dove dormire a Porto?", prompt)
        self.assertIn("Si dorme in ostelli", prompt)
        self.assertLess(len(prompt), ev.RERANK["snippet"] + 300)
        self.assertTrue(prompt.endswith("Rispondi solo sì o no."))
        general = {"city": None, "general_guide": True, "category": "SALUTE", "title": "Jet lag", "body": "Breve."}
        self.assertIn("guida generale, Jet lag", ev.rerank_prompt("Jet lag?", general, set(), "it"))

    def test_candidate_del_riordino_con_le_pagine_della_guida_generale(self):
        guides = Path(self.tmp.name) / "generale-riordino.db"
        g = sqlite3.connect(guides)
        g.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        g.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", [
            ("italia", "DOGANE", "Dogane", "Per entrare serve il passaporto.", "u"),
            ("_general", "DOGANE", "Documenti", "Il passaporto smarrito va denunciato " + "e poi rifatto. " * 30, "u"),
        ])
        g.commit()
        g.close()
        db = ev.build_db(guides, "italia", [])
        # Oggi la pagina della guida generale non entra; tra le candidate del riordino si'.
        with self.rerank_with({"smarrito": 0.9}, mode="replace", candidates=10, general=2):
            top, _ = ev.search(db, "italia", "Serve il passaporto?", [])
        self.assertEqual([s["title"] for s in top], ["Documenti", "Dogane"])
        db.close()

    def test_parole_del_modello_fuori_dal_confronto_con_la_guida_generale(self):
        info = lambda q, c: {"p": 2, "c": 2, "hits": [[0, q], [0, c]], "docs": [[2, 3], [2, 3]], "n": 20, "avg": [3, 50], "len": [3, 50]}
        region = [({"category": "TRASPORTI", "title": "Trasporti"}, info(1, 5))]
        general = [({"category": "SICUREZZA", "title": "Borseggiatori", "general_guide": True}, info(2, 0))]
        titles = lambda **kw: [s["title"] for s in ev.rank_sections(region, [], True, general_guide=general, lang="it", **kw)]
        self.assertEqual(titles(), ["Trasporti"])
        self.assertEqual(titles(compare_phrases=1), ["Borseggiatori", "Trasporti"])

    def test_soglia_della_guida_generale_piu_bassa_in_italiano(self):
        info = lambda hits: {"p": 1, "c": 2, "hits": [[0, hits]], "docs": [[0, 1]], "n": 10, "avg": [2, 20], "len": [2, 20]}
        region = [({"category": "DOGANE", "title": "Dogane"}, info(2))]
        general = [({"category": "DOGANE", "title": "Documenti", "general_guide": True}, info(3))]
        # Supera la migliore della regione ma non del doppio: in testa nelle guide italiane, fuori in quelle inglesi.
        self.assertEqual([s["title"] for s in ev.rank_sections(region, [], True, general_guide=general, lang="it")], ["Documenti", "Dogane"])
        self.assertEqual([s["title"] for s in ev.rank_sections(region, [], True, general_guide=general, lang="en")], ["Dogane"])

    def test_contesto_come_nel_prompt_e_sezione_attesa(self):
        top = [{"body": "A" * 1990}, {"body": "Alberghi sul lungomare.\nB" + "b" * 300}]
        row = ev.context_row(top, {"alber"}, [top[1]])
        self.assertEqual(row["context"], ev.select_context([s["body"] for s in top], {"alber"}))
        # La seconda sezione e' tra quelle scelte, ma nei 2000 caratteri non entra.
        self.assertEqual((row["found"], row["section_in_context"], row["answer_in_context"]), (True, False, False))
        row = ev.context_row(top[1:], {"alber"}, top[1:])
        self.assertEqual((row["found"], row["section_in_context"], row["answer_in_context"]), (True, True, True))
        self.assertEqual(ev.context_row(top[:1], {"alber"}, [])["found"], False)

    def test_stesse_domande_in_ogni_variante(self):
        rows = [{"kind": k, "question": f"{k}{i}"} for k in ("paese", "distanze") for i in range(10)]
        with tempfile.TemporaryDirectory() as tmp:
            paths = [Path(tmp) / "a.jsonl", Path(tmp) / "b.jsonl"]
            for p in paths:
                ev.write_contexts(p, rows, 3)
            first, second = (p.read_text(encoding="utf-8") for p in paths)
        self.assertEqual(first, second)
        self.assertEqual(len(first.splitlines()), 6)

    def test_nazione_nominata_cerca_nella_sua_guida(self):
        self.assertEqual(ev.named_place("Serve il passaporto per entrare in Grecia?", "italia"), ("gr", "Grecia", ["grecia"]))
        self.assertIsNone(ev.named_place("Serve il passaporto per entrare in Italia?", "italia"))
        guides = Path(self.tmp.name) / "nazioni.db"
        g = sqlite3.connect(guides)
        g.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        g.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", [
            ("italia", "DOGANE", "Come arrivare", "Per entrare serve il passaporto.", "u"),
            ("grecia", "DOGANE", "Come arrivare", "Per entrare in Grecia serve il passaporto.", "u"),
        ])
        g.commit()
        g.close()
        db = ev.build_db(guides, "italia", [])
        top, _ = ev.search(db, "italia", "Serve il passaporto per entrare in Grecia?", [])
        self.assertEqual([s["region"] for s in top], ["grecia"])
        self.assertEqual(ev.fts_query("Serve il passaporto per entrare in Grecia?", "italia", "Grecia"), "passap* OR entra*")
        top, _ = ev.search(db, "italia", "Serve il passaporto per entrare in Grecia?", [], before_rag=True)
        self.assertEqual([s["region"] for s in top], ["italia"])
        db.close()

    def test_matchinfo_pcxnal_letto(self):
        blob = self.db.execute("SELECT matchinfo(city_sections_fts, 'pcxnal') FROM city_sections_fts "
                               "WHERE city_sections_fts MATCH 'alberghi'").fetchone()[0]
        info = ev.parse_matchinfo(blob)
        self.assertEqual((info["p"], info["c"], info["n"]), (1, 2, 3))
        self.assertEqual(info["docs"][0][1], 3)



class DownloadTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)

    def test_scarica_una_volta_finche_l_url_non_cambia(self):
        out = self.dir / "guides.db"
        with mock.patch.object(ev, "fetch", return_value=b"dati-v1") as fetch:
            ev.download("https://x/guides-v1.db", out)
            ev.download("https://x/guides-v1.db", out)
            self.assertEqual(1, fetch.call_count)
            self.assertEqual(b"dati-v1", out.read_bytes())
        with mock.patch.object(ev, "fetch", return_value=b"dati-v2") as fetch:
            ev.download("https://x/guides-v2.db", out)   # la versione pubblicata e' cambiata
            self.assertEqual(1, fetch.call_count)
        self.assertEqual(b"dati-v2", out.read_bytes())
        self.assertEqual("https://x/guides-v2.db", out.with_name("guides.db.url").read_text(encoding="utf-8"))
        self.assertFalse(out.with_name("guides.db.part").exists())

    def test_file_xz_decompresso(self):
        out = self.dir / "guides.db"
        with mock.patch.object(ev, "fetch", return_value=lzma.compress("città".encode("utf-8"))):
            ev.download("https://x/guides.db.xz", out)
        self.assertEqual("città", out.read_text(encoding="utf-8"))

    def test_file_scomparso_si_riscarica_anche_con_lo_stesso_url(self):
        out = self.dir / "guides.db"
        with mock.patch.object(ev, "fetch", return_value=b"x") as fetch:
            ev.download("https://x/a.db", out)
            out.unlink()
            ev.download("https://x/a.db", out)
        self.assertEqual(2, fetch.call_count)

    def test_download_interrotto_non_lascia_il_file(self):
        out = self.dir / "guides.db"
        with mock.patch.object(ev, "fetch", return_value=b"not lzma"):
            with self.assertRaises(lzma.LZMAError):
                ev.download("https://x/guides.db.xz", out)
        self.assertFalse(out.exists())
        self.assertFalse(out.with_name("guides.db.url").exists())


def make_cities_db(path, rows):
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE city_sections (city TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
    con.executemany("INSERT INTO city_sections VALUES (?, ?, ?, ?, ?)", rows)
    con.commit()
    con.close()


class CityRowsTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.addCleanup(gc.collect)  # city_rows_of lascia le connessioni sqlite ad aprirsi da sole: su Windows bloccano la cartella
        self.cache = Path(tmp.name)
        make_cities_db(self.cache / "italia.cities.db", [
            ("Roma", "COSA_VEDERE", "Vedere", "Colosseo.", "u1"), ("Roma", "STORIA", "Storia", "Fondata nel 753.", "u2"),
            ("Pisa", "TRASPORTI", "Trasporti", "Aeroporto.", "u3")])
        make_cities_db(self.cache / "italia.wp.db", [("Roma", "CLIMA", "Clima", "Mite.", "u4"), ("Sconosciuta", "CLIMA", "Clima", "x", "u5")])
        make_cities_db(self.cache / "italia.cities-en.db", [("Rome", "SEE", "See", "Colosseum.", "u6")])

    def test_senza_wikipedia_toglie_storia_e_clima(self):
        rows = ev.city_rows_of(self.cache, "italia", False)
        self.assertEqual({("Roma", "COSA_VEDERE"), ("Pisa", "TRASPORTI")}, {r[:2] for r in rows})

    def test_con_wikipedia_aggiunge_il_db_wp_solo_per_citta_note_dopo_wikivoyage(self):
        rows = ev.city_rows_of(self.cache, "italia", True)
        self.assertEqual([("Roma", "COSA_VEDERE"), ("Roma", "STORIA"), ("Roma", "CLIMA"), ("Pisa", "TRASPORTI")], [r[:2] for r in rows])

    def test_suffisso_della_lingua(self):
        self.assertEqual([("Rome", "SEE")], [r[:2] for r in ev.city_rows_of(self.cache, "italia", False, "en")])


class PlanTest(unittest.TestCase):
    def test_distanze_tra_citta_della_regione(self):
        rows = [("Firenze", "TRASPORTI", "t", "Da Firenze a Bologna sono 100 km.\nPaese senza cifre verso Bologna.", "u"),
                ("Firenze", "COSA_VEDERE", "v", "Da Firenze a Siena 70 km.", "u"),     # solo TRASPORTI conta
                ("Bologna", "TRASPORTI", "t", "Bologna dista 3 ore da Firenze e 50 minuti da Roma.", "u"),
                ("Siena", "TRASPORTI", "t", "Siena e' a 5 minuti.", "u"),
                ("Roma", "TRASPORTI", "t", "A Roma ci sono 10 km di metro.", "u"),     # nome di 4 lettere: escluso
                ("San Gimignano", "TRASPORTI", "t", "Da Firenze 60 km.", "u")]            # nome con spazio: escluso
        pairs = ev.distance_pairs(rows)
        self.assertEqual([("Bologna", "Firenze", "Bologna dista 3 ore da Firenze e 50 minuti da Roma."),
                          ("Firenze", "Bologna", "Da Firenze a Bologna sono 100 km.")], pairs)

    def test_distanze_senza_dati(self):
        self.assertEqual([], ev.distance_pairs([]))

    def test_questions_of_per_serie(self):
        self.assertIs(ev.LANGS["it"]["questions"], ev.questions_of("it", "direct"))
        self.assertIs(ev.LANGS["it"]["reworded"], ev.questions_of("it", "reworded"))
        # guide in una lingua, domande nell'altra
        self.assertIs(ev.LANGS["en"]["questions"], ev.questions_of("it", "other-lang"))
        self.assertIs(ev.LANGS["it"]["questions"], ev.questions_of("en", "other-lang"))

    def test_piano_deterministico_con_i_semi_e_domande_solo_per_le_categorie_presenti(self):
        questions = ({"COSA_VEDERE": ["Cosa vedere a {c}?"], "TRASPORTI": ["Come muoversi a {c}?"], "ALLOGGIO": ["Dove dormire a {c}?"]},
                     {"STORIA": ["Storia di {c}?"], "CLIMA": ["Clima a {c}?"]}, {"CIBO_BEVANDE": ["Cosa mangiare?"]}, ["Quanto da {c} a {o}?"])
        rows = [(c, cat, "t", "Testo.", "u") for c in ("Verona (Veneto)", "Padova") for cat in ("COSA_VEDERE", "TRASPORTI", "STORIA")]
        rows.append(("Isolata", "COSA_VEDERE", "t", "Testo.", "u"))   # senza TRASPORTI: non e' una citta' ammissibile

        def plan():
            return ev.make_plan("veneto", rows, random.Random(7), random.Random(8), questions)

        first = plan()
        self.assertEqual(first, plan())
        kinds = Counter(p[0] for p in first)
        self.assertEqual({ev.KINDS[0]: 4, ev.KINDS[2]: 2, ev.KINDS[1]: 1}, dict(kinds))   # ALLOGGIO e CLIMA mancano
        self.assertNotIn("Isolata", {p[2] for p in first})
        self.assertIn("Cosa vedere a Verona?", [p[3] for p in first])   # senza il disambiguatore
        self.assertTrue(all(p[4] is None for p in first))


class WriteContextsTest(unittest.TestCase):
    ROWS = [{"kind": "a", "question": f"A{i}"} for i in range(6)] + [{"kind": "b", "question": f"B{i}"} for i in range(2)]

    def write(self, sample, rows=None):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            path = Path(tmp) / "c.jsonl"
            ev.write_contexts(path, rows or self.ROWS, sample)
            return path.read_text(encoding="utf-8")

    def test_tutte_le_righe_senza_campione(self):
        self.assertEqual(self.ROWS, [json.loads(l) for l in self.write(0).splitlines()])

    def test_campione_per_tipo_riproducibile_e_nell_ordine_originale(self):
        first = [json.loads(l) for l in self.write(3).splitlines()]
        self.assertEqual(first, [json.loads(l) for l in self.write(3).splitlines()])
        self.assertEqual(3, sum(r["kind"] == "a" for r in first))
        self.assertEqual(2, sum(r["kind"] == "b" for r in first))     # meno righe del campione: tutte
        numbers = [int(r["question"][1:]) for r in first if r["kind"] == "a"]
        self.assertEqual(sorted(numbers), numbers)

    def test_unicode_non_escapato(self):
        self.assertIn("città", self.write(0, [{"kind": "a", "question": "Qual è la città?"}]))


class MeasureTest(unittest.TestCase):
    def test_margine_di_wilson(self):
        self.assertEqual(ev.margin(0, 0), 0.0)
        # 85% su 20 righe: intervallo al 95% circa 64-95% (calcolo del piano valutazione-modelli.md)
        self.assertAlmostEqual(ev.margin(17, 20), 21.0, delta=1.0)
        self.assertLess(ev.margin(850, 1000), 3.0)

    def test_impronta_e_avviso_con_dati_diversi(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "guides.db", Path(tmp) / "manca.db"
            a.write_bytes(b"uno")
            first = ev.fingerprint([a, b])
            self.assertEqual(list(first), ["guides.db"])
            history = Path(tmp) / "storico.jsonl"
            config = {"lang": "it", "series": "control"}
            ev.record_history(history, config, first, {"paese": {"n": 1}})
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                ev.check_history(history, config, first)
            self.assertEqual(err.getvalue(), "")
            a.write_bytes(b"due")
            with contextlib.redirect_stderr(err):
                ev.check_history(history, config, ev.fingerprint([a]))
            self.assertIn("dati diversi (guides.db)", err.getvalue())
            # Configurazione diversa: nessun confronto, nessun avviso
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                ev.check_history(history, {"lang": "en", "series": "control"}, ev.fingerprint([a]))
            self.assertEqual(err.getvalue(), "")

    def test_paragrafi_uniti_e_divisi(self):
        body = "Hotel Uno, centro.\nHotel Due, mare.\n\n" + "Frase lunga del paragrafo. " * 60
        self.assertEqual(ev.paragraph_chunks(body, 0)[:2], ["Hotel Uno, centro.", "Hotel Due, mare."])
        chunks = ev.paragraph_chunks(body, 400)
        self.assertEqual(chunks[0].split("\n")[:2], ["Hotel Uno, centro.", "Hotel Due, mare."])
        self.assertTrue(all(len(c) <= 2.5 * 400 for c in chunks))
        # Nessun testo perso: ogni pezzo e' dentro la sezione, e l'ordine del testo si ricostruisce
        flat = lambda s: " ".join(s.split())
        self.assertEqual(flat(" ".join(chunks)), flat(body))
        self.assertEqual(ev.in_text_order(body, {chunks[1], chunks[0]}), chunks[0] + "\n" + chunks[1])


if __name__ == "__main__":
    unittest.main()
