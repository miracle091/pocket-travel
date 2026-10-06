#!/usr/bin/env python3
"""Test di drop_shared_pages (generate_sft.py): una pagina usata da due regioni (sft-sources.tsv: "Saint Martin" per
saint-martin e sint-maarten) entra nel dataset una volta sola, con la prima regione.

Uso: python test_sft_shared_pages.py
"""
import unittest

from generate_sft import drop_shared_pages


class DropSharedPagesTest(unittest.TestCase):
    def test_pagina_gia_usata_tolta_alla_regione_dopo(self):
        regions = [("saint-martin", "Saint-Martin", "Saint Martin", "Saint Martin"),
                   ("sint-maarten", "Sint Maarten", "Saint Martin", "Sint Maarten"),
                   ("russia-volga", "Volga", "Regione del Volga", "Volga Region"),
                   ("russia-volga-vjatka", "Volga-Vjatka", "Regione del Volga", "Volga Region")]
        kept, dropped = drop_shared_pages(regions)
        self.assertEqual(kept, [regions[0], ("sint-maarten", "Sint Maarten", None, "Sint Maarten"), regions[2],
                                ("russia-volga-vjatka", "Volga-Vjatka", None, None)])
        self.assertEqual(dropped, [("sint-maarten", "it"), ("russia-volga-vjatka", "it"), ("russia-volga-vjatka", "en")])

    def test_pagine_assenti_non_contano(self):
        regions = [("a", "A", None, "X"), ("b", "B", None, "Y")]
        self.assertEqual(drop_shared_pages(regions), (regions, []))


if __name__ == "__main__":
    unittest.main()
