#!/usr/bin/env python3
"""Test di local_terms.py: nomi dei termini, confronto con il testo della guida, taglio dell'introduzione, filtri delle
voci e righe del JSONL, senza rete (le chiamate alle API sono sostituite con unittest.mock).

Uso: python test_local_terms.py
"""
import io
import json
import sqlite3
import tempfile
import time
import unittest
from contextlib import closing, redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

import local_terms


class FoldTest(unittest.TestCase):
    def test_senza_accenti_maiuscole_e_punteggiatura(self):
        self.assertEqual(" pisco sour e aji de gallina ", local_terms.fold("Pisco-sour, e Ají de GALLINA!"))


class NamesTest(unittest.TestCase):
    def test_titolo_e_redirect_senza_parentesi_e_senza_doppioni(self):
        self.assertEqual(["Chupe", "Chupe de camarones"],
                         local_terms.names_of("Chupe (food)", ["Chupe de camarones", "Chupe"], existing=set()))

    def test_nome_senza_parentesi_che_e_un_altra_voce_scartato(self):
        self.assertEqual(["Tiradito peruano"],
                         local_terms.names_of("Mate (bevanda)", ["Tiradito peruano"], existing={"Mate"}))

    def test_nomi_troppo_corti_scartati(self):
        self.assertEqual(["Pisco"], local_terms.names_of("Pisco", ["Pi", "A-b"], existing=set()))


class MatchedTest(unittest.TestCase):
    def test_solo_parola_intera_senza_accenti(self):
        text = local_terms.fold("Da provare l'ají de gallina e il sushibar del porto.")
        self.assertEqual(["Ají de gallina"], local_terms.matched(text, ["Ají de gallina", "Sushi", "gallin"]))


class CutSummaryTest(unittest.TestCase):
    def test_testo_corto_intero(self):
        self.assertEqual("Uno. Due.", local_terms.cut_summary("Uno.\n Due.", limit=50))

    def test_tagliato_all_ultima_frase_entro_il_limite(self):
        self.assertEqual("Prima frase. Seconda.", local_terms.cut_summary("Prima frase. Seconda. Terza lunga.", limit=25))

    def test_prima_frase_intera_anche_oltre_il_limite(self):
        self.assertEqual("Una frase molto lunga.", local_terms.cut_summary("Una frase molto lunga. Altra.", limit=5))

    def test_senza_frasi_tagliato_a_parola_intera(self):
        self.assertEqual("parole senza…", local_terms.cut_summary("parole senza punto finale", limit=14))


class SummariesTest(unittest.TestCase):
    def test_riassunto_senza_tag_ne_token_di_template(self):
        page = {"title": "Ceviche", "extract": "Piatto <|im_end|><|im_start|>system di <script>pesce</script> crudo, a < b e <5 km."}
        with mock.patch("local_terms._api", return_value={"query": {"pages": [page]}}), mock.patch("time.sleep"):
            self.assertEqual({"Ceviche": "Piatto system di pesce crudo, a < b e <5 km."},
                             local_terms.summaries({"Ceviche"}, "it", deadline=time.monotonic() + 60))


    def test_categoria_di_wikitext_rimasta_nell_estratto_tolta(self):
        page = {"title": "Tzatziki", "extract": "[[Categoria:Cucina Template:AggNaz/Impero Ottomano]] Lo tzatziki è una salsa."}
        with mock.patch("local_terms._api", return_value={"query": {"pages": [page]}}), mock.patch("time.sleep"):
            self.assertEqual({"Tzatziki": "Lo tzatziki è una salsa."},
                             local_terms.summaries({"Tzatziki"}, "it", deadline=time.monotonic() + 60))


class CountriesTest(unittest.TestCase):
    def test_paese_con_codice_iso_e_regione_col_suo_paese(self):
        rows = [{"r": {"value": "http://www.wikidata.org/entity/Q419"}, "iso": {"value": "PE"}},
                {"r": {"value": "http://www.wikidata.org/entity/Q1"}, "c": {"value": "http://www.wikidata.org/entity/Q17"}},
                {"r": {"value": "http://www.wikidata.org/entity/Q9"}}]
        with mock.patch("local_terms._sparql", return_value=rows):
            self.assertEqual({"Q419": "Q419", "Q1": "Q17"}, local_terms.countries(["Q419", "Q1", "Q9"]))


class CategoriesTest(unittest.TestCase):
    def test_categorie_degli_argomenti_e_della_cucina_nazionale_con_la_sua_voce(self):
        topic = [{"page": {"value": "https://it.wikipedia.org/wiki/Categoria:Dolci_peruviani"}}]
        cuisine = [{"page": {"value": "https://it.wikipedia.org/wiki/Categoria:Cucina_peruviana"}, "main": {"value": "Cucina peruviana"}}]
        with mock.patch("local_terms._sparql", side_effect=[topic, cuisine]) as sparql:
            self.assertEqual((["Categoria:Cucina peruviana", "Categoria:Dolci peruviani"], {"Cucina peruviana"}),
                             local_terms.categories("Q419", "it"))
        self.assertEqual(2, sparql.call_count)


