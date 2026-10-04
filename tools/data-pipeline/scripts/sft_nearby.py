#!/usr/bin/env python3
"""Esempi SFT con i blocchi di contesto "qui vicino" e "prossime partenze" di TravelAssistant.kt (generate_sft.py --nearby).

Per le domande su cosa c'e' attorno all'utente o sui mezzi pubblici l'app mette, prima delle sezioni della guida, il testo di
nearbyPoiContext (POI della regione attorno all'ultima posizione nota) o di transitContext (tabellone GTFS delle fermate
vicine). Qui le stesse funzioni, carattere per carattere, su dati sintetici: nomi, distanze, orari OSM e partenze inventati
ma plausibili. Niente estratti OSM o feed GTFS veri: non servono per imparare a leggere il formato, e non aggiungono licenze
(ODbL, termini delle reti) al dataset. I nomi sono di cinque lingue locali qualunque, non legati alla regione.

Risposte solo dal blocco (al massimo 3 frasi, come chiede il prompt); rifiuti quando il blocco non ha la risposta (categoria
o mezzo assenti) o quando manca del tutto (nessuna posizione, nessuna fermata): allora il contesto e' solo la guida.
"""
import re
from datetime import date, timedelta

# Copie di nearbyWords e transitWords di TravelAssistant.kt: le domande degli esempi attivano il blocco giusto.
NEARBY_WORDS = re.compile(r"qui vicino|vicino a me|qui intorno|qui attorno|nei dintorni|nelle vicinanze|pi(ù|u'|u) vicin|"
                          r"near me|nearby|nearest|closest|around here|close by", re.I)
TRANSIT_WORDS = re.compile(r"\b(autobus|bus|tram|metro|metropolitana|treno|treni|filobus|traghetto|ferry|train|subway)\b|"
                           r"partenz|prossima corsa|departure|timetable", re.I)

# Come PoiRepository.nearby: il raggio piu' piccolo con almeno 5 POI; nel contesto i 12 piu' vicini (NEARBY_CONTEXT_MAX).
NEARBY_RADII = (150, 300, 600)
NEARBY_MIN_COUNT = 5
NEARBY_CONTEXT_MAX = 12
# Come TransitBoard.kt: finestra di 180 minuti, al massimo 10 partenze.
TRANSIT_WINDOW = 180
TRANSIT_MAX = 10


def nearby_radius(distances):
    """Come nearbyRadius di PoiRepository.kt."""
    return next((r for r in NEARBY_RADII if sum(d <= r for d in distances) >= NEARBY_MIN_COUNT), NEARBY_RADII[-1])


def poi_context(pois, radius, lang):
    """Come nearbyPoiContext: [pois] = [(categoria, nome, metri, orari o None)] in ordine di distanza. Una riga per
    categoria (etichetta plurale dell'app), nell'ordine del suo POI piu' vicino."""
    if not pois:
        return None
    en = lang == "en"
    header = f"Points of interest within {radius} m of your position:" if en else f"Punti di interesse entro {radius} m dalla tua posizione:"
    groups = {}
    for cat, name, meters, hours in pois:
        details = [f"{meters} m"] + ([f"{'hours' if en else 'orari'} {hours}"] if hours else [])
        groups.setdefault(cat, []).append(f"{name} ({', '.join(details)})")
    return "\n".join([header] + [f"{CATS[cat][1 if en else 0]}: {', '.join(items)}" for cat, items in groups.items()])


def clock(minute_of_day):
    return f"{minute_of_day // 60:02d}:{minute_of_day % 60:02d}"


def transit_context(board, lang):
    """Come transitContext: [board] = ("departures", [(minuto del giorno, tra minuti, mezzo, linea, direzione o None)]),
    ("expired", data) o None (nessuna fermata vicina)."""
    if board is None:
        return None
    en = lang == "en"
    kind, value = board
    if kind == "expired":
        return (f"The installed public transport timetables expired on {value.isoformat()}." if en else
                f"Gli orari dei mezzi pubblici installati sono scaduti il {value.day}/{value.month}/{value.year}.")
    if not value:
        return "No departures in the next hours from the stops nearby." if en else "Nessuna partenza nelle prossime ore dalle fermate qui vicino."
    header = "Next departures from the stops nearby:" if en else "Prossime partenze dalle fermate qui vicino:"
    return "\n".join([header] + [f"{clock(m)} ({'in' if en else 'tra'} {n} min) {MODES[mode][1 if en else 0]} {line}"
                                 f"{(' to ' if en else ' per ') + head if head else ''}" for m, n, mode, line, head in value])


