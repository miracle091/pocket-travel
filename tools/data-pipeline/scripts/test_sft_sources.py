#!/usr/bin/env python3
"""Test della scelta delle fonti dei dataset SFT (generate_sft_dataset.py): titoli Wikipedia da provare, pagine assenti
distinte dagli errori di rete, nuova ricerca delle assenti, elenco delle esclusioni.

Uso: python test_sft_sources.py
"""
import tempfile
import unittest
from pathlib import Path

import generate_sft_dataset as g

REGIONS = [("bahamas", "Bahamas", "The_Bahamas"), ("cina-fujian", "Cina - Fujian", "Fujian")]


class WpTitlesTest(unittest.TestCase):
    def test_articolo_minuscolo_a_meta_titolo(self):
        self.assertEqual(g.wp_candidate_titles("The_Bahamas", "USI_COSTUMI"), ["Culture of the Bahamas"])

    def test_cucina_regionale_col_nome_davanti(self):
        self.assertEqual(g.wp_candidate_titles("Fujian", "CIBO_BEVANDE"), ["Cuisine of Fujian", "Fujian cuisine"])

    def test_parola_del_tema(self):
        self.assertEqual(g.topic_word("Cuisine of the Bahamas", "The_Bahamas"), "cuisine")
        self.assertEqual(g.topic_word("Vatican City cuisine", "Vatican_City"), "cuisine")
        self.assertEqual(g.topic_word("Telecommunications in Italy", "Italy"), "telecommunications")


class SourcesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out, g.OUT = g.OUT, Path(self.tmp.name)
        (g.OUT / "raw").mkdir()

    def tearDown(self):
        g.OUT = self.out
        self.tmp.cleanup()

    def fake_load_page(self, pages, failing=()):
        """load_page con le pagine di [pages] ({(rid, fonte): url}): una pagina assente lascia la cache vuota come
        load_page, una fonte in [failing] fallisce come un errore di rete (nessuna cache)."""
        def load(rid, src, title):
            if (rid, src) in failing:
                return None
            cache, meta = g.raw_cache(rid, src)
            url = pages.get((rid, src), "")
            cache.write_text("testo" if url else "", encoding="utf-8")
            meta.write_text(url, encoding="utf-8")
            return ("testo", url) if url else None
        return load

    def test_assente_diventa_trattino_errore_di_rete_resta_da_cercare(self):
        sources = {}
        load = self.fake_load_page({("bahamas", "it"): "https://it.wikivoyage.org/wiki/Bahamas"},
                                   failing={("cina-fujian", "it")})
        g.resolve_titles(REGIONS, ("it", "wp_cibo"), sources, load)
        self.assertEqual(sources, {("bahamas", "it"): "Bahamas", ("bahamas", "wp_cibo"): "-", ("cina-fujian", "wp_cibo"): "-"})

    def test_titoli_gia_noti_non_si_cercano(self):
        sources = {("bahamas", "it"): "Bahamas"}
        g.resolve_titles(REGIONS[:1], ("it",), sources, lambda *_: self.fail("cercato di nuovo"))

    def test_recheck_toglie_trattini_e_cache_vuote(self):
        sources = {("bahamas", "it"): "-", ("bahamas", "en"): "Bahamas"}
        g.resolve_titles(REGIONS[:1], ("wp_cibo",), sources, self.fake_load_page({}))
        g.forget_missing(REGIONS[:1], ("it", "en", "wp_cibo"), sources)
        self.assertEqual(sources, {("bahamas", "en"): "Bahamas"})
        self.assertFalse(g.raw_cache("bahamas", "wp_cibo")[0].exists())

    def test_elenco_delle_esclusioni(self):
        path = g.OUT / "EXCLUDED.tsv"
        g.write_excluded(path, [("bahamas", "wp_conn", "CONNETTIVITA", "pagina-assente")])
        self.assertEqual(path.read_text(encoding="utf-8").splitlines(),
                         ["regionId\tsource\tcategory\treason", "bahamas\twp_conn\tCONNETTIVITA\tpagina-assente"])


class DumpTextTest(unittest.TestCase):
    def test_titolo_redirect(self):
        pages, redirects = {"Curaçao": "testo"}, {"Curacao": "Curaçao"}
        self.assertEqual(g.dump_text(pages, redirects, "Curacao"), "testo")
        self.assertEqual(g.dump_text(pages, redirects, "curaçao"), "testo")
        self.assertIsNone(g.dump_text(pages, redirects, "Bonaire"))


class DroppedSectionsTest(unittest.TestCase):
    def test_sezione_con_markup_residuo_finisce_tra_le_scartate(self):
        clean, g.app_clean = g.app_clean, lambda raw: raw.strip()  # niente JVM: il testo e' gia' pulito
        try:
            dropped = []
            secs = g.parse_sections("== Sicurezza ==\nZona tranquilla.\n== A tavola ==\n{| tabella |}\n", dropped=dropped)
        finally:
            g.app_clean = clean
        self.assertEqual(secs, [("SICUREZZA", "Zona tranquilla.")])
        self.assertEqual(dropped, ["CIBO_BEVANDE"])


if __name__ == "__main__":
    unittest.main()