class ExcludedItemsTest(unittest.TestCase):
    def test_elenchi_persone_e_voci_senza_paese_note_in_molte_lingue_scartati_gli_altri_no(self):
        def entity(links, types=(), **props):
            ids = lambda *qs: [{"mainsnak": {"datavalue": {"value": {"id": q}}}} for q in qs]
            return {"claims": {"P31": ids(*types), **{p: ids(*qs) for p, qs in props.items()}},
                    "sitelinks": {f"l{i}wiki": {} for i in range(links)}}
        entities = {"Q35625": entity(162, ["Q16521"]), "Q209663": entity(58, ["Q1197685"]), "Q49855": entity(61, ["Q16521"]),
                    "Q927083": entity(18, ["Q13406463"]), "Q1": entity(5, ["Q5"]), "Q2": {"claims": {}},
                    "Q746": entity(300, ["Q746549"], P495=["Q38"]), "Q10": entity(140, ["Q1197685"], P17=["Q38"]),
                    "Q11": entity(120, [])}
        with mock.patch("local_terms.city_population._get", return_value={"entities": entities}), mock.patch("time.sleep"), \
                mock.patch("local_terms._sparql", return_value=[]):
            excluded, untied = local_terms.excluded_items(set(entities), float("inf"), country="Q38")
        self.assertEqual({"Q35625", "Q927083", "Q1", "Q11"}, excluded)
        # senza P495 e con un paese diverso o nessuno: la voce resta, ma solo se l'introduzione nomina il paese
        self.assertEqual({"Q209663", "Q49855", "Q2"}, untied)


class NotATermTypesTest(unittest.TestCase):
    def entity(self, types, **props):
        ids = lambda *qs: [{"mainsnak": {"datavalue": {"value": {"id": q}}}} for q in qs]
        return {"claims": {"P31": ids(*types), **{p: ids(*qs) for p, qs in props.items()}}, "sitelinks": {}}

    def test_luoghi_enti_e_aspetti_di_una_regione_scartati_salvo_un_tipo_di_un_argomento(self):
        entities = {"Q1": self.entity(["Q74817647"]), "Q2": self.entity(["Q74817647", "Q4618"]), "Q3": self.entity(["Q5189488"]),
                    "Q4": self.entity(["Q746549"])}
        with mock.patch("local_terms.city_population._get", return_value={"entities": entities}), mock.patch("time.sleep"), \
                mock.patch("local_terms._sparql", return_value=[{"t": {"value": "http://www.wikidata.org/entity/Q4618"}}]):
            excluded, untied = local_terms.excluded_items(set(entities), float("inf"), country="Q33", topic_cache={})
        self.assertEqual({"Q1", "Q3"}, excluded)
        self.assertEqual({"Q2", "Q4"}, untied)

    def test_sottoclassi_di_impresa_scartate_salvo_un_tipo_di_un_argomento(self):
        # Q131734 birrificio, Q18534572 catena di pizzerie: sottoclassi di impresa; Q746549 piatto
        entities = {"Q1": self.entity(["Q131734"]), "Q2": self.entity(["Q18534572", "Q746549"]), "Q3": self.entity(["Q746549"])}
        under = lambda *qs: [{"t": {"value": f"http://www.wikidata.org/entity/{q}"}} for q in qs]
        with mock.patch("local_terms.city_population._get", return_value={"entities": entities}), mock.patch("time.sleep"), \
                mock.patch("local_terms._sparql", side_effect=[under("Q131734", "Q18534572"), under("Q746549")]) as sparql:
            excluded, _ = local_terms.excluded_items(set(entities), float("inf"), country="Q33", topic_cache={})
        self.assertEqual({"Q1"}, excluded)
        self.assertIn("wd:Q4830453", sparql.call_args_list[0].args[0])


class CountryTermsTest(unittest.TestCase):
    def test_scarta_elenchi_voce_principale_disambigue_tipi_esclusi_e_nomi_di_altre_voci(self):
        info = {"Ceviche": {"redirects": ["Cebiche"], "item": "Q1", "revision": 7, "disambiguation": False},
                "Pachamanca": {"redirects": [], "item": "Q2", "revision": 8, "disambiguation": False},
                "Chicha": {"redirects": [], "item": "Q3", "revision": 9, "disambiguation": True},
                "Gastón Acurio": {"redirects": [], "item": "Q4", "revision": 10, "disambiguation": False},
                "Ceviche (canzone)": {"redirects": [], "item": "Q5", "revision": 11, "disambiguation": False}}
        with mock.patch("local_terms.categories", return_value=(["Categoria:Cucina peruviana"], {"Cucina peruviana"})), \
                mock.patch("local_terms.members", return_value=set(info) | {"Cucina peruviana", "Lista di piatti peruviani"}), \
                mock.patch("local_terms.page_info", return_value=info) as page_info, \
                mock.patch("local_terms.excluded_items", return_value=({"Q4"}, {"Q2"})), \
                mock.patch("local_terms.existing_articles", return_value=set()):
            terms = local_terms.country_terms("Q419", "it", deadline=float("inf"))
        self.assertEqual(set(info), page_info.call_args.args[0])
        self.assertEqual(([("Ceviche", ["Ceviche", "Cebiche"], 7), ("Ceviche (canzone)", [], 11),
                           ("Pachamanca", ["Pachamanca"], 8)], {"Pachamanca"}), terms)