# Categorie dei POI: etichetta plurale dell'app (poi_* in core/ui strings.xml, IT ed EN), un nome singolare per i POI senza
# nome nelle risposte, e il valore del tag OSM che GeneratePoi usa come nome quando OSM non ne ha uno (None: hanno sempre nome).
CATS = {
    "CIBO_BEVANDE": ("Ristoranti e bar", "Restaurants & bars", "un locale", "a place to eat", None),
    "NEGOZI": ("Negozi", "Shops", "un negozio", "a shop", None),
    "FARMACIA": ("Farmacie", "Pharmacies", "una farmacia", "a pharmacy", None),
    "BANCOMAT": ("Bancomat", "ATMs", "un bancomat", "an ATM", "atm"),
    "BANCA": ("Banche", "Banks", "una banca", "a bank", None),
    "AUTOBUS": ("Autobus", "Buses", "una fermata", "a stop", None),
    "PARCHEGGIO": ("Parcheggi", "Parking", "un parcheggio", "a car park", "parking"),
    "LUOGHI_DI_CULTO": ("Luoghi di culto", "Places of worship", "un luogo di culto", "a place of worship", None),
    "MUSEI_ARTE": ("Musei e arte", "Museums & art", "un museo", "a museum", None),
    "ALLOGGIO": ("Hotel", "Hotels", "un hotel", "a hotel", None),
    "BAGNI_PUBBLICI": ("Bagni pubblici", "Public toilets", "un bagno pubblico", "a public toilet", "toilets"),
    "ACQUA_POTABILE": ("Fontanelle", "Drinking water", "una fontanella", "a drinking fountain", "drinking_water"),
    "UFFICIO_POSTALE": ("Uffici postali", "Post offices", "un ufficio postale", "a post office", None),
    "INFORMAZIONI": ("Uffici turistici", "Tourist offices", "un ufficio turistico", "a tourist office", None),
    "OSPEDALE": ("Ospedali", "Hospitals", "un ospedale", "a hospital", None),
    "POLIZIA": ("Polizia", "Police", "la polizia", "a police station", None),
    "TAXI": ("Taxi", "Taxis", "un posteggio dei taxi", "a taxi rank", "taxi"),
    "LUOGHI_STORICI": ("Luoghi storici", "Historic sites", "un luogo storico", "a historic site", None),
    "CARBURANTE": ("Benzinai", "Gas stations", "un benzinaio", "a gas station", None),
}
# Quanto spesso compaiono attorno a un punto di una citta' (pesi relativi).
CAT_WEIGHTS = {"CIBO_BEVANDE": 10, "NEGOZI": 8, "AUTOBUS": 5, "BANCOMAT": 3, "PARCHEGGIO": 3, "FARMACIA": 2, "BANCA": 2,
               "LUOGHI_DI_CULTO": 2, "ALLOGGIO": 3, "MUSEI_ARTE": 1, "BAGNI_PUBBLICI": 1, "ACQUA_POTABILE": 1,
               "UFFICIO_POSTALE": 1, "INFORMAZIONI": 1, "OSPEDALE": 0.3, "POLIZIA": 0.5, "TAXI": 0.7, "LUOGHI_STORICI": 1,
               "CARBURANTE": 0.7}

SURNAMES = {"it": ["Rossi", "Bianchi", "Esposito", "Colombo", "Ricci", "Marino", "Greco", "Gallo", "Conti", "Fontana"],
            "es": ["García", "López", "Martínez", "Sánchez", "Romero", "Navarro", "Torres", "Ruiz"],
            "fr": ["Martin", "Bernard", "Dubois", "Moreau", "Laurent", "Lefebvre", "Roux", "Fournier"],
            "de": ["Müller", "Schmidt", "Schneider", "Fischer", "Weber", "Becker", "Wagner", "Hoffmann"],
            "en": ["Smith", "Taylor", "Brown", "Wilson", "Evans", "Walker", "Hughes", "Wright"]}
PLACES = {"it": ["Garibaldi", "Duomo", "Mazzini", "San Marco", "Porta Nuova", "Castello", "Mercato", "Stazione"],
          "es": ["Plaza Mayor", "del Carmen", "San Juan", "del Puerto", "la Alameda", "Santa Ana", "del Mercado"],
          "fr": ["du Marché", "de la Gare", "du Port", "Saint-Michel", "des Halles", "du Château", "de la Mairie"],
          "de": ["Markt", "Bahnhof", "Rathaus", "Dom", "Linden", "Hafen", "Schloss"],
          "en": ["Market", "Station", "Harbour", "Castle", "Bridge", "Church", "Park"]}
