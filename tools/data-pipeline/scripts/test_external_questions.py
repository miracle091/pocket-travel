#!/usr/bin/env python3
"""Test di external_questions.py: filtri di forma, di servizio e di testo, finestre di download e cache, con dati finti
e senza rete. Prova: python test_external_questions.py (dalla radice del repository, come in CI)."""
import gzip
import io
import json
import sys
import tempfile
import unittest
import urllib.error
from collections import Counter
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import external_questions as eq

eq._UA = {"User-Agent": "pocket-travel-sft/test"}  # niente import del generatore per leggere UA

KEYWORDS_IT = {"ALLOGGIO": ["hotel", "albergh", "camere"], "SALUTE": ["farmac"], "TRASPORTI": ["treno", "autobus"],
               "ACQUISTI": ["carta", "prezz"]}
KEYWORDS_EN = {"ALLOGGIO": ["hotel", "rooms"], "SALUTE": ["pharmac"], "TRASPORTI": ["train", " bus"],
               "ACQUISTI": ["card", "pay", "price"]}
STEMS_IT = ("viagg", "citta'")
STEMS_EN = ("travel", "city")


class Rfile(io.BytesIO):
    """Risposta finta con intestazioni, come urlopen."""
    headers = {}


class FormTest(unittest.TestCase):
    def test_accepts_well_formed_questions(self):
        for q in ["A che ora è il check-in in hotel?", "Accettate gli animali domestici?", "L'hotel dispone di parcheggio?",
                  "C'è una piscina nella struttura?"]:
            self.assertIsNone(eq.form_reject(q, "it"), q)
        for q in ["What time is check-in?", "Do you accept pets?", "Can I bring my dog to the hotel?",
                  "What's the cancellation policy?"]:
            self.assertIsNone(eq.form_reject(q, "en"), q)

    def test_rejects_malformed_questions(self):
        cases = {
            "it": {"COME FACCIO A PRENOTARE?": "maiuscole", "Accettate la carta Visa?": "nome proprio",
                   "Quanto dista dall'stazione del treno?": "traduzione o forma",
                   "Il albergo accetta i cani?": "traduzione o forma", "Che ore?": "lunghezza",
                   "Quanto costa il parcheggio a 15 euro?": "caratteri", "Visita il sito www.hotel.it per info?": "caratteri",
                   "Accettate gli animali? Quanto costa?": "punteggiatura", "Do you accept pets in the hotel?": "inizio",
                   "Accettate the animali domestici?": "lingua mista", "Il parcheggio dell'hotel è di?": "traduzione o forma",
                   "Posso lasciare lasciare i bagagli in hotel?": "parola ripetuta",
                   "Yile Hotel accetta carte di credito?": "nome proprio",
                   "accettate gli animali domestici in hotel?": "maiuscola iniziale",
                   "Quanto costa il check-in ?": "punteggiatura"},
            "en": {"WHAT TIME IS CHECK-IN?": "maiuscole", "Do you accept pets at the Hilton Garden Inn?": "nome proprio",
                   "Do you accept pets at a hotel in Rome (Italy)?": "caratteri", "Is there a airport shuttle?": "traduzione o forma",
                   "Do you accept gli animali in hotel?": "lingua mista", "Hotel check-in time please?": "inizio",
                   "Is there parking for 20 euro per night?": "caratteri", "Is breakfast included in the?": "traduzione o forma"},
        }
        for lang, qs in cases.items():
            for q, why in qs.items():
                self.assertEqual(eq.form_reject(q, lang), why, q)