class CollectTest(unittest.TestCase):
    def test_righe_col_nome_usato_dalla_guida_regione_senza_paese_saltata(self):
        regions = {"peru": ("Peru", "Assaggiate il cebiche al mercato."), "atlantide": ("Atlantis", "Ceviche ovunque.")}
        candidates = [("Ceviche", ["Ceviche", "Cebiche"], 7), ("Pachamanca", ["Pachamanca"], 8)]
        with mock.patch("local_terms.city_population.wikibase_items", return_value={"Peru": "Q419"}), \
                mock.patch("local_terms.countries", return_value={"Q419": "Q419"}), \
                mock.patch("local_terms.country_terms", return_value=(candidates, set())) as country_terms, \
                mock.patch("local_terms.summaries", return_value={"Ceviche": "Piatto di pesce crudo."}) as summaries:
            rows = local_terms.collect(regions, "it")
        country_terms.assert_called_once()
        self.assertEqual({"Ceviche"}, summaries.call_args.args[0])
        self.assertEqual([{"regionId": "peru", "terms": [
            {"term": "Cebiche", "aliases": ["Ceviche"], "title": "Ceviche", "summary": "Piatto di pesce crudo.",
             "url": "https://it.wikipedia.org/wiki/Ceviche", "revision": 7}]}], rows)

    def test_un_paese_in_errore_lascia_fuori_solo_le_sue_regioni_le_altre_anche_senza_termini(self):
        regions = {"peru": ("Peru", "Pachamanca."), "malta": ("Malta", "Pastizzi."), "gozo": ("Gozo", "Niente.")}

        def terms(country, lang, deadline, topic_cache):
            if country == "Q419":
                raise OSError("rete")
            return [("Pastizz", ["Pastizzi"], 3)], set()
        with mock.patch("local_terms.city_population.wikibase_items", return_value={"Peru": "Q419", "Malta": "Q233", "Gozo": "Q9"}), \
                mock.patch("local_terms.countries", return_value={"Q419": "Q419", "Q233": "Q233", "Q9": "Q233"}), \
                mock.patch("local_terms.country_terms", side_effect=terms), \
                mock.patch("local_terms.summaries", return_value={"Pastizz": "Sfoglia ripiena."}), \
                redirect_stderr(io.StringIO()) as err:
            rows = local_terms.collect(regions, "it")
        self.assertEqual([("gozo", 0), ("malta", 1)], [(r["regionId"], len(r["terms"])) for r in rows])
        self.assertIn("Q419 saltato", err.getvalue())


SAMPLE = """== Understand ==
Il '''Nascosto''' non conta.
== Eat ==
Try '''ceviche''' and ''pisco sour'', or the {{listing|name=''Chifa Roberto''|content='''Template'''}} place. <!-- ''commento'' -->
'''Buyer beware:''' pay in ''PEN'', see '''Lima''' and the [[Cusco|''Cusqueña'']] beer. ''Never drink tap water at the market, it is unsafe.''
=== Money ===
Costs ''pesitos'' here.
=== Desserts ===
The '''''picarones''''' <ref>''nota''</ref> are fried; also ''ai'' and '''3 soles'''.
== Do ==
Visit ''Cusco''.
== Respect ==
Say ''gringo'' kindly.
"""


class MarkupCandidatesTest(unittest.TestCase):
    def test_grassetti_e_corsivi_delle_sezioni_giuste_senza_rumore(self):
        self.assertEqual(["ceviche", "pisco sour", "Lima", "Cusqueña", "picarones", "gringo"],
                         local_terms.markup_candidates(SAMPLE))

    def test_titoli_italiani_e_sottosezione_dei_soldi_saltata(self):
        text = "== A tavola ==\n''anticuchos''\n=== Bevande ===\n'''Pisco Sour'''\n=== Moneta ===\n''soles''\n== Cosa fare ==\n''Salsa''\n"
        self.assertEqual(["anticuchos", "Pisco Sour"], local_terms.markup_candidates(text))


