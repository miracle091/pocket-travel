# Pipeline dei dati

Script, tool Kotlin e workflow (`.github/workflows/publish-regions.yml`) che producono i pacchetti
scaricati dall'app: manifest delle regioni, indice delle reti di trasporto, indice dei civici a celle.
La panoramica e' nel `README.md` della root.

## Firma dei file JSON

I JSON che l'app scarica sono firmati con ECDSA P-256 e SHA-256 (supportati da `java.security` su
Android API 26+, a differenza di Ed25519). Accanto a ogni file c'e' `<nome>.sig`, la firma in formato DER
calcolata sui byte esatti del file:

| File | URL |
|---|---|
| `manifest.json` | `https://miracle091.github.io/pocket-travel/manifest.json` (+ `.sig`) |
| `address-grid.json` | `https://miracle091.github.io/pocket-travel/address-grid.json` (+ `.sig`) |
| `transit.json` | `https://miracle091.github.io/pocket-travel/transit.json` (+ `.sig`) |
| `app-status.json` | `https://github.com/miracle091/pocket-travel/releases/download/app-status/app-status.json` (+ `.sig`) |

La firma si fa nel workflow (`.github/scripts/sign-files.sh`) con la chiave privata del secret
`MANIFEST_SIGNING_KEY`, solo nel passo che la usa; se il secret manca il job fallisce, per non pubblicare
file non firmati.

`app-status.json` si aggiorna in tre modi, tutti in `publish-apk.yml`:
- al rilascio dell'app (bump di `versionCode`): versione dal catalogo di main; per ogni modello l'impronta
  che coincide con il file su HuggingFace, presa dal catalogo o dal file online se la sua firma è valida
  (`scripts/resolve_ai_models.py`). Se nessuna coincide il job si ferma prima di compilare: una release che
  rifiuterebbe i download non esce;
- lancio manuale con `update_models`, avviato da `scripts/upload_hf.py --update-app-status` dopo il
  caricamento di un GGUF con lo stesso nome file: cambia solo sha256 e dimensione di quei modelli, dopo aver
  verificato la firma del file online e le impronte su HuggingFace. Le app dalla 0.9.1 verificano il
  download con queste impronte, quindi il modello nuovo si scarica senza rilasciare l'app; lo script poi
  aggiorna anche `LlmModelCatalog.kt`, che resta la riserva quando l'app non raggiunge `app-status.json`;
- lancio manuale con `resign_app_status`: lo ricostruisce dal tag della versione rilasciata, con le stesse
  regole del rilascio per le impronte, e lo firma.

Impostazione della chiave (una volta sola; la chiave privata non va mai committata):

```bash
# chiave privata (PEM)
openssl ecparam -name prime256v1 -genkey -noout -out key.pem
# chiave pubblica da incorporare nell'app: DER in base64, su una riga
openssl ec -in key.pem -pubout -outform DER | base64 -w0
# secret del repository
gh secret set MANIFEST_SIGNING_KEY < key.pem
```

Per controllare a mano un file scaricato:

```bash
openssl ec -in key.pem -pubout -out pub.pem
openssl dgst -sha256 -verify pub.pem -signature manifest.json.sig manifest.json
```

## Civici a celle (`address-grid.json`)

I civici (OSM + Overture) non si pubblicano per regione ma in celle di una griglia: job `address-grid` di
`publish-regions.yml`, una cella alla volta con `scripts/build-address-cell.sh`, indice unito da
`GenerateAddressGrid.kt` e controllato da `validateAddressGridJson` (`ValidateManifest.kt`).

- **Cella**: nodo `z/x/y` del quadtree Web Mercator con `z <= 14` (`tileZoom`), che contiene le tile z14
  discendenti. Il file e' un PMTiles con i civici di quelle tile (`GenerateAddresses --cell`) e, se ci sono
  vie, un indice di ricerca `addresses-search.db`. Le celle senza dati non esistono.
- **Partizione**: le celle dell'indice pubblicato piu' quelle del seme `address-grid-seed.tsv` (righe
  `z/x/y`, da `scripts/seed-address-grid.py`) non ancora coperte, cioe' ne' pubblicate ne' antenate di una
  cella pubblicata. Nessuna cella dell'indice e' antenata di un'altra.
