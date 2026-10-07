#!/usr/bin/env python3
"""Test di audit_sft.py: ogni controllo su righe sintetiche costruite con on_device_prompt, e nessun falso positivo sui
rifiuti e sulle categorie con risposte non copiate dalle guide (DISTANZE, NOTE, VACCINAZIONI, FATTI_RAPIDI, VICINO, PARTENZE).

Uso: python test_audit_sft.py
"""
import contextlib
import io
import json
import random
import tempfile
import unittest
from pathlib import Path

import audit_sft
import generate_sft
import generate_sft_dataset as it
import generate_sft_dataset_en as en
import sft_nearby
from audit_sft import (count_sentences, find_drops, hard_errors, is_extractive, leakage, load, parse_row, prompt_lang,
                       wrong_language)

PROMPT = {"it": it.on_device_prompt, "en": en.on_device_prompt}
GUIDE = {"it": "Il museo apre alle 9. Costa 10 euro. L'ingresso e' gratuito la domenica.",
         "en": "The museum opens at nine. It costs ten euros. Entry is free on Sundays."}
QUESTION = {"it": "Quando apre il museo?", "en": "When does the museum open?"}


def make(context, question, answer, kind="pos", region="francia", category="COSA_VEDERE", lang="it"):
    return {"messages": [{"role": "user", "content": PROMPT[lang](context, question)},
                         {"role": "assistant", "content": answer}],
            "kind": kind, "region": region, "category": category, "translated": False}


def refusal(lang, topic="sulla sicurezza"):
    return generate_sft.LANGS[lang]["refusal"](topic, "verifica.")


def errors(row, lang="it"):
    return hard_errors(row, lang)


class ParseTest(unittest.TestCase):
    def test_roundtrip_nelle_due_lingue(self):
        for lang in ("it", "en"):
            row = make(GUIDE[lang], QUESTION[lang], "x", lang=lang)
            self.assertEqual(parse_row(row), (GUIDE[lang], QUESTION[lang], "x"))
            self.assertEqual(prompt_lang(row["messages"][0]["content"]), lang)

    def test_contesto_con_a_capo_e_domanda_nel_contesto(self):
        ctx = "Prima riga.\n\nDOMANDA: non e' la domanda\nSeconda."
        self.assertEqual(parse_row(make(ctx, "Vero?", "x"))[:2], (ctx, "Vero?"))

    def test_malformate(self):
        good = make(GUIDE["it"], QUESTION["it"], "Il museo apre alle 9.")
        bad = [{}, {"messages": []}, {"messages": [good["messages"][0]]}, dict(good, messages=[{"role": "x"}, {}]),
               dict(good, messages=[{"role": "user", "content": "ciao"}, good["messages"][1]]),
               dict(good, messages=[good["messages"][0], {"role": "assistant", "content": 3}]),
               dict(good, kind="boh"), dict(good, region=""), dict(good, category=None)]
        for row in bad:
            self.assertEqual(errors(row), ["malformata"], row)
        self.assertEqual(errors(good), [])

    def test_prompt_in_altra_lingua(self):
        row = make(GUIDE["en"], QUESTION["en"], "The museum opens at nine.", lang="en")
        self.assertEqual(errors(row, "it"), ["prompt-lingua"])
        self.assertEqual(errors(row, "en"), [])


class LimitsTest(unittest.TestCase):
    def test_contesto_lungo(self):
        ctx = ("Il museo apre alle 9. " * 200)[:it.MAX_CONTEXT + 1]
        self.assertIn("contesto-lungo", errors(make(ctx, "Quando?", "Il museo apre alle 9.")))
        self.assertEqual(errors(make(ctx[:it.MAX_CONTEXT], "Quando?", "Il museo apre alle 9.")), [])

    def test_risposta_lunga_e_vuota(self):
        long_ = "Il museo apre alle 9. " * 40
        self.assertIn("risposta-lunga", errors(make(long_, "Quando?", long_.strip()[:it.MAX_ANSWER + 1])))
        self.assertEqual(errors(make("Il museo apre alle 9.", "Quando?", "")), ["risposta-vuota"])
        self.assertEqual(errors(make("Il museo apre alle 9.", "Quando?", "  \n")), ["risposta-vuota"])

    def test_piu_di_tre_frasi(self):
        self.assertEqual(count_sentences("Uno. Due. Tre.", "it"), 3)
        self.assertEqual(count_sentences("Uno. Due. Tre. Quattro.", "it"), 4)
        self.assertEqual(count_sentences("Riga uno\nRiga due", "it"), 2)
        # l'inglese non divide dopo le abbreviazioni, come il generatore
        self.assertEqual(count_sentences("Take the U.S. Route 1 north. Then turn left.", "en"), 2)