# Nomi per categoria e lingua locale: {s} cognome, {p} luogo.
NAMES = {
    "CIBO_BEVANDE": {"it": ["Trattoria da {s}", "Bar {p}", "Pizzeria {p}", "Osteria {s}"], "es": ["Bar {s}", "Restaurante {p}", "Taberna {s}"],
                     "fr": ["Café {p}", "Brasserie {s}", "Bistrot {p}"], "de": ["Gasthaus {s}", "Café am {p}", "Brauhaus {p}"],
                     "en": ["The {p} Inn", "{s}'s Diner", "{p} Café"]},
    "NEGOZI": {"it": ["Alimentari {s}", "Panificio {s}", "Market {p}"], "es": ["Supermercado {p}", "Panadería {s}"],
               "fr": ["Boulangerie {s}", "Épicerie {p}"], "de": ["Bäckerei {s}", "{p}-Markt"], "en": ["{s}'s Grocery", "{p} Mini Market"]},
    "FARMACIA": {"it": ["Farmacia {s}", "Farmacia {p}"], "es": ["Farmacia {s}", "Farmacia {p}"], "fr": ["Pharmacie {s}", "Pharmacie {p}"],
                 "de": ["{p}-Apotheke"], "en": ["{s} Pharmacy", "{p} Pharmacy"]},
    "BANCOMAT": {"it": ["Banca {p}"], "es": ["Caja {p}"], "fr": ["Crédit {p}"], "de": ["Sparkasse {p}"], "en": ["{p} Bank"]},
    "BANCA": {"it": ["Banca {p}", "Cassa di Risparmio {p}"], "es": ["Banco {p}", "Caja {p}"], "fr": ["Banque {p}", "Crédit {p}"],
              "de": ["Sparkasse {p}", "Volksbank {p}"], "en": ["{p} Bank", "{p} Building Society"]},
    "AUTOBUS": {"it": ["{p}", "Piazza {p}", "Via {p}"], "es": ["Plaza {p}", "Calle {p}"], "fr": ["Place {p}", "Rue {p}"],
                "de": ["{p}platz", "{p}straße"], "en": ["{p} Street", "{p} Road"]},
    "PARCHEGGIO": {"it": ["Parcheggio {p}"], "es": ["Aparcamiento {p}"], "fr": ["Parking {p}"], "de": ["Parkhaus {p}"], "en": ["{p} Car Park"]},
    "LUOGHI_DI_CULTO": {"it": ["Chiesa di San Giorgio", "Chiesa di Santa Maria"], "es": ["Iglesia de San Pedro", "Parroquia de Santa Ana"],
                        "fr": ["Église Saint-Pierre", "Église Notre-Dame"], "de": ["Pfarrkirche St. Martin", "Stadtkirche"],
                        "en": ["St Mary's Church", "St John's Church"]},
    "MUSEI_ARTE": {"it": ["Museo civico", "Pinacoteca comunale", "Museo archeologico"], "es": ["Museo Municipal", "Museo de Bellas Artes"],
                   "fr": ["Musée municipal", "Musée des Beaux-Arts"], "de": ["Stadtmuseum", "Kunsthalle"], "en": ["Town Museum", "{p} Gallery"]},
    "ALLOGGIO": {"it": ["Hotel {p}", "Albergo {s}", "B&B {p}"], "es": ["Hotel {p}", "Hostal {s}"], "fr": ["Hôtel {p}", "Hôtel {s}"],
                 "de": ["Hotel {p}", "Pension {s}"], "en": ["{p} Hotel", "{s} Guest House"]},
    "BAGNI_PUBBLICI": {"it": ["Servizi igienici {p}"], "es": ["Aseos {p}"], "fr": ["Toilettes {p}"], "de": ["WC {p}"], "en": ["{p} Toilets"]},
    "ACQUA_POTABILE": {"it": ["Fontana {p}"], "es": ["Fuente {p}"], "fr": ["Fontaine {p}"], "de": ["{p}brunnen"], "en": ["{p} Fountain"]},
    "UFFICIO_POSTALE": {"it": ["Poste {p}", "Ufficio postale {p}"], "es": ["Correos {p}"], "fr": ["La Poste {p}"], "de": ["Postfiliale {p}"],
                        "en": ["{p} Post Office"]},
    "INFORMAZIONI": {"it": ["Ufficio turistico", "Infopoint {p}"], "es": ["Oficina de Turismo"], "fr": ["Office de tourisme"],
                     "de": ["Tourist-Information"], "en": ["Visitor Centre", "{p} Tourist Information"]},
    "OSPEDALE": {"it": ["Ospedale civile", "Ospedale San Giovanni"], "es": ["Hospital General", "Hospital San Rafael"],
                 "fr": ["Centre hospitalier", "Hôpital Saint-Louis"], "de": ["Kreiskrankenhaus", "St.-Josef-Krankenhaus"],
                 "en": ["General Hospital", "St Thomas' Hospital"]},
    "POLIZIA": {"it": ["Carabinieri", "Polizia municipale"], "es": ["Policía Local", "Comisaría {p}"], "fr": ["Commissariat {p}", "Gendarmerie"],
                "de": ["Polizeiinspektion {p}", "Polizeirevier"], "en": ["{p} Police Station"]},
    "TAXI": {"it": ["Taxi {p}"], "es": ["Parada de taxis {p}"], "fr": ["Station de taxis {p}"], "de": ["Taxistand {p}"], "en": ["{p} Taxi Rank"]},
    "LUOGHI_STORICI": {"it": ["Torre civica", "Porta {p}", "Monumento ai caduti"], "es": ["Torre del Reloj", "Muralla {p}"],
                       "fr": ["Porte {p}", "Monument aux morts"], "de": ["Stadttor", "Alter Turm"], "en": ["Old Town Gate", "War Memorial"]},
    "CARBURANTE": {"it": ["Distributore {p}"], "es": ["Gasolinera {p}"], "fr": ["Station-service {p}"], "de": ["Tankstelle {p}"],
                   "en": ["{p} Service Station"]},
}
# Orari OSM (opening_hours) per tipo di POI, come li riporta il contesto.
HOURS = {
    "shop": ["Mo-Fr 08:30-19:30; Sa 09:00-13:00", "Mo-Sa 09:00-20:00", "Mo-Fr 09:00-13:00,15:30-19:30; Sa 09:00-13:00", "Mo-Su 08:00-21:00"],
    "food": ["Mo-Su 12:00-15:00,19:00-23:00", "Tu-Su 07:00-22:00", "Mo-Sa 18:00-02:00", "Mo-Su 06:30-20:00"],
    "office": ["Mo-Fr 08:30-13:30", "Mo-Fr 08:20-19:05; Sa 08:20-12:35", "Mo-Sa 09:00-18:00"],
    "museum": ["Tu-Su 10:00-18:00", "We-Mo 09:30-17:30", "Tu-Sa 10:00-17:00; Su 10:00-13:00"],
    "always": ["24/7"],
}
CAT_HOURS = {"CIBO_BEVANDE": "food", "NEGOZI": "shop", "FARMACIA": "shop", "BANCOMAT": "always", "BANCA": "office",
             "UFFICIO_POSTALE": "office", "INFORMAZIONI": "office", "MUSEI_ARTE": "museum", "OSPEDALE": "always",
             "POLIZIA": "always", "CARBURANTE": "shop", "PARCHEGGIO": "always"}

