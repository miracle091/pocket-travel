#!/usr/bin/env python3
"""Test di wiki_dump.py su piccoli dump XML sintetici (bz2 e multistream) creati in una cartella temporanea:
nessuna rete.

Uso: python test_wiki_dump.py
"""
import bz2
import tempfile
import unittest
from pathlib import Path

import wiki_dump

NS = "http://www.mediawiki.org/xml/export-0.11/"


def page(title, text="", ns=0, redirect=None, namespace=True):
    tag = f' xmlns="{NS}"' if namespace else ""
    red = f'<redirect title="{redirect}" />' if redirect else ""
    return (f"<page{tag}><title>{title}</title><ns>{ns}</ns>{red}"
            f"<revision><text>{text}</text></revision></page>")


def dump(*pages):
    return f"<mediawiki>{''.join(pages)}</mediawiki>".encode("utf-8")


class NormTitleTest(unittest.TestCase):
    def test_underscore_spazi_e_maiuscola_iniziale(self):
        self.assertEqual("Emilia Romagna", wiki_dump.norm_title("emilia_Romagna"))

    def test_spazi_ai_bordi_e_stringa_vuota(self):
        self.assertEqual("Roma", wiki_dump.norm_title("  roma "))
        self.assertEqual("", wiki_dump.norm_title(""))


class IterPagesTest(unittest.TestCase):
    def _iter(self, xml):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "dump.xml.bz2"
            path.write_bytes(bz2.compress(xml))
            return list(wiki_dump.iter_pages(path))

    def test_solo_namespace_zero_con_redirect_normalizzato(self):
        xml = dump(page("Roma", "testo &amp; altro", namespace=True),
                   page("Discussione:Roma", "x", ns=1),
                   page("Roma Capitale", redirect="roma_(citta)"))
        self.assertEqual([("Roma", "testo & altro", None), ("Roma Capitale", "", "Roma (citta)")], self._iter(xml))

    def test_senza_namespace_xml(self):
        self.assertEqual([("Rimini", "t", None)], self._iter(dump(page("Rimini", "t", namespace=False))))


class LoadMultistreamTest(unittest.TestCase):
    def _build(self, directory, blocks):
        """blocks: lista di liste di pagine XML; un flusso bz2 per blocco. Indice con offset:pageid:titolo."""
        xml, index, offset = b"", [], 0
        for n, pages in enumerate(blocks):
            body = "".join(pages).encode("utf-8")
            chunk = bz2.compress(body)
            titles = [t for t in _titles(pages)]
            index += [f"{offset}:{n * 10 + i}:{t}" for i, t in enumerate(titles)]
            xml += chunk
            offset += len(chunk)
        xml_path, index_path = Path(directory) / "dump.xml.bz2", Path(directory) / "index.txt.bz2"
        xml_path.write_bytes(xml)
        index_path.write_bytes(bz2.compress("\n".join(index).encode("utf-8")))
        return xml_path, index_path

    def test_trova_le_pagine_richieste_in_blocchi_diversi(self):
        with tempfile.TemporaryDirectory() as d:
            xml, idx = self._build(d, [[page("Roma", "r"), page("Milano", "m")], [page("Torino", "t")]])
            result = wiki_dump.load_multistream(xml, idx, ["torino", "Roma", "Assente"])
        self.assertEqual({"Torino": "t", "Roma": "r"}, result)

    def test_segue_un_livello_di_redirect(self):
        with tempfile.TemporaryDirectory() as d:
            xml, idx = self._build(d, [[page("Alias", redirect="Destinazione")], [page("Destinazione", "ok")]])
            result = wiki_dump.load_multistream(xml, idx, ["Alias"])
        self.assertEqual({"Alias": "ok"}, result)

    def test_pagina_vuota_non_e_restituita(self):
        with tempfile.TemporaryDirectory() as d:
            xml, idx = self._build(d, [[page("Vuota", "")]])
            self.assertEqual({}, wiki_dump.load_multistream(xml, idx, ["Vuota"]))


def _titles(pages):
    import re
    return [re.search(r"<title>(.*?)</title>", p).group(1) for p in pages]


if __name__ == "__main__":
    unittest.main()
