#!/usr/bin/env python3
"""Test di sft_multi_turn.py (righe a piu' turni del v12): turni prima compattati a domanda e risposta, prompt completo
solo nell'ultimo turno, i tre tipi di riga, training e test separati, limite di token con i turni di mezzo tolti, righe
che passano audit_sft.py e domande di seguito diverse da quelle delle serie di misura.

Uso: python test_sft_multi_turn.py
"""
import random
import unittest

import audit_sft
import eval_retrieval
import generate_sft
import generate_sft_dataset as it
import generate_sft_dataset_en as en
import generate_eval_set
import sft_multi_turn
from eval_common import TEST_REGIONS

PROMPT = {"it": lambda c, q: it.on_device_prompt(c, q, True), "en": lambda c, q: en.on_device_prompt(c, q, True)}
NAMES = {"francia": "Francia", "spagna": "Spagna", "canada": "Canada", "germania": "Germania"}
# Una frase per categoria: la risposta e' la frase, il contesto la frase con una sezione di un'altra categoria
SENTENCES = {"COSA_VEDERE": "La cattedrale domina la piazza principale.", "ALLOGGIO": "Gli ostelli stanno vicino alla stazione.",
             "CIBO_BEVANDE": "Il piatto tipico e' lo stufato di carne.", "TRASPORTI": "Gli autobus passano ogni dieci minuti.",
             "SICUREZZA": "I borseggi sono frequenti sui mezzi affollati."}
QUESTIONS = {"COSA_VEDERE": "Cosa vedere in {r}?", "ALLOGGIO": "Dove dormire in {r}?", "CIBO_BEVANDE": "Cosa si mangia in {r}?",
             "TRASPORTI": "Come mi sposto in {r}?", "SICUREZZA": "{r} e' sicuro?"}


def rows_for(region):
    """Positivi di tutte le categorie, rifiuti su ALLOGGIO e SICUREZZA, un rifiuto fuori tema."""
    name, out = NAMES[region], []
    for cat, sentence in SENTENCES.items():
        context = f"{sentence} [{name}]\n\nUna sezione qualsiasi su {name}."
        out.append(row("pos", region, cat, context, QUESTIONS[cat].format(r=name), sentence))
    for cat in ("ALLOGGIO", "SICUREZZA"):
        context = f"{SENTENCES['COSA_VEDERE']} [{name}, rifiuto {cat}]"
        out.append(row("neg", region, cat, context, QUESTIONS[cat].format(r=name), refusal(it.TOPIC[cat])))
    out.append(row("neg", region, "OFF", f"Una sezione qualsiasi su {name}.", "Chi ha dipinto la Gioconda?",
                   refusal("sulla domanda")))
    return out


def row(kind, region, cat, context, q, answer):
    return {"messages": [{"role": "user", "content": PROMPT["it"](context, q)}, {"role": "assistant", "content": answer}],
            "kind": kind, "region": region, "category": cat, "translated": False}


def refusal(topic):
    return generate_sft.LANGS["it"]["refusal"](topic, "verifica.")


def chars(msgs):
    return sum(len(m["content"]) for m in msgs) // 4


ROWS = [r for region in NAMES for r in rows_for(region)]


def generate(n=60, seed=1, max_tokens=sft_multi_turn.MAX_TOKENS, test_rows=30, rows=ROWS):
    return sft_multi_turn.multi_turn_rows("it", rows, n, random.Random(seed), PROMPT["it"], NAMES.get, chars,
                                          max_tokens, test_rows)


