# Changelog

Formato ispirato a [Keep a Changelog](https://keepachangelog.com/it/1.1.0/); versionamento secondo [Semantic Versioning](https://semver.org/lang/it/). Le voci non riportano date: la cronologia dettagliata resta nella storia git del repository.

## [Non rilasciato]

## [0.6.0]

### Aggiunto
- Numeri civici sulla mappa, visibili avvicinandosi (da zoom 17): un pacchetto a parte e leggero per regione (San Marino 146 kB, circa 50 MB l'Italia intera), da scaricare dal foglio Pacchetti; la mappa resta quella di prima. Le regioni ricevono i civici man mano che vengono ripubblicate.
- Mappa: segnalini con simbolo proprio per polizia, bagni pubblici, farmacie, ospedali, vigili del fuoco, veterinari, banche, bancomat, uffici postali, uffici turistici, distributori di carburante, colonnine di ricarica, noleggi, svago (cinema, teatri, discoteche, casinò, bowling, sale giochi), parcheggi (anche per bici e moto, visibili da zoom 15), stazioni ferroviarie, metropolitana, autostazioni, taxi, traghetti e aeroporti. Tra le attrazioni anche chiese, monasteri, fontane, parchi, parchi giochi, riserve naturali, parchi a tema e acquatici. Quelli disegnati in OpenStreetMap come aree (parcheggi, stazioni, aeroporti, ospedali, chiese, parchi...) e gli uffici turistici arrivano con il prossimo aggiornamento dei punti di interesse di ogni regione.
- Elenco regioni: la bandiera di ogni nazione al posto dell'icona del globo.
- Componenti Material 3 Expressive: indicatore di caricamento animato, barra di avanzamento ondulata per i download, titolo grande che si riduce scorrendo l'elenco regioni, animazioni a molla.
- Numeri di emergenza per tutte le 246 regioni (prima solo 7), presi da Travel.gc.ca (Governo del Canada) e confrontati con Wikipedia e Wikidata, più le schede di viaggio del Governo britannico dove non bastavano; le fonti sono nella schermata Licenze. Per gli 11 paesi senza un numero di emergenza nazionale (es. Iraq, Libia, Guinea, Sud Sudan) la scheda lo dice e consiglia di annotare prima i contatti di polizia e strutture sanitarie locali.

### Modificato
- Nuova icona dell'app e nuova schermata di avvio: una bussola cucita in una tasca, tutta vettoriale, con la versione monocromatica per le icone a tema di Android 13+.
- Mappa: segnalini in 8 colori, uno per famiglia (alloggio, cibo, negozi, attrazioni, svago, trasporti, servizi pubblici, altro); il tipo lo dice il simbolo.
- Mappa: i punti di interesse senza nome non compaiono più, tranne bagni, bancomat, parcheggi, distributori, colonnine di ricarica, farmacie, ospedali, taxi, uffici postali e fontane, che in OpenStreetMap di solito un nome non l'hanno; nella loro scheda il titolo è la categoria.
- Mappa: non compaiono più come punti di interesse cestini, panchine, tavoli da picnic, singoli stalli e parcheggi privati, cartelli informativi, fontanelle, raccolta differenziata, scuole, asili e università, stabilimenti balneari e altri tipi poco utili in viaggio.
- Aggiornamento dei percorsi: l'app riscarica solo i segmenti cambiati e riusa quelli già installati (prima riscaricava tutti i segmenti della regione, anche centinaia di MB per un paese grande).

### Corretto
- Ricerca regioni: scrivendo si perdevano lettere e il cursore tornava all'inizio (es. "Letton" diventava "Ltt").
- Elenco regioni con testo ingrandito oltre il 150%: il pulsante "Aggiorna" schiacciava il nome della nazione fino a spezzarlo lettera per lettera; ora è un pulsante con la sola icona.
- Documenti: cambiando la lingua del sistema con l'app già aperta, la richiesta di sblocco con impronta o volto restava nella lingua precedente (titolo, sottotitolo, pulsante Annulla e messaggi di errore).
- Guide: 225 nazioni su 246 risultavano senza nessuna sezione (es. Isole Faroe, Germania, Stati Uniti). La pipeline salvava come guida le risposte di errore di Wikivoyage e non trovava la pagina italiana per i titoli con lettere accentate o con un redirect. Ora tutte le 246 nazioni hanno la guida: 245 in italiano, e la Siberia in inglese perché la sua pagina italiana ha solo i titoli.
- Punti di interesse: una regione con i segmenti di percorso invariati non aggiornava mai i propri POI. Ora vengono rigenerati quando hanno più di 30 giorni, senza riscaricare mappa e percorsi, e l'app li riscarica solo se sono davvero cambiati.

## [0.5.0]

### Aggiunto
- Interfaccia completamente ridisegnata su Material Design 3: tema con colori dal wallpaper (Android 12+, disattivabile da Altro → "Colori dal wallpaper") o palette del brand, in tre livelli di contrasto; icone Material Symbols; tema scuro anche per la mappa. Navigazione con barra in basso (Regioni, Documenti, Altro) al posto del menu laterale, che su tablet e pieghevoli diventa una barra laterale con elenco regioni e dettaglio affiancati. L'app va a tutto schermo (edge-to-edge) e supporta il gesto Indietro predittivo, con animazioni di transizione.
- Ricerca nell'elenco delle regioni, senza distinzione di maiuscole e accenti.
- Elenco regioni raggruppato per continente (Europa, Asia, Africa, Nord America, Sud America, Oceania), con le regioni in ordine alfabetico: il manifest pubblicato riporta ora il continente di ogni regione. Fino alla prossima pubblicazione del manifest le regioni restano nel gruppo "Altro".
- Elenco regioni: le nazioni scaricate (prima quelle da aggiornare) stanno in un gruppo "Nazioni scaricate" in cima, aperto; i continenti contengono solo le nazioni non scaricate e si aprono e chiudono toccandone l'intestazione (chiusi di default, tutti aperti durante una ricerca).
- Mappa del mondo offline nella schermata Regioni, con i paesi colorati per stato (scaricato, disponibile, non ancora disponibile) e nomi in italiano: toccando un paese si apre la sua regione, o si sceglie tra più regioni (es. Stati Uniti). Sul telefono si passa da elenco a mappa con il pulsante in alto; su tablet la mappa occupa il pannello di destra finché non si sceglie una regione.
- Mappa: linee di confine, nazionali continue e più marcate, regionali/provinciali tratteggiate da zoom 5. Nessun dato in più da scaricare.
- Mappa: segnalini in stile Google Maps, con colore e simbolo diversi per ogni categoria (alloggio, cibo, negozi, attrazioni, ambasciate); i filtri fanno anche da legenda e la scheda di un punto si apre dal basso.
- Assistente in stile chat (domanda e risposta in bolle, campo di invio in basso); Documenti con modulo a schermo intero; Guida con filtri per categoria.
- Accessibilità: testi leggibili fino al 200% di ingrandimento, tutti i pulsanti di almeno 48 dp e con etichetta per TalkBack, titoli annunciati come tali, risposta dell'assistente annunciata quando arriva.
- Pagina di stato GitHub Pages ridisegnata: elenco nazioni a card invece che a righe, nav sticky per saltare rapidamente a un continente (meno scroll per trovare una nazione), barra di ricerca più grande e a misura di tocco. Rimossa la riga "Ultimo aggiornamento: data" in testa, sostituita da un pannello "Ultimi aggiornamenti" (raggruppato per data di pubblicazione, derivato da `version`/`updatedAt` già presenti nel manifest). Il nome di ogni nazione è ora un link alla propria pagina Wikivoyage (edizione italiana quando esiste, fallback su quella inglese — stessa preferenza già usata dalla pipeline dati).

### Modificato
- Modelli IA on-device: rimossi Gemma 3 1B e Gemma 3n (E2B/E4B) dal catalogo e la relativa voce nelle licenze, perché richiedono di accettare una licenza su HuggingFace. Il modello predefinito diventa Qwen3 0.6B; chi aveva selezionato un modello Gemma torna al predefinito. Di conseguenza rimossi anche il campo per il token HuggingFace nella schermata Assistente IA e il suo codice.
- Motore IA on-device: passaggio da LiteRT-LM (Google, senza nuove release da agosto 2026) a llama.cpp, compilato da sorgente nell'app. I modelli del catalogo sono ora file GGUF quantizzati Q4_K_M, più leggeri dei precedenti `.litertlm` (es. Qwen3 0.6B da 474 a 378 MB). I modelli già scaricati nel vecchio formato non sono più utilizzabili e vengono eliminati in automatico al primo avvio, liberando spazio: il modello va scaricato di nuovo dalla schermata Assistente IA. Rimosso dal catalogo DeepSeek R1 Distill Qwen 1.5B: come modello "reasoning" genera un lungo ragionamento prima della risposta, troppo lento su un telefono; per la fascia da 8 GB di RAM resta Qwen2.5 1.5B.
- Pacchetti regionali separati: le guide (usi e costumi, sicurezza, numeri di emergenza) sono ora un unico pacchetto per tutte le nazioni, di poche centinaia di KB, e ogni regione ha tre pacchetti indipendenti, mappa, percorsi e punti di interesse, ciascuno con la propria versione.
  - Le guide si scaricano da un nuovo passo facoltativo dell'onboarding; se lo si salta, si scaricano da sole alla prima connessione Wi-Fi, e si aggiornano da sole, sempre su Wi-Fi.
  - L'anteprima di una nazione non scaricata non scarica più nulla (prima scaricava l'intero pacchetto della regione, es. 146 MB per l'Italia, per mostrare pochi KB di guida).
  - "Scarica" installa ancora tutta la regione; "Aggiorna" scarica solo i pacchetti cambiati e ne mostra la dimensione (es. 66 kB di punti di interesse invece dell'intera regione).
  - Nuovo menu "Pacchetti" su ogni regione installata: stato e dimensione di mappa, percorsi e punti di interesse, ciascuno scaricabile, aggiornabile o eliminabile da solo, più "Elimina tutto". Se manca la mappa, la scheda Mappa della regione offre di scaricarla.
  - Spazio di archiviazione: riga per le guide e, sotto ogni regione, righe separate per mappa, percorsi e punti di interesse.
  - Le regioni già installate restano utilizzabili; le guide vanno riscaricate una volta (in automatico su Wi-Fi). Le versioni precedenti dell'app non leggono il nuovo catalogo: dopo la sua pubblicazione non scaricano più regioni finché non vengono aggiornate.
- Rimossi dal catalogo i territori senza popolazione permanente (Antartide, Isole Heard e McDonald, Terre Australi e Antartiche Francesi, Georgia del Sud e Sandwich Meridionali, Territorio Britannico dell'Oceano Indiano, Isole Minori Esterne degli Stati Uniti, Clipperton, Isola Bouvet) e il relativo gruppo "Territori disabitati". Chi ne aveva già scaricato uno lo trova ancora in Spazio di archiviazione, da dove può eliminarlo, ma non più nell'elenco Regioni, e non riceve più aggiornamenti.
- Pubblicazione automatica settimanale: calendario dei giorni rigenerato sul nuovo elenco di 246 regioni, con un carico per giorno tra 297 e 324 tile (prima tra 257 e 331 dopo la rimozione dei territori disabitati).
- Pubblicazione automatica: pacchetti su una release per continente (una release GitHub ha al massimo 1000 file), asset non piu' usati dal catalogo cancellati dopo ogni pubblicazione, e regioni con le stesse tile di routing aggiornate solo nei segmenti cambiati invece di essere rigenerate da capo.

### Corretto
- Pubblicazione automatica (`publish-regions.yml`): rilanciare una pubblicazione fallita falliva sempre ("Multiple artifacts named github-pages"), perché ogni tentativo caricava un altro artefatto con lo stesso nome. Ora l'artefatto ha un nome diverso per ogni tentativo.
- Pubblicazione automatica: le nazioni con moltissimi punti di interesse (es. Germania) fallivano per memoria esaurita generando il pacchetto POI, e restavano fuori dal catalogo. Ora i POI si leggono in streaming.
- Aprendo l'app sulla mappa dell'ultima regione, il tasto Indietro chiudeva l'app invece di tornare all'elenco regioni.
- Elenco regioni: l'avviso "spazio insufficiente" sostituiva l'intero elenco con un messaggio di errore; ora compare come notifica temporanea (Snackbar).
- Onboarding: il passo "Scarica una regione" indicava di farlo da "Spazio di archiviazione", da cui non si scarica; ora rimanda alla schermata Regioni. Ruotando lo schermo il tutorial non torna più al primo passo.
- Guida: le sottosezioni delle voci Wikivoyage (es. "Vini rossi", "Moscati e passiti" sotto "Bevande") comparivano come testo semplice preceduto da `;` o `▸`, senza che si capisse che erano titoli. Ora sono mostrate come sottotitoli, senza simbolo e annunciate come titoli da TalkBack; vale subito anche per le regioni già scaricate. La pipeline dati converte ora anche la sintassi `;Titolo` in sottosezione, per i pacchetti pubblicati da qui in avanti.
- Pubblicazione automatica settimanale (`publish-regions.yml`): lo sharding a 3 vie dentro il bucket di ogni giorno era round-robin, senza tenere conto del peso reale delle regioni — una nazione enorme (es. Canada) poteva finire da sola in uno shard e rischiare di sforare il limite di 6h di un job GitHub Actions. Ora usa lo stesso bin-packing goloso per peso (tile `.rd5` misurate) già impiegato per bilanciare i 7 giorni della settimana.

## [0.4.0]

### Aggiunto
- Tasto "Controlla aggiornamenti" nella schermata Regioni: in aggiunta al controllo periodico automatico, forza subito un nuovo controllo del catalogo regioni, della versione app e del modello IA.
- Onboarding: richiesta del permesso di notifica (Android 13+) all'ultimo step, indicatore testuale "Passo X di Y" oltre ai pallini, tasto Indietro di sistema che torna allo step precedente invece di uscire dal wizard, icona coerente anche sugli step di download regione/modello IA (prima privi di icona a differenza degli altri step).
- Anteprima della guida Wikivoyage al tap su una regione non ancora installata: scarica e importa solo content.db (nessuna mappa, nessun routing), con un banner che permette di avviare da li' il download completo.
- Numero di telefono dei POI (in primo luogo ambasciate/consolati) estratto da OSM (`phone`/`contact:phone`) fino alla mappa: nuova categoria "Ambasciate e consolati" nel filtro della mappa, e un tocco su un pin ora apre una scheda con nome/categoria e, se presente, un tasto "Chiama" (intent di composizione).
- Numeri di emergenza nazionali (generale/polizia/ambulanza/vigili del fuoco) generati dalla pipeline dati (`generateEmergencyNumbers`, dataset statico per regionId) e distribuiti dentro content.db come le altre tabelle: mostrati in una scheda dedicata in cima alla Guida di ogni regione, con tasto di chiamata diretta per ciascun numero.

### Modificato
- Stile della mappa offline riscritto: nomi di strade e localita' (prima assenti, nessun layer di etichette nello stile) ora visibili grazie a un font bundlato in locale (Klokantech Noto Sans, solo range ASCII/Latin-1, nessun hosting proprio); palette con piu' contrasto in stile Google Maps (strade principali gialle, strade minori bianche con leggera "casing" grigia, edifici con contorno, acqua piu' satura) al posto della tinta piatta precedente; gerarchia stradale e ispessimento delle linee in base allo zoom. Le etichette preferiscono `name:it`/`name:en` quando OpenStreetMap li ha gia' mappati, altrimenti usano il nome locale: per le regioni a caratteri latini non cambia nulla (quei tag non esistono quasi mai sulle strade locali), ma per il Giappone permette di mostrare la romanizzazione inglese al posto dei kanji, che il font bundlato (solo alfabeto latino) non potrebbe comunque disegnare.
- Onboarding: chiarito che la modalità IA "Online" richiede di configurare una chiave API personale prima di poterla usare.
- Passata di coerenza visiva su icone e menu: stati vuoti/di errore con icona invece del solo testo (Regioni, Guida, Documenti), icona per categoria dentro un cerchio colorato nelle sezioni guida, menu laterale raggruppato per "Viaggio"/"App" con icona app in testata, selettore modalità dell'assistente IA passato a un componente Material 3 nativo (segmented button), barra di avanzamento visiva nella schermata Spazio di archiviazione.
- Schermata di avvio: se l'ultima regione aperta è ancora installata, l'app riparte direttamente dalla sua mappa invece che dall'elenco regioni; se non lo è più (mai scaricata, o eliminata dall'ultima sessione), torna in automatico all'elenco regioni invece di mostrare una mappa vuota.
- Fonti ufficiali (Farnesina, OMS, Agenzia delle Dogane): ora si aprono in una WebView dentro l'app invece che in Chrome Custom Tabs, sia dal registro fonti sia dal banner "Verifica sempre sulla fonte ufficiale" dell'assistente IA. Stessa WebView anche per il link "Fonte: Wikivoyage" in fondo a ogni sezione guida (Guida di una regione installata e anteprima pre-download): Custom Tabs resta invariato solo per le pagine modello HuggingFace (licenze).
- Schermata Regioni: le nazioni sono ora raggruppate per continente (Europa, Americhe, Asia) invece di un unico elenco piatto.
- Segnalini POI sulla mappa più simili a quelli di Google Maps: pallino bianco pieno al centro (prima un buco trasparente lasciato dal path del pin) e una piccola ombra di contatto alla base.
- Rimossa l'etichetta col nome della nazione dalla mappa (poteva comparire in un punto qualunque dello schermo, anche in alto, a seconda di dove OSM posiziona il punto rappresentativo del paese): il nome della regione è già visibile nella barra in alto, l'etichetta era ridondante.
- Guida: la pipeline dati preferisce ora l'edizione italiana di Wikivoyage quando esiste (risolta dai langlinks interwiki della pagina inglese), invece della sola edizione inglese — testo scritto nativamente in italiano da editor Wikivoyage, non una traduzione meccanica aggiunta da questo progetto (nessuna nuova dipendenza/servizio di traduzione). Fallback trasparente all'edizione inglese per le regioni prive di pagina italiana o con langlink assente; per le regioni già in catalogo con pagina IT (Italia, San Marino, Andorra, Giappone) cambia la fonte del testo, non le categorie mostrate in Guida.
- Avviso di conferma per download di grandi dimensioni (pacchetto regionale o modello IA, sopra i 100 MB) mostrato ora solo quando si è connessi via rete cellulare: su Wi-Fi/ethernet i MB non pesano su un piano tariffario, quindi non ha senso fermare l'utente con una conferma in più.

### Corretto
- Mappa offline: buchi di rendering zoomando oltre il livello 14 (zoom massimo delle tile estratte da PmtilesExtractor per ogni regione) — lo stile non dichiarava "maxzoom" sulla source vettoriale, cosi' MapLibre richiedeva davvero tile piu' profonde che non erano mai state scaricate, invece di ri-scalare (overzoom) l'ultima tile disponibile come fa Google Maps quando non ha piu' dettaglio.
- Mappa offline: la mappa apriva sempre sulla vista mondo, e zoomando manualmente verso la regione installata si attraversava una fascia di zoom bassa dove meta' schermo (o piu') restava vuota. Causa: a quei livelli una singola tile copre un'area enorme, e PmtilesExtractor scarica solo le tile che intersecano davvero il bounding box della regione (per tenere piccolo il pacchetto) — le tile "vicine" a bassa risoluzione semplicemente non fanno parte del pacchetto scaricato, non è un ritardo di caricamento. La mappa ora centra e zooma automaticamente sui punti d'interesse della regione appena disponibili, cosi' l'utente apre direttamente un'area completamente coperta invece di doverci arrivare a mano dalla vista mondo. Aggiunto anche un `prefetchZoomDelta` più basso (le tile sono comunque tutte locali, quindi un placeholder più vicino allo zoom target non costa una richiesta di rete in più) per rendere più fluido il caricamento quando si zooma dentro l'area già coperta.
- Import di content.db reso tollerante all'assenza della colonna `phone` (introdotta in questa stessa versione): i pacchetti regionali già pubblicati prima di questa modifica ne sono privi, e senza questo fallback l'intero download/import di qualunque regione sarebbe fallito finché non ripubblicata dalla pipeline.
- Guida: la pulizia del wikitext Wikivoyage lasciava passare artefatti di sintassi grezza nel testo mostrato in app — verificato dal vivo sull'anteprima di Andorra: sottosezioni prive di corpo (es. "Money", quando il suo unico contenuto era un template tipo `{{Pricerange}}` ormai rimosso) restavano come parola orfana seguita da una riga vuota enorme, gli elenchi puntati mostravano l'asterisco grezzo (`* Perfume`) invece di un punto elenco, e il testo di una citazione `<ref>...</ref>` restava come prosa vagante perché veniva tolto solo il tag e non il contenuto. Ora le sottosezioni vuote sono scartate, i sottotitoli non vuoti diventano "▸ Titolo", gli elenchi usano "•", le citazioni sono rimosse interamente e righe vuote consecutive sono ridotte a una sola.

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