class ExtractiveTest(unittest.TestCase):
    def test_frasi_del_contesto(self):
        for lang in ("it", "en"):
            first, third = GUIDE[lang].split(". ")[0] + ".", GUIDE[lang].split(". ")[2]
            row = make(GUIDE[lang], QUESTION[lang], f"{first} {third}", lang=lang)  # non contigue: va bene
            self.assertEqual(errors(row, lang), [])

    def test_frase_inventata(self):
        row = make(GUIDE["it"], QUESTION["it"], "Il museo apre alle 9. Si paga solo in contanti.")
        self.assertEqual(errors(row), ["non-estrattiva"])

    def test_spazi_normalizzati(self):
        ctx = "Il museo   apre\nalle 9.\n\nCosta 10 euro."
        self.assertEqual(errors(make(ctx, "Quando?", "Il museo apre alle 9. Costa 10 euro.")), [])

    def test_elenco_puntato(self):
        ctx = "• Il museo apre alle 9.\n• Costa 10 euro."
        self.assertEqual(errors(make(ctx, "Quando?", "Il museo apre alle 9. Costa 10 euro.")), [])

    def test_rifiuti_non_controllati(self):
        for lang in ("it", "en"):
            for kind in ("pos", "neg"):  # un rifiuto non e' nel contesto, ne' per i negativi ne' per un "pos" sbagliato
                row = make(GUIDE[lang], QUESTION[lang], refusal(lang), kind=kind, category="OFF", lang=lang)
                self.assertEqual(errors(row, lang), [])
            self.assertEqual(errors(make(it.FALLBACK_CONTEXT, "Domanda?", refusal("it"), kind="neg")), [])

    def test_negativo_non_controllato(self):
        self.assertEqual(errors(make(GUIDE["it"], "Domanda?", "Non lo so.", kind="neg")), [])


