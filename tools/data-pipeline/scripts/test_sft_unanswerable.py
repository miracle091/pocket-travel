#!/usr/bin/env python3
"""Test di sft_unanswerable.py: schemi validi, sezione vicina che non risponde, frase di rifiuto del dataset, nessuna
domanda (ne' coppia regione/domanda) delle serie unanswerable e control di eval_retrieval.py.

Uso: python test_sft_unanswerable.py
"""
import random
import unittest

import audit_sft
import eval_retrieval as ev
import external_questions
import generate_sft as gs
import sft_nearby
import sft_unanswerable as su

# Nomi dei posti delle due serie: gli schemi non devono ridare una loro domanda neanche con questi nomi
SERIES_PLACES = ["Bologna", "Firenze", "Napoli", "Venezia", "Milano", "Roma", "Verona", "Trieste", "Genova", "Bari", "Catania",
                 "Faro", "Lisbona", "Porto", "Braga", "Tavira", "Sintra", "Cascais", "Lagos", "Coimbra", "Atene", "Salonicco",
                 "Zurigo", "Locarno", "Ginevra", "Spalato", "Rovigno", "Italia", "Portogallo", "Grecia", "Svizzera", "Croazia",
                 "Florence", "Naples", "Venice", "Milan", "Rome", "Genoa", "Lisbon", "Athens", "Zurich", "Geneva", "Split",
                 "Italy", "Portugal", "Greece", "Switzerland", "Croatia"]
SECTIONS = {
    "it": {"ARRIVARE": "L'aeroporto di {r} è collegato al centro da un autobus ogni 20 minuti. I voli arrivano da tutta Europa.",
           "TRASPORTI": "In centro a {r} conviene girare a piedi; il parcheggio è difficile e la sosta in centro è a pagamento.",
           "COSA_VEDERE": "Il museo civico di {r} raccoglie dipinti del Trecento e una pinacoteca con opere di scuola locale.",
           "ALLOGGIO": "A {r} ci sono alberghi in centro e un ostello vicino alla stazione, aperto tutto l'anno.",
           "CIBO_BEVANDE": "Le trattorie di {r} servono piatti della tradizione; i ristoranti del porto propongono pesce.",
           "SALUTE": "Gli ospedali pubblici sono buoni e le farmacie sono diffuse anche nei piccoli centri."},
    "en": {"ARRIVARE": "The airport of {r} is linked to the centre by a shuttle bus every 20 minutes. Flights arrive from all over Europe.",
           "TRASPORTI": "In central {r} it is best to walk; parking is difficult and street parking in the centre is paid.",
           "COSA_VEDERE": "The civic museum of {r} holds fourteenth-century paintings and a gallery of works by local artists.",
           "ALLOGGIO": "{r} has hotels in the centre and a hostel near the station, open all year round.",
           "CIBO_BEVANDE": "The trattorias of {r} serve traditional dishes; the restaurants by the harbour specialise in fish.",
           "SALUTE": "Public hospitals are good and pharmacies are common even in small towns."},
}


def places(lang):
    """Citta' e paesi finti con i nomi delle serie (Bologna, Italia...), tutte le categorie delle sezioni di prova."""
    out = []
    for name in SERIES_PLACES:
        scope = "paese" if name in ("Italia", "Portogallo", "Grecia", "Svizzera", "Croazia", "Italy", "Portugal", "Greece",
                                    "Switzerland", "Croatia") else "citta"
        secs = [(cat, text.format(r=name), False) for cat, text in SECTIONS[lang].items()]
        out.append((f"citta:{name}" if scope == "citta" else name.lower(), name, scope, secs))
    return out