class MultiTurnRowsTest(unittest.TestCase):
    def setUp(self):
        self.rows = generate()

    def test_tipi_e_conteggi(self):
        train = [r for r in self.rows if r["region"] not in TEST_REGIONS]
        test = [r for r in self.rows if r["region"] in TEST_REGIONS]
        self.assertEqual({"seguito": 24, "cambio": 18, "rifiuto": 18}, {t: sum(r["turns"] == t for r in train) for t in sft_multi_turn.SHARES})
        self.assertEqual({"seguito": 10, "cambio": 10, "rifiuto": 10}, {t: sum(r["turns"] == t for r in test) for t in sft_multi_turn.SHARES})
        self.assertTrue(all(r["category"] == sft_multi_turn.CATEGORY for r in self.rows))
        self.assertEqual({"neg"}, {r["kind"] for r in self.rows if r["turns"] == "rifiuto"})
        self.assertEqual({"pos"}, {r["kind"] for r in self.rows if r["turns"] != "rifiuto"})
        self.assertEqual({2, 3, 4}, {len(r["messages"]) // 2 for r in self.rows})

    def test_turni_prima_compattati_e_prompt_solo_nell_ultimo(self):
        by_answer = {r["messages"][1]["content"] for r in ROWS}
        for r in self.rows:
            *before, user, answer = r["messages"]
            self.assertTrue(all(audit_sft.prompt_lang(m["content"]) is None for m in before if m["role"] == "user"))
            self.assertIsNotNone(audit_sft.prompt_lang(user["content"]))
            self.assertTrue(all(m["content"] in by_answer for m in before if m["role"] == "assistant"))  # risposte vere
            self.assertNotIn(answer["content"], [m["content"] for m in before])
            self.assertTrue(any(n in before[0]["content"] for n in NAMES.values()), before[0]["content"])  # il primo nomina il luogo

    def test_seguito_con_il_contesto_del_luogo_e_domanda_di_seguito(self):
        for r in (r for r in self.rows if r["turns"] == "seguito"):
            context, q, answer = audit_sft.parse_row(r)
            self.assertIn(q, sft_multi_turn.FOLLOW_UPS["it"][r["last_category"]])
            self.assertIn(f"[{NAMES[r['region']]}]", context)
            self.assertIn(NAMES[r["region"]], r["messages"][0]["content"])  # stesso luogo del primo turno
            self.assertIn(answer, context)

    def test_cambio_con_domanda_che_nomina_il_luogo(self):
        for r in (r for r in self.rows if r["turns"] == "cambio"):
            _, q, _ = audit_sft.parse_row(r)
            self.assertIn(NAMES[r["region"]], q)

    def test_training_e_test_non_si_mescolano(self):
        for r in self.rows:
            test = r["region"] in TEST_REGIONS
            for m in r["messages"][:-2]:
                if m["role"] == "user":
                    places = [reg for reg, n in NAMES.items() if n in m["content"]]
                    self.assertTrue(all((p in TEST_REGIONS) == test for p in places), r)

    def test_righe_pulite_per_l_audit(self):
        for r in self.rows:
            self.assertEqual([], audit_sft.hard_errors(r, "it"), r)
        entries = [(repr(r), r) for r in ROWS + self.rows]
        self.assertTrue(all(not e for e in audit_sft.find_drops(entries, "it")[0]))

    def test_stesso_seme_stesse_righe(self):
        self.assertEqual(self.rows, generate())


class TokenLimitTest(unittest.TestCase):
    def test_tolti_i_turni_di_mezzo_e_tenuto_il_primo(self):
        full = generate()
        limit = max(chars(r["messages"]) for r in full if len(r["messages"]) == 4)  # la riga da 2 turni piu' lunga
        rows = generate(max_tokens=limit)
        self.assertTrue(rows)
        longer = lambda rs: sum(len(r["messages"]) > 4 for r in rs)
        self.assertLess(longer(rows), longer(full))
        for r in rows:
            self.assertLessEqual(chars(r["messages"]), limit)
            self.assertTrue(any(n in r["messages"][0]["content"] for n in NAMES.values()))

    def test_nessuna_riga_se_l_ultimo_turno_non_ci_sta(self):
        self.assertEqual([], generate(max_tokens=10))


class FollowUpsTest(unittest.TestCase):
    def test_stesse_categorie_nelle_due_lingue(self):
        self.assertEqual(set(sft_multi_turn.FOLLOW_UPS["it"]), set(sft_multi_turn.FOLLOW_UPS["en"]))

    def test_nessuna_domanda_delle_serie_di_misura(self):
        def strings(x):
            if isinstance(x, str):
                yield x
            elif isinstance(x, dict):
                for v in x.values():
                    yield from strings(v)
            elif isinstance(x, (list, tuple, set)):
                for v in x:
                    yield from strings(v)
        measured = {s.strip().lower() for s in strings([eval_retrieval.LANGS, generate_eval_set.PARA, generate_eval_set.PARA_EN])}
        measured.add("e per quanto tempo posso restare?")  # MultiTurnDeviceTest
        for lang, cats in sft_multi_turn.FOLLOW_UPS.items():
            for q in (q for qs in cats.values() for q in qs):
                self.assertNotIn(q.lower(), measured, (lang, q))
                self.assertNotIn("{r}", q)

    def test_domande_nella_lingua_del_dataset(self):
        from eval_common import text_lang
        for lang, cats in sft_multi_turn.FOLLOW_UPS.items():
            for q in (q for qs in cats.values() for q in qs):
                self.assertIn(text_lang(q), (None, lang), q)


if __name__ == "__main__":
    unittest.main()