class SyntheticCategoriesTest(unittest.TestCase):
    """Le categorie con risposte non copiate dalle guide non danno falsi positivi."""

    def audit(self, rows, lang):
        for r in rows:
            self.assertEqual(errors(r, lang), [], (r["category"], r["messages"][1]["content"]))

    def test_vicino_e_partenze(self):
        for lang in ("it", "en"):
            rows = []
            for seed in range(300):
                rng = random.Random(seed)
                guide = GUIDE[lang]
                for cat, make_example in (("VICINO", sft_nearby.poi_example), ("PARTENZE", sft_nearby.transit_example)):
                    block, q, answer, kind = make_example(rng, lang, lambda t: refusal(lang, t))
                    ctx = "\n\n".join(x for x in (block, guide) if x)
                    rows.append(make(ctx, q, answer, kind, category=cat, lang=lang))
            self.assertGreater(sum(r["kind"] == "pos" for r in rows), 200)
            self.audit(rows, lang)

    def test_vicino_con_numero_inventato(self):
        block = "Punti di interesse entro 150 m dalla tua posizione:\nFarmacie: Rossi (90 m)"
        self.assertEqual(errors(make(block, "Dov'e' una farmacia qui vicino?", "La farmacia Rossi e' a 90 m.", category="VICINO")), [])
        self.assertEqual(errors(make(block, "Dov'e' una farmacia qui vicino?", "La farmacia Rossi e' a 95 m.", category="VICINO")),
                         ["non-estrattiva"])

    def test_distanze(self):
        torino = [("ARRIVARE", "Torino ha un aeroporto internazionale. Da Milano sono 140 km in autostrada."),
                  ("TRASPORTI", "Torino ha una metropolitana.")]
        genova = [("ARRIVARE", "Genova ha un porto. Da Torino si arriva in 2 ore di treno.")]
        by_name = {"Torino": torino, "Genova": genova}
        coords = {"Torino": (45.07, 7.69), "Genova": (44.41, 8.95)}
        for lang in ("it", "en"):
            rows, seen = [], set()
            for seed in range(80):
                ex = generate_sft.distance_example(random.Random(seed), "Torino", "Genova", by_name, lang,
                                                   lambda t: refusal(lang, t), coords=coords)
                ctx, q, answer, kind, _ = ex
                seen.add(kind)
                rows.append(make(ctx, q, answer, kind, region="citta:Torino", category="DISTANZE", lang=lang))
            self.assertEqual(seen, {"pos"})
            self.audit(rows, lang)
            ex = generate_sft.distance_example(random.Random(1), "Torino", "Genova", by_name, lang, lambda t: refusal(lang, t))
            self.audit([make(ex[0], ex[1], ex[2], ex[3], category="DISTANZE", lang=lang)], lang)

    def test_emergenze(self):
        lines = {"it": "Numeri di emergenza: Generale 112, Polizia 113, Ambulanza 118, Vigili del fuoco 115",
                 "en": "Emergency numbers: General 112, Police 113, Ambulance 118, Fire 115"}
        for lang, line in lines.items():
            L, rows = generate_sft.LANGS[lang], []
            for seed in range(20):
                ctx, q, answer, kind = generate_sft.emergency_example(random.Random(seed), lang, line, "Italia", [GUIDE[lang]], L,
                                                                      lambda t: refusal(lang, t))
                rows.append(make(ctx, q, answer, kind, category="EMERGENZE", lang=lang))
            self.audit(rows, lang)

    def test_note(self):
        for lang in ("it", "en"):
            L, rows = generate_sft.LANGS[lang], []
            for title, body, questions, answer in L["notes"]:
                ctx = f"{GUIDE[lang]}\n\n{L['note_label']}: {title}\n{body}"
                rows.append(make(ctx, questions[0], answer, category="NOTE", lang=lang))
            self.assertGreater(len(rows), 4)
            self.audit(rows, lang)

    def test_fatti_rapidi(self):
        for lang in ("it", "en"):
            ctx = f"{GUIDE[lang]}\n\nFatti rapidi\nValuta: euro."
            self.audit([make(ctx, "Qual e' la valuta?", "Valuta: euro.", category="FATTI_RAPIDI", lang=lang)], lang)

    def test_vaccinazioni(self):
        texts = {"it": "Vaccinazioni per un viaggio dall'Italia al Ghana\nCertificati richiesti:\n- Febbre gialla\n- Poliomielite\n"
                       "Raccomandate per la destinazione:\n- Epatite A\nVerifica sempre le regole ufficiali.",
                 "en": "Vaccinations for a trip from Italy to Ghana\nRequired certificates:\n- Yellow fever\n- Polio\n"
                       "Recommended for the destination:\n- Hepatitis A\nAlways check the official rules."}
        nessuno = {"it": "Vaccinazioni per un viaggio dall'Italia in Francia\nNessun certificato richiesto nei nostri dati\n"
                         "Verifica sempre le regole ufficiali.",
                   "en": "Vaccinations for a trip from Italy to France\nNo certificate required in our data\n"
                         "Always check the official rules."}
        for lang in ("it", "en"):
            rows = []
            for text in (texts[lang], nessuno[lang]):
                answers = generate_sft.vaccination_answers(text, lang)
                self.assertIn("any", answers)
                rows += [make(f"{GUIDE[lang]}\n\n{text}", "Quali vaccini?", a, category="VACCINAZIONI", lang=lang)
                         for a in answers.values()]
            self.assertGreaterEqual(len(rows), 5)
            self.audit(rows, lang)
        # i certificati riuniti in una frase non sono una sottostringa, ma lo sono senza punteggiatura
        text = texts["it"]
        multi = generate_sft.vaccination_answers(text, "it")["any"]
        self.assertFalse(multi.split(". ")[0] in text)
        # senza il controllo allentato (altra categoria) lo stesso testo sarebbe segnalato
        self.assertFalse(is_extractive("COSA_VEDERE", text, multi))
        self.assertTrue(is_extractive("VACCINAZIONI", text, multi))
        self.assertFalse(is_extractive("VACCINAZIONI", text, multi + " Serve anche il visto."))


class MarkupTest(unittest.TestCase):
    def test_residui(self):
        for bad in ("{{", "}}", "[[", "]]", "<ref name=a>", "&amp;", "&nbsp;", "()", "�"):
            ctx = f"Il museo apre alle 9 {bad} e costa 10 euro."
            self.assertTrue(errors(make(ctx, "Quando?", "Il museo apre alle 9.")), bad)
            ans = f"Il museo apre alle 9 {bad}."
            self.assertTrue(errors(make(ans, "Quando?", ans)), bad)

    def test_motivi(self):
        self.assertEqual(errors(make("Costa 10 � euro.", "Quanto?", "Costa 10 � euro.")), ["fffd"])
        self.assertEqual(errors(make("Vedi [[Louvre]].", "Cosa?", "Vedi [[Louvre]].")), ["markup-residuo"])

    def test_nessun_falso_positivo(self):
        ctx = "Il museo (aperto dal 1900) costa 10 euro [circa]. Orari (9-18)."
        self.assertEqual(errors(make(ctx, "Quando?", "Il museo (aperto dal 1900) costa 10 euro [circa].")), [])