class TemplateTest(unittest.TestCase):
    def test_templates_are_well_formed(self):
        for lang, templates in su.TEMPLATES.items():
            L = gs.LANGS[lang]
            for scope, cat, question, near, absent, topic in templates:
                self.assertIn(scope, ("citta", "paese"), question)
                self.assertIn(cat, L["city_questions"] if scope == "citta" else L["questions"], question)
                self.assertEqual(question.count("{r}"), 1, question)
                self.assertTrue(near and absent and topic, question)
                self.assertFalse(set(near) & set(absent), question)
                self.assertTrue(topic.startswith(("su", "sul", "sull", "sui", "sugli", "sulla", "sulle")) if lang == "it"
                                else topic.startswith("about "), topic)
            self.assertGreaterEqual(len(templates), 40)
            self.assertEqual(len({t[2] for t in templates}), len(templates))

    def test_templates_are_not_series_questions(self):
        measured = su.measured_questions()
        extended = external_questions.eval_questions()
        for lang, templates in su.TEMPLATES.items():
            for t in templates:
                self.assertNotIn(external_questions.normalize(t[2]), extended, t[2])  # gli schemi del test hanno ancora {r}
                self.assertIsNone(sft_nearby.NEARBY_WORDS.search(t[2]), t[2])  # nell'app porterebbero i luoghi vicini
                for name in SERIES_PLACES + ["X"]:
                    q = t[2].format(r=name)
                    self.assertFalse(su.is_measured(q, name, measured), q)
                    self.assertNotIn(external_questions.normalize(q), extended, q)

    def test_is_measured_catches_series_questions(self):
        measured = su.measured_questions()
        self.assertTrue(su.is_measured("Quanto costa il parcheggio all'aeroporto di Bologna per una settimana?", "Bologna", measured))
        self.assertTrue(su.is_measured("Can I bring my dog into museums in Florence?", "Florence", measured))
        self.assertTrue(su.is_measured("Dove si parcheggia a Torino?", "Torino", measured))  # control, qualunque citta'
        self.assertTrue(su.is_measured("Is there 4G coverage in Peru?", "Peru", measured))
        self.assertTrue(su.is_measured("Quanti km ci sono tra Pisa e Lucca?", "Lucca", measured))  # DISTANCE_Q
        self.assertFalse(su.is_measured("C'è un deposito bagagli all'aeroporto di Bologna?", "Bologna", measured))

    def test_matcher_whole_words_and_symbols(self):
        self.assertFalse(su.matcher(("volo$",))("Cercano volontari"))
        self.assertTrue(su.matcher(("volo$",))("Il volo dura un'ora"))
        self.assertTrue(su.matcher(("aeroport",))("Gli aeroporti"))
        self.assertTrue(su.matcher(("$",))("Entry costs US$5"))
        self.assertFalse(su.matcher(("$",))("Entry is free"))
        self.assertFalse(su.matcher(("sim$",))("Un edificio simile"))
        self.assertFalse(su.matcher(su.CAR_RENTAL_EN)("You can rent bikes and rickshaws"))
        self.assertFalse(su.matcher(("cab$",))("Take the cable car"))


class PremiseTest(unittest.TestCase):
    def test_sections_that_deny_the_airport_or_give_the_nearest_are_skipped(self):
        template = next(t for t in su.TEMPLATES["en"] if "check-in desk" in t[2])
        for text in ("{r} does not have an airport; flights land in the nearest city.",
                     "The nearest airport is 80 km away, with flights to the capital."):
            secs = [("ARRIVARE", text.format(r="Madikeri"), False)]
            rows = su.unanswerable_rows("en", gs.LANGS["en"], [("citta:Madikeri", "Madikeri", "citta", secs)], 5,
                                        random.Random(1), templates=[template])
            self.assertEqual(rows, [], text)
        secs = [("ARRIVARE", "Madikeri airport has two flights a day from the capital.", False)]
        rows = su.unanswerable_rows("en", gs.LANGS["en"], [("citta:Madikeri", "Madikeri", "citta", secs)], 5,
                                    random.Random(1), templates=[template])
        self.assertEqual(len(rows), 1)

    def test_own_airport_must_carry_the_town_name(self):
        template = next(t for t in su.TEMPLATES["it"] if "check-in" in t[2])
        for text, n in (("• Aeroporto di Verona: voli per tutta Europa.", 0), ("• Aeroporto di Malcesine: voli estivi.", 1)):
            rows = su.unanswerable_rows("it", gs.LANGS["it"], [("citta:Malcesine", "Malcesine", "citta", [("ARRIVARE", text, False)])],
                                        5, random.Random(1), templates=[template])
            self.assertEqual(len(rows), n, text)
        self.assertTrue(su.names_own_airport("Palermo Airport: flights to Rome.", "Palermo"))
        self.assertFalse(su.names_own_airport("The easiest way is to fly to Porto airport.", "Vila Nova de Famalicão"))
        self.assertFalse(su.names_own_airport("L'Aeroporto di Napoli-Capodichino dista 90 km da Gesualdo.", "Gesualdo"))
        self.assertTrue(su.names_own_airport("Mogadishu International Airport, also known as Aden Adde.", "Mogadishu"))
        self.assertTrue(su.names_own_airport("• Aeroporto di Costantina-Mohamed Boudiaf", "Costantina"))
        self.assertTrue(su.NOT_HERE.search("The railway line to Elhovo was closed in the early 2000s."))
        self.assertTrue(su.NOT_HERE.search("Kherson International Airport was destroyed in 2022, and has not been rebuilt."))