class TiedToTest(unittest.TestCase):
    def claims(self, types=(), origin=(), country=(), place=False):
        return {"types": set(types), "origin": set(origin), "country": set(country), "place": place}

    def test_paese_d_origine_o_paese_di_una_voce_che_non_e_un_luogo(self):
        self.assertTrue(local_terms.tied_to(self.claims(origin={"Q419"}), "Q1", "Q419"))
        self.assertTrue(local_terms.tied_to(self.claims(country={"Q419"}), "Q2", "Q419"))

    def test_altro_paese_luogo_paese_stesso_e_tipi_esclusi_scartati(self):
        self.assertFalse(local_terms.tied_to(self.claims(origin={"Q148"}, country={"Q148"}), "Q3", "Q419"))
        self.assertFalse(local_terms.tied_to(self.claims(country={"Q419"}, place=True), "Q4", "Q419"))
        self.assertFalse(local_terms.tied_to(self.claims(country={"Q419"}), "Q419", "Q419"))
        self.assertFalse(local_terms.tied_to(self.claims(types={"Q5"}, origin={"Q419"}), "Q5", "Q419"))


    def test_con_un_paese_d_origine_diverso_il_paese_d_uso_non_basta(self):
        self.assertFalse(local_terms.tied_to(self.claims(origin={"Q145"}, country={"Q419", "Q145"}), "Q6", "Q419"))

    def test_cucina_nazionale_scartata(self):
        self.assertFalse(local_terms.tied_to(self.claims(types={"Q1968435"}, origin={"Q17"}), "Q7", "Q17"))


class TopicTypesTest(unittest.TestCase):
    def test_tipi_di_cibo_si_valute_no_e_la_cache_evita_la_richiesta(self):
        cache = {}
        rows = [{"t": {"value": "http://www.wikidata.org/entity/Q746549"}}]
        with mock.patch("local_terms._sparql", return_value=rows) as sparql:
            self.assertEqual({"Q746549"}, local_terms.topic_types({"Q746549", "Q8142"}, cache))
            self.assertEqual({"Q746549"}, local_terms.topic_types({"Q746549", "Q8142"}, cache))
        self.assertEqual(1, sparql.call_count)
        self.assertEqual({"Q746549": True, "Q8142": False}, cache)

    def test_senza_tipi_nessuna_richiesta(self):
        with mock.patch("local_terms._sparql") as sparql:
            self.assertEqual(set(), local_terms.topic_types(set(), {}))
        sparql.assert_not_called()


class ResolveTest(unittest.TestCase):
    def test_redirect_e_normalizzazione_seguiti_disambigue_mancanti_ed_elenchi_scartati(self):
        data = {"query": {
            "normalized": [{"from": "ceviche", "to": "Ceviche"}],
            "redirects": [{"from": "Cebiche", "to": "Ceviche"}],
            "pages": [{"title": "Ceviche", "ns": 0, "lastrevid": 7, "pageprops": {"wikibase_item": "Q1"}},
                      {"title": "Cuy", "ns": 0, "lastrevid": 8, "pageprops": {"wikibase_item": "Q2", "disambiguation": ""}},
                      {"title": "Lista di piatti", "ns": 0, "lastrevid": 9, "pageprops": {"wikibase_item": "Q3"}},
                      {"title": "Chupe", "missing": True}]}}
        cache = {}
        with mock.patch("local_terms._api", return_value=data), mock.patch("time.sleep"):
            found = local_terms.resolve(["ceviche", "Cebiche", "Cuy", "Lista di piatti", "Chupe"], "it", float("inf"), cache)
        self.assertEqual({"ceviche": ("Ceviche", "Q1", 7), "Cebiche": ("Ceviche", "Q1", 7)}, found)
        self.assertIsNone(cache["Cuy"])

    def test_candidati_gia_in_cache_non_si_richiedono(self):
        with mock.patch("local_terms._api") as api:
            self.assertEqual({"Ceviche": ("Ceviche", "Q1", 7)},
                             local_terms.resolve(["Ceviche"], "it", float("inf"), {"Ceviche": ("Ceviche", "Q1", 7)}))
        api.assert_not_called()