- **Crescita**: se il `.xz` di una cella supera `ADDRESS_CELL_MAX_BYTES` (10.000.000 byte) si pubblicano al
  suo posto i 4 figli con dati, ricorsivamente fino a z14 (li' la cella resta oltre il tetto, con un
  warning). Le celle non si fondono mai: gli id restano stabili.
- **Ricostruzione**: ogni notte le celle con `cksum(id) % 30` uguale al giorno dall'epoca `% 30`; una cella
  pubblicata da meno di `ADDRESS_CELL_MAX_AGE_DAYS` (30) giorni tiene la sua voce, e una cella rigenerata
  con lo stesso sha256 tiene versione e asset pubblicati. Input manuali: `address_cells` (id della
  partizione separati da virgola, al posto della quota del giorno) e `address_cell_max_age_days` (-1 =
  rigenera anche le celle recenti).
- **Release**: `address-cells-<n>` con `n = cksum(id) % ADDRESS_CELL_RELEASES` (12), calcolato sulla cella
  scelta per la run (i figli di una divisione finiscono nella stessa release). Erano 6: 12 e' multiplo di
  6, quindi le celle con `n < 6` restano dove sono e le altre passano alle release 6-11 solo quando si
  rigenerano. La pulizia finale guarda tutte le `address-cells-*`. Asset caricati:
  `cell-<z>-<x>-<y>--<version>--addresses.pmtiles.xz` e `...--addresses-search.db.xz`.

Indice, firmato come gli altri JSON e puntato dalla voce `"addressGrid": { "version", "url", "sizeBytes",
"sha256" }` del manifest:

```json
{
  "version": "2026.10.01.123.1",
  "tileZoom": 14,
  "cells": [
    { "id": "12/2178/1500", "version": "2026.09.30.120.1",
      "file":   { "name": "cell-12-2178-1500--<version>--addresses.pmtiles", "url": "...", "sizeBytes": 0, "sha256": "..." },
      "fileXz": { "name": "cell-12-2178-1500--<version>--addresses.pmtiles.xz", "url": "...", "sizeBytes": 0, "sha256": "..." },
      "search": { "file": { ... }, "fileXz": { ... } } }
  ],
  "attributions": [ { "source": "OpenStreetMap", "license": "ODbL-1.0", "url": "..." } ]
}
```

- `cells` ordinato per `z`, `x`, `y`; `url` e' sempre quello del `.xz`, `file` ne da' dimensione e sha256
  decompressi. `search` e' facoltativo (le app vecchie lo ignorano).
- `attributions` (schermata Licenze): OSM, Overture con i distributori e il testo di ogni fonte ammessa
  dalla lista bianca `overture-address-sources.tsv`, anche se le celle di oggi non la usano.
- L'app scarica le celle che intersecano il riquadro della regione, le unisce in un solo
  `addresses.pmtiles` locale e usa come versione dei civici `grid-` + i primi 16 esadecimali dello sha256
  degli `id@version` delle celle ordinati (`+search` per le celle con l'indice di ricerca;
  `AddressGridSelection.kt`).

## Vaccinazioni (tabelle `vacc_*` di `guides.db`)

I dati vaccinali sono file TSV curati a mano in `content/src/main/resources/vaccinations/` (febbre gialla in
ingresso, in uscita e per rischio, polio, requisiti speciali come Hajj e Umrah, vaccini consigliati per
destinazione). Le fonti sono Travel.gc.ca (Open Government Licence - Canada 2.0) e TravelHealthPro (Open
Government Licence v3.0): i fatti sono riscritti con parole proprie e le fonti sono indicate nelle righe `#` di ogni
file. `GenerateVaccinations.kt` li scrive in `guides.db` come tabelle `vacc_*`, tutte le righe e non solo quelle delle
regioni pubblicate, perché partenza e scali di un viaggio possono essere paesi senza regione; la validazione è in
`GenerateVaccinationsTest`. `scripts/vaccinations_draft.py` scarica i dati di Travel.gc.ca e produce solo bozze da
rivedere a mano: non vanno copiate nel repository così come sono.