# Domande per categoria (attivano isNearbyQuestion e non isTransitQuestion), inizio della risposta, argomento del rifiuto.
ASK = {
    "it": {
        None: (["Cosa c'è qui vicino?", "Cosa posso trovare nei dintorni?", "Che cosa c'è qui intorno?", "Cosa c'è vicino a me?"],
               None, "su cosa c'è qui vicino"),
        "FARMACIA": (["Dov'è la farmacia più vicina?", "C'è una farmacia qui vicino?", "Mi serve una farmacia nelle vicinanze, dove la trovo?"],
                     "La farmacia più vicina", "sulle farmacie qui vicino"),
        "BANCOMAT": (["Dove trovo un bancomat qui vicino?", "Qual è il bancomat più vicino?"], "Il bancomat più vicino", "sui bancomat qui vicino"),
        "CIBO_BEVANDE": (["Dove posso mangiare qui vicino?", "C'è un bar qui intorno?", "Qual è il ristorante più vicino?"],
                         "Il locale più vicino", "su ristoranti e bar qui vicino"),
        "BAGNI_PUBBLICI": (["Ci sono bagni pubblici qui vicino?", "Dov'è il bagno pubblico più vicino?"],
                           "Il bagno pubblico più vicino", "sui bagni pubblici qui vicino"),
        "OSPEDALE": (["Qual è l'ospedale più vicino?", "C'è un ospedale nelle vicinanze?"], "L'ospedale più vicino", "sugli ospedali qui vicino"),
        "UFFICIO_POSTALE": (["Dov'è l'ufficio postale più vicino?"], "L'ufficio postale più vicino", "sugli uffici postali qui vicino"),
        "INFORMAZIONI": (["C'è un ufficio turistico qui vicino?"], "L'ufficio turistico più vicino", "sugli uffici turistici qui vicino"),
        "PARCHEGGIO": (["Dove posso parcheggiare qui vicino?", "Qual è il parcheggio più vicino?"], "Il parcheggio più vicino", "sui parcheggi qui vicino"),
        "ALLOGGIO": (["C'è un hotel qui vicino?", "Dove posso dormire nei dintorni?"], "L'hotel più vicino", "sugli alberghi qui vicino"),
        "NEGOZI": (["C'è un negozio qui vicino?", "Dove posso fare la spesa qui intorno?"], "Il negozio più vicino", "sui negozi qui vicino"),
        "POLIZIA": (["Dov'è la polizia più vicina?", "C'è un posto di polizia qui vicino?"], "La polizia più vicina", "sulla polizia qui vicino"),
        "MUSEI_ARTE": (["C'è un museo qui vicino?"], "Il museo più vicino", "sui musei qui vicino"),
        "ACQUA_POTABILE": (["C'è una fontanella qui vicino?", "Dove trovo acqua potabile nelle vicinanze?"],
                           "La fontanella più vicina", "sulle fontanelle qui vicino"),
        "CARBURANTE": (["Dov'è il benzinaio più vicino?"], "Il benzinaio più vicino", "sui benzinai qui vicino"),
    },
    "en": {
        None: (["What's nearby?", "What is there around here?", "What can I find close by?", "What's near me?"], None, "about what is nearby"),
        "FARMACIA": (["Where is the nearest pharmacy?", "Is there a pharmacy nearby?", "I need a pharmacy close by, where can I find one?"],
                     "The nearest pharmacy", "about pharmacies nearby"),
        "BANCOMAT": (["Where is the nearest ATM?", "Is there an ATM near me?"], "The nearest ATM", "about ATMs nearby"),
        "CIBO_BEVANDE": (["Where can I eat nearby?", "Is there a café around here?", "What's the closest restaurant?"],
                         "The nearest place to eat", "about restaurants and bars nearby"),
        "BAGNI_PUBBLICI": (["Are there public toilets nearby?", "Where is the nearest public toilet?"],
                           "The nearest public toilet", "about public toilets nearby"),
        "OSPEDALE": (["Where is the nearest hospital?", "Is there a hospital close by?"], "The nearest hospital", "about hospitals nearby"),
        "UFFICIO_POSTALE": (["Where is the nearest post office?"], "The nearest post office", "about post offices nearby"),
        "INFORMAZIONI": (["Is there a tourist office nearby?"], "The nearest tourist office", "about tourist offices nearby"),
        "PARCHEGGIO": (["Where can I park nearby?", "Where is the closest parking?"], "The nearest car park", "about parking nearby"),
        "ALLOGGIO": (["Is there a hotel nearby?", "Where can I sleep around here?"], "The nearest hotel", "about hotels nearby"),
        "NEGOZI": (["Is there a shop nearby?", "Where can I buy groceries around here?"], "The nearest shop", "about shops nearby"),
        "POLIZIA": (["Where is the nearest police station?", "Is there a police station near me?"], "The nearest police station", "about the police nearby"),
        "MUSEI_ARTE": (["Is there a museum nearby?"], "The nearest museum", "about museums nearby"),
        "ACQUA_POTABILE": (["Is there drinking water nearby?", "Where is the nearest drinking fountain?"],
                           "The nearest drinking fountain", "about drinking water nearby"),
        "CARBURANTE": (["Where is the nearest gas station?"], "The nearest gas station", "about gas stations nearby"),
    },
}


