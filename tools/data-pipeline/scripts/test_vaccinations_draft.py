#!/usr/bin/env python3
"""Test di vaccinations_draft.py: estrazione dei blocchi dal campo "health" di Travel.gc.ca, parti della febbre gialla,
cache su disco e main() con la cache gia' piena. Nessuna rete (urlopen e' sostituito con unittest.mock).

Uso: python test_vaccinations_draft.py
"""
import io
import json
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import vaccinations_draft as vd

YF_BLOCK = ("Risk\nThere is a risk of yellow fever.\nCountry Entry Requirement*\n"
            "Proof of vaccination is required.\nRecommendation\nVaccination is recommended.")


def details(title, body):
    return f"<details><summary>{title}</summary><p>{body}</p></details>"


class TextOfTest(unittest.TestCase):
    def test_tag_entita_e_spazi(self):
        self.assertEqual("Uno\nDue & tre", vd.text_of("<p>Uno</p><p>Due&nbsp;&amp;   tre</p>"))

    def test_br_e_liste_diventano_righe(self):
        self.assertEqual("a\nb\nc", vd.text_of("<ul><li>a</li><li>b</li></ul><p>c"))

    def test_righe_vuote_multiple_collassate(self):
        self.assertEqual("a\nb", vd.text_of("a<br><br>\n\n<br>b"))


class BlocksOfTest(unittest.TestCase):
    def test_titolo_e_corpo_per_ogni_details(self):
        html = details("<strong>Polio</strong>", "Category: <b>A</b>") + "\n" + details("Hepatitis A", "Recommended")
        self.assertEqual({"Polio": "Category: A", "Hepatitis A": "Recommended"}, vd.blocks_of(html))

    def test_details_con_attributi_e_a_capo(self):
        html = '<details class="x">\n <summary id="s">Typhoid</summary>\n<p>Si</p></details>'
        self.assertEqual({"Typhoid": "Si"}, vd.blocks_of(html))

    def test_senza_details(self):
        self.assertEqual({}, vd.blocks_of("<p>niente</p>"))


class YfPartsTest(unittest.TestCase):
    def test_tre_sezioni(self):
        self.assertEqual({"risk": "There is a risk of yellow fever.",
                          "entry": "Proof of vaccination is required.",
                          "recommendation": "Vaccination is recommended."}, vd.yf_parts(YF_BLOCK))

    def test_sezioni_mancanti_restano_vuote(self):
        parts = vd.yf_parts("Risk\nNessun rischio.")
        self.assertEqual({"risk": "Nessun rischio.", "entry": "", "recommendation": ""}, parts)

    def test_blocco_senza_intestazioni(self):
        self.assertEqual({"risk": "", "entry": "", "recommendation": ""}, vd.yf_parts("testo libero"))


class FetchTest(unittest.TestCase):
    def test_usa_la_cache_senza_rete(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "a.json").write_text("{}", encoding="utf-8")
            with mock.patch("vaccinations_draft.urllib.request.urlopen") as urlopen:
                self.assertEqual("{}", vd.fetch("a.json", Path(d)))
        urlopen.assert_not_called()

    def test_scarica_e_salva_in_cache(self):
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = b'{"ok": true}'
        with tempfile.TemporaryDirectory() as d, \
                mock.patch("vaccinations_draft.urllib.request.urlopen", return_value=response), \
                mock.patch("vaccinations_draft.time.sleep") as sleep:
            self.assertEqual('{"ok": true}', vd.fetch("b.json", Path(d)))
            self.assertEqual('{"ok": true}', (Path(d) / "b.json").read_text(encoding="utf-8"))
        sleep.assert_called_once_with(vd.DELAY_SECONDS)

    def test_404_salva_file_vuoto_e_restituisce_none(self):
        error = urllib.error.HTTPError("http://x", 404, "Not Found", {}, None)
        with tempfile.TemporaryDirectory() as d, \
                mock.patch("vaccinations_draft.urllib.request.urlopen", side_effect=error):
            self.assertIsNone(vd.fetch("c.json", Path(d)))
            self.assertEqual("", (Path(d) / "c.json").read_text(encoding="utf-8"))

    def test_altri_errori_http_rilanciati(self):
        error = urllib.error.HTTPError("http://x", 500, "Boom", {}, None)
        with tempfile.TemporaryDirectory() as d, \
                mock.patch("vaccinations_draft.urllib.request.urlopen", side_effect=error):
            with self.assertRaises(urllib.error.HTTPError):
                vd.fetch("d.json", Path(d))


class MainTest(unittest.TestCase):
    def test_bozze_da_una_cache_gia_piena(self):
        health = (details("Yellow Fever - Country Entry Requirements", YF_BLOCK.replace("\n", "<br>"))
                  + details("Polio", "<p>Category 1</p><p>Altro</p>")
                  + details("Hepatitis A", "<p>Consigliato</p>")
                  + details("Meningitis", "<p>Si</p>"))
        with tempfile.TemporaryDirectory() as d:
            cache, out = Path(d) / "cache", Path(d) / "out"
            cache.mkdir()
            (cache / "index-alpha-eng.json").write_text(
                json.dumps({"data": {"BR": {}, "KE": {}, "US-CA": {}, "XX": {}}}), encoding="utf-8")
            (cache / "cta-cap-br.json").write_text(json.dumps(
                {"data": {"eng": {"name": "Brazil", "health": health}, "date-published": {"date": "2026-01-02"}}}),
                encoding="utf-8")
            (cache / "cta-cap-ke.json").write_text(json.dumps(
                {"data": {"eng": {"name": "Kenya", "health": None}, "date-published": "2026"}}), encoding="utf-8")
            (cache / "cta-cap-xx.json").write_text("", encoding="utf-8")  # 404 in cache: paese saltato

            with mock.patch.object(vd.sys, "argv", ["x", str(cache), str(out)]), \
                    mock.patch("vaccinations_draft.urllib.request.urlopen") as urlopen, \
                    redirect_stdout(io.StringIO()) as stdout:
                vd.main()

            urlopen.assert_not_called()
            drafts = json.loads((out / "vaccinations-draft.json").read_text(encoding="utf-8"))
            yf = (out / "yf-entry.draft.tsv").read_text(encoding="utf-8").splitlines()
            polio = (out / "polio-status.draft.tsv").read_text(encoding="utf-8").splitlines()
            rec = (out / "recommended.draft.tsv").read_text(encoding="utf-8").splitlines()

        self.assertEqual("2 paesi scaricati", stdout.getvalue().strip())
        self.assertEqual({"br", "ke"}, set(drafts))
        self.assertEqual("2026-01-02", drafts["br"]["published"])
        self.assertIsNone(drafts["ke"]["published"])
        self.assertEqual({"HEPA", "MENACWY"}, set(drafts["br"]["vaccines"]))
        self.assertEqual("There is a risk of yellow fever.", drafts["br"]["yf"]["risk"])
        self.assertIsNone(drafts["ke"]["yf"])
        self.assertEqual(["iso2\trisk\tentry",
                          "br\tThere is a risk of yellow fever.\tProof of vaccination is required.",
                          "ke\tNO-BLOCK\t"], yf)
        self.assertEqual(["iso2\tpolio-first-paragraph", "br\tCategory 1", "ke\t"], polio)
        self.assertEqual(["iso2\tvaccine\tfirst-lines", "br\tHEPA\tConsigliato", "br\tMENACWY\tSi"], rec)


if __name__ == "__main__":
    unittest.main()