Il controllo deriva `scripts/vaccinations_drift.py` confronta le fonti con l'istantanea `vaccinations-drift.tsv`
(per ogni paese, le frasi di Travel.gc.ca su febbre gialla e polio e l'elenco dei vaccini, più l'hash del PDF
saudita per Hajj e Umrah) e controlla che l'OMS non abbia pubblicato uno statement polio più recente di quello di
`polio-status.tsv`. Gira nel job `vaccinations-check` di publish-regions, col bucket del lunedì e nelle run manuali:
se qualcosa è cambiato lascia un warning e il resoconto nel riepilogo della run, senza toccare i TSV e senza
bloccare la pubblicazione. Dopo aver aggiornato i TSV a mano si rigenera l'istantanea con
`python scripts/vaccinations_drift.py --update` e si committano insieme.

## Città, guide e dataset di addestramento

- **Popolazione e capitale delle città**: `scripts/city_population.py` è usato da `extract-cities-dump.py` e
  `extract-cities-dump-en.py`. La popolazione viene dal campo "Abitanti" di Wikivoyage, in alternativa da Wikidata
  (P1082); la capitale della regione da Wikidata (P36). Finiscono nelle colonne `population` e `capital` di
  `cities.db`; senza dati la città resta senza popolazione. Le coordinate (Wikidata P625) finiscono nelle colonne
  `latitude` e `longitude`: con queste l'assistente calcola la distanza tra due città nominate in una domanda.
- **Storia e clima delle città**: `scripts/city_wikipedia.py`, usato dagli stessi due script, trova la voce di
  Wikipedia (IT o EN) della città dal sitelink dell'elemento Wikidata della pagina di Wikivoyage e ne prende le sezioni
  Storia e Clima (History e Climate in inglese) dall'API di Wikipedia, a lotti: i dump completi (IT ~5 GB, EN ~24 GB)
  non stanno nei runner. `GenerateCities.kt` le pulisce come le guide e le scrive in `cities.db` come categorie
  `STORIA` e `CLIMA`, con la voce come `sourceUrl`; la Storia si ferma all'ultimo paragrafo entro 4.000 caratteri.
  Le versioni dell'app che non conoscono le due categorie le saltano all'import. Mai fatale: un errore di rete lascia
  le città senza le due sezioni. Il tempo è limitato a 20 minuti per shard e lingua (`MAX_SECONDS`); l'Italia in
  italiano, ~2.700 città, ne richiede circa 4, in inglese circa uno.
- **Misura della ricerca dell'assistente**: `scripts/eval_retrieval.py` replica in Python la ricerca di
  `TravelAssistant.kt` e `FtsRanking.kt` (BM25, città nominata nella domanda, paragrafi del contesto) e la misura sulle
  guide pubblicate di alcune regioni, con Storia e Clima da Wikipedia se i `cities.db` pubblicati non li hanno ancora,
  e domande costruite da modelli fissi (pratiche sulle città, sul paese, storia e clima). Stampa quante volte la sezione
  attesa entra nel contesto e quante volte la risposta ci sta dentro. Va aggiornato insieme alla ricerca dell'app;
  `--lang en` misura le guide e le domande inglesi, `--no-wikipedia` misura senza le due sezioni. Anche `make_context`
  (`generate_sft_dataset.py`), che costruisce il contesto dei dataset SFT e dei set di valutazione, sceglie i paragrafi
  come l'app (`test_make_context.py` lo confronta con la replica di `eval_retrieval.py`).
- **Guide**: la sottosezione "Costo della vita" ("Cost of living" in inglese) viene omessa da guide e città. I fatti
  rapidi delle guide inglesi comprendono anche la lingua e i numeri di emergenza, come quelli italiani.