class RowsTest(unittest.TestCase):
    def rows(self, lang, n=400):
        return su.unanswerable_rows(lang, gs.LANGS[lang], places(lang), n, random.Random(1))

    def test_rows_refuse_with_a_near_section_that_does_not_answer(self):
        for lang in ("it", "en"):
            rows = self.rows(lang)
            self.assertGreater(len(rows), 100, lang)
            by_question = {t[2]: t for t in su.TEMPLATES[lang]}
            for r in rows:
                context, q, answer = audit_sft.parse_row(r)
                self.assertEqual(r["kind"], "neg")
                self.assertTrue(r["unanswerable"])
                self.assertTrue(audit_sft.is_refusal(answer), answer)
                self.assertEqual(audit_sft.hard_errors(r, lang), [], q)
                name = r["region"].removeprefix("citta:") if r["region"].startswith("citta:") else \
                    next(p[1] for p in places(lang) if p[0] == r["region"])
                scope, cat, _, near, absent, topic = by_question[next(t for t in by_question if t.format(r=name) == q)]
                self.assertEqual(r["category"], cat)
                self.assertTrue(su.matcher(near)(context), q)
                self.assertFalse(su.matcher(absent)(context), q)
                self.assertIn(topic, answer)

    def test_no_series_question_or_region_question_pair(self):
        series = {(region, external_questions.normalize(q)) for qs in ev.UNANSWERABLE_Q.values() for region, q in qs}
        questions = {q for _, q in series}
        measured = su.measured_questions()
        for lang in ("it", "en"):
            names = {p[0]: p[1] for p in places(lang)}
            for r in self.rows(lang):
                question = audit_sft.parse_row(r)[1]
                q = external_questions.normalize(question)
                self.assertNotIn(q, questions)
                self.assertNotIn((r["region"], q), series)
                # control, anche CITY_Q_CONTROL e DISTANCE_Q con il posto della riga
                for table in ev.LANGS[lang]["control"]:
                    for c in (table if isinstance(table, list) else [x for v in table.values() for x in v]):
                        self.assertNotEqual(q, external_questions.normalize(c.format(c=names[r["region"]], o="X")))
                self.assertFalse(su.is_measured(question, names[r["region"]], measured), question)

    def test_measured_questions_are_skipped(self):
        # uno schema uguale a una domanda della serie non da' righe
        template = ("citta", "ARRIVARE", "Quanto costa il parcheggio all'aeroporto di {r} per una settimana?",
                    ("aeroport",), ("parchegg",), "sul parcheggio dell'aeroporto")
        rows = su.unanswerable_rows("it", gs.LANGS["it"], places("it"), 10, random.Random(1), templates=[template])
        self.assertEqual(rows, [])

    def test_absent_words_rule_out_the_section(self):
        template = ("citta", "TRASPORTI", "Dove si lascia l'auto a {r} per tutto il giorno?", ("parchegg",), ("sosta",),
                    "sulla sosta lunga")
        rows = su.unanswerable_rows("it", gs.LANGS["it"], places("it"), 10, random.Random(1), templates=[template])
        self.assertEqual(rows, [])  # la sezione dei trasporti parla di sosta: potrebbe rispondere

    def test_deterministic_and_balanced(self):
        a, b = self.rows("it", 60), self.rows("it", 60)
        self.assertEqual(a, b)
        self.assertEqual(len(a), 60)
        self.assertLessEqual(max(su.template_counts(a).values()), 3)


if __name__ == "__main__":
    unittest.main()
