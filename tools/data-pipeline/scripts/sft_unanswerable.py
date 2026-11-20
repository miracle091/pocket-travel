#!/usr/bin/env python3
"""Rifiuti del v12 sulle domande a cui le guide non rispondono (--unanswerable-rows di generate_sft.py): nel contesto una
sezione vicina all'argomento, della categoria giusta e della citta' o del paese nominati (la sezione Come arrivare per una
domanda sull'aeroporto, Dove mangiare per un menu senza glutine), che pero' non risponde alla domanda precisa; la
risposta e' la frase di rifiuto del dataset, con il tema della domanda. Senza, il modello rispondeva con la sezione vicina
(i parcheggi di Bologna a una domanda sul parcheggio dell'aeroporto, i musei di Firenze a una domanda sui cani).

Domande da schemi fissi (TEMPLATES), nessun testo scritto da un modello. Ogni schema ha le radici che la sezione deve
avere (vicine: e' davvero vicina) e quelle che il contesto non deve avere (assenti: le parole della domanda precisa e di
una risposta possibile, come fanno le altre righe del generatore con covers; una radice in piu' toglie solo righe). Gli
schemi su un aeroporto o un treno della citta' saltano le sezioni che dicono che la citta' non ne ha o che danno il piu'
vicino (NOT_HERE), e quelli sull'aeroporto "di {r}" vogliono un aeroporto con il nome della citta' (names_own_airport):
la domanda presupporrebbe una cosa falsa. Temi lontani da quelli della serie unanswerable di
eval_retrieval.py (parcheggi, oggetti smarriti e servizi degli aeroporti, animali, dentisti e altre visite mediche,
veterinari, sedie a rotelle, passeggini, stampe, concerti, colonnine, pagamenti col telefono, parrucchieri, lavanderie,
monopattini, droni, uffici postali), che resta il controllo: nessuna domanda quasi uguale a una di quella serie o della
serie control (is_measured).
"""
import re
from collections import Counter

import audit_sft
import generate_sft_dataset as it

# Radici all'inizio di una parola; con "$" in fondo, parola intera ("volo$" non prende "volontari")
AIRPORT_IT, TRAIN_IT = ("aeroport", "voli$", "volo$"), ("treno$", "treni$", "ferrovi")
TRANSIT_IT = ("autobus", "tram$", "metropolitan", "taxi", "filobus")
MUSEUM_IT = ("muse", "galleri", "pinacotec")
LODGING_IT = ("albergh", "hotel", "ostell", "pension")
FOOD_IT = ("ristorant", "trattori", "osteri", "pizzeri")
CAR_RENTAL_IT = ("autonoleggi", "noleggio auto", "noleggio di auto", "auto a noleggio", "noleggiare un'auto", "noleggiare l'auto")
ROAD_IT = ("strad", "autostrad", "auto$", "automobil")
PHONE_IT = ("telefon", "internet", "cellular", "sim$", "operator")
AIRPORT_EN, TRAIN_EN = ("airport", "flight"), ("train$", "trains$", "railway", "rail$")
TRANSIT_EN = ("bus$", "buses$", "tram$", "trams$", "metro$", "taxi", "subway")
MUSEUM_EN = ("museum", "galler")
LODGING_EN = ("hotel", "hostel", "guesthouse", "guest house")
FOOD_EN = ("restaurant", "trattoria", "pizzeria", "eatery", "eateries")
CAR_RENTAL_EN = ("car rental", "rental car", "car hire", "hire car", "rent a car", "hire a car", "rent-a-car")
ROAD_EN = ("road", "motorway", "highway", "car$", "cars$", "driv")
PHONE_EN = ("phone", "internet", "mobile", "sim$", "operator")
# Schemi che presuppongono un aeroporto o una stazione della citta': fuori le sezioni che li negano o danno il piu' vicino
PRESUPPOSED = {AIRPORT_IT, TRAIN_IT, AIRPORT_EN, TRAIN_EN}
NOT_HERE = re.compile(r"non ha (un |una )?(aeroport|stazion)|non c'[eè] (un |una )?(aeroport|stazion)|non (è|e') servit|"
                      r"pi(ù|u'|u) vicin|nessun (aeroport|collegament)|nessuna stazion|"
                      r"\bno (airport|railway|train station)|(does not|doesn't) have (an |a )?(airport|railway|train)|"
                      r"nearest|closest|not served by|was closed|no longer|non (è|e') più|soppress|dismess|"
                      r"destroyed|distrutt|not been rebuilt", re.I)
