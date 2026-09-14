# Changelog

Formato ispirato a [Keep a Changelog](https://keepachangelog.com/it/1.1.0/); versionamento secondo [Semantic Versioning](https://semver.org/lang/it/). Le voci non riportano date: la cronologia dettagliata, verificata passo per passo, resta in [README.md](README.md).

## [Non rilasciato]

Nessuna modifica non ancora rilasciata.

## [0.1.0]

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