def _poi_name(rng, cat, locale, surnames, places):
    if CATS[cat][4] and rng.random() < 0.4:
        return CATS[cat][4]  # senza nome in OSM: GeneratePoi usa il valore del tag
    return rng.choice(NAMES[cat][locale]).format(s=rng.choice(surnames[locale]), p=rng.choice(places[locale]))


def nearby_pois(rng, must=None, avoid=None, surnames=SURNAMES, places=PLACES):
    """POI sintetici attorno a un punto come li vede nearbyPoiContext: (raggio, [(categoria, nome, metri, orari)]) con il
    raggio e il taglio di PoiRepository.nearby e NEARBY_CONTEXT_MAX. [must]: una categoria che deve esserci; [avoid]: una
    che non deve esserci. [surnames] e [places]: altri nomi per lingua locale (il test esteso usa nomi fuori dal training)."""
    cats, weights = list(CAT_WEIGHTS), list(CAT_WEIGHTS.values())
    while True:
        locale = rng.choice(list(surnames))
        count = rng.choice([rng.randint(8, 30), rng.randint(3, 15), rng.randint(1, 6)])  # centro, quartiere, campagna
        pois, names = [], set()
        for cat in rng.choices(cats, weights, k=count) + ([must] if must else []):
            if cat == avoid:
                continue
            name = _poi_name(rng, cat, locale, surnames, places)
            if name in names and name != CATS[cat][4]:
                continue
            names.add(name)
            hours = rng.choice(HOURS[CAT_HOURS[cat]]) if cat in CAT_HOURS and rng.random() < 0.45 else None
            pois.append((cat, name, rng.randint(8, 600), hours))
        pois.sort(key=lambda p: p[2])
        radius = nearby_radius([p[2] for p in pois])
        shown = [p for p in pois if p[2] <= radius][:NEARBY_CONTEXT_MAX]
        if shown and (must is None or any(p[0] == must for p in shown)):
            return radius, shown