# Schemi sull'aeroporto della citta' stessa: la sezione deve nominarlo con il nome della citta' ("Aeroporto di Verona" non
# vale per Malcesine, "fly to Porto airport" non vale per Famalicão)
OWN_AIRPORT = ("aeroporto di {r}", "{r} airport")


def names_own_airport(text, name):
    """True se [text] ha il nome di un aeroporto fatto con [name]: "Aeroporto (internazionale) di <name>", "<name>
    (International) Airport" ("L'aeroporto di Napoli dista 90 km da <name>" no)."""
    n = re.escape(name.lower())
    kind = r"(?: (?:international|regional|municipal|public|domestic|county))*"
    return bool(re.search(rf"aeroporto(?: internazionale| regionale| civile)? (?:di )?{n}(?![^\W\d_])|"
                          rf"(?<![^\W\d_]){n}{kind} airport", text.lower()))


# (ambito, categoria, domanda con {r}, radici vicine, radici assenti, tema del rifiuto); ambito "citta" (sezioni delle
# pagine delle citta') o "paese" (sezioni della guida del paese)
TEMPLATES = {
    "it": [
        ("citta", "ARRIVARE", "All'aeroporto di {r} c'è un posto per dormire tra un volo e l'altro?", AIRPORT_IT,
         ("dorm", "nott", "albergh", "hotel", "lounge", "sala", "transit"), "su dove dormire in aeroporto"),
        ("citta", "ARRIVARE", "Il mio volo per {r} è stato cancellato: che rimborso mi spetta?", AIRPORT_IT,
         ("rimbors", "cancell", "annull", "risarc", "diritt", "compens"), "sui rimborsi dei voli cancellati"),
        ("citta", "ARRIVARE", "A che ora apre il banco del check-in all'aeroporto di {r}?", AIRPORT_IT,
         ("check", "accettazion", "banc", "orari", "apr", "apert"), "sugli orari del check-in"),
        ("citta", "ARRIVARE", "Il treno per {r} è arrivato con un'ora di ritardo: come chiedo l'indennizzo?", TRAIN_IT,
         ("ritard", "indenni", "rimbors", "risarc", "compens"), "sugli indennizzi per i treni in ritardo"),
        ("citta", "ARRIVARE", "Si può caricare la bicicletta sul treno per {r}?", TRAIN_IT, ("bici", "cicl"),
         "sul trasporto delle biciclette in treno"),
        ("citta", "ARRIVARE", "I treni per {r} hanno la carrozza ristorante?", TRAIN_IT,
         ("ristor", "bar$", "vagon", "carrozz", "bistrot", "cibo", "mangi"), "sulle carrozze ristorante"),
        ("citta", "TRASPORTI", "Quanto costa l'abbonamento mensile ai mezzi pubblici di {r}?", TRANSIT_IT,
         ("abbonament", "mensil", "tessera"), "sugli abbonamenti ai mezzi pubblici"),
        ("citta", "TRASPORTI", "A che ora passa l'ultima corsa notturna dell'autobus a {r}?", TRANSIT_IT,
         ("nottur", "notte", "mezzanott", "ultim"), "sulle corse notturne"),
        ("citta", "TRASPORTI", "Ho preso una multa per sosta vietata a {r}: come la pago?", ("parchegg", "sosta"),
         ("mult", "sanzion", "contravvenz", "vigil"), "sulle multe per la sosta"),
        ("citta", "TRASPORTI", "L'auto a noleggio presa a {r} ha un graffio: come contesto l'addebito dei danni?", CAR_RENTAL_IT,
         ("dann", "graff", "contest", "addebit", "cauzion", "assicura"), "sulle contestazioni dei danni all'auto a noleggio"),
        ("citta", "TRASPORTI", "I taxi di {r} hanno il seggiolino per i bambini?", ("taxi",), ("seggiolin", "bambin", "neonat"),
         "sui seggiolini nei taxi"),
        ("citta", "TRASPORTI", "Ci sono sconti per gli anziani sui mezzi pubblici di {r}?", TRANSIT_IT,
         ("anzian", "scont", "ridott", "riduz", "senior", "over", "pension", "gratuit", "gratis"), "sugli sconti per gli anziani"),
        ("citta", "COSA_VEDERE", "Nei musei di {r} si possono fare fotografie con il flash?", MUSEUM_IT, ("foto", "flash"),
         "sulle regole per le foto nei musei"),
        ("citta", "COSA_VEDERE", "C'è una riduzione per studenti nei musei di {r}?", MUSEUM_IT,
         ("ridott", "riduz", "scont", "student", "gratuit", "gratis", "bigliett", "prezz", "euro", "€"),
         "sulle riduzioni per studenti"),
        ("citta", "COSA_VEDERE", "Nei musei di {r} c'è un guardaroba per lasciare lo zaino?", MUSEUM_IT,
         ("guardarob", "zain", "armadiett", "deposit"), "sui guardaroba dei musei"),
        ("citta", "COSA_VEDERE", "I musei di {r} offrono audioguide in italiano?", MUSEUM_IT, ("audioguid", "guid", "italian", "lingu"),
         "sulle audioguide"),
        ("citta", "COSA_VEDERE", "Che abbigliamento serve per entrare nelle chiese di {r}?", ("chies", "cattedral", "basilic", "duomo"),
         ("abbigliam", "vest", "spall", "copert", "copr", "gamb", "ginocch", "gonn", "pantalon", "decor", "velo$"),
         "sull'abbigliamento per le chiese"),
        ("citta", "ALLOGGIO", "Ho trovato un addebito in più sul conto dell'albergo a {r}: come lo contesto?", LODGING_IT,
         ("addebit", "contest", "reclam", "conto", "fattur"), "sui reclami per il conto dell'albergo"),
        ("citta", "ALLOGGIO", "Gli alberghi di {r} chiedono la tassa di soggiorno?", LODGING_IT, ("tass", "impost"),
         "sulla tassa di soggiorno"),
        ("citta", "ALLOGGIO", "Quali alberghi di {r} mettono a disposizione una culla per il neonato?", LODGING_IT,
         ("cull", "neonat", "bambin", "famigl"), "sulle culle negli alberghi"),
        ("citta", "ALLOGGIO", "Negli ostelli di {r} ci sono armadietti con il lucchetto?", ("ostell",),
         ("armadiett", "lucchett", "cassett", "deposit"), "sugli armadietti degli ostelli"),
        ("citta", "ALLOGGIO", "A che ora si lascia la camera negli alberghi di {r}?", LODGING_IT, ("check", "partenz", "orari"),
         "sugli orari di partenza dagli alberghi"),
        ("citta", "CIBO_BEVANDE", "Quale ristorante di {r} ha un menu senza glutine?", FOOD_IT, ("glutin", "celiac"),
         "sui menu senza glutine"),
        ("citta", "CIBO_BEVANDE", "I ristoranti di {r} accettano prenotazioni per un gruppo di venti persone?", FOOD_IT,
         ("prenot", "grupp", "comitiv"), "sulle prenotazioni per gruppi"),
        ("citta", "CIBO_BEVANDE", "C'è un ristorante aperto la sera di Capodanno a {r}?", FOOD_IT,
         ("capodann", "festiv", "apert", "chius"), "sulle aperture a Capodanno"),
        ("citta", "CIBO_BEVANDE", "Nei ristoranti di {r} il coperto si paga a parte?", FOOD_IT, ("copert", "servizio", "manci", "conto"),
         "sul coperto"),
        ("citta", "SHOPPING", "Fino a che ora sono aperti i supermercati di {r} la domenica?", ("negoz", "mercat", "bottegh"),
         ("supermerc", "domenic", "orari", "apert", "chius"), "sugli orari dei supermercati"),
        ("citta", "SHOPPING", "Posso avere il rimborso dell'IVA sugli acquisti fatti a {r}?", ("negoz", "mercat", "bottegh"),
         ("iva$", "tax", "rimbors", "tasse"), "sul rimborso dell'IVA"),
        ("citta", "SHOPPING", "I negozi di {r} spediscono gli acquisti in Italia?", ("negoz", "bottegh"), ("sped", "corrier", "posta"),
         "sulle spedizioni degli acquisti"),
        ("citta", "SICUREZZA", "Mi hanno rubato il telefono a {r}: come faccio a bloccarlo?", ("borseggi", "furt", "ladr", "scipp"),
         ("telefon", "cellular", "smartphone", "blocc", "sim$"), "sul blocco di un telefono rubato"),
        ("citta", "SICUREZZA", "A {r} si può girare con un coltellino in tasca?", ("borseggi", "furt", "ladr", "scipp", "aggress"),
         ("coltell", "arma", "armi", "lam"), "sul porto di coltelli"),
        ("citta", "CONNETTIVITA", "Il wifi della biblioteca di {r} chiede una password?", ("wifi", "wi-fi", "internet"),
         ("bibliotec", "password"), "sul wifi delle biblioteche"),
        ("citta", "CONNETTIVITA", "Dove posso ricaricare il telefono gratis a {r}?", ("wifi", "wi-fi", "internet", "sim$"),
         ("ricaric", "prese", "presa", "caricabatt"), "su dove ricaricare il telefono"),
        ("paese", "SALUTE", "Gli ospedali in {r} accettano la tessera sanitaria europea?", ("ospedal", "medic", "clinic", "sanit"),
         ("tessera", "team$", "europe", "assicura", "convenzion"), "sulla tessera sanitaria europea"),
        ("paese", "SALUTE", "Serve la ricetta per comprare l'ibuprofene in farmacia in {r}?", ("farmac",),
         ("ricett", "ibuprof", "prescri", "antidolorif"), "sui farmaci senza ricetta"),
        ("paese", "SALUTE", "Gli ospedali di {r} hanno medici che parlano italiano?", ("ospedal", "medic", "clinic"),
         ("italian", "lingu", "interpret"), "sui medici che parlano italiano"),
        ("paese", "SALUTE", "Le lenti a contatto si trovano facilmente in farmacia in {r}?", ("farmac",), ("lent", "ottic", "occhial"),
         "sulle lenti a contatto"),
        ("paese", "DOGANE", "Quante sigarette elettroniche posso portare in {r}?", ("visto$", "visti$", "passaport", "frontier", "dogan"),
         ("sigarett", "svap", "tabacc", "nicotin"), "sulle sigarette elettroniche in dogana"),
        ("paese", "DOGANE", "Quanto formaggio posso portare in {r} nel bagaglio?", ("visto$", "visti$", "passaport", "frontier", "dogan"),
         ("formagg", "aliment", "cibo", "carne", "latt", "prodott", "vegetal"), "sui cibi ammessi in dogana"),
        ("paese", "DOGANE", "Quanto si aspetta di solito al controllo passaporti all'arrivo in {r}?", ("passaport", "frontier"),
         ("attes", "aspett", "cod", "minut", "tempi"), "sui tempi di attesa alla frontiera"),
        ("paese", "TRASPORTI", "Quanto costa il pedaggio autostradale per attraversare {r}?", ROAD_IT,
         ("pedagg", "vignett", "casell", "telepass", "pagament", "pagare", "gratuit"), "sui pedaggi autostradali"),
        ("paese", "TRASPORTI", "Con la patente italiana posso guidare uno scooter di 125 cc in {r}?", ("strad", "guidare", "patent"),
         ("scooter", "motorin", "moto", "ciclomot", "125"), "sulla guida degli scooter"),
        ("paese", "TRASPORTI", "In {r} i distributori di benzina sono aperti di notte?", ROAD_IT,
         ("benzin", "carburant", "distribut", "rifornim", "nott"), "sui distributori aperti di notte"),
        ("paese", "ACQUISTI", "Quanto costa la commissione per prelevare al bancomat in {r} con una carta straniera?",
         ("bancomat", "carta$", "carte$", "contant"), ("commission", "tariff", "cost", "addebit", "tass", "spes", "gratuit"),
         "sulle commissioni dei prelievi"),
        ("paese", "ACQUISTI", "Le banche in {r} sono aperte il sabato mattina?", ("banca$", "banche$", "bancari", "valut", "moneta"),
         ("sabat", "orari", "apert", "chius", "luned", "venerd"), "sugli orari delle banche"),
        ("paese", "ACQUISTI", "Posso pagare il taxi con la carta di credito in {r}?", ("carta$", "carte$", "bancomat"),
         ("taxi", "tassist"), "sui pagamenti nei taxi"),
        ("paese", "USI_COSTUMI", "In {r} è educato portare un regalo quando si è invitati a cena?",
         ("usanz", "tradizi", "rispett", "educat", "cortes", "comport"), ("regal", "dono", "doni", "invit", "ospit"),
         "sui regali a chi ti invita"),
        ("paese", "USI_COSTUMI", "In {r} si può fumare nei bar e nei ristoranti?", ("usanz", "tradizi", "rispett", "educat", "comport"),
         ("fum", "sigarett", "tabacc"), "sul fumo nei locali"),
        ("paese", "CONNETTIVITA", "In {r} quanto costa il francobollo per una cartolina verso l'Italia?", PHONE_IT,
         ("francobol", "cartolin", "posta", "postal", "sped"), "sulle tariffe postali"),
        ("paese", "CONNETTIVITA", "In {r} bisogna registrare il telefono straniero per usare una SIM locale?", PHONE_IT,
         ("registr", "imei", "passaport", "document"), "sulla registrazione dei telefoni"),
    ],
    "en": [
        ("citta", "ARRIVARE", "Is there somewhere to sleep between flights at {r} airport?", AIRPORT_EN,
         ("sleep", "overnight", "night", "hotel", "lounge", "transit"), "about sleeping at the airport"),
        ("citta", "ARRIVARE", "My flight to {r} was cancelled: what refund am I entitled to?", AIRPORT_EN,
         ("refund", "cancel", "compensat", "rights", "entitle"), "about refunds for cancelled flights"),
        ("citta", "ARRIVARE", "What time does the check-in desk open at {r} airport?", AIRPORT_EN,
         ("check", "desk", "opening", "hours", "open"), "about check-in times"),
        ("citta", "ARRIVARE", "My train to {r} arrived an hour late: how do I claim compensation?", TRAIN_EN,
         ("delay", "late", "compensat", "refund", "claim"), "about compensation for delayed trains"),
        ("citta", "ARRIVARE", "Can I take my bicycle on the train to {r}?", TRAIN_EN, ("bicycl", "bike", "cycl"),
         "about bicycles on trains"),
        ("citta", "ARRIVARE", "Do trains to {r} have a dining car?", TRAIN_EN,
         ("dining", "restaurant", "buffet", "bar$", "café", "cafe", "catering", "food", "meal"), "about dining cars"),
        ("citta", "TRASPORTI", "How much is a monthly public transport pass in {r}?", TRANSIT_EN, ("monthly", "month", "pass"),
         "about public transport passes"),
        ("citta", "TRASPORTI", "What time does the last night bus run in {r}?", TRANSIT_EN, ("night", "midnight", "last", "late"),
         "about night buses"),
        ("citta", "TRASPORTI", "I got a parking fine in {r}: how do I pay it?", ("parking",), ("fine", "ticket", "penalt", "warden"),
         "about parking fines"),
        ("citta", "TRASPORTI", "My rental car from {r} has a scratch: how do I dispute the damage charge?", CAR_RENTAL_EN,
         ("damage", "scratch", "dispute", "charge", "deposit", "insur"), "about damage disputes on rental cars"),
        ("citta", "TRASPORTI", "Do taxis in {r} have child seats?", ("taxi", "cab$", "cabs$"), ("child", "seat", "infant", "baby"),
         "about child seats in taxis"),
        ("citta", "TRASPORTI", "Are there senior discounts on public transport in {r}?", TRANSIT_EN,
         ("senior", "discount", "reduced", "concession", "elderly", "pension", "free"), "about senior discounts"),
        ("citta", "COSA_VEDERE", "Can I take flash photos in the museums of {r}?", MUSEUM_EN, ("photo", "flash", "camera"),
         "about photography rules in museums"),
        ("citta", "COSA_VEDERE", "Is there a student discount at the museums in {r}?", MUSEUM_EN,
         ("student", "discount", "reduced", "concession", "free", "ticket", "price", "admission", "fee", "€", "$", "£"),
         "about student discounts"),
        ("citta", "COSA_VEDERE", "Is there a cloakroom for backpacks at the museums in {r}?", MUSEUM_EN,
         ("cloakroom", "backpack", "bag", "locker", "storage"), "about museum cloakrooms"),
        ("citta", "COSA_VEDERE", "Do the museums in {r} offer audio guides in English?", MUSEUM_EN, ("audio", "guide", "english", "language"),
         "about audio guides"),
        ("citta", "COSA_VEDERE", "What should I wear to enter the churches in {r}?", ("church", "cathedral", "basilica"),
         ("wear", "dress", "cloth", "shoulder", "cover", "knee", "trouser", "shorts", "modest", "skirt", "scarf", "sleeve"),
         "about dress codes for churches"),
        ("citta", "ALLOGGIO", "I found an extra charge on my hotel bill in {r}: how do I dispute it?", LODGING_EN,
         ("charge", "bill", "dispute", "complain", "invoice"), "about disputing a hotel bill"),
        ("citta", "ALLOGGIO", "Do hotels in {r} charge a tourist tax?", LODGING_EN, ("tax",), "about tourist taxes"),
        ("citta", "ALLOGGIO", "Which hotels in {r} provide a cot for a baby?", LODGING_EN, ("cot", "crib", "baby", "infant", "child", "famil"),
         "about cots in hotels"),
        ("citta", "ALLOGGIO", "Do the hostels in {r} have lockers with padlocks?", ("hostel",), ("locker", "padlock", "safe"),
         "about hostel lockers"),
        ("citta", "ALLOGGIO", "What time is check-out at hotels in {r}?", LODGING_EN, ("check", "depart", "noon"),
         "about check-out times"),
        ("citta", "CIBO_BEVANDE", "Which restaurant in {r} has a gluten-free menu?", FOOD_EN, ("gluten", "coeliac", "celiac"),
         "about gluten-free menus"),
        ("citta", "CIBO_BEVANDE", "Do restaurants in {r} take bookings for a group of twenty?", FOOD_EN,
         ("book", "reserv", "group", "party"), "about group bookings"),
        ("citta", "CIBO_BEVANDE", "Is there a restaurant open on New Year's Eve in {r}?", FOOD_EN,
         ("new year", "holiday", "open", "closed"), "about opening on New Year's Eve"),
        ("citta", "CIBO_BEVANDE", "Do restaurants in {r} add a cover charge?", FOOD_EN, ("cover", "service", "tip", "bill", "charge"),
         "about cover charges"),
        ("citta", "SHOPPING", "Until what time are supermarkets in {r} open on Sundays?", ("shop", "market", "store"),
         ("supermarket", "sunday", "hours", "open", "closed"), "about supermarket opening hours"),
        ("citta", "SHOPPING", "Can I get a VAT refund on purchases made in {r}?", ("shop", "market", "store"),
         ("vat$", "tax", "refund"), "about VAT refunds"),
        ("citta", "SHOPPING", "Do shops in {r} ship purchases abroad?", ("shop", "store"), ("ship", "deliver", "courier", "post", "mail"),
         "about shipping purchases"),
        ("citta", "SICUREZZA", "My phone was stolen in {r}: how do I block it?", ("pickpocket", "theft", "thie", "steal", "snatch"),
         ("phone", "mobile", "block", "sim$"), "about blocking a stolen phone"),
        ("citta", "SICUREZZA", "Can I carry a pocket knife in {r}?", ("pickpocket", "theft", "thie", "steal", "snatch", "mugg"),
         ("knife", "knives", "weapon", "blade"), "about carrying knives"),
        ("citta", "CONNETTIVITA", "Does the library wifi in {r} need a password?", ("wifi", "wi-fi", "internet"),
         ("librar", "password"), "about library wifi"),
        ("citta", "CONNETTIVITA", "Where can I charge my phone for free in {r}?", ("wifi", "wi-fi", "internet", "sim$"),
         ("charg", "socket", "plug", "outlet"), "about where to charge your phone"),
        ("paese", "SALUTE", "Do hospitals in {r} accept the European Health Insurance Card?", ("hospital", "medic", "clinic", "health"),
         ("ehic", "ghic", "europe", "insur", "card"), "about the European Health Insurance Card"),
        ("paese", "SALUTE", "Do I need a prescription to buy ibuprofen at a pharmacy in {r}?", ("pharmac",),
         ("prescri", "ibuprofen", "painkill", "over the counter", "over-the-counter"), "about medicines without a prescription"),
        ("paese", "SALUTE", "Do hospitals in {r} have doctors who speak English?", ("hospital", "medic", "clinic"),
         ("english", "language", "interpret", "speak"), "about English-speaking doctors"),
        ("paese", "SALUTE", "Are contact lenses easy to find at pharmacies in {r}?", ("pharmac",), ("lens", "optic", "glasses"),
         "about contact lenses"),
        ("paese", "DOGANE", "How many e-cigarettes can I bring into {r}?", ("visa", "passport", "border", "customs"),
         ("cigar", "e-cig", "vape", "vaping", "tobacco", "nicotin"), "about e-cigarettes at customs"),
        ("paese", "DOGANE", "How much cheese can I bring into {r} in my luggage?", ("visa", "passport", "border", "customs"),
         ("cheese", "food", "dairy", "meat", "product", "plant"), "about food allowed through customs"),
        ("paese", "DOGANE", "How long is the usual wait at passport control when arriving in {r}?", ("passport", "border"),
         ("wait", "queue", "line", "minute", "hour"), "about waiting times at the border"),
        ("paese", "TRASPORTI", "How much are motorway tolls to drive across {r}?", ROAD_EN,
         ("toll", "vignette", "free", "pay"), "about motorway tolls"),
        ("paese", "TRASPORTI", "Can I ride a 125 cc scooter in {r} with a foreign car licence?", ("road", "driv", "licen"),
         ("scooter", "moped", "motorbike", "motorcycle", "125"), "about riding scooters"),
        ("paese", "TRASPORTI", "Are petrol stations in {r} open at night?", ROAD_EN,
         ("petrol", "fuel", "gas station", "filling", "night"), "about petrol stations open at night"),
        ("paese", "ACQUISTI", "What is the fee for withdrawing cash at an ATM in {r} with a foreign card?", ("atm", "card", "cash"),
         ("fee", "commission", "charge", "cost", "surcharge", "free"), "about ATM withdrawal fees"),
        ("paese", "ACQUISTI", "Are banks in {r} open on Saturday mornings?", ("bank", "currenc", "money"),
         ("saturday", "hours", "open", "closed", "monday", "friday", "weekday"), "about bank opening hours"),
        ("paese", "ACQUISTI", "Can I pay for a taxi by credit card in {r}?", ("card", "atm", "cash"), ("taxi", "cab$", "cabs$"),
         "about paying in taxis"),
        ("paese", "USI_COSTUMI", "Is it polite to bring a gift when invited to dinner in {r}?",
         ("custom", "tradition", "respect", "polite", "etiquette", "rude"), ("gift", "present", "invit", "host"),
         "about gifts for your host"),
        ("paese", "USI_COSTUMI", "Can you smoke in bars and restaurants in {r}?", ("custom", "tradition", "respect", "etiquette", "rude"),
         ("smok", "cigar", "tobacco"), "about smoking in bars and restaurants"),
        ("paese", "CONNETTIVITA", "How much is a stamp for a postcard from {r} to Europe?", PHONE_EN,
         ("stamp", "postcard", "post", "postal", "mail"), "about postage rates"),
        ("paese", "CONNETTIVITA", "Do I have to register a foreign phone in {r} to use a local SIM?", PHONE_EN,
         ("regist", "imei", "passport", "document"), "about phone registration"),
    ],
}