class ServiceTest(unittest.TestCase):
    def test_service_questions(self):
        self.assertIsNone(eq.service_reject("A che ora è il check-in?", "it"))
        self.assertIsNone(eq.service_reject("Accettate gli animali?", "it"))
        self.assertIsNone(eq.service_reject("C'è un parcheggio in hotel?", "it"))
        self.assertIsNone(eq.service_reject("Is breakfast included?", "en"))
        self.assertIsNone(eq.service_reject("Do you accept credit cards?", "en"))

    def test_not_services(self):
        self.assertEqual(eq.service_reject("Dove si trova il parcheggio più vicino?", "it"), "nessun servizio")
        self.assertEqual(eq.service_reject("Quali sono i migliori hotel con piscina?", "it"), "ricerca di un posto")
        self.assertEqual(eq.service_reject("Which hotels have a pool near the center?", "en"), "ricerca di un posto")
        self.assertEqual(eq.service_reject("Is the deposit refundable for a wedding?", "en"), "altro settore")
        self.assertEqual(eq.service_reject("Il sito dispone di parcheggio presso rosa dei venti?", "it"), "nome dell'esercizio")
        self.assertEqual(eq.service_reject("Il baia domizia villaggio camping dispone di parcheggio?", "it"),
                         "nome dell'esercizio")
        self.assertIsNone(eq.service_reject("A che ora è il check-in presso questo hotel?", "it"))


class TextTest(unittest.TestCase):
    def test_normalize(self):
        self.assertEqual(eq.normalize("Qual è la capitale d’Italia?"), "qual e la capitale d italia")
        self.assertEqual(eq.normalize("Qual e' la capitale d'Italia?"), "qual e la capitale d italia")

    def test_skeleton_ignores_lodging_noun(self):
        self.assertEqual(eq.skeleton("A che ora è il check-in presso questa villa?", "it"),
                         eq.skeleton("A che ora è il check-in presso questo cottage?", "it"))

    def test_flatten(self):
        self.assertEqual(sorted(eq._flatten({"a": ["x", ("y",)], "b": {"c": {"z"}}})), ["x", "y", "z"])

    def test_topic_matcher_start_of_word_and_exempt(self):
        match = eq.topic_matcher(KEYWORDS_IT, STEMS_IT)
        self.assertTrue(match("Dov'è la farmacia?"))
        self.assertFalse(match("Accettate gli animali?"))
        self.assertTrue(match("Accettate la carta di credito?"))
        self.assertFalse(eq.topic_matcher(KEYWORDS_IT, STEMS_IT, {"carta"})("Accettate la carta di credito?"))

    def test_select_faq_filters(self):
        rows = [("a.it", "Accettate gli animali?"), ("b.it", "accettate gli animali?".capitalize()),
                ("c.it", "Dov'è la farmacia più vicina all'hotel?"), ("d.it", "Accettate la carta di credito?"),
                ("e.it", "A che ora è il check-in in hotel?"), ("f.it", "Che treno prendo per il check-in?")]
        stats = Counter()
        out = eq.select_faq(rows, "it", KEYWORDS_IT, STEMS_IT, {eq.normalize("A che ora è il check-in in hotel?")}, stats)
        self.assertEqual(sorted(out), ["Accettate gli animali?", "Accettate gli animali?", "Accettate la carta di credito?"])
        self.assertEqual(stats["uguale al test"], 1)
        self.assertEqual(stats["parole chiave delle guide"], 2)
        self.assertEqual(stats["tenute"], 3)

    def test_select_faq_caps_per_site_and_skeleton(self):
        rows = [("a.it", f"Accettate gli animali {w}?") for w in ("piccoli", "grandi", "pelosi", "docili", "buoni")]
        rows += [("b.it", f"A che ora è il check-in presso {n}?") for n in ("questa villa", "questo hotel", "questa suite")]
        stats = Counter()
        out = eq.select_faq(rows, "it", KEYWORDS_IT, STEMS_IT, set(), stats)
        self.assertEqual(sum("animali" in q for q in out), eq.MAX_PER_SITE)
        self.assertEqual(sum("check-in" in q for q in out), eq.SKELETON_MAX)
        self.assertEqual(stats["troppe dal sito"], 2)
        self.assertEqual(stats["stesso schema"], 1)

    def test_select_faq_is_deterministic(self):
        rows = [(f"s{i}.it", f"Accettate gli animali di tipo {'abcdefghij'[i]}?") for i in range(10)]
        a = eq.select_faq(rows, "it", {}, (), set())
        b = eq.select_faq(list(reversed(rows)), "it", {}, (), set())
        self.assertEqual(a, b)

    def test_common_words_drops_site_specific_names(self):
        rows = [(f"https://www.s{i}.it", "Accettate gli animali?") for i in range(eq.MIN_SITES)]
        rows += [("https://www.x.it", "Accettate gli animali al baiadomizia?")] * 5
        common = eq.common_words(rows)
        self.assertIn("animali", common)
        self.assertNotIn("baiadomizia", common)

    def test_build_faq_cache_counts(self):
        rows = [(f"https://www.s{i}.it", q) for i in range(eq.MIN_SITES) for q in ("Accettate gli animali?", "Accettate gli animali?")]
        rows += [("https://x.it", "COME FACCIO A PRENOTARE ORA?"), ("https://x.it", "Dove si trova la stazione?")]
        out, stats = eq.build_faq_cache(rows, "it")
        self.assertEqual([q for _, q in out], ["Accettate gli animali?"])
        self.assertEqual(stats["righe"], len(rows))
        self.assertEqual(stats["doppione"], 2 * eq.MIN_SITES - 1)
        self.assertEqual(stats["forma: maiuscole"], 1)
        self.assertEqual(stats["servizio: nessun servizio"], 1)
        self.assertEqual(stats["dopo i doppioni"], 1)