def _poi_ref(poi, lang):
    """Il POI in una risposta: il nome, o il nome singolare della categoria se OSM non ne ha uno."""
    cat, name, meters, _ = poi
    return CATS[cat][3 if lang == "en" else 2] if name == CATS[cat][4] else name


def poi_example(rng, lang, refusal, asks=None, surnames=SURNAMES, places=PLACES):
    """(blocco di contesto o None, domanda, risposta, "pos"/"neg") per una domanda su cosa c'e' qui vicino. [asks]: altre
    domande con la struttura di ASK[lang]; [surnames] e [places] come in nearby_pois."""
    en, asks = lang == "en", asks or ASK[lang]
    cat = rng.choice([None, None] + [c for c in asks if c])
    questions, lead, topic = asks[cat]
    q = rng.choice(questions)
    x = rng.random()
    if x < 0.15:  # nessuna posizione recente o nessun POI: il blocco non c'e'
        return None, q, refusal(topic), "neg"
    if x < 0.3 and cat:  # il blocco c'e' ma senza la categoria chiesta
        radius, pois = nearby_pois(rng, avoid=cat, surnames=surnames, places=places)
        return poi_context(pois, radius, lang), q, refusal(topic), "neg"
    radius, pois = nearby_pois(rng, must=cat, surnames=surnames, places=places)
    block = poi_context(pois, radius, lang)
    if cat is None:
        refs = [f"{_poi_ref(p, lang)} ({p[2]} m)" for p in pois[:3]]
        listed = refs[0] if len(refs) == 1 else ", ".join(refs[:-1]) + (" and " if en else " e ") + refs[-1]
        if en:
            answer = f"Within {radius} m of your position, the closest " + ("is " if len(refs) == 1 else "are ") + listed + "."
        else:
            answer = f"Entro {radius} m dalla tua posizione " + ("il più vicino è " if len(refs) == 1 else "i più vicini sono ") + listed + "."
        return block, q, answer, "pos"
    found = [p for p in pois if p[0] == cat]
    first = found[0]
    if first[1] == CATS[cat][4]:
        sentences = [f"{lead} is {first[2]} m away." if en else f"{lead} è a {first[2]} m."]
    else:
        sentences = [f"{lead} is {first[1]}, {first[2]} m away." if en else f"{lead} è {first[1]}, a {first[2]} m."]
    if first[3]:
        sentences.append(f"Hours: {first[3]}." if en else f"Orari: {first[3]}.")
    if len(found) > 1 and found[1][1] != CATS[cat][4]:
        sentences.append(f"There is also {found[1][1]}, {found[1][2]} m away." if en else f"C'è anche {found[1][1]}, a {found[1][2]} m.")
    return block, q, " ".join(sentences), "pos"


# Mezzi: nome nel contesto (promptName di TravelAssistant.kt, IT/EN), peso, minuti tra due corse.
MODES = {
    "BUS": ("Autobus", "Bus", 50, (6, 30)), "TRAM": ("Tram", "Tram", 15, (5, 15)), "METRO": ("Metro", "Metro", 10, (3, 8)),
    "TRAIN": ("Treno", "Train", 10, (15, 60)), "TROLLEYBUS": ("Filobus", "Trolleybus", 5, (8, 20)),
    "FERRY": ("Traghetto", "Ferry", 4, (30, 90)), "CABLE": ("Funivia", "Cable car", 2, (10, 30)),
    "MONORAIL": ("Monorotaia", "Monorail", 1, (5, 15)), "OTHER": ("Linea", "Line", 3, (10, 40)),
}
HEADSIGNS = {"it": ["Stazione Centrale", "Ospedale", "Porto", "Aeroporto", "Stadio", "Università", "Cimitero", "Centro"],
             "es": ["Estación Central", "Hospital", "Puerto", "Aeropuerto", "Universidad", "Centro", "Playa"],
             "fr": ["Gare Centrale", "Hôpital", "Port", "Aéroport", "Université", "Centre-ville", "Plage"],
             "de": ["Hauptbahnhof", "Krankenhaus", "Hafen", "Flughafen", "Universität", "Stadtmitte", "Friedhof"],
             "en": ["Central Station", "Hospital", "Harbour", "Airport", "University", "Town Centre", "Beach"]}


def _line(rng, mode):
    n = rng.randint(1, 99)
    return {"BUS": rng.choice([str(n), f"N{n % 20 + 1}", f"{rng.randint(100, 999)}", f"C{n % 9 + 1}"]),
            "TRAM": rng.choice([str(n % 20 + 1), f"T{n % 9 + 1}"]), "METRO": rng.choice([f"M{n % 5 + 1}", "ABCDE"[n % 5], f"L{n % 9 + 1}"]),
            "TRAIN": rng.choice(["S", "R", "RE", "RB"]) + str(n % 30 + 1), "FERRY": f"F{n % 6 + 1}"}.get(mode, str(n % 12 + 1))