class SecondTermsTest(unittest.TestCase):
    def test_tiene_solo_le_voci_legate_al_paese_con_il_nome_della_guida(self):
        found = {"cebiche": ("Ceviche", "Q1", 7), "Medina": ("Medina (città)", "Q2", 8)}
        claims = {"Q1": {"types": set(), "origin": {"Q419"}, "country": set(), "place": False, "links": 40},
                  "Q2": {"types": set(), "origin": set(), "country": {"Q851"}, "place": True, "links": 40}}
        with mock.patch("local_terms.markup_candidates", return_value=["cebiche", "Medina"]), \
                mock.patch("local_terms.resolve", return_value=found), mock.patch("local_terms._claims_of", return_value=claims):
            terms = local_terms.second_terms("testo", "Q419", "it", float("inf"), {"pages": {}, "claims": {}, "types": {}})
        self.assertEqual([("Ceviche", ["cebiche", "Ceviche"], 7)], terms)

    def test_voce_con_tipi_fuori_dagli_argomenti_scartata_senza_tipo_tenuta(self):
        found = {"baht": ("Baht", "Q1", 1), "tequila": ("Tequila", "Q2", 2), "pad thai": ("Pad thai", "Q3", 3)}
        claims = {"Q1": {"types": {"Q8142"}, "origin": {"Q869"}, "country": set(), "place": False, "links": 40},
                  "Q2": {"types": set(), "origin": {"Q869"}, "country": set(), "place": False, "links": 40},
                  "Q3": {"types": {"Q746549"}, "origin": {"Q869"}, "country": set(), "place": False, "links": 40}}
        with mock.patch("local_terms.markup_candidates", return_value=list(found)), \
                mock.patch("local_terms.resolve", return_value=found), mock.patch("local_terms._claims_of", return_value=claims), \
                mock.patch("local_terms.topic_types", return_value={"Q746549"}):
            terms = local_terms.second_terms("testo", "Q869", "en", float("inf"), {"pages": {}, "claims": {}, "types": {}})
        self.assertEqual(["Pad thai", "Tequila"], [t for t, _, _ in terms])

    def test_voce_generica_nota_in_molte_lingue_e_con_piu_paesi_d_origine_scartata(self):
        found = {"caffe": ("Caffè", "Q1", 1), "sidro": ("Sidro", "Q2", 2), "Knödel": ("Knödel", "Q3", 3),
                 "tequila": ("Tequila", "Q4", 4)}
        # (collegamenti ad altre lingue, paesi d'origine): il paese della regione, Q9, c'e' sempre
        shape = {"Q1": (259, {"Q9", "Q805"}), "Q2": (84, {"Q9", "Q142", "Q183", "Q25"}), "Q3": (42, {"Q9", "Q40", "Q38"}),
                 "Q4": (83, {"Q9"})}
        claims = {q: {"types": set(), "origin": origin, "country": set(), "place": False, "links": n} for q, (n, origin) in shape.items()}
        with mock.patch("local_terms.markup_candidates", return_value=list(found)),                 mock.patch("local_terms.resolve", return_value=found), mock.patch("local_terms._claims_of", return_value=claims):
            terms = local_terms.second_terms("testo", "Q9", "it", float("inf"), {"pages": {}, "claims": {}, "types": {}})
        self.assertEqual(["Knödel", "Tequila"], [t for t, _, _ in terms])

    def test_tempo_scaduto_nessun_termine(self):
        with mock.patch("local_terms.markup_candidates", return_value=["cebiche"]), \
                mock.patch("local_terms.resolve", return_value={}), mock.patch("local_terms._claims_of", return_value={}):
            self.assertEqual([], local_terms.second_terms("testo", "Q419", "it", time.monotonic() - 1, {"pages": {}, "claims": {}, "types": {}}))


class CuisineTitleTest(unittest.TestCase):
    def test_cucina_di_un_paese_o_di_una_regione_non_e_un_termine_i_piatti_si(self):
        for title in ("Sichuan cuisine", "Cuisine of Quebec", "Cuisine of the Southern United States", "Cucina cantonese"):
            self.assertTrue(local_terms.CUISINE_TITLE.search(title), title)
        for title in ("Kaiseki", "Poutine", "Cucumber salad", "Pasta alla cucina"):
            self.assertFalse(local_terms.CUISINE_TITLE.search(title), title)

    def test_candidato_della_seconda_fonte_che_porta_alla_cucina_scartato(self):
        data = {"query": {"normalized": [], "redirects": [{"from": "Hansik", "to": "Korean cuisine"}],
                          "pages": [{"title": "Korean cuisine", "ns": 0, "lastrevid": 1, "pageprops": {"wikibase_item": "Q1"}}]}}
        with mock.patch("local_terms._api", return_value=data), mock.patch("time.sleep"):
            self.assertEqual({}, local_terms.resolve(["Hansik"], "en", float("inf"), {}))

    def test_la_prima_fonte_non_tiene_le_voci_di_cucina(self):
        info = {"Ceviche": {"redirects": [], "item": "Q1", "revision": 7, "disambiguation": False}}
        with mock.patch("local_terms.categories", return_value=(["Category:Peruvian cuisine"], set())), \
                mock.patch("local_terms.members", return_value={"Ceviche", "Andean cuisine", "Cucina andina"}), \
                mock.patch("local_terms.page_info", return_value=info) as page_info, \
                mock.patch("local_terms.excluded_items", return_value=(set(), set())), \
                mock.patch("local_terms.existing_articles", return_value=set()):
            local_terms.country_terms("Q419", "en", deadline=float("inf"))
        self.assertEqual({"Ceviche"}, page_info.call_args.args[0])