- **Consigli di viaggio del Governo del Canada (guide inglesi)**: `build-guides.sh en` scarica per ogni paese (`flagCode`
  di `regions.sh`) il JSON open data `data.international.gc.ca/travel-voyage/cta-cap-<iso2>.json` (Open Government
  Licence - Canada 2.0) e `GenerateTravelAdvice.kt` ne fa quattro sezioni della guida, con l'url della pagina del paese
  su travel.gc.ca e la data dell'ultimo aggiornamento: sicurezza (con il livello di rischio e gli avvisi regionali in
  testa), leggi e cultura, catastrofi naturali e clima, salute. Restano fuori ingresso e uscita, uffici consolari, i
  sottotitoli per i canadesi o sui vaccini (`excludedAdviceHeadings`) e le frasi che nominano il Canada o in cui parla il
  governo ("we", "our"). Un paese non scaricato tiene i consigli del `guides-en.db` pubblicato. Sui dati del 2026-09/10:
  232 regioni, +4,4 MB di `guides-en.db` e +0,4 MB compresso. Il testo cambia per quasi tutti i paesi ogni due
  settimane e il job `guides` gira ogni giorno: per regione la tabella `travel_advice_meta` (livello di rischio, avvisi
  regionali, data di download; le app la ignorano) e `keepPublishedGuides` tiene il `guides-en.db` pubblicato quando sono
  cambiati solo i consigli, finché sono stati scaricati da meno di 7 giorni (`TRAVEL_ADVICE_MAX_AGE_DAYS`) e nessun paese
  ha cambiato livello di rischio o avvisi regionali; un cambio di rischio pubblica subito e lascia un `::warning::` nel
  job, un cambio di Wikivoyage pubblica subito con i consigli freschi.