class StreamTest(unittest.TestCase):
    DATA = b"".join(b'{"n": %d}\n' % i for i in range(300))

    def fake_open(self, fail_once=False):
        calls = []
        failed = []

        def fake(url, headers=None, method="GET"):
            rng = (headers or {}).get("Range")
            start, end = map(int, rng[len("bytes="):].split("-"))
            calls.append((start, end))
            body = self.DATA[start:end + 1]
            if fail_once and not failed and len(body) > 1000:
                failed.append(1)

                class Broken(io.BytesIO):
                    def read(s, n=-1):
                        if s.tell() >= 500:
                            raise urllib.error.URLError("caduta")
                        return super().read(min(n, 500) if n > 0 else 500)
                return Broken(body)
            return io.BytesIO(body)
        return fake, calls

    def test_windows(self):
        self.assertEqual(eq.windows(1000, 5, 100), [(0, 1000)])
        w = eq.windows(10_000, 5, 100)
        self.assertEqual(len(w), 5)
        self.assertEqual((w[0][0], w[-1][1]), (0, 10_000))
        self.assertTrue(all(b - a == 100 for a, b in w))

    def test_stream_lines_window_skips_cut_first_line_and_ends_on_line_boundary(self):
        fake, _ = self.fake_open()
        with mock.patch.object(eq, "_open", fake), mock.patch.object(eq, "LINE_SLACK", 200):
            lines = list(eq.stream_lines("u", 1000, 1500, len(self.DATA)))
        nums = [json.loads(l)["n"] for l in lines]
        self.assertEqual(nums, list(range(nums[0], nums[-1] + 1)))
        self.assertGreater(nums[0], 0)
        first_complete_start = self.DATA.index(b"\n", 999) + 1
        self.assertEqual(lines[0], self.DATA[first_complete_start:self.DATA.index(b"\n", first_complete_start)])
        self.assertTrue(all(l.endswith(b"}") for l in lines))

    def test_stream_lines_whole_file_and_resume_after_error(self):
        fake, calls = self.fake_open(fail_once=True)
        with mock.patch.object(eq, "_open", fake), mock.patch.object(eq.time, "sleep"):
            lines = list(eq.stream_lines("u", 0, len(self.DATA), len(self.DATA)))
        self.assertEqual([json.loads(l)["n"] for l in lines], list(range(300)))
        self.assertGreater(len(calls), 1)  # ripreso dal byte giunto, non da capo
        self.assertGreater(calls[1][0], 0)

    def test_webfaq_rows_extracts_topic_only_and_reuses_the_file(self):
        rows = [{"origin": "http://a.it", "question": "Accettate gli animali?", "answer": "No", "topic": eq.WEBFAQ_TOPIC},
                {"origin": "http://b.it", "question": "Come si cucina?", "answer": "Così", "topic": "Food"},
                {"origin": "http://c.it", "question": "A che ora è il check-in?", "answer": "Alle 14", "topic": eq.WEBFAQ_TOPIC}]
        data = b"".join(json.dumps(r).encode() + b"\n" for r in rows)

        def fake(url, headers=None, method="GET"):
            if method == "HEAD":
                r = Rfile()
                r.headers = {"Content-Length": str(len(data))}
                return r
            start, end = map(int, headers["Range"][len("bytes="):].split("-"))
            return io.BytesIO(data[start:end + 1])
        with tempfile.TemporaryDirectory() as d, mock.patch.object(eq, "_open", fake), \
                mock.patch.object(eq, "require_revision") as req:
            got = eq.webfaq_rows("it", d)
            self.assertEqual(got, [("http://a.it", "Accettate gli animali?"), ("http://c.it", "A che ora è il check-in?")])
            self.assertNotIn("No", "".join(p.read_text(encoding="utf-8") for p in Path(d).iterdir()))  # niente risposte
            with mock.patch.object(eq, "_open", side_effect=AssertionError("rete")):
                self.assertEqual(eq.webfaq_rows("it", d), got)
            req.assert_called_once_with(eq.WEBFAQ, eq.WEBFAQ_REVISION)