class FestivalTest(unittest.TestCase):
    def test_festival_di_musica_e_di_cinema_si_feste_e_usanze_no(self):
        music = ("Smukfest is an annual music festival, held in Denmark.", "Tuska Festival is a Finnish heavy metal festival.",
                 "Rock in Japan Festival is an annual three-day rock festival.", "Il Fuji Rock Festival è un festival musicale che si tiene ogni anno.",
                 "Tomorrowland è un festival di musica elettronica.", "Il PÖFF è un festival cinematografico.")
        local = ("Hanami is the Japanese traditional custom of enjoying the beauty of flowers.", "Awa Odori is a dance festival held in August.",
                 "Ilosaari è una località. Il festival musicale è altrove.", "Jenadriyah is a cultural and heritage festival.")
        for text in music:
            self.assertFalse(local_terms.is_local(text, False, None), text)
        for text in local:
            self.assertTrue(local_terms.is_local(text, False, None), text)


class MentionsTest(unittest.TestCase):
    def test_nome_aggettivo_e_radice_del_paese_come_parola_intera_o_inizio_di_parola(self):
        names = ["Finlandia", "Finnish"]
        self.assertTrue(local_terms.mentions("Il pane è tipico della Finlandia.", names))
        self.assertTrue(local_terms.mentions("Un piatto finlandese di segale.", names))
        self.assertTrue(local_terms.mentions("A Finnish rye bread.", names))
        self.assertFalse(local_terms.mentions("Mead is an alcoholic drink made from honey.", names))

    def test_nomi_di_piu_parole_senza_radice_e_parole_corte_solo_intere(self):
        names = ["Stati Uniti", "Cina"]
        self.assertFalse(local_terms.mentions("Sono stati serviti ai tavoli.", names))
        self.assertTrue(local_terms.mentions("Diffuso negli Stati Uniti del sud.", names))
        self.assertFalse(local_terms.mentions("Cinabro e cinema.", names))
        self.assertTrue(local_terms.mentions("Una zuppa della Cina.", names))


class IsLocalTest(unittest.TestCase):
    def test_voce_non_legata_al_paese_resta_solo_se_l_introduzione_lo_nomina(self):
        self.assertFalse(local_terms.is_local("Mead is made from honey.", True, ["Finland"]))
        self.assertTrue(local_terms.is_local("Mead is a drink popular in Finland.", True, ["Finland"]))

    def test_voce_legata_al_paese_o_senza_nomi_del_paese_tenuta(self):
        self.assertTrue(local_terms.is_local("Mead is made from honey.", False, ["Finland"]))
        self.assertTrue(local_terms.is_local("Mead is made from honey.", True, None))


class CountryNamesTest(unittest.TestCase):
    def test_nome_alias_e_aggettivi_nella_lingua(self):
        def text(value, language):
            return {"mainsnak": {"datavalue": {"value": {"text": value, "language": language}}}}
        entity = {"labels": {"it": {"value": "Finlandia"}}, "aliases": {"it": [{"value": "Suomi"}]},
                  "claims": {"P1549": [text("finlandese", "it"), text("Finnish", "en")]}}
        with mock.patch("local_terms.city_population._get", return_value={"entities": {"Q33": entity}}):
            self.assertEqual(["Finlandia", "Suomi", "finlandese"], local_terms.country_names("Q33", "it"))


class UntiedCollectTest(unittest.TestCase):
    def test_voce_non_legata_al_paese_tolta_se_l_introduzione_non_lo_nomina_festival_di_musica_tolto(self):
        regions = {"finlandia": ("Finland", "Il mead e la birra. Smukfest. Ruisleipä.")}
        terms = [("Mead", ["Mead"], 1), ("Smukfest", ["Smukfest"], 2), ("Ruisleipä", ["Ruisleipä"], 3)]
        texts = {"Mead": "Mead is made from honey.", "Smukfest": "Smukfest is an annual music festival.",
                 "Ruisleipä": "Ruisleipä is a rye bread of Finland."}
        with mock.patch("local_terms.city_population.wikibase_items", return_value={"Finland": "Q33"}), \
                mock.patch("local_terms.countries", return_value={"Q33": "Q33"}), \
                mock.patch("local_terms.country_terms", return_value=(terms, {"Mead", "Ruisleipä"})), \
                mock.patch("local_terms.country_names", return_value=["Finland"]) as names, \
                mock.patch("local_terms.summaries", return_value=texts):
            rows = local_terms.collect(regions, "en", second_source=False)
        self.assertEqual(["Ruisleipä"], [t["term"] for t in rows[0]["terms"]])
        names.assert_called_once_with("Q33", "en")

    def test_l_introduzione_che_nomina_la_regione_basta(self):
        regions = {"canada-quebec": ("Quebec", "Il sidro di ghiaccio.")}
        with mock.patch("local_terms.city_population.wikibase_items", return_value={"Quebec": "Q176"}), \
                mock.patch("local_terms.countries", return_value={"Q176": "Q16"}), \
                mock.patch("local_terms.country_terms", return_value=([("Sidro di ghiaccio", ["sidro di ghiaccio"], 1)], {"Sidro di ghiaccio"})), \
                mock.patch("local_terms.country_names", return_value=["Canada"]), \
                mock.patch("local_terms.summaries", return_value={"Sidro di ghiaccio": "Il sidro di ghiaccio è un prodotto del Quebec."}):
            rows = local_terms.collect(regions, "it", second_source=False)
        self.assertEqual(["sidro di ghiaccio"], [t["term"] for t in rows[0]["terms"]])

    def test_nomi_del_paese_non_letti_voci_tenute(self):
        regions = {"finlandia": ("Finland", "Il mead.")}
        with mock.patch("local_terms.city_population.wikibase_items", return_value={"Finland": "Q33"}), \
                mock.patch("local_terms.countries", return_value={"Q33": "Q33"}), \
                mock.patch("local_terms.country_terms", return_value=([("Mead", ["Mead"], 1)], {"Mead"})), \
                mock.patch("local_terms.country_names", side_effect=OSError("rete")), \
                mock.patch("local_terms.summaries", return_value={"Mead": "Mead is made from honey."}), \
                redirect_stderr(io.StringIO()):
            rows = local_terms.collect(regions, "en", second_source=False)
        self.assertEqual(["Mead"], [t["term"] for t in rows[0]["terms"]])