def matcher(stems):
    """funzione(testo) -> True se il testo ha una delle radici [stems] all'inizio di una parola; una radice con "$" in
    fondo vale solo come parola intera; una che comincia con uno spazio o un simbolo vale ovunque."""
    parts = []
    for s in stems:
        whole = len(s) > 1 and s.endswith("$")  # "$" da solo e' il simbolo del dollaro
        body = re.escape(s[:-1] if whole else s)
        literal = not s[0].isalnum()  # spazio iniziale o simbolo (€, $): ovunque
        parts.append(body if literal else r"(?<![^\W\d_])" + body + (r"(?![^\W\d_])" if whole else ""))
    rx = re.compile("|".join(parts))
    return lambda text: bool(rx.search(text.lower()))


def measured_questions():
    """Insiemi di parole (audit_sft.question_words) delle domande delle serie unanswerable e control di eval_retrieval.py,
    nelle due lingue; le domande di controllo senza il posto ({c}, {o})."""
    import eval_retrieval as ev
    out = [audit_sft.question_words(q) for qs in ev.UNANSWERABLE_Q.values() for _, q in qs]
    for lang in ("it", "en"):
        for table in ev.LANGS[lang]["control"]:
            qs = [q for v in table.values() for q in v] if isinstance(table, dict) else table
            out += [audit_sft.question_words(q.replace("{c}", "").replace("{o}", "")) for q in qs]
    return out


