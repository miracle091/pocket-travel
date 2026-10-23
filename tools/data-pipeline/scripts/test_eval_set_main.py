#!/usr/bin/env python3
"""Test di main() di generate_eval_set.py e generate_eval_set_en.py su una cache sintetica (data/sft/raw in una cartella
temporanea): tipi di riga prodotti, risposte estratte dal contesto, rifiuti con il testo atteso, riproducibilita' con il
seme fisso. Niente rete, niente JVM (app_clean e' sostituita), niente dump.

Uso: python test_eval_set_main.py
"""
import contextlib
import io
import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest import mock

import generate_eval_set
import generate_eval_set_en
import generate_sft_dataset as g
from eval_common import REFUSAL, REFUSAL_EN, TEST_REGIONS, question_of

FILLER_IT = "Questa parte del testo spiega con frasi chiare e abbastanza lunghe quello che serve sapere sul tema indicato dalla guida."
FILLER_EN = "This part of the text explains with clear and sufficiently long sentences everything that is worth knowing about the topic."

# Una parola chiave per categoria, scelta fuori dalle radici delle altre categorie, cosi' una sezione "copre" solo la sua
IT_SECTIONS = {
    "rispettare le usanze": "Le usanze locali vanno seguite con molta attenzione ovunque.",
    "come arrivare": "Per entrare serve il passaporto, da mostrare alla dogana.",
    "situazione sanitaria": "Prima di partire conviene fare tutte le vaccinazioni consigliate.",
    "sicurezza": "Il furto nei mercati affollati e' molto frequente in estate.",
    "come spostarsi": "Il taxi e la bicicletta sono i modi piu' comodi in centro.",
    "valuta e acquisti": "Il bancomat funziona quasi ovunque, anche nei villaggi piccoli.",
    "come restare in contatto": "Il wifi e' gratuito in quasi tutti gli alberghi della zona.",
}  # senza "a tavola" e "tenersi informati": servono categorie assenti per i negativi
EN_SECTIONS = {
    "respect": "Local etiquette matters a lot when visiting any home or village.",
    "get in": "A visa and a valid passport are required for every visitor.",
    "stay healthy": "Get all the recommended vaccinations well before leaving.",
    "stay safe": "Theft is common in crowded markets, especially during summer.",
    "get around": "Taxis and bicycles are the easiest ways to move around town.",
    "eat": "Every restaurant in the area serves the same traditional dishes.",
    "buy": "Cash and credit cards are accepted nearly everywhere in the region.",
    "connect": "Free wifi is offered in most hotels and in many small cafes.",
    "cope": "The local newspaper comes out each morning in the main square.",
}


def page(sections, filler):
    return "\n".join(f"== {heading.capitalize()} ==\n{text} {filler}\n" for heading, text in sections.items())


class EvalSetMainTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.out = Path(tmp.name)
        (self.out / "raw").mkdir()
        for rid in TEST_REGIONS:
            (self.out / "raw" / f"{rid}.txt").write_text(page(IT_SECTIONS, FILLER_IT), encoding="utf-8")
            (self.out / "raw" / f"{rid}.en.txt").write_text(page(EN_SECTIONS, FILLER_EN), encoding="utf-8")
        self.sources = {(rid, "en"): rid.replace("-", " ").title().replace(" ", "_") for rid in TEST_REGIONS}
        for module in (g, generate_eval_set, generate_eval_set_en):
            patcher = mock.patch.object(module, "OUT", self.out)
            patcher.start()
            self.addCleanup(patcher.stop)
        for patcher in (mock.patch.object(g, "app_clean", lambda raw: raw.strip()),
                        mock.patch.object(generate_eval_set, "load_sources", lambda: {}),
                        mock.patch.object(generate_eval_set_en, "load_sources", lambda: self.sources),
                        mock.patch.object(generate_eval_set, "load_regions", lambda: []),
                        mock.patch("sys.argv", ["generate_eval_set.py"])):
            patcher.start()
            self.addCleanup(patcher.stop)

    def run_main(self, module, name):
        with contextlib.redirect_stdout(io.StringIO()):
            module.main()
        path = self.out / name
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()], path.read_bytes()

    def check_rows(self, rows, refusal):
        kinds = Counter(r["kind"] for r in rows)
        for kind in ("pos_para", "neg_para", "neg_empty", "neg_off"):
            self.assertGreater(kinds[kind], 0, kind)
        self.assertEqual({"messages", "kind", "region", "category"}, {k for r in rows for k in r} - {"dump", "set", "question_lang"})
        tail = [r.get("set") for r in rows]  # le righe in piu' stanno tutte in coda, dopo quelle di prima
        self.assertEqual(tail, sorted(tail, key=lambda s: s is not None))
        for r in rows:
            user, answer = (m["content"] for m in r["messages"])
            self.assertEqual(["user", "assistant"], [m["role"] for m in r["messages"]])
            if r["kind"] == "pos_para":
                context = user.split("CONTE", 1)[1]
                self.assertFalse(answer.startswith(refusal), r["category"])
                self.assertGreaterEqual(len(answer), 40)
                for part in answer.split("\n"):
                    self.assertIn(part, context)    # risposta estratta dal contesto, mai inventata
            elif r["kind"] in ("neg_para", "neg_empty", "neg_off"):
                self.assertTrue(answer.startswith(refusal), (r["kind"], answer))
        self.assertTrue(all(r["region"] in TEST_REGIONS for r in rows if r["kind"] in ("pos_para", "neg_para", "neg_empty", "neg_off")))
        return kinds

    def test_test_esteso_italiano(self):
        rows, first = self.run_main(generate_eval_set, "eval_extended.jsonl")
        kinds = self.check_rows(rows, REFUSAL)
        self.assertEqual(len(TEST_REGIONS) * len(generate_eval_set.OFF_TOPIC), kinds["neg_off"])
        # negativo vuoto: contesto di fallback italiano
        empty = next(r for r in rows if r["kind"] == "neg_empty")
        self.assertIn(g.FALLBACK_CONTEXT, empty["messages"][0]["content"])
        # stesso seme, stesso file
        _, second = self.run_main(generate_eval_set, "eval_extended.jsonl")
        self.assertEqual(first, second)

    def test_test_esteso_inglese(self):
        rows, first = self.run_main(generate_eval_set_en, "eval_extended.en.jsonl")
        kinds = self.check_rows(rows, REFUSAL_EN)
        self.assertEqual(len(TEST_REGIONS) * len(generate_eval_set_en.OFF_TOPIC), kinds["neg_off"])
        self.assertTrue(all(r["messages"][0]["content"].startswith("You are an offline travel guide.") for r in rows))
        for r in rows:
            self.assertNotIn("DOMANDA", r["messages"][0]["content"])
        # righe con la domanda in italiano, in coda: domanda delle liste italiane, risposta inglese
        italian = [r for r in rows if r.get("question_lang") == "it"]
        self.assertTrue(italian)
        italian_questions = {q for qs in generate_eval_set.PARA.values() for q in qs}
        for r in italian:
            self.assertEqual(r["set"], "v11")
            self.assertTrue(any(question_of(r).startswith(q.split("{r}")[0]) for q in italian_questions)
                            or r["kind"].endswith("_city"), question_of(r))
            if r["kind"].startswith("neg"):
                self.assertTrue(r["messages"][1]["content"].startswith(REFUSAL_EN))
        _, second = self.run_main(generate_eval_set_en, "eval_extended.en.jsonl")
        self.assertEqual(first, second)

    def test_le_domande_del_test_non_sono_in_quelle_di_training(self):
        rows, _ = self.run_main(generate_eval_set, "eval_extended.jsonl")
        training = {q.format(r=name) for qs in g.QUESTIONS.values() for q in qs
                    for name in {rid.replace("-", " ").title() for rid in TEST_REGIONS}}
        self.assertFalse({question_of(r) for r in rows if r["kind"] == "pos_para"} & training)

    def test_regione_senza_cache_ferma_lo_script(self):
        for suffix in (".txt", ".en.txt"):
            (self.out / "raw" / f"canada{suffix}").unlink()
        with self.assertRaises(SystemExit) as ctx:
            self.run_main(generate_eval_set, "eval_extended.jsonl")
        self.assertIn("canada", str(ctx.exception))
        with self.assertRaises(SystemExit) as ctx:
            self.run_main(generate_eval_set_en, "eval_extended.en.jsonl")
        self.assertIn("canada", str(ctx.exception))