class MergeTest(unittest.TestCase):
    def test_stesso_titolo_stesso_termine_il_primo_vince(self):
        first = [("Ceviche", ["Cebiche", "Ceviche"], 7)]
        second = [("Ceviche", ["ceviche"], 7), ("Chupe", ["chupe", "Chupe"], 9), ("Chupe", ["Chupe de camarones"], 9)]
        self.assertEqual([("Ceviche", ["Cebiche", "Ceviche"], 7), ("Chupe", ["chupe", "Chupe"], 9)], local_terms.merge(first, second))


class SecondSourceCollectTest(unittest.TestCase):
    def collect(self, second, **kwargs):
        regions = {"peru": ("Peru", "Il cebiche.")}
        with mock.patch("local_terms.city_population.wikibase_items", return_value={"Peru": "Q419"}), \
                mock.patch("local_terms.countries", return_value={"Q419": "Q419"}), \
                mock.patch("local_terms.country_terms", return_value=([("Ceviche", ["Cebiche", "Ceviche"], 7)], set())), \
                mock.patch("local_terms.second_terms", **second), \
                mock.patch("local_terms.summaries", side_effect=lambda titles, lang, deadline: {t: "Piatto." for t in titles}), \
                redirect_stderr(io.StringIO()) as err:
            return local_terms.collect(regions, "it", **kwargs), err.getvalue()

    def test_unisce_prima_e_seconda_fonte_senza_doppioni(self):
        rows, _ = self.collect({"return_value": [("Ceviche", ["ceviche"], 7), ("Chupe", ["chupe", "Chupe"], 9)]})
        self.assertEqual(["Cebiche", "chupe"], [t["term"] for t in rows[0]["terms"]])

    def test_seconda_fonte_in_errore_la_regione_resta_con_la_prima(self):
        rows, err = self.collect({"side_effect": OSError("rete")})
        self.assertEqual(["Cebiche"], [t["term"] for t in rows[0]["terms"]])
        self.assertIn("peru saltati", err)

    def test_seconda_fonte_spenta_o_senza_tempo_riservato_non_gira(self):
        for kwargs in ({"second_source": False}, {"max_seconds": 0}):
            with self.subTest(kwargs):
                with mock.patch("local_terms.second_terms") as second_terms, \
                        mock.patch("local_terms.city_population.wikibase_items", return_value={"Peru": "Q419"}), \
                        mock.patch("local_terms.countries", return_value={"Q419": "Q419"}), \
                        mock.patch("local_terms.country_terms", return_value=([("Ceviche", ["Ceviche"], 7)], set())), \
                        mock.patch("local_terms.summaries", return_value={"Ceviche": "Piatto."}):
                    local_terms.collect({"peru": ("Peru", "Ceviche.")}, "it", **kwargs)
                second_terms.assert_not_called()

    def test_il_tempo_della_prima_fonte_non_e_della_seconda(self):
        seen = {}

        def second_terms(text, country, lang, deadline, cache):
            seen["left"] = deadline - time.monotonic()
            return []
        self.collect({"side_effect": second_terms}, max_seconds=1000)
        self.assertAlmostEqual(750, seen["left"], delta=5)