class CacheTest(unittest.TestCase):
    def test_load_faq_refusals_writes_cache_once(self):
        rows = [(f"https://www.s{i}.it", "Accettate gli animali?") for i in range(eq.MIN_SITES)]
        rows += [(f"https://www.s{i}.it", "Accettate la carta di credito in hotel?") for i in range(eq.MIN_SITES)]
        with tempfile.TemporaryDirectory() as d:
            with mock.patch.object(eq, "webfaq_rows", return_value=rows) as wr:
                stats = Counter()
                out = eq.load_faq_refusals(d, "it", d, KEYWORDS_IT, STEMS_IT, set(), stats)
                self.assertEqual(sorted(out), ["Accettate gli animali?", "Accettate la carta di credito in hotel?"])
                self.assertEqual(stats["cache: righe"], 2 * eq.MIN_SITES)
                self.assertEqual(stats["tenute"], 2)
                eq.load_faq_refusals(d, "it", d, KEYWORDS_IT, STEMS_IT, set())
                wr.assert_called_once()  # la seconda volta basta la cache
            # un test esteso cambiato si applica anche a una cache vecchia
            out = eq.load_faq_refusals(d, "it", d, KEYWORDS_IT, STEMS_IT, {eq.normalize("Accettate gli animali?")})
            self.assertEqual(out, ["Accettate la carta di credito in hotel?"])

    def test_mkqa_reject_and_question(self):
        self.assertIsNone(eq.mkqa_reject("chi ha scritto la divina commedia", "it"))
        self.assertIsNone(eq.mkqa_reject("who wrote the divine comedy", "en"))
        self.assertEqual(eq.mkqa_reject("la politica è l'assegnazione dei valori nella società", "it"), "senza interrogativo")
        self.assertEqual(eq.mkqa_reject('chi canta "summer breeze" (la brezza estiva)', "it"), "caratteri")
        self.assertEqual(eq.mkqa_reject("who", "en"), "lunghezza")
        self.assertEqual(eq.mkqa_question("chi  ha scritto la divina commedia"), "Chi ha scritto la divina commedia?")

    def test_load_mkqa_off_topic(self):
        def row(it, en):
            return json.dumps({"queries": {"it": it, "en": en, "de": "x"}, "answers": {}, "example_id": 1})
        lines = [row("chi ha scritto la divina commedia", "who wrote the divine comedy"),
                 row("chi ha scritto la divina commedia", "who wrote the divine comedy"),  # doppione
                 row("qual è la capitale della francia", "what is the capital of france"),  # uguale al test
                 row("quale treno va a roma", "which train goes to rome"),  # parole chiave
                 row("chi canta qualcosa", None),
                 row("la luna è un satellite", "the moon is a satellite")]
        with tempfile.TemporaryDirectory() as d:
            gz = Path(d) / "mkqa.jsonl.gz"
            with gzip.open(gz, "wt", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")
            guide = mock.patch.object(eq, "_guide_filters", side_effect=lambda l: (KEYWORDS_IT, STEMS_IT) if l == "it"
                                      else (KEYWORDS_EN, STEMS_EN))
            with mock.patch.object(eq, "mkqa_file", return_value=gz), guide:
                stats = Counter()
                got = eq.load_mkqa_off_topic(Path(d) / "raw", d, {eq.normalize("Qual è la capitale della Francia?")}, stats)
            self.assertEqual(got, [("Chi ha scritto la divina commedia?", "Who wrote the divine comedy?")])
            self.assertEqual(stats["cache: righe"], 6)
            self.assertEqual(stats["uguale al test"], 1)
            self.assertEqual(stats["parole chiave delle guide"], 1)
            self.assertTrue((Path(d) / "raw" / eq.MKQA_CACHE).exists())

    def test_mkqa_file_checks_sha256(self):
        with tempfile.TemporaryDirectory() as d, mock.patch.object(eq, "require_revision"), \
                mock.patch.object(eq, "_open", return_value=io.BytesIO(b"altro contenuto")):
            with self.assertRaises(RuntimeError):
                eq.mkqa_file(d)
            self.assertFalse((Path(d) / "mkqa.jsonl.gz").exists())  # niente file se la verifica fallisce
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "mkqa.jsonl.gz").write_bytes(b"file gia' presente ma diverso")
            with mock.patch.object(eq, "_open", side_effect=AssertionError("rete")):
                with self.assertRaises(RuntimeError):
                    eq.mkqa_file(d)
            self.assertTrue((Path(d) / "mkqa.jsonl.gz").exists())  # non si cancella da solo

    def test_require_revision(self):
        reply = io.BytesIO(json.dumps({"sha": "abc1234"}).encode())
        with mock.patch.object(eq, "_open", return_value=reply):
            with self.assertRaises(RuntimeError):
                eq.require_revision("x/y", "def5678")
        with mock.patch.object(eq, "_open", return_value=io.BytesIO(json.dumps({"sha": "abc1234"}).encode())):
            eq.require_revision("x/y", "abc1234")

    def test_attribution_is_in_the_dataset_language(self):
        for lang, rows in eq.ATTRIBUTION.items():
            for row in rows:
                self.assertEqual(len(row), 4)
                self.assertEqual(row[0], "-")
                self.assertTrue(row[2].startswith("https://"))
        self.assertIn("domande", eq.ATTRIBUTION["it"][0][1])
        self.assertIn("questions", eq.ATTRIBUTION["en"][0][1])
        self.assertEqual({r[3] for rows in eq.ATTRIBUTION.values() for r in rows}, {"CC BY 4.0", "CC BY 3.0"})


class EvalSetTest(unittest.TestCase):
    def test_eval_questions_cover_the_extended_test(self):
        try:
            qs = eq.eval_questions()
        except Exception as e:  # moduli pesanti non disponibili in questo ambiente
            self.skipTest(f"generate_eval_set non importabile: {e}")
        for q in ["Qual e' la capitale della Francia?", "What is the capital of Spain?",
                  "Which kinds of accommodation exist in {r}?", "Fra quanto arriva un autobus?"]:
            self.assertIn(eq.normalize(q), qs, q)
        self.assertGreater(len(qs), 100)


if __name__ == "__main__":
    unittest.main()