class CityRefusalsTest(unittest.TestCase):
    """Rifiuti neg_city: contesto senza sezioni che trattano la categoria chiesta (radici italiane o inglesi), come nel
    training, e righe in piu' con un seme proprio."""
    SECS = [("ARRIVARE", "Si arriva con il treno regionale dalla stazione centrale in un'ora di viaggio."),
            ("COSA_VEDERE", "Il castello domina la piazza principale e ospita un piccolo museo civico."),
            ("ALLOGGIO", "La pensione del paese ha camere semplici e una colazione abbondante."),
            ("COSA_VEDERE", "In bici si gira tutta la zona, molto adatta alle biciclette.")]

    def row(self, kind, rid, cat, context, q, answer):
        return {"messages": [{"role": "user", "content": g.on_device_prompt(context, q)}, {"role": "assistant", "content": answer}],
                "kind": kind, "region": rid, "category": cat}

    def test_contesto_senza_la_categoria_chiesta(self):
        import random
        for seed in range(40):
            context = generate_eval_set.refusal_context(random.Random(seed), random.Random(seed + 1), self.SECS, "TRASPORTI",
                                                        "Come giro per Sigmaringen senza auto?", "Sigmaringen")
            self.assertIsNotNone(context)
            self.assertNotIn("bici", context)  # "In bici" tratta gli spostamenti: fuori anche se la sezione e' Cosa vedere
            self.assertNotIn("treno", context)
        # nessuna sezione utile: niente riga
        self.assertIsNone(generate_eval_set.refusal_context(random.Random(1), random.Random(2), self.SECS[:1], "TRASPORTI", "q", "X"))

    def test_righe_in_piu_con_casi_difficili(self):
        cities = [(f"Citta{i}", self.SECS[1:3]) for i in range(30)]
        pools = [("it", generate_eval_set.PARA_CITY_EXTRA["it"]), ("en", generate_eval_set.PARA_CITY_EXTRA["en"])]
        with contextlib.redirect_stdout(io.StringIO()):
            rows = generate_eval_set.city_extra_refusals(cities, pools, lambda t: t, self.row, lambda cat: REFUSAL + " " + cat, n=20)
            again = generate_eval_set.city_extra_refusals(cities, pools, lambda t: t, self.row, lambda cat: REFUSAL + " " + cat, n=20)
        self.assertEqual(rows, again)
        self.assertEqual(len(rows), 20)
        self.assertEqual(Counter(r["question_lang"] for r in rows), {"it": 10, "en": 10})
        for r in rows:
            self.assertEqual((r["kind"], r["set"]), ("neg_city", "v11"))
            self.assertNotIn(r["category"], ("COSA_VEDERE", "ALLOGGIO"))  # la citta' ha quelle sezioni
            context = r["messages"][0]["content"].split("CONTESTO: ")[1].rsplit("\n\nDOMANDA: ", 1)[0]
            self.assertFalse(generate_eval_set.treats(r["category"], context))
        # CIBO_BEVANDE con la sezione Dove dormire (che non parla di cibo) nel contesto
        food = [r for r in rows if r["category"] == "CIBO_BEVANDE"]
        self.assertTrue(food and all("pensione" in r["messages"][0]["content"] for r in food))

    def test_risposta_attesa_senza_annuncio(self):
        body = "Qui di seguito i link per il cambio:\n▸ Mance\nIl cameriere riceve 1 o 2 euro al giorno, il facchino 1 euro."
        legacy = g.pick_answer(body, body, "prezzi", "ACQUISTI", "Germania", legacy=True)
        self.assertTrue(legacy.startswith("Qui di seguito"))
        self.assertEqual(generate_eval_set.expected_answer(legacy, body, body, "prezzi", "ACQUISTI", "Germania"),
                         "Il cameriere riceve 1 o 2 euro al giorno, il facchino 1 euro.")
        only_intro = "Qui di seguito i link per conoscere l'attuale cambio con le principali monete mondiali:"
        self.assertIsNone(generate_eval_set.expected_answer(only_intro, only_intro, only_intro, "moneta", "ACQUISTI", "Emirati"))
        kept = "La valuta e' l'euro, che si usa ovunque nel paese da molti anni."
        self.assertEqual(generate_eval_set.expected_answer(kept, kept, kept, "moneta", "ACQUISTI", "Malta"), kept)


if __name__ == "__main__":
    unittest.main()