class RegionTest(unittest.TestCase):
    def test_sottoregione_di_test(self):
        row = lambda region: make(GUIDE["it"], QUESTION["it"], "Il museo apre alle 9.", region=region)
        self.assertEqual(errors(row("canada-ontario")), ["sottoregione-di-test"])
        self.assertEqual(errors(row("germania-baviera")), ["sottoregione-di-test"])
        # le regioni di test vere restano nel file (le separa train_lora.py), come le citta' e gli altri paesi
        for ok in ("canada", "samoa-americane", "citta:Toronto", "francia", "figi-occidentali"):
            self.assertEqual(errors(row(ok)), [], ok)


class DuplicatesTest(unittest.TestCase):
    def entries(self, rows):
        return [(json.dumps(r, ensure_ascii=False), r) for r in rows]

    def test_duplicati(self):
        a = make(GUIDE["it"], "Quando apre?", "Il museo apre alle 9.")
        b = make(GUIDE["it"], "Quando apre?", "Costa 10 euro.", region="spagna")  # stesso (contesto, domanda), altra riga
        c = make(GUIDE["it"], "Quanto costa?", "Costa 10 euro.")
        errs, drops = find_drops(self.entries([a, a, b, c]), "it")
        self.assertEqual(errs, [[], ["riga-duplicata"], ["contesto-domanda-duplicati"], []])
        self.assertEqual(drops, [None, "riga-duplicata", "contesto-domanda-duplicati", None])

    def test_riga_sbagliata_non_nasconde_la_buona(self):
        bad = make(GUIDE["it"], "Quando apre?", "Si paga in contanti.")
        good = make(GUIDE["it"], "Quando apre?", "Il museo apre alle 9.")
        errs, drops = find_drops(self.entries([bad, good]), "it")
        self.assertEqual(drops, ["non-estrattiva", None])

    def test_json_non_valido(self):
        errs, drops = find_drops([("{rotto", None)], "it")
        self.assertEqual(errs, [["malformata"]])
        self.assertEqual(drops, ["malformata"])


class RepeatsTest(unittest.TestCase):
    def rows(self, n, category, question="Cosa e' la luna?"):
        return [(json.dumps(r), r) for r in (make(f"{GUIDE['it']} {i}", question, refusal("it"), kind="neg", category=category)
                                              for i in range(n))]

    def test_off_ha_il_limite_di_default(self):
        entries = self.rows(5, "OFF")
        _, drops = find_drops(entries, "it")
        self.assertEqual(drops, [None] * it.OFF_TOPIC_MAX_USES + ["domanda-ripetuta"] * (5 - it.OFF_TOPIC_MAX_USES))

    def test_altre_categorie_senza_limite_salvo_opzione(self):
        entries = self.rows(5, "SICUREZZA")
        self.assertEqual(find_drops(entries, "it")[1], [None] * 5)
        self.assertEqual(find_drops(entries, "it", 3)[1], [None] * 3 + ["domanda-ripetuta"] * 2)

    def test_l_opzione_vale_anche_per_off(self):
        self.assertEqual(find_drops(self.rows(5, "OFF"), "it", 4)[1], [None] * 4 + ["domanda-ripetuta"])

    def test_la_riga_con_errori_non_conta(self):
        bad = make(GUIDE["it"], "Q?", "Si paga in contanti.", category="SICUREZZA")
        entries = [(json.dumps(bad), bad)] + self.rows(1, "SICUREZZA", "Q?")
        self.assertEqual(find_drops(entries, "it", 1)[1], ["non-estrattiva", None])


class LanguageTest(unittest.TestCase):
    def test_lingua_sbagliata(self):
        english = "The museum is open every day and you can buy the tickets at the entrance for a small price."
        italian = "Il museo e' aperto tutti i giorni e i biglietti si comprano all'ingresso con un prezzo molto basso."
        self.assertTrue(wrong_language(english, "it"))
        self.assertFalse(wrong_language(english, "en"))
        self.assertTrue(wrong_language(italian, "en"))
        self.assertFalse(wrong_language(italian, "it"))

    def test_prudente(self):
        self.assertFalse(wrong_language("Louvre", "it"))  # troppo corta
        # nomi e citazioni nell'altra lingua dentro una frase della propria
        self.assertFalse(wrong_language("Il museo espone il quadro Girl with a Pearl Earring of the Dutch master e molto altro.", "it"))
        for lang in ("it", "en"):
            self.assertFalse(wrong_language(refusal(lang), lang))
            self.assertFalse(wrong_language(GUIDE[lang], lang))