def departures(rng, headsigns=HEADSIGNS):
    """Partenze sintetiche delle fermate vicine come le restituisce TransitBoard (ordinate per minuti d'attesa, al
    massimo 10 nella finestra di 180 minuti): [(minuto del giorno, tra minuti, mezzo, linea, direzione o None)]."""
    locale = rng.choice(list(headsigns))
    now = rng.randint(0, 1439)
    modes, weights = list(MODES), [m[2] for m in MODES.values()]
    items, lines = [], set()
    for _ in range(rng.randint(1, 4)):
        mode = rng.choices(modes, weights)[0]
        line = _line(rng, mode)
        if (mode, line) in lines:
            continue
        lines.add((mode, line))
        lo, hi = MODES[mode][3]
        for head in rng.sample(headsigns[locale], rng.randint(1, 2)):
            head = head if rng.random() < 0.85 else None
            every = rng.randint(lo, hi)
            items += [((now + t) % 1440, t, mode, line, head) for t in range(rng.randint(0, every - 1), TRANSIT_WINDOW + 1, every)]
    return sorted(items, key=lambda d: d[1])[:TRANSIT_MAX]


# Domande (attivano isTransitQuestion e non isNearbyQuestion): generiche, per mezzo, per linea ({l}); inizio della
# risposta per mezzo; argomento del rifiuto.
TRANSIT_ASK = {
    "it": {
        "any": ["Quali sono le prossime partenze?", "A che ora è la prossima partenza?", "Qual è la prossima corsa?",
                "Mi dici gli orari delle prossime partenze?"],
        "mode": {"BUS": ["Quando passa il prossimo autobus?", "A che ora passa il prossimo bus?"], "TRAM": ["Quando passa il prossimo tram?"],
                 "METRO": ["Quando passa la prossima metro?", "A che ora è la prossima metropolitana?"],
                 "TRAIN": ["Quando parte il prossimo treno?"], "TROLLEYBUS": ["Quando passa il prossimo filobus?"],
                 "FERRY": ["Quando parte il prossimo traghetto?"]},
        "line": {"BUS": ["Quando passa il prossimo autobus {l}?", "A che ora c'è il bus {l}?"], "TRAM": ["Quando passa il tram {l}?"],
                 "METRO": ["Quando passa la metro {l}?"], "TRAIN": ["A che ora parte il treno {l}?"], "FERRY": ["Quando parte il traghetto {l}?"]},
        "lead": {"BUS": "Il prossimo autobus", "TRAM": "Il prossimo tram", "METRO": "La prossima metro", "TRAIN": "Il prossimo treno",
                 "TROLLEYBUS": "Il prossimo filobus", "FERRY": "Il prossimo traghetto"},
        "topic": {"any": "sulle prossime partenze dei mezzi pubblici", "BUS": "sui prossimi autobus", "TRAM": "sui prossimi tram",
                  "METRO": "sulle prossime metro", "TRAIN": "sui prossimi treni", "TROLLEYBUS": "sui prossimi filobus",
                  "FERRY": "sui prossimi traghetti"},
    },
    "en": {
        "any": ["What are the next departures?", "When is the next departure?", "What does the departure board say?",
                "Can you read me the timetable for the next departures?"],
        "mode": {"BUS": ["When is the next bus?", "What time does the next bus come?"], "TRAM": ["When is the next tram?"],
                 "METRO": ["When does the next metro leave?", "When is the next subway?"], "TRAIN": ["When does the next train leave?"],
                 "TROLLEYBUS": ["When is the next trolleybus departure?"], "FERRY": ["When does the next ferry leave?"]},
        "line": {"BUS": ["When is the next bus {l}?", "What time is the bus {l}?"], "TRAM": ["When does tram {l} come?"],
                 "METRO": ["When is the next metro {l}?"], "TRAIN": ["What time does train {l} leave?"], "FERRY": ["When does ferry {l} leave?"]},
        "lead": {"BUS": "The next bus", "TRAM": "The next tram", "METRO": "The next metro", "TRAIN": "The next train",
                 "TROLLEYBUS": "The next trolleybus", "FERRY": "The next ferry"},
        "topic": {"any": "about the next public transport departures", "BUS": "about the next buses", "TRAM": "about the next trams",
                  "METRO": "about the next metro trains", "TRAIN": "about the next trains", "TROLLEYBUS": "about the next trolleybuses",
                  "FERRY": "about the next ferries"},
    },
}