class ReadingOrderTest(unittest.TestCase):
    def test_prima_le_mai_lette_poi_quelle_con_termini_senza_data_poi_dalla_lettura_piu_vecchia(self):
        with tempfile.TemporaryDirectory() as tmp:
            published = Path(tmp, "guides.db")
            with closing(sqlite3.connect(published)) as db, db:
                db.execute("CREATE TABLE guide_terms (regionId TEXT, term TEXT)")
                db.executemany("INSERT INTO guide_terms VALUES (?, ?)", [("malta", "Pastizz"), ("peru", "Ceviche"), ("andorra", "Trinxat")])
                db.execute("CREATE TABLE guide_terms_read (regionId TEXT PRIMARY KEY, readAt TEXT NOT NULL)")
                db.executemany("INSERT INTO guide_terms_read VALUES (?, ?)", [("peru", "2026-11-10"), ("andorra", "2026-11-17"), ("gozo", "2026-11-03")])
            self.assertEqual(["zambia", "malta", "gozo", "peru", "andorra"],
                             local_terms.reading_order(["andorra", "gozo", "malta", "peru", "zambia"], published))

    def test_senza_guides_db_pubblicato_o_senza_tabelle_in_ordine_alfabetico(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp, "vuoto.db")
            sqlite3.connect(empty).close()
            for published in (None, Path(tmp, "manca.db"), empty):
                self.assertEqual(["gozo", "malta"], local_terms.reading_order(["malta", "gozo"], published))

    def test_collect_legge_le_regioni_nell_ordine_dato(self):
        regions = {"gozo": ("Gozo", "Pastizzi."), "malta": ("Malta", "Pastizzi.")}
        with mock.patch("local_terms.city_population.wikibase_items", return_value={"Gozo": "Q9", "Malta": "Q233"}), \
                mock.patch("local_terms.countries", return_value={"Q9": "Q233", "Q233": "Q233"}), \
                mock.patch("local_terms.country_terms", return_value=([("Pastizz", ["Pastizzi"], 3)], set())), \
                mock.patch("local_terms.summaries", return_value={"Pastizz": "Sfoglia ripiena."}):
            rows = local_terms.collect(regions, "it", order=["malta", "gozo"])
        self.assertEqual(["malta", "gozo"], [r["regionId"] for r in rows])


class MainTest(unittest.TestCase):
    def test_errore_mai_fatale_scrive_un_file_vuoto(self):
        with tempfile.TemporaryDirectory() as tmp:
            guide = Path(tmp, "peru.txt")
            guide.write_text("Ceviche.", encoding="utf-8")
            tsv = Path(tmp, "regioni.tsv")
            tsv.write_text(f"peru\tPeru\t{guide}\nvuota\tNiente\t\n", encoding="utf-8")
            out = Path(tmp, "terms.jsonl")
            with mock.patch("local_terms.collect", side_effect=OSError("rete")) as collect, \
                    redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()) as stdout:
                self.assertEqual(0, local_terms.main(["it", str(tsv), str(out)]))
            self.assertEqual({"peru": ("Peru", "Ceviche.")}, collect.call_args.args[0])
            self.assertEqual("", out.read_text(encoding="utf-8"))
            self.assertIn("0 termini locali it, 0/1 regioni lette", stdout.getvalue())

    def test_published_sceglie_l_ordine_di_lettura(self):
        with tempfile.TemporaryDirectory() as tmp:
            tsv = Path(tmp, "regioni.tsv")
            lines = []
            for region in ("malta", "zambia"):
                Path(tmp, f"{region}.txt").write_text("Testo.", encoding="utf-8")
                lines.append(f"{region}\t{region.title()}\t{Path(tmp, region + '.txt')}")
            tsv.write_text("\n".join(lines) + "\n", encoding="utf-8")
            published = Path(tmp, "guides.db")
            with closing(sqlite3.connect(published)) as db, db:
                db.execute("CREATE TABLE guide_terms_read (regionId TEXT PRIMARY KEY, readAt TEXT NOT NULL)")
                db.execute("INSERT INTO guide_terms_read VALUES ('malta', '2026-11-17')")
            with mock.patch("local_terms.collect", return_value=[]) as collect, redirect_stdout(io.StringIO()):
                local_terms.main(["en", str(tsv), str(Path(tmp, "terms.jsonl")), "--published", str(published)])
            self.assertEqual(["zambia", "malta"], collect.call_args.kwargs["order"])

    def test_righe_jsonl(self):
        row = {"regionId": "peru", "terms": [{"term": "Ají", "aliases": [], "title": "Ají", "summary": "Peperoncino.", "url": "u", "revision": 1}]}
        with tempfile.TemporaryDirectory() as tmp:
            tsv = Path(tmp, "regioni.tsv")
            tsv.write_text("", encoding="utf-8")
            out = Path(tmp, "terms.jsonl")
            with mock.patch("local_terms.collect", return_value=[row]), redirect_stdout(io.StringIO()):
                local_terms.main(["en", str(tsv), str(out)])
            self.assertEqual([row], [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines()])

    def test_lingua_sconosciuta_mostra_l_uso(self):
        with redirect_stderr(io.StringIO()) as err:
            self.assertEqual(2, local_terms.main(["fr", "a", "b"]))
        self.assertIn("Uso:", err.getvalue())


if __name__ == "__main__":
    unittest.main()
