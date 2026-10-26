# Stile dei testi

Regole per tutti i testi di Pocket Travel: interfaccia dell'app (italiano e inglese), README, CHANGELOG, `docs/`,
wiki, model card e commenti nel codice. L'obiettivo è un testo **chiaro e formale**: preciso, completo, senza
toni colloquiali.

## Regole comuni

- Frasi complete e brevi, una informazione per frase; il soggetto e il verbo all'inizio.
- Un solo termine per ogni concetto, sempre lo stesso (vedi "Terminologia"); niente sinonimi per variare.
- Nessuna espressione colloquiale, esclamazione, emoji, ironia o abbreviazione informale ("ok", "tipo", "roba",
  "ecc." nei testi dell'interfaccia). Fanno eccezione per le emoji `README.md`, `README.en.md`, `CONTRIBUTING.md`
  e `CONTRIBUTING.en.md`: una sola emoji all'inizio di titoli, passi numerati, elenchi puntati brevi, righe della
  tabella delle funzioni e badge, mai dentro una frase.
- Numeri, unità e date nel formato della lingua: in italiano "2,8 milioni", "4–6 settimane", "3 ott 2026"; in
  inglese "2.8 million", "4 to 6 weeks", "Oct 3, 2026".
- Nessun punto finale nei testi dell'interfaccia, di una frase (etichette, pulsanti, titoli, avvisi) o di più frasi
  (spiegazioni, note delle Licenze): il punto separa le frasi, l'ultima resta senza. Lo stesso vale per i testi
  composti nel codice.
- Maiuscola solo all'inizio della frase e nei nomi propri (sentence case, anche in inglese): "Fonti ufficiali",
  "Official sources", non "Official Sources".

## Interfaccia in italiano

- Pulsanti e istruzioni brevi all'imperativo di seconda persona singolare, come le app di sistema Android in
  italiano: "Scarica", "Scegli il paese", "Apri la guida".
- Etichette, titoli e stati come sostantivi o forme impersonali: "Paese di partenza", "Download in corso",
  "Nessun certificato richiesto nei dati dell'app".
- Spiegazioni e avvisi in forma impersonale, senza rivolgersi direttamente a chi legge quando non serve:
  "La guida si aggiorna da sola" invece di "Non devi aggiornare la guida".
- Apostrofi e accenti corretti ("perché", "è", "qual è"); in `strings.xml` l'apostrofo si scrive `\'`.
- Un nome inserito nel testo (paese, luogo, rete, documento) non segue una preposizione o un verbo che vorrebbero
  l'articolo ("di Nepal", "Chiama Consolato italiano"): va tra parentesi o dopo i due punti, come "Scarica (Nepal)" e
  "Partenza: Italia, destinazione: Nepal". Restano diretti i numeri di telefono e i nomi di prodotti e siti.

## Interfaccia in inglese

- Stesso contenuto e stesso registro dell'italiano, non una traduzione parola per parola.
- Pulsanti all'imperativo ("Download", "Choose the country"), etichette come sostantivi.
- Inglese britannico o americano: americano ("color", "center"), in modo uniforme.

## Documentazione (README, CHANGELOG, docs, wiki, model card)

- Registro formale e impersonale: "Si lancia con", "Il comando genera"; seconda persona solo nelle istruzioni
  passo per passo.
- Prima il risultato o la regola, poi il perché; niente racconto cronologico di come ci si è arrivati.
- Nomi di file, comandi e codici tra backtick; collegamenti con testo descrittivo, mai "qui".

## Commenti nel codice

- In italiano, come il resto del codice, al presente e in forma impersonale: cosa fa il codice e perché, non
  la storia delle modifiche ("prima faceva...") se non serve a capire una scelta ancora valida.
- Brevi: una o due righe vicino al codice che spiegano; KDoc/docstring per le funzioni pubbliche.
- Nessun riferimento a file che chi legge il repository non può aprire (piani privati, percorsi locali).

## Testi da non modificare

Alcuni testi sono anche il formato su cui sono addestrati i modelli dell'assistente: cambiarli peggiorerebbe le
risposte finché i modelli non vengono riaddestrati. Restano come sono, salvo errori veri:

- i prompt in `PromptTemplates.kt` e `OnlinePromptTemplates.kt`;
- le etichette del contesto in `TravelAssistant.kt` ("Nota personale", "Personal note");
- il riassunto delle vaccinazioni in `VaccinationSummary.kt`;
- le etichette dei fatti rapidi generate dalla pipeline (`GenerateGuideContent.kt`, `GenerateEmergencyNumbers.kt`)
  e i testi dei dataset in `tools/data-pipeline/scripts/`.

## Terminologia

| Concetto | Italiano | Inglese |
|---|---|---|
| Paese o territorio scaricabile | nazione (elenco), regione (unità che si scarica) | country, region |
| Rettangolo di una regione scaricato al posto della regione intera | zona | zone |
| Regione principale di una nazione con gli arcipelaghi lontani in regioni a parte | Continentale | Mainland |
| Rettangolo geografico di una regione, di una zona o di un estratto ("riquadro" è solo dell'interfaccia) | rettangolo | bounding box |
| Scheda della barra con l'elenco delle nazioni | Nazioni | Countries |
| Pagina con le schede Guida, Mappa, Navigatore, IA | pagina della regione | region page |
| Pianificatore e navigazione, nella barra e nella pagina della regione | Navigatore | Navigator |
| Scheda della barra con documenti e note | Documenti | Documents |
| Guida di una regione non ancora scaricata | anteprima | preview |
| Riquadro che spiega una funzione la prima volta che si apre una schermata | suggerimento | hint |
| Testo di Wikivoyage di una nazione | guida | guide |
| Testo di Wikivoyage di una città | guida della città | city guide |
| Indicazioni durante il viaggio | navigazione | navigation |
| Voce della barra o della pagina della regione (Nazioni, Guida, IA...) | scheda | tab |
| Riquadro con un contenuto (meteo, punto di interesse...) | riquadro | card |
| Parte scaricabile di una regione (mappa, guide, mezzi pubblici...) | contenuto | content |
| Assistente, con il modello sul dispositivo o online | assistente | assistant |
| Testo dalla voce, sul dispositivo, nel campo dell'assistente | riconoscimento vocale | voice recognition |
| Dire la domanda al posto di scriverla | dettare (Detta la domanda) | dictate (Dictate the question) |
| Domande e risposte di seguito con l'assistente | conversazione | conversation |
| Elenco delle conversazioni salvate (solo con il motore Sul dispositivo) | cronologia | history |
| Dove gira l'assistente | motore (Sul dispositivo, Online; mai "offline" o "locale" per il modello) | engine (On device, Online) |
| Modello scaricabile | modello (modello IA) | model (AI model) |
| Gruppo di dispositivi per RAM che decide i modelli proposti | fascia di RAM | RAM tier |
| Gruppi della lista dei modelli: come li pubblicano gli autori, addestrati dal progetto | Ufficiali, Addestrati sulle guide | Official, Trained on the guides |
| Chiave personale per il motore Online | chiave API | API key |
| Fornitore del motore Online (ChatGPT, Mistral...) | servizio | service |
| Interruttore che lascia mandare le foto al motore Online | Invia le foto al servizio | Send photos to the service |
| Dati EXIF della foto (data, luogo, modello del telefono), tolti prima dell'invio | dati della foto (mai "EXIF" nell'interfaccia) | photo details |
| Evento reale in corso (terremoto, alluvione, allerta meteo...) letto dalle fonti online | allerta, allerte (mai "avviso", "allarme" o "notifica") | alert, alerts |
| Riquadro della guida con le allerte della regione | Allerte in corso | Active alerts |
| Riquadro in cima alla risposta dell'assistente quando la domanda parla di un pericolo per la vita | riquadro di emergenza (titolo: Emergenza? Chiama subito) | emergency card (title: Emergency? Call now) |
| Frase sotto ogni allerta | Informazioni non ufficiali e probabilmente non aggiornate: segui le direttive locali delle autorità | Not official and probably out of date: follow the instructions of the local authorities |
| Fonte e ora delle allerte | Fonte: %1$s · aggiornate alle %2$s; senza rete: Ultime allerte scaricate alle %1$s | Source: %1$s · updated at %2$s; offline: Last alerts downloaded at %1$s |
| Riquadro delle allerte senza allerte nella regione | Nessuna allerta in corso nella regione | No active alerts in this region |
| Avviso nella guida quando il consiglio di viaggio è cambiato dopo il pacchetto | Consigli di viaggio aggiornati il %1$s, con il pulsante Apri | Travel advice updated on %1$s, with the Open button |
| Allerta tradotta o riassunta dall'assistente | Riassunto dall'assistente, testo originale qui sotto | Summarized by the assistant, original text below |
| Azione che condivide un modello o i contenuti di una regione con un altro telefono, senza internet | Invia a un altro telefono | Send to another phone |
| Azione che installa un modello o i contenuti ricevuti come file | Importa da file | Import from file |
| File ricevuto che non corrisponde a niente del catalogo | Questo file non è nel catalogo di Pocket Travel | This file is not in the Pocket Travel catalog |
| File ricevuto alterato o incompleto (sha256 diverso) | File danneggiato: chiedi di inviarlo di nuovo | Damaged file: ask for it to be sent again |
| Parola della lingua del posto spiegata da una voce di Wikipedia | termine locale (nel foglio: Da Wikipedia) | local term (in the sheet: From Wikipedia) |
| Pulsanti della lettura ad alta voce della risposta | Ascolta, Ferma | Listen, Stop |
| Voce di Pocket Travel nel menù del testo selezionato e in Condividi | Chiedi all'assistente | Ask the assistant |
| Widget della schermata Home | Emergenza, Scorciatoie | Emergency, Shortcuts |
| Foglio al lancio dopo una chiusura inattesa | L'app si è chiusa in modo inatteso, con Condividi il rapporto e Apri una segnalazione su GitHub | The app closed unexpectedly, with Share the report and Report on GitHub |
| Proposta dopo una chiusura per memoria | Il modello %1$s richiede più RAM di questo dispositivo. Scarica %2$s | The %1$s model needs more RAM than this device has. Download %2$s |
| Errori del motore Online | Credito del servizio esaurito. Ricarica l'account su %1$s; Servizio occupato. Riprova tra poco | The service credit has run out. Top up your account on %1$s; The service is busy. Try again shortly |
| Telefono o tablet dell'utente | dispositivo | device |
| Colori presi dal sistema (colore dinamico, da Android 12) al posto di quelli dell'app | colori del sistema | system colors |
| Dove si trova l'utente | posizione | location |
| Percorso calcolato | percorso | route |
| Percorso mostrato prima di "Avvia" (sempre con "del percorso") | anteprima del percorso | route preview |
| Elenco delle manovre del percorso | svolte | turns |
| Pulsante di un punto di interesse che apre il Navigatore verso il posto | Percorso | Directions |
| Partenza e arrivo di un biglietto (solo nei biglietti) | tratta | route |
| Partenza, scali e destinazione nelle vaccinazioni | viaggio | trip |
| Dati e fonti su cui l'app basa un esito (mai "nostri") | dati dell'app, fonti dell'app | the app's data, the app's sources |
| Contenuto con strade e sentieri per calcolare i percorsi | rete stradale | road network |
| Rete stradale con tutte le strade e i sentieri | rete stradale completa | full road network |
| Rete stradale con le sole strade percorribili in auto | rete stradale solo auto | car-only road network |
| Contenuto con i numeri civici | numeri civici | house numbers |
| Contenuto con linee e fermate | mezzi pubblici | public transport |
| Partenze dalle fermate | orari dei mezzi pubblici | public transport timetables |
| Reti scaricate che il catalogo sposta tra le "Non incluse" (orari scaduti, licenza) | orari non più aggiornati | timetables no longer updated |
| Rete scaricata che il catalogo toglie del tutto, di solito perché un'altra la sostituisce | rete sostituita | replaced network |
| Ritardi e soppressioni dal feed dell'ente, solo con la rete | in tempo reale | real time |
| Minuti dopo l'orario programmato ("+3 min"; nelle partenze successive "10:50 +3") | ritardo (in ritardo di 3 minuti) | delay (3 minutes late) |
| Minuti prima dell'orario programmato (nelle partenze successive "11:20 −2") | in anticipo | early |
| Corsa che non parte (nelle partenze successive "11:50 soppressa", minuscolo) | soppressa | canceled |
| Ora dell'ultimo download del tempo reale | Aggiornato alle 14:05 | Updated at 14:05 |
| Pulsante che scarica di nuovo il tempo reale | Aggiorna i ritardi | Refresh delays |
| Pulsante sotto una risposta dell'assistente con le previsioni più vecchie di 10 minuti | Aggiorna le previsioni | Refresh the forecast |
| Aggiornamento automatico fermo dopo un quarto d'ora senza tocchi | in pausa | paused |
| Contenuto con negozi, ristoranti, musei... (e la sua aggiunta) | punti di interesse (punti di interesse extra) | points of interest (extra points of interest) |
| Contenuto con le guide di Wikivoyage delle città | guide delle città | city guides |
| Mappa con meno dettagli e meno spazio occupato | mappa leggera | light map |
| Categorie della mappa da mostrare o nascondere | filtri | filters |
| Come ci si sposta (A piedi, Bici, Auto...), anche nel selettore del Navigatore | modo (mai "mezzo") | travel mode |
| Opzione di accessibilità | in sedia a rotelle | wheelchair |
| Accessibilità dal tag OSM `wheelchair`, nel riquadro di treno, metro, autobus e traghetto | stazione | station |
| Accessibilità dal GTFS della rete (`wheelchair_boarding`) | fermata dei mezzi | public transport stop |
| Categoria della guida con i dati essenziali | Fatti rapidi | Quick facts |
| Mappa a bassa risoluzione installata da sola | mappa d'insieme | overview map |
| Mappa per scegliere la nazione | mappa delle nazioni | country map |
| Mappa online a bassa risoluzione | mappa del mondo | world map |
| Dove si vuole arrivare (Navigatore, vaccinazioni, assistente) | destinazione (mai "meta") | destination |
| JSON firmati dei dati scaricabili, pubblicati dal progetto | catalogo ufficiale | official catalog |
| Catalogo di altri, scelto in Impostazioni → Catalogo | catalogo personalizzato | custom catalog |
| Catalogo letto con `-PpocketTravel.manifestUrl`, solo in debug e senza firme | catalogo di debug | debug catalog |
| Testo di travel.gc.ca e del FCDO nelle guide inglesi | consigli di viaggio | travel advice |
| Pagina web di un sito esterno (ministero, servizio sanitario) | pagina (mai "scheda") | page |
| Da dove viene il testo mostrato (guida, vaccinazioni) | Fonti | Sources |
| Siti di ambasciate, ministeri e OMS da controllare di persona, solo online | Fonti ufficiali (mai solo "Fonti") | Official sources |
| Sezione tradotta da un'altra lingua | tradotta automaticamente | machine-translated |
| Schermata delle licenze | Licenze | Licenses |
| Proposte sotto un campo mentre si scrive (aeroporti, compagnie) | completamento | autocomplete |
| Spazio cifrato con la biometria per i documenti | cassaforte | vault |
| Note di viaggio cifrate, fuori dalla cassaforte | Note | Notes |
| Paese di cui l'utente ha il passaporto | nazionalità | nationality |
| Schermata con lo spazio occupato dai contenuti | Spazio di archiviazione | Storage |
| Schermata in Altro con gli aggiornamenti di app, nazioni, guide e modello IA | Aggiornamenti | Updates |
| Pulsante che scarica la versione nuova dell'app (con la dimensione) | Aggiorna | Update |
| Pulsante che installa l'aggiornamento dell'app scaricato | Installa | Install |
| Permesso di sistema per installare gli aggiornamenti dell'app (nome di Android) | Installa app sconosciute | Install unknown apps |
| Rete che Android segna come a consumo (dati mobili, hotspot di un altro telefono) | rete a consumo | metered connection |
| Punti di interesse tolti per mancanza di spazio, da scaricare di nuovo | punti di interesse da riscaricare | points of interest to download again |
| Livello di una vaccinazione non obbligatoria (altrove "consigliato" resta) | raccomandata | recommended |
| Scalo aereo nelle regole delle vaccinazioni | scalo | stopover |

"Paese" resta per i paesi del mondo che non si scaricano: nazionalità, partenza e destinazione delle vaccinazioni
("Paese di partenza", "del tuo paese").