def _dep(d, lang, with_mode=True):
    """Una partenza in una risposta: "Autobus 22 per Centrale alle 14:20 (tra 18 min)"."""
    en, (m, n, mode, line, head) = lang == "en", d
    what = (f"{MODES[mode][1 if en else 0]} {line}" if with_mode else (f"line {line}" if en else f"la linea {line}"))
    to = ((" to " if en else " per ") + head) if head else ""
    return f"{what}{to} " + (f"at {clock(m)} (in {n} min)" if en else f"alle {clock(m)} (tra {n} min)")


def transit_example(rng, lang, refusal, asks=None, headsigns=HEADSIGNS):
    """(blocco di contesto o None, domanda, risposta, "pos"/"neg") per una domanda sui mezzi pubblici. [asks]: altre
    domande con la struttura di TRANSIT_ASK[lang]; [headsigns]: altre direzioni per lingua locale."""
    en, T = lang == "en", asks or TRANSIT_ASK[lang]
    x = rng.random()
    if x < 0.12:  # nessuna fermata vicina o nessuna posizione: il blocco non c'e'
        mode = rng.choice([None, None] + list(T["mode"]))
        return None, rng.choice(T["mode"][mode] if mode else T["any"]), refusal(T["topic"][mode or "any"]), "neg"
    if x < 0.2:  # orari scaduti
        expired = date(2025, 1, 1) + timedelta(days=rng.randint(0, 900))
        q = rng.choice(T["any"] + [q for qs in T["mode"].values() for q in qs])
        when = expired.isoformat() if en else f"{expired.day}/{expired.month}/{expired.year}"
        answer = (f"The installed public transport timetables expired on {when}, so I can't tell you the next departures." if en else
                  f"Gli orari dei mezzi pubblici installati sono scaduti il {when}, quindi non posso indicarti le prossime partenze.")
        return transit_context(("expired", expired), lang), q, answer, "pos"
    if x < 0.25:  # nessuna partenza nelle prossime ore
        q = rng.choice(T["any"] + [q for qs in T["mode"].values() for q in qs])
        answer = ("There are no departures in the next hours from the stops nearby." if en else
                  "Dalle fermate qui vicino non ci sono partenze nelle prossime ore.")
        return transit_context(("departures", []), lang), q, answer, "pos"
    items = departures(rng, headsigns)
    block = transit_context(("departures", items), lang)
    present = {d[2] for d in items}
    if x < 0.37:  # mezzo chiesto che nel tabellone non c'e'
        absent = [m for m in T["mode"] if m not in present]
        if absent:
            mode = rng.choice(absent)
            return block, rng.choice(T["mode"][mode]), refusal(T["topic"][mode]), "neg"
    if x < 0.6:
        first = items[:3]
        listed = ", ".join(_dep(d, lang) for d in first[:-1]) + (" and " if en else " e ") + _dep(first[-1], lang) if len(first) > 1 else _dep(first[0], lang)
        answer = ((f"The next departures are: {listed}." if len(first) > 1 else f"The next departure is {listed}.") if en else
                  (f"Le prossime partenze sono: {listed}." if len(first) > 1 else f"La prossima partenza è {listed}."))
        return block, rng.choice(T["any"]), answer, "pos"
    lines = [(d[2], d[3]) for d in items if d[2] in T["line"]]
    if x < 0.8 or not lines:
        mode = rng.choice(sorted(present & set(T["mode"])) or [None])
        if mode is None:
            return block, rng.choice(T["any"]), (f"The next departure is {_dep(items[0], lang)}." if en else
                                                f"La prossima partenza è {_dep(items[0], lang)}."), "pos"
        same = [d for d in items if d[2] == mode]
        answer = f"{T['lead'][mode]} is {_dep(same[0], lang, False)}." if en else f"{T['lead'][mode]} è {_dep(same[0], lang, False)}."
        if len(same) > 1:
            answer += f" After that, {_dep(same[1], lang, False)}." if en else f" Dopo c'è {_dep(same[1], lang, False)}."
        return block, rng.choice(T["mode"][mode]), answer, "pos"
    mode, line = rng.choice(lines)
    same = [d for d in items if (d[2], d[3]) == (mode, line)]
    answer = (f"The next {MODES[mode][1].lower()} {line} " + (f"to {same[0][4]} " if same[0][4] else "") + f"leaves at {clock(same[0][0])}, in {same[0][1]} min."
              if en else
              f"{T['lead'][mode]} {line} " + (f"per {same[0][4]} " if same[0][4] else "") + f"parte alle {clock(same[0][0])}, tra {same[0][1]} min.")
    if len(same) > 1:
        answer += f" The one after is at {clock(same[1][0])} (in {same[1][1]} min)." if en else f" La corsa dopo è alle {clock(same[1][0])} (tra {same[1][1]} min)."
    return block, rng.choice(T["line"][mode]).format(l=line), answer, "pos"