def is_measured(question, name, measured):
    """True se [question] (con il posto [name]) ha Jaccard delle parole >= NEAR_JACCARD di audit_sft con una domanda di
    [measured], intera o senza le parole del posto: vale per qualunque regione, non solo per quella della serie."""
    words = audit_sft.question_words(question)
    bare = words - audit_sft.question_words(name)
    return any(w and len(w & m) / len(w | m) >= audit_sft.NEAR_JACCARD for w in (words, bare) for m in measured)


def unanswerable_rows(lang, L, places, n, rng, measured=None, templates=None):
    """Fino a [n] rifiuti nel formato di row() di generate_sft.py, in [lang] con le tabelle [L]. [places]: [(regione della
    riga, nome, ambito, [(categoria, corpo, tradotta)])], le citta' con ambito "citta" e i paesi con "paese". Gli schemi di
    [templates] (TEMPLATES[lang]) si usano a turno, ognuno su una sezione della sua categoria con una radice vicina e
    nessuna radice assente (e senza NOT_HERE per gli schemi di PRESUPPOSED), in un posto scelto a caso; nel contesto anche
    0-2 altre sezioni dello stesso posto senza radici assenti. Il contesto finale deve avere ancora una radice vicina e
    nessuna assente; niente domande delle serie di misura ([measured], di default measured_questions()). Le righe hanno
    "unanswerable": true."""
    measured = measured_questions() if measured is None else measured
    templates = TEMPLATES[lang] if templates is None else templates
    queues = []
    for scope, cat, question, near, absent, topic in templates:
        has_near, has_absent = matcher(near), matcher(absent)
        presupposed, own = near in PRESUPPOSED, any(o in question for o in OWN_AIRPORT)
        eligible = [(rid, name, sec, secs) for rid, name, s, secs in places if s == scope
                    for sec in secs if sec[0] == cat and has_near(sec[1]) and not has_absent(sec[1])
                    and not (presupposed and NOT_HERE.search(sec[1])) and not (own and not names_own_airport(sec[1], name))]
        rng.shuffle(eligible)
        queues.append((iter(eligible), question, has_near, has_absent, cat, topic))
    rows, seen, active = [], set(), list(range(len(queues)))
    while len(rows) < n and active:
        for i in list(active):
            if len(rows) >= n:
                break
            candidates, question, has_near, has_absent, cat, topic = queues[i]
            for rid, name, sec, secs in candidates:
                q = question.format(r=name)
                if is_measured(q, name, measured):
                    continue
                others = [s for s in secs if s is not sec and not has_absent(s[1])]
                chosen = [sec] + rng.sample(others, min(len(others), rng.choice([0, 1, 2])))
                context = it.make_context(rng, [s[1] for s in chosen], q, name)
                if not has_near(context) or has_absent(context) or (context, q) in seen:
                    continue
                seen.add((context, q))
                answer = L["refusal"](topic, rng.choice(L["tails"]))
                rows.append({"messages": [{"role": "user", "content": L["prompt"](context, q)},
                                          {"role": "assistant", "content": answer}],
                             "kind": "neg", "region": rid, "category": cat, "translated": any(s[2] for s in chosen),
                             "unanswerable": True})
                break
            else:
                active.remove(i)
    return rows


def template_counts(rows):
    """Righe per tema del rifiuto (per il riepilogo del generatore)."""
    return Counter(r["messages"][1]["content"].split(":")[0] for r in rows)