- **Dataset SFT (v9-v10)**: `scripts/generate_sft.py --lang it|en` genera il dataset dei due modelli linguistici con le
  stesse fonti, la stessa composizione e gli stessi tipi di domanda. Con `--vaccinations` aggiunge domande sui
  vaccini, con il riassunto che l'app inserisce nel contesto dell'assistente. Con `--nearby <quota>` (per esempio 0.03)
  aggiunge esempi con i blocchi "Punti di interesse entro…" e "Prossime partenze…" che l'app mette nel contesto per le
  domande su cosa c'è vicino e sui mezzi, nello stesso formato (`scripts/sft_nearby.py`, dati sintetici, circa un quarto
  di rifiuti); senza il flag l'output resta quello del v9. Con `--distances <quota>` (per esempio 0.02, insieme a
  `--cities`) aggiunge domande sulla distanza tra due città con le sezioni "Come arrivare" e "Come spostarsi" di
  entrambe nel contesto: metà coppie che una guida collega con km o tempi di viaggio, metà coppie che si nominano
  senza. Con le coordinate di Wikidata delle due città (scaricate durante la generazione e conservate in `data/sft/raw/coordinates.<lang>.json`; senza rete e senza
  cache la generazione si ferma) circa sei esempi su dieci
  hanno in testa al contesto la distanza calcolata come nell'app, in linea d'aria o con il percorso in auto (lunghezza
  e tempo sintetici, ricavati dalla distanza vera in linea d'aria), e la risposta è quel testo; negli altri la risposta
  è la frase della guida con i km, o il rifiuto se manca. Con `--cities-db <file> ...` (i `cities.db` pubblicati, o
  `cities-en.db` per l'inglese, uno per regione: la regione è il nome del file fino al primo `--`, come negli asset
  delle release `<regionId>--<versione>--cities.db`, oppure si scrive `<regionId>=<file>`) aggiunge domande di storia e
  clima sulle sezioni `STORIA` e `CLIMA` di Wikipedia, per al massimo `--wikipedia-cities` città (1500): positivi con la
  sezione nel contesto, rifiuti per le città che non l'hanno, e domande pratiche con Storia o Clima in coda al contesto
  (le domande di storia e clima hanno una parola di `historyClimateWords` in `TravelAssistant.kt`, che altrimenti
  declassa quelle sezioni). Con `--emergency <quota>` (per esempio 0.01) aggiunge domande sui numeri di emergenza con la
  riga dell'app (`emergencyNumbersContext`, da `emergency-numbers.tsv`) in testa al contesto e come risposta, e il
  rifiuto per le regioni senza numero centralizzato. Di default (`--user-style-questions 0.2`) nelle categorie Cosa vedere,
  Alloggio, Sicurezza, Trasporti e Usi e costumi una domanda su cinque, tra positivi e rifiuti, è una domanda di viaggio
  in stile utente invece di un modello: `scripts/travel_questions.py` prende le prime domande delle conversazioni di
  `soniawmeyer/travel-conversations-finetuning` della sola parte UltraChat (MIT; fuori Reddit e Dolly), tiene quelle
  senza nomi di luogo che toccano una sola categoria e le traduce in italiano con MarianMT per il dataset italiano;
  senza rete restano i modelli. Di default (`--city-daily-life`) le città hanno anche la sezione "Informazioni utili" /
  "Cope" (Vita quotidiana, come in `cities.db` dell'app), con in più le città oltre `--cities` che la hanno (solo quella
  domanda, nessun rifiuto): nel v9 la categoria aveva solo le sezioni dei paesi e quasi tanti rifiuti quanti positivi. Le
  sue parole chiave in più (uffici turistici, farmacie, consolati) valgono anche per i paesi, e i rifiuti delle città
  hanno nel contesto solo sezioni che non trattano la categoria. Di
  default (`--balanced-negatives`) la categoria di un rifiuto si sceglie in proporzione ai positivi della categoria
  (paesi) o alle sezioni che la trattano (città), invece che con la stessa probabilità per tutte. Di default
  (`--clear-questions`) restano fuori le domande che una sezione di un'altra categoria soddisfa altrettanto ("Quali
  informazioni pratiche mi servono…") e le domande sui vaccini tra quelle sulla salute: nell'app portano nel contesto il
  riassunto delle vaccinazioni, quindi sono domande di Vaccinazioni. I file di output sono
  `pocket_travel_sft.<versione>.<lang>.jsonl` e `ATTRIBUTION.<versione>.<lang>.tsv`: la versione è v10 con
  `--nearby`, `--distances`, `--cities-db`, `--emergency`, le domande in stile utente, `--city-daily-life`,
  `--balanced-negatives` o `--clear-questions` (i default), v9 senza nessuna di queste,
  oppure quella data con `--version`; un v9 che esiste già (lo usano i training) si sovrascrive solo con
  `--version v9`. Le fonti e la pulizia sono cambiate dopo il v9 generato: gli stessi argomenti non ridanno quel file. I test estesi
  (`generate_eval_set.py`, `generate_eval_set_en.py`) hanno in fondo le righe `pos_near`/`neg_near` e
  `pos_dep`/`neg_dep` sugli stessi blocchi, con domande, nomi e seme diversi da quelli del training, e con
  `--cities-db` (solo le regioni di test) `pos_wiki`/`neg_wiki` su storia e clima; le righe
  precedenti restano identiche. `scripts/translate_dataset.py` traduce
  con MarianMT (`opus-mt-tc-big`, CC BY 4.0) le sezioni assenti o molto più brevi in una lingua; scarta le
  traduzioni con numeri diversi dall'originale, toglie le parentesi rimaste vuote (MarianMT perde i caratteri di altri
  alfabeti, come i nomi cinesi o arabi tra parentesi) e conserva le frasi tradotte in una cache. Le tabelle per lingua
  stanno in `generate_sft_dataset.py` e `generate_sft_dataset_en.py`; il primo cerca anche i titoli mancanti di
  `sft-sources.tsv` e riempie la cache `data/sft/raw/` usata dai test estesi.
  Ogni generatore scrive anche `EXCLUDED[.<versione>].<lang>.tsv`: per regione, fonte e categoria, cosa è rimasto
  fuori e perché (regione di test, pagina assente, pagina senza sezioni utili, markup residuo, sezione che non tratta la
  sua categoria, nessuna risposta estratta); il riepilogo per fonte e motivo si stampa a fine generazione.
  `scripts/audit_sft.py <dataset.jsonl> [--eval <test esteso>] [--out <pulito.jsonl>] [--strict]` controlla un dataset
  prima del training: righe malformate, contesti o risposte oltre i limiti, risposte positive con frasi che non sono nel
  contesto, markup residuo, sottoregioni delle regioni di test, duplicati, righe che ripetono il test esteso; stampa
  anche quota di rifiuti, righe per categoria e regione e domande più ripetute. Con `--out` scrive una copia senza le
  righe con errori e senza i duplicati.
  Nelle risposte estratte dalle guide, frasi di righe diverse (voci di elenco, sottosezioni) restano su righe diverse.
  `scripts/generate-sft.sh [--new | --old] --data <cartella>` fa tutto in sequenza per le due lingue: dataset, test
  estesi e audit `--strict`, dal dump più recente in `<cartella>/dumps/` e dai `guides*.db`, `cities*.db` e
  riassunti delle vaccinazioni pubblicati nella stessa cartella (lo schema è nell'intestazione dello script). `--new` (il
  default) è la ricetta v10; `--old` quella del v9, scritta come `v9-rigenerato` per non sostituire il v9 dei training.