class LeakageTest(unittest.TestCase):
    def test_stessa_domanda_o_stesso_contesto(self):
        ev = [make("Contesto del test uno con abbastanza testo.", "Domanda del test?", "Contesto del test uno.", region="canada"),
              make("Contesto del test due.", "Altra domanda?", "Contesto del test due.", region="citta:Lione")]
        train = [make("Altro contesto.", "Altra domanda?", "Altro contesto.", region="citta:Lione"),  # domanda e regione
                 make("Contesto del test due.", "Una domanda diversa?", "Contesto del test due.", region="francia"),  # contesto
                 make("Altro.", "Altra domanda?", "Altro.", region="francia"),  # stessa domanda, regione diversa: no
                 make("Contesto del test uno con abbastanza testo.", "Altra?", "Contesto del test uno.", region="canada"),  # test: no
                 make(it.FALLBACK_CONTEXT, "Mai?", refusal("it"), kind="neg", region="francia")]
        ev.append(make(it.FALLBACK_CONTEXT, "Un'altra?", refusal("it"), kind="neg", region="francia"))
        self.assertEqual(leakage(train, ev), (1, 1))


class CommandLineTest(unittest.TestCase):
    def run_main(self, *args):
        out, code = io.StringIO(), 0
        with contextlib.redirect_stdout(out):
            try:
                audit_sft.sys.argv = ["audit_sft.py", *args]
                audit_sft.main()
            except SystemExit as e:
                code = e.code
        return out.getvalue(), code

    def test_rapporto_pulizia_e_strict(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "sft.it.jsonl", Path(tmp) / "clean.jsonl"
            good = make(GUIDE["it"], "Quando apre?", "Il museo apre alle 9.")
            rows = [good, good, make(GUIDE["it"], "Quanto costa?", "Costa 99 euro."),
                    make(GUIDE["it"], "Chi e' Dante?", refusal("it"), kind="neg", category="OFF", region="spagna"),
                    make("Altro contesto uno.", "Chi e' Dante?", refusal("it"), kind="neg", category="OFF", region="spagna"),
                    make("Altro contesto due.", "Chi e' Dante?", refusal("it"), kind="neg", category="OFF", region="spagna")]
            lines = [json.dumps(r, ensure_ascii=False) for r in rows] + ["{rotto", ""]
            src.write_text("\n".join(lines) + "\n", encoding="utf-8")
            out, code = self.run_main(str(src))
            self.assertEqual(code, 0)  # senza --strict gli errori non cambiano il codice di uscita
            for text in ("lingua it", "riga-duplicata: 1", "non-estrattiva: 1", "malformata: 1", "rifiuti: 50.0%"):
                self.assertIn(text, out)
            self.assertEqual(self.run_main(str(src), "--strict")[1], 1)
            out, _ = self.run_main(str(src), "--out", str(dst))
            kept = dst.read_text(encoding="utf-8").splitlines()
            self.assertEqual(kept, [lines[0], lines[3], lines[4]])  # le righe sono quelle originali, nello stesso ordine
            self.assertIn("scritto", out)
            self.assertIn("domanda-ripetuta", self.run_main(str(src), "--out", str(dst), "--max-question-repeats", "1")[0])
            self.assertEqual(dst.read_text(encoding="utf-8").splitlines(), [lines[0], lines[3]])

    def test_dataset_pulito_strict_ok(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, ev = Path(tmp) / "sft.en.jsonl", Path(tmp) / "eval.jsonl"
            src.write_text(json.dumps(make(GUIDE["en"], QUESTION["en"], "The museum opens at nine.", lang="en")) + "\n",
                           encoding="utf-8")
            ev.write_text(json.dumps(make("Altro.", QUESTION["en"], "Altro.", lang="en")) + "\n", encoding="utf-8")
            out, code = self.run_main(str(src), "--strict", "--eval", str(ev))
            self.assertEqual(code, 0)
            self.assertIn("lingua en", out)
            self.assertIn("errori: nessuno", out)
            self.assertIn("stessa domanda e regione: 1", out)


if __name__ == "__main__":
    unittest.main()
