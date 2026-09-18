# Changelog

Formato ispirato a [Keep a Changelog](https://keepachangelog.com/it/1.1.0/); versionamento secondo [Semantic Versioning](https://semver.org/lang/it/). Le voci non riportano date: la cronologia dettagliata resta in [README.md](README.md).

## [Non rilasciato]

### Aggiunto
- Tasto "Controlla aggiornamenti" nella schermata Regioni: in aggiunta al controllo periodico automatico, forza subito un nuovo controllo del catalogo regioni, della versione app e del modello IA.
- Onboarding: richiesta del permesso di notifica (Android 13+) all'ultimo step, indicatore testuale "Passo X di Y" oltre ai pallini, tasto Indietro di sistema che torna allo step precedente invece di uscire dal wizard, icona coerente anche sugli step di download regione/modello IA (prima privi di icona a differenza degli altri step).

### Modificato
- Onboarding: chiarito che la modalità IA "Online" richiede di configurare una chiave API personale prima di poterla usare.

## [0.3.0]

### Aggiunto
- Controllo periodico (qualunque rete inclusi i dati cellulari) di aggiornamenti disponibili per l'app e per il modello IA on-device, in aggiunta a quello già esistente per i pacchetti regionali — solo notifica, mai un download automatico. Fonte: `app-status.json`, asset di una release GitHub fissa (`app-status`) pubblicato da `publish-apk.yml` ad ogni rilascio dell'app, volutamente separato da `manifest.json`/GitHub Pages (pacchetti regionali) così i due cicli di pubblicazione restano indipendenti.
- Avviso di conferma prima di avviare un download (pacchetto regionale o modello IA) sopra i 100 MB.
- Pubblicazione automatica settimanale dei pacchetti regionali (`publish-regions.yml`): 7 trigger `schedule` (uno per giorno) processano un bucket di regioni bilanciato per carico reale (tile `.rd5` misurate su `brouter.de`, non stima geometrica), con sharding a matrice dentro ogni bucket per restare sotto il limite di 6h/job e nel fair use dei mirror Overpass pubblici; `workflow_dispatch` resta invariato per run manuali/mirate.
- Controllo di necessità in `build-region.sh`: prima di rigenerare una regione, confronta le tile `.rd5` attese con quelle già pubblicate (nome e dimensione) e salta l'intera rigenerazione — niente fetch Wikivoyage né query Overpass — se non è cambiato nulla dalla settimana precedente.
- Catalogo di 9 modelli IA on-device su 3 fasce di RAM (invece di un unico modello fisso), un solo modello installato alla volta: selezionarne uno diverso elimina quello precedente prima di scaricare il nuovo.
- Benchmark on-device del modello installato (velocità e un punteggio euristico di qualità su prompt fissi).
- Istruzioni in-app per il download di modelli con licenza ad accesso ristretto su HuggingFace, non più specifiche del solo modello Gemma.
- Due nuovi step facoltativi nell'onboarding al primo avvio: download di una regione e download del modello IA, entrambi saltabili.

### Modificato
- Il controllo periodico di aggiornamenti dei pacchetti regionali non è più limitato al solo Wi-Fi (manifest.json è pochi KB; i pacchetti veri e propri restano scaricati solo su richiesta esplicita).
- Sotto i 4 GB di RAM l'assistente IA è solo Online: la modalità "sul dispositivo" non viene più mostrata come opzione, dato che il dispositivo non può comunque usarla.
- Il controllo periodico di aggiornamenti del modello IA (`app-status.json`) copre ora l'intero catalogo invece del solo modello storico.

## [0.2.0]

### Aggiunto
- Cinque nuove categorie guida (alloggio, cibo e bevande, acquisti, connettività, vita quotidiana), mappate dalle sezioni Wikivoyage "Sleep"/"Eat"/"Drink"/"Buy"/"Connect"/"Cope".
- Agenzia delle Dogane e dei Monopoli nel registro delle fonti ufficiali, citata specificamente per la categoria dogane al posto della fonte generica Farnesina.
- Macro-categorie POI (alloggio, cibo e bevande, negozi, attrazioni, altro) derivate dal tag OSM grezzo, con pin colorati per categoria e filtro a chip sulla mappa regionale.
- Pubblicazione dell'APK di release firmato come asset di una GitHub Release, automatica sui push a `main` con bump di versione (`publish-apk.yml`); l'APK di debug viene invece caricato come artifact ad ogni push (`android-ci.yml`).

## [0.1.0] - mai rilasciata

Prima baseline documentata: nessuna versione precedente pubblicata da cui derivare un diff.

### Aggiunto
- Guida turistica offline-first: contenuti Wikivoyage (usi/dogane/salute/trasporti/frasi utili) con ricerca full-text, indicizzati per regione.
- Mappa vettoriale offline (MapLibre) con overlay POI e routing pedonale offline (BRouter).
- Assistente IA con due modalità: modello locale on-device (LiteRT-LM, con controllo RAM a fasce e limite di download in base alla capacità del dispositivo) e modalità online via un endpoint compatibile Chat Completions con chiave personale dell'utente, mai su un server dell'app.
- Autenticazione con token utente per il download del modello IA da un repository ad accesso ristretto, con verifica di integrità SHA-256 e ripresa del download interrotto.
- Vault passaporti cifrato (AES-256-GCM via Android Keystore), sbloccato con biometria/PIN del dispositivo.
- Gestione pacchetti regionali: manifest remoto, download con ripresa HTTP e verifica SHA-256, aggiornamento periodico solo Wi-Fi, installazione e rollback atomici, controllo dello spazio libero prima del download.
- Estrazione lato device della mappa di una regione dal basemap pubblico Protomaps (nessun file mappa ospitato dal progetto); segmenti di routing referenziati direttamente dai server pubblici BRouter.
- Registro delle fonti ufficiali esterne (ambasciate, ministeri, OMS), aperte solo in Chrome Custom Tabs, mai in cache persistente.
- Onboarding al primo avvio, schermata storage/spazio libero, schermata licenze open source.
- Pipeline dati e orchestratore batch (`tools/data-pipeline`) per generare e pubblicare pacchetti regionali su hosting statico, con validazione del manifest prima della pubblicazione.
- Icone reali (adaptive icon, set vettoriale) e splash screen.

### Modificato
- Motore di routing offline migrato da GraphHopper a BRouter, non compatibile il primo con il runtime Android reale.
- Icone dell'interfaccia da segnaposto testuali a icone vettoriali reali.

### Corretto
- Deduplicazione dei nodi OSM nella pipeline dati, che causava un crash nella generazione dei grafi di routing su estrazioni reali.
- Parsing dei sotto-titoli Wikivoyage, che troncava silenziosamente il contenuto reale delle guide.
- Attribuzione mancante a schermo per OpenStreetMap e link sorgente mancante per le citazioni Wikivoyage.
- Inizializzazione di WorkManager con Hilt, che impediva l'esecuzione di qualunque download in background.