- **Fonti del dataset SFT**: i titoli delle pagine di ogni regione stanno in `sft-sources.tsv` (`-` = pagina che non
  esiste). Le righe mancanti si cercano via API durante `generate_sft_dataset.py --dump-dir`; un errore di rete non
  diventa `-`, così la volta dopo si riprova, e `--recheck-missing` cerca di nuovo tutte le pagine date per inesistenti.
  L'articolo tematico di Wikipedia si cerca dal titolo inglese ("Cuisine of the Bahamas", "Fujian cuisine",
  "Culture of X"…) e vale solo se la pagina a cui arriva ha nel titolo la parola del tema: un titolo che rinvia alla
  voce del paese non conta. I titoli che nei dump sono redirect ("The Bahamas", "Curacao") si leggono dalla pagina di
  destinazione. Il dataset italiano usa gli articoli di Wikipedia IT; quello inglese (`generate_sft.py --lang en`) gli
  articoli originali di Wikipedia EN con gli stessi titoli, e traduce da Wikipedia IT solo i temi senza articolo inglese.
- **Dump di Wikimedia**: tutti gli script usano i MediaWiki Content File Exports, uno al mese il 1°
  (`https://dumps.wikimedia.org/other/mediawiki_content_current/<wiki>/<AAAA-MM-GG>/xml/bzip2/`): una o più parti
  `<wiki>-<AAAA-MM-GG>-p<da>p<a>.xml.bz2` per wiki e i loro sha256 in `SHA256SUMS`, scritto a dump finito.
  `scripts/download-wikimedia-dumps.sh <cartella> [data]` scarica Wikivoyage IT ed EN e Wikipedia IT ed EN (19 parti,
  circa 47 GB) in `<cartella>/<AAAA-MM-GG>/`, con ripresa e verifica sha256; senza data prende l'ultimo dump completo
  delle quattro wiki e, a download verificato, cancella le cartelle dei dump più vecchi. Per `--dump-dir` servono
  le quattro wiki dello stesso giorno nella stessa cartella (Wikipedia EN solo per il dataset inglese); il nome della
  cartella fa da data, se non si passa `--dump-date`. La data si scrive come mese e anno (`10-2026` o `2026-10`, anche
  con `/` o `.`: il giorno è sempre il 1°) oppure per intero (`2026-10-01`). Wikipedia non ha un indice: la prima
  generazione scorre le parti in parallelo (un processo per CPU) e salva gli articoli che servono in
  `<wiki>-<AAAA-MM-GG>.pages.json` accanto ai dump; le generazioni successive li leggono da lì. Le guide delle città (`build-cities-dump.sh`) scaricano invece l'ultimo
  dump completo di Wikivoyage (`fetch_wikivoyage_dump_parts` in `lib.sh`, quello del mese prima finché il nuovo non
  ha `SHA256SUMS`), verificato con sha256 e tenuto nella cache di Actions fino al dump successivo; dopo un download
  riuscito le parti dei dump vecchi escono dalla cache.
- **Guide arricchite dall'altra lingua**: `scripts/translate_guides.py` confronta, per regione o città e categoria, la
  sezione con quella dell'altra lingua (assente, sotto 300 caratteri o lunga meno della metà) e traduce la più ricca con
  MarianMT (`opus-mt-tc-big-en-it` e `-it-en`, CC BY 4.0, CTranslate2 int8 su CPU): l'italiano dall'inglese e l'inglese
  dall'italiano. `generateGuides` e `generateCities` con `--translated` sostituiscono la sezione povera e segnano la
  riga con `translated = 1`, che le app vecchie ignorano. In publish-regions i passi girano solo con la variabile del
  repository `TRANSLATE_GUIDES` uguale a `true`, quindi per ora le guide escono come prima: va accesa dopo una revisione
  a campione, perché sui dati attuali sostituirebbe circa 2.000 sezioni italiane. Mai bloccante: se il motore o il
  modello mancano, o il tempo finisce, la guida esce originale; la cache delle frasi tradotte tiene il lavoro fatto.
