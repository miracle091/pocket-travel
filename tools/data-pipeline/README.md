# Pipeline dei dati

Script, tool Kotlin e workflow (`.github/workflows/publish-regions.yml`) che producono i pacchetti
scaricati dall'app: il catalogo (`manifest.json`, `transit.json`, `address-grid.json`) e i file che elenca.
La panoramica è nel `README.md` della root.

## Workflow `publish-regions.yml`

`manifest.json`, `address-grid.json`, `transit.json` e `region-timings.tsv` si pubblicano su GitHub Pages; i
pacchetti (guide, POI, segmenti `.rd5`, città, mappe d'insieme, celle dei civici, orari) sono asset delle release
`region-data*`, `address-cells-*`, `transit-feeds` e `world-map`. Pages ha un limite fisso di 1 GB per l'intero sito,
superato con la copertura mondiale di `regions.sh`; gli asset di una release no (al massimo 1000 per release, da cui
le release numerate). `map.pmtiles` non si ospita: l'app lo estrae dalla build Protomaps (`PmtilesExtractor`). I
segmenti `.rd5` invece si ripubblicano, perché i file di brouter.de cambiano dimensione e hash nel tempo (commento in
testa a `scripts/build-region.sh`). `scripts/clip_rd5.py` confronta la versione dei lookups di ogni tile con quella
di `feature/map/src/main/assets/brouter-profile/lookups.dat`: se la versione maggiore è diversa (BRouter non leggerebbe
il segmento) esce con errore, la regione fallisce e resta il pacchetto già pubblicato; se solo la minore è più alta
dà un avviso. Per adeguarsi si aggiornano `lookups.dat` e i profili con una nuova versione dell'app.

| Job | Nome | Cosa fa |
|---|---|---|
| `plan` | Piano degli shard | crea le release mancanti, sceglie le regioni della run e le divide in 3 shard |
| `build` | Regioni (shard N) | genera i pacchetti delle regioni dello shard (`scripts/build-region.sh`, `build-cities.sh`) |
| `guides` | Guide | rigenera `guides.db` e `guides-en.db` per tutte le regioni; pubblica solo se cambiano |
| `world-map` | Mappa del mondo | mappa del mondo z0-8, rigenerata ogni ~30 giorni o se assente |
| `address-grid` | Civici | celle dei numeri civici della quota del giorno (sezione sotto) |
| `transit` | Mezzi pubblici | reti di `transit-feeds.tsv`, ricostruite se il feed cambia o dopo 7 giorni; una rete con gli orari scaduti passa da sola tra le "Non incluse" e torna pubblicata quando gli orari tornano validi, con un warning nella run solo in questi due cambi di stato; finche' il feed scaricato ha lo stesso sha256 (`sourceSha256` nella voce di `missing`), la rete scaduta non si riconverte e resta muta; un host che non risponde a nessun tentativo di una rete (30 s per connettersi) non si riprova per le altre reti dello stesso job, che tengono la voce pubblicata; le reti con `url_rt` (feed GTFS-RT dell'ente) tengono i `trip_id` e gli `stop_id` del GTFS e hanno in `transit.json` `realtimeUrl` e `realtimeSizeBytes` |
| `overture-check` | Controllo Overture | lista bianca delle fonti Overture, una volta per rilascio; esito in una issue |
| `vaccinations-check` | Controllo vaccinazioni | deriva dei dati vaccinali, il lunedì e nelle run manuali |
| `publish` | Pubblicazione | unisce i frammenti di catalogo al `manifest.json` pubblicato, lo firma e fa il deploy su Pages |
| `cleanup` | Pulizia degli asset | toglie dalle release gli asset che il catalogo nuovo e il precedente non referenziano più |

Gli script lunghi dei job stanno in `.github/scripts/publish-regions/`; la preparazione comune (JDK, Gradle con la
cache in sola lettura, go-pmtiles, duckdb, osmium) è l'azione `.github/actions/setup-pipeline`. Le cache di Actions
superate (dump, frasi tradotte, Gradle, ccache) le toglie ogni domenica `.github/workflows/cache-cleanup.yml`: di ogni
famiglia di chiavi restano le 2 voci usate più di recente; a mano, con `dry_run`, elenca senza cancellare.

**Calendario.** Sette righe `schedule` (una al giorno) rigenerano il bucket del giorno, definito in
`scripts/weekly-schedule.sh` (generato da `generate-weekly-schedule.sh`): la cadenza è settimanale perché i segmenti
`.rd5` di BRouter si rigenerano ogni settimana. `workflow_dispatch` serve per le run mirate (`regions`) o per
rigenerare tutte le regioni (`regions` vuoto).

**Bucket e shard.** Il lavoro si divide su più giorni e, in ogni giorno, su 3 shard in parallelo, per due limiti:
le 6 ore massime di un job di GitHub Actions e l'uso corretto dei mirror Overpass pubblici. La divisione tra shard è
per peso, non per indice: il peso è il tempo dell'ultima generazione completa della regione (`region-timings.tsv`
sul sito), o 50 secondi per tile di terra se non c'è una misura. Senza bilanciamento più regioni grandi (Canada,
Stati Uniti, Russia, Brasile) potrebbero finire nello stesso shard e superare le 6 ore. Nelle run del calendario,
dopo le regioni del giorno, ogni shard riceve una coda di regioni in ritardo e di quelle del giorno dopo (quota
adattiva), avviate solo se la stima sta nel budget di tempo; le regioni del giorno oltre il budget passano a una
continuazione automatica.

**Run sovrapposte.** Il workflow non ha `concurrency`: un gruppo tiene al massimo una run in attesa, e una run
manuale lanciata durante un'altra farebbe annullare il bucket del giorno dopo rimasto in coda. Solo `publish` sta
nel gruppo `pages` e solo `cleanup` nel gruppo `asset-cleanup` (una pulizia annullata in coda non perde nulla: la
successiva toglie gli stessi asset). Gli asset di run diverse non si scontrano (versione unica per run e tentativo) e
la pulizia non tocca gli asset caricati da meno di 24 ore.

**Permessi.** A livello di workflow solo `contents: read`; `contents: write` ai job che creano release o caricano
asset, `pages` e `id-token` solo a `publish`. I checkout dei job che scrivono sulle release hanno
`persist-credentials: false`, così il token di scrittura non resta nella configurazione git leggibile dal codice di
build.

**Prove in locale.** Gli script dei job (`.github/scripts/`, `.github/scripts/publish-regions/`) e quelli di
`scripts/` girano anche su un PC, Linux o Windows con Git Bash, dalla root del repository. Servono bash, curl, jq 1.7
o più recente, xz, unzip, python 3, un JDK per `./gradlew` e `gh` autenticato; go-pmtiles si installa con
`bash .github/scripts/install-go-pmtiles.sh`, osmium-tool è facoltativo. macOS non è supportato (`date`, `stat` e
`sed` di BSD). Fuori da GitHub Actions `.github/scripts/env.sh` dà alle variabili del runner un valore di prova
(cartella temporanea nuova, repository dal remote `origin`, run 0) e stampa invece di eseguire i comandi `gh` che
scrivono sul repository (esegue solo le letture: release, cache, run, `gh api` in GET): con `PUBLISH=1` li esegue
davvero. Anche `.github/scripts/cache_cleanup.py` fuori da Actions elenca soltanto, salvo `PUBLISH=1`. Su Git Bash
`scripts/portable.sh` fa di `python3` un alias di `python` e fa scrivere a `jq` righe senza `\r`. Ogni script crea una
sua cartella temporanea, che non si cancella da sola: per concatenare più script come nei job (per esempio
`build-shard.sh` e poi `assemble.sh`) si esporta prima la stessa `RUNNER_TEMP`. Esempi:

```bash
# Divisione in shard di alcune regioni (stampa il piano, scrive gli output in un file temporaneo)
REGIONS_INPUT=andorra,italia bash .github/scripts/publish-regions/plan-shards.sh
# Una regione come nel job "build", senza caricare nulla
SELECTED=san-marino bash .github/scripts/publish-regions/build-shard.sh
```

Il job `windows-scripts` di Android CI esegue i test Python e una di queste prove su Windows, perché uno script che
funziona solo su Linux si veda subito.

Il job `llm-smoke` di Android CI ("Modello vero con llama.cpp") gira quando cambiano le patch di llama.cpp, il commit
del submodule o gli script della prova: `.github/scripts/llm-smoke.sh` compila `llama-server` per la CPU dalla copia
patchata del submodule, come il build dell'app, e `scripts/llm_smoke.py` controlla poche risposte fisse del modello più
piccolo del catalogo dei modelli (risposta attesa, rifiuto, nessun `<think>`, grammatica della categoria). In locale
serve `cmake` nel PATH (su Windows anche Visual Studio con il C++) e il file del modello:

```bash
bash .github/scripts/llm-smoke.sh <file .gguf>
```

**Rumore noto nei log.** Messaggi che non dipendono dal progetto e non vanno inseguiti:

- `DeprecationWarning: Buffer() is deprecated` (Node, nel job `publish`): da `actions/download-artifact` 8.0.2,
  l'ultima versione;
- `The Configuration.setVisible(boolean) method has been deprecated` (Gradle, in Android CI): dal plugin Android
  (AGP 9.4.1, l'ultima stabile), non dagli script del progetto;
- emulatore su un runner senza schermo (Android CI, `device-tests`): `Could not open libX11.so`, `libX11-xcb.so`,
  `Feature QuickbootFileBacked is disabled`, `Failed to process .ini file`, `Netsim Wifi ... is gone`,
  `Emulator client has not yet been configured`, `cannnot unmap ptr`;
- `if-no-files-found: warn` e `digest-mismatch: error`: parametri delle action stampati nel log, non avvisi.

## Punti di interesse e confine

`build-region.sh` prende i POI dagli estratti Geofabrik con `scripts/geofabrik_pois.py` e li taglia sul confine
completo di OpenStreetMap della nazione o della suddivisione (`scripts/country_border.py`). Ogni oggetto ha un solo
punto sull'oggetto e sta nella regione il cui confine lo contiene. Le regioni russe hanno il confine della Russia e
rettangoli sovrapposti: un punto in più rettangoli va alla regione con il centro del rettangolo più vicino
(`--sibling`, campo `poi.scope` del catalogo).

- Se gli estratti non sono usabili, i POI vengono da Overpass, tile per tile. `geofabrik_pois.py --overpass` applica
  confine e sorelle sul nodo o sul `<center>` di Overpass, che per le vie aperte e le aree a C non è il punto
  sull'oggetto. La voce resta quindi senza `poi.borderRule` e `poi.scope`, e la run successiva rigenera i POI dagli
  estratti.
- I rettangoli non attraversano il meridiano di 180°. Le terre oltre il meridiano di una nazione con una sola regione
  non sono in nessuna regione: Chatham e Kermadec (Nuova Zelanda), Attu e Shemya (Alaska), la punta della Čukotka e
  l'isola di Wrangel (Russia), le Phoenix occidentali (Kiribati). Figi e Kiribati sono divise in due regioni per
  questo motivo. `scripts/bbox_audit.py` elenca le terre di ogni confine fuori da tutti i rettangoli.

## Firma dei file JSON

I JSON che l'app scarica sono firmati con ECDSA P-256 e SHA-256 (supportati da `java.security` su
Android API 26+, a differenza di Ed25519). Accanto a ogni file c'è `<nome>.sig`, la firma in formato DER
calcolata sui byte esatti del file:

| File | URL |
|---|---|
| `manifest.json` | `https://miracle091.github.io/pocket-travel/manifest.json` (+ `.sig`) |
| `address-grid.json` | `https://miracle091.github.io/pocket-travel/address-grid.json` (+ `.sig`) |
| `transit.json` | `https://miracle091.github.io/pocket-travel/transit.json` (+ `.sig`) |
| `app-status.json` | `https://github.com/miracle091/pocket-travel/releases/download/app-status/app-status.json` (+ `.sig`) |
| `online-models.json` | `https://github.com/miracle091/pocket-travel/releases/download/app-status/online-models.json` (+ `.sig`) |

La firma si fa nel workflow (`.github/scripts/sign-files.sh`) con la chiave privata del secret
`MANIFEST_SIGNING_KEY`, solo nel passo che la usa; se il secret manca il job fallisce, per non pubblicare
file non firmati.

`app-status.json` si aggiorna in tre modi, tutti in `publish-apk.yml`:
- al rilascio dell'app (bump di `versionCode`): versione dal catalogo di main; per ogni modello l'impronta
  che coincide con il file su Hugging Face, presa dal catalogo dei modelli o dal file online se la sua firma è valida
  (`scripts/resolve_ai_models.py`). Se nessuna coincide il job si ferma prima di compilare: una release che
  rifiuterebbe i download non esce;
- lancio manuale con `update_models`, avviato da `scripts/upload_hf.py --update-app-status` dopo il
  caricamento di un GGUF con lo stesso nome file: cambia solo sha256 e dimensione di quei modelli, dopo aver
  verificato la firma del file online e le impronte su Hugging Face. Le app dalla 0.9.1 verificano il
  download con queste impronte, quindi il modello nuovo si scarica senza rilasciare l'app; lo script poi
  aggiorna anche `LlmModelCatalog.kt`, che resta la riserva quando l'app non raggiunge `app-status.json`;
- lancio manuale con `resign_app_status`: lo ricostruisce dal tag della versione rilasciata, con le stesse
  regole del rilascio per le impronte, e lo firma.

`online-models.json` è l'elenco dei modelli proposti per i servizi del motore Online, con i prezzi per la stima
del costo. Si scrive a mano in `feature/ai/src/main/assets/online-models.json`, che resta nell'APK come elenco di
riserva; `publish-online-models.yml` lo controlla, aggiunge `publishedAt`, lo firma e lo pubblica sulla release
`app-status` a ogni push su `main` che lo modifica. Per ogni servizio (`CHATGPT`, `MISTRAL`, `GEMINI`, `CLAUDE`):
un solo modello con `"role": "auto"` (l'opzione Automatico), gli altri con `"capable"`; `"temperature": false` per
i modelli che rifiutano una temperatura impostata o non la usano più; prezzi in dollari per milione di token. Come
`app-status.json`, resta quello del progetto anche con il catalogo di un fork.

Impostazione della chiave (una volta sola; la chiave privata non va mai committata):

```bash
# chiave privata (PEM)
openssl ecparam -name prime256v1 -genkey -noout -out key.pem
# chiave pubblica da incorporare nell'app: DER in base64, su una riga
openssl ec -in key.pem -pubout -outform DER | base64 -w0
# secret del repository
gh secret set MANIFEST_SIGNING_KEY < key.pem
```

Senza ricompilare l'app, il catalogo di un fork si usa da **Altro → Impostazioni → Catalogo**: si inseriscono l'indirizzo di `manifest.json` (solo https) e la chiave pubblica in base64. L'app scarica `manifest.json` e la sua firma, li verifica e solo allora salva il catalogo e si riavvia. I file del catalogo devono stare sull'host di `manifest.json` o su `github.com` (asset delle release). `app-status.json` e `online-models.json` restano quelli del progetto, verificati con la chiave del progetto.

Per controllare a mano un file scaricato:

```bash
openssl ec -in key.pem -pubout -out pub.pem
openssl dgst -sha256 -verify pub.pem -signature manifest.json.sig manifest.json
```

## Numeri civici a celle (`address-grid.json`)

I numeri civici (OSM + Overture) non si pubblicano per regione ma in celle di una griglia: job `address-grid` di
`publish-regions.yml`, una cella alla volta con `scripts/build-address-cell.sh`, indice unito da
`GenerateAddressGrid.kt` e controllato da `validateAddressGridJson` (`ValidateManifest.kt`).

- **Cella**: nodo `z/x/y` del quadtree Web Mercator con `z <= 14` (`tileZoom`), che contiene le tile z14
  discendenti. Il file è un PMTiles con i numeri civici di quelle tile (`GenerateAddresses --cell`) e, se ci sono
  vie, un indice di ricerca `addresses-search.db`. Le celle senza dati non esistono.
- **Partizione**: le celle dell'indice pubblicato più quelle del seme `address-grid-seed.tsv` (righe
  `z/x/y`, da `scripts/seed-address-grid.py`) non ancora coperte, cioè né pubblicate né antenate di una
  cella pubblicata. Nessuna cella dell'indice è antenata di un'altra.
- **Crescita**: se il `.xz` di una cella supera `ADDRESS_CELL_MAX_BYTES` (10.000.000 byte) si pubblicano al
  suo posto i 4 figli con dati, ricorsivamente fino a z14 (lì la cella resta oltre il tetto, con un
  warning). Le celle non si fondono mai: gli id restano stabili.
- **Ricostruzione**: ogni notte le celle con `cksum(id) % 30` uguale al giorno dall'epoca `% 30`; una cella
  pubblicata da meno di `ADDRESS_CELL_MAX_AGE_DAYS` (30) giorni tiene la sua voce, e una cella rigenerata
  con lo stesso sha256 tiene versione e asset pubblicati. Input manuali: `address_cells` (id della
  partizione separati da virgola, al posto della quota del giorno) e `address_cell_max_age_days` (-1 =
  rigenera anche le celle recenti).
- **Release**: `address-cells-<n>` con `n = cksum(id) % ADDRESS_CELL_RELEASES` (12), calcolato sulla cella
  scelta per la run (i figli di una divisione finiscono nella stessa release). Erano 6: 12 è multiplo di
  6, quindi le celle con `n < 6` restano dove sono e le altre passano alle release 6–11 solo quando si
  rigenerano. La pulizia finale guarda tutte le `address-cells-*`. Asset caricati:
  `cell-<z>-<x>-<y>--<version>--addresses.pmtiles.xz` e `...--addresses-search.db.xz`.

Indice, firmato come gli altri JSON e puntato dalla voce `"addressGrid": { "version", "url", "sizeBytes",
"sha256" }` di `manifest.json`:

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

- `cells` ordinato per `id` come testo (`"4/11/4"` prima di `"4/9/3"`): l'app rifiuta l'indice in un altro ordine; `url` è sempre quello del `.xz`, `file` ne dà dimensione e sha256
  decompressi. `search` è facoltativo (le app vecchie lo ignorano).
- `attributions` (schermata Licenze): OSM, Overture con i distributori e il testo di ogni fonte ammessa
  dalla lista bianca `overture-address-sources.tsv`, anche se le celle di oggi non la usano.
- L'app scarica le celle che intersecano il rettangolo della regione, le unisce in un solo
  `addresses.pmtiles` locale e usa come versione dei numeri civici `grid-` + i primi 16 esadecimali dello sha256
  degli `id@version` delle celle ordinati (`+search` per le celle con l'indice di ricerca;
  `AddressGridSelection.kt`).

## Vaccinazioni (tabelle `vacc_*` di `guides.db`)

I dati vaccinali sono file TSV curati a mano in `content/src/main/resources/vaccinations/` (febbre gialla in
ingresso, in uscita e per rischio, polio, requisiti speciali come Hajj e Umrah, vaccini raccomandati per
destinazione). Le fonti sono Travel.gc.ca (Open Government Licence - Canada 2.0) e TravelHealthPro (Open
Government Licence v3.0): i fatti sono riscritti con parole proprie e le fonti sono indicate nelle righe `#` di ogni
file. `GenerateVaccinations.kt` li scrive in `guides.db` come tabelle `vacc_*`, tutte le righe e non solo quelle delle
regioni pubblicate, perché partenza e scali di un viaggio possono essere paesi senza regione; la validazione è in
`GenerateVaccinationsTest`. `scripts/vaccinations_draft.py` scarica i dati di Travel.gc.ca e produce solo bozze da
rivedere a mano: non vanno copiate nel repository così come sono.

Requisiti polio in ingresso senza fonte aperta e attuale (ricerca dell'8 ott 2026): per Egitto, Nepal, Iran e India
`polio-entry.tsv` ha solo righe con provenienza `UNLISTED`, dal FCDO (India, Nepal, Iran) e da France Diplomatie
(Egitto), che dicono che un requisito esiste. L'app ne fa una voce "da valutare" per qualunque provenienza, con il
link al sito degli esteri della nazionalità dell'utente. Cosa dicono le fonti:
- OMS, elenco per paese del 2022: CC BY-NC-SA 3.0 IGO, non usabile.
- FCDO (Open Government Licence v3.0): per India, Nepal e Iran il vaccino serve a chi arriva da un paese colpito dalla
  polio, senza elenco di paesi, tipo di vaccino o finestra; per l'Egitto nulla. TravelHealthPro rimanda all'OMS.
- Travel.gc.ca, pagina "Entry and exit requirements" dell'India: 8 paesi di provenienza (Afghanistan, RD Congo,
  Etiopia, Kenya, Nigeria, Pakistan, Somalia, Siria), che non coincidono con l'ordine del Ministero della salute
  indiano del 10 giugno 2024 (OPV o IPV almeno 4 settimane prima, certificato valido un anno, per chi ha il passaporto
  o risiede in Afghanistan, Pakistan, Camerun, Nigeria, Malawi, Mozambico, Madagascar, Congo, RD Congo, Somalia e
  Siria). L'ordine è "All Rights Reserved", quindi serve solo da controllo.
- France Diplomatie, pagine "Santé" (Licence Ouverte 2.0, riuso commerciale ammesso): elenchi per Egitto (Afghanistan,
  Nigeria, Pakistan, Papua Nuova Guinea, Somalia; Kenya, Niger, RD Congo, Siria) e Nepal (Afghanistan, Kenya, Nigeria,
  Pakistan, Papua Nuova Guinea, Somalia) che sembrano fermi al 2018–2019 e per l'Egitto non coincidono con quanto
  riportato dalla stampa nel 2023; per l'India solo vaccino orale per i residenti dei paesi colpiti, per l'Iran nulla.
- Senza dati sulla polio in ingresso o con licenza non compatibile: Smartraveller, SafeTravel, FORTH (Giappone),
  KDCA, Irlanda, Spagna, Wikipedia e Wikivoyage, International SOS, IATA Timatic.

Il controllo deriva segnala quando Travel.gc.ca cambia la sezione polio della pagina d'ingresso (vedi sotto).

Il controllo deriva `scripts/vaccinations_drift.py` confronta le fonti con l'istantanea `vaccinations-drift.tsv`
(per ogni paese, le frasi di Travel.gc.ca su febbre gialla e polio e l'elenco dei vaccini della pagina "Health", la
sezione polio della pagina "Entry and exit requirements", più l'hash del PDF
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
- **Dati dei paesi**: `scripts/wikidata_countries.py --out content/src/main/resources/countries.tsv` scrive da
  Wikidata (CC0) una riga per regione di `regions.sh` con capitale, valuta, lato di guida, prefisso, lingue ufficiali,
  fusi orari, numeri di emergenza, prese e tensione, con le etichette in italiano e in inglese. I fusi orari sono
  normalizzati come `UTC+01:00` (ora solare), le prese come lettere A-N. Le regioni dei paesi divisi (stati USA,
  province...) e le Canarie prendono capitale e fusi orari dal proprio elemento Wikidata, il resto dal paese; senza
  una capitale propria restano senza capitale. La tabella `CORREZIONI` dello script corregge gli errori noti di
  Wikidata; lo script avvisa delle regioni senza elemento. Il TSV è un file curato: si rigenera a mano, non nel job
  delle guide, e si rivede il diff prima del commit. `GenerateCountryFacts.kt` lo usa per i Fatti rapidi di
  `guides.db` e `guides-en.db`. I campi della pagina Wikivoyage italiana hanno la precedenza: lingua, elettricità,
  fuso orario e valuta nella guida italiana; lingua, elettricità e fuso orario nella guida inglese, quando si
  possono tradurre. Wikidata aggiunge capitale, prefisso, lato di guida e i campi mancanti. I numeri di emergenza
  restano quelli di `emergency-numbers.tsv`.
- **Storia e clima delle città**: `scripts/city_wikipedia.py`, usato dagli stessi due script, trova la voce di
  Wikipedia (IT o EN) della città dal sitelink dell'elemento Wikidata della pagina di Wikivoyage e ne prende le sezioni
  Storia e Clima (History e Climate in inglese) dall'API di Wikipedia, a lotti: i dump completi (IT ~5 GB, EN ~24 GB)
  non stanno nei runner. `GenerateCities.kt` le pulisce come le guide e le scrive in `cities.db` come categorie
  `STORIA` e `CLIMA`, con la voce come `sourceUrl`; la Storia si ferma all'ultimo paragrafo entro 4.000 caratteri.
  Le versioni dell'app che non conoscono le due categorie le saltano all'import. Mai fatale: un errore di rete lascia
  le città senza le due sezioni. Il tempo è limitato a 20 minuti per shard e lingua (`MAX_SECONDS`); l'Italia in
  italiano, ~2.700 città, ne richiede circa 4, in inglese circa uno.
- **Sezioni di Wikipedia delle guide**: `scripts/country_wikipedia.py`, lanciato da `build-guides.sh` per ogni lingua,
  trova allo stesso modo la voce di Wikipedia di ogni regione (dalla pagina di Wikivoyage EN di `regions.sh`) e ne
  prende le sezioni Gastronomia o Cucina, Tradizioni e Festività, Media o Mezzi di comunicazione, Telecomunicazioni o
  Comunicazioni (in inglese Cuisine, Traditions, Holidays e Festivals, Media, Telecommunications o Communications).
  `generateGuides --wikipedia` (`CountryWikipedia.kt`) le pulisce come le guide e le aggiunge in fondo alla guida, in
  ogni caso, nelle categorie `CIBO_BEVANDE`, `USI_COSTUMI`, `VITA_QUOTIDIANA` e `CONNETTIVITA`, con la voce come
  `sourceUrl`: le app che già mostrano le fonti di Wikipedia le vedono senza aggiornamento. Una sezione che pulita
  resta sotto i 200 caratteri (di solito il solo rimando alla voce a parte) si scarta, una lunga si ferma all'ultimo
  paragrafo entro 4.000 caratteri. Mai fatale: le regioni la cui voce non si legge tengono le sezioni di Wikipedia
  del `guides.db` pubblicato. Con le voci di ottobre 2026 escono circa 250 sezioni di 160 regioni in italiano e 390 di 250
  regioni in inglese.
- **Misura della scelta del contesto**: `scripts/eval_retrieval.py` replica in Python la scelta del contesto di
  `TravelAssistant.kt` e `FtsRanking.kt` (BM25, città nominata nella domanda, paragrafi del contesto) e la misura sulle
  guide pubblicate di alcune regioni, con Storia e Clima da Wikipedia se i `cities.db` pubblicati non li hanno ancora,
  e domande costruite da schemi di domanda fissi (pratiche sulle città, sul paese, storia e clima). Stampa quante volte la sezione
  attesa entra nel contesto e quante volte la risposta ci sta dentro. Va aggiornato insieme alla scelta del contesto
  dell'app; `--lang en` misura le guide e le domande inglesi, `--no-wikipedia` misura senza le due sezioni,
  `--questions reworded` usa le stesse richieste con altre parole e `--questions other-lang` le domande nell'altra
  lingua (stesse città nei tre casi), `--questions general` le domande generali sulle pagine della guida generale,
  `--questions control` le domande di controllo (mai usate per costruire le liste di parole), `--questions dev` e `dev2`
  i controlli di prima, diventati serie di sviluppo, `--questions other-country` le domande che nominano un'altra nazione (cercate nella sua guida, `CountryGuides.kt`),
  `--guides-db` un `guides.db` locale al posto di quello pubblicato, `--word-lists` la ricerca con le liste di parole
  che l'app usa (`CategoryWords.kt`; senza l'opzione si misura la ricerca senza liste), `--model-categories` le categorie
  che il modello sceglie quando le liste non ne riconoscono nessuna (`ModelQuestionCategory.kt`), scritte da
  `scripts/model_categories.py` con un GGUF e lo stesso prompt dell'app. La tabella delle guide contiene tutte le regioni,
  come nell'app, perché le statistiche di BM25 valgono per tutta la tabella. Anche `make_context`
  (`generate_sft_dataset.py`), che costruisce il contesto dei dataset SFT e dei set di valutazione, sceglie i paragrafi
  come l'app (`test_make_context.py` lo confronta con la replica di `eval_retrieval.py`).
- **Guide**: la sottosezione "Costo della vita" ("Cost of living" in inglese) viene omessa da guide e città. Le guide
  delle nazioni hanno anche "Cosa vedere" e "Cosa fare" ("See" e "Do"), come quelle delle città, categoria `COSA_VEDERE`. I fatti
  rapidi delle guide inglesi comprendono anche la lingua e i numeri di emergenza, come quelli italiani.
- **Guida generale**: `build-guides.sh` scarica anche le pagine tematiche di Wikivoyage di `GENERAL_GUIDE_PAGES` (salute,
  jet lag, mal di montagna, assicurazione, documenti, visti, borseggiatori, truffe, pagamenti, mance solo in inglese,
  telefono) e `generateGuides --general-guide` le scrive in `guide_sections` con regionId `_general` (`GenerateGeneralGuide.kt`):
  una sezione per titolo "==", intitolata "<pagina>: <titolo>", con la categoria della pagina e il suo link. Senza la
  pagina italiana la guida italiana la salta; una pagina non scaricata riprende le sezioni pubblicate. L'assistente
  le cerca insieme alla regione aperta; le versioni precedenti dell'app le importano senza cercarle. Pesano circa il 5%
  di `guides.db` compresso. Non entrano nei dataset SFT: `load_guide_sections` di `generate_sft.py` le esclude finché
  non esce l'app che le cerca.
- **Consigli di viaggio del Governo del Canada (guide inglesi)**: `build-guides.sh en` scarica per ogni paese (`flagCode`
  di `regions.sh`) il JSON open data `data.international.gc.ca/travel-voyage/cta-cap-<iso2>.json` (Open Government
  Licence - Canada 2.0) e `GenerateTravelAdvice.kt` ne fa quattro sezioni della guida, con l'url della pagina del paese
  su travel.gc.ca e la data dell'ultimo aggiornamento: sicurezza (con il livello di rischio e gli avvisi regionali in
  testa), leggi e cultura, catastrofi naturali e clima, salute. Restano fuori ingresso e uscita, uffici consolari, i
  sottotitoli per i canadesi o sui vaccini (`excludedAdviceHeadings`) e le frasi che nominano il Canada o in cui parla il
  governo ("we", "our"). La salute è compatta: il 73% del suo testo è uguale in almeno un paese su quattro, quindi
  restano gli avvisi di epidemia, le strutture sanitarie e le medicine, e dei blocchi sulle malattie il nome e la prima
  frase ("There is a risk of typhoid fever in this destination, but the risk is low for most travellers."); fuori la
  prevenzione generica (`genericHealthHeadings`). Palestina, Pitcairn e Wallis e Futuna, senza pagina su travel.gc.ca,
  prendono le stesse quattro sezioni dalla pagina FCDO della GOV.UK Content API
  (`www.gov.uk/api/content/foreign-travel-advice/<slug>`, `FCDO_BASE_CODES` in `build-guides.sh`, Open Government Licence
  v3.0): Safety and security divisa per sottotitolo, Regional risks, Health, il livello di `alert_status` e le aree da
  evitare di Warnings and insurance, senza assicurazioni e senza le frasi sui britannici, il Regno Unito o l'FCDO. Delle
  frasi in cui parla il governo ("we", "our", tolte per entrambe le fonti) le poche utili diventano impersonali (`impersonalRewrites`: "where we advise against" -> "where the advice is against"). La pagina
  della Palestina copre "Israel and Palestine": i sottotitoli solo israeliani (`fcdoSharedCountries`) vanno in una
  sezione col link che finisce in `#for-nationality=IL`, che l'app mostra solo con la nazionalità israeliana; per gli
  altri c'è un rimando alla guida di Israele (`#not-for-nationality=IL`). L'app toglie il suffisso dai link
  (`GuideSectionAudience.kt`). Per tutti i paesi, fuori dalle sezioni per tutti, due sezioni per i soli britannici
  (`fcdoBritishSections`: Entry requirements e Getting help della pagina FCDO, per intero, link con
  `#for-nationality=GB`) e due per i soli canadesi (`canadianSections`: `entry-exit` e gli uffici di `offices` con il
  testo sull'assistenza consolare di `offices-html`, link con `#for-nationality=CA`). Il testo canadese nomina il centro
  di emergenza di Ottawa senza numeri: telefono dall'estero, SMS ed email si leggono a ogni run dalla pagina
  `travel.gc.ca/assistance/emergency-assistance` (`--emergency-centre` di `generateGuides`, `emergencyCentreContacts`), e
  se la pagina non si scarica o cambia forma restano quelli della sezione pubblicata. L'avviso generico all'inizio di
  `entry-exit` ("Some countries have imposed measures in response to the Ebola disease outbreak") resta solo in Africa
  (`geo-group`) e dove la salute della pagina nomina l'Ebola; i paragrafi scritti per un paese restano sempre. La
  pagina FCDO di ogni nazione si scarica con lo slug di `fcdo-slugs.tsv` (codice ISO, slug; mancano Regno Unito, Vaticano, Fær Øer, Groenlandia,
  Svalbard, Norfolk, Isola di Natale, Cocos, Canarie e i territori statunitensi): `build-guides.sh` scrive nel TSV dei
  consigli la colonna del JSON di travel.gc.ca e quella dell'FCDO, e una colonna vuota tiene le sezioni pubblicate di
  quella fonte. Sui dati del 2026-10-17: 680 sezioni britanniche e 668 canadesi, `guides-en.db` da 23,2 a 30,0 MB e da
  4,46 a 4,73 MB compresso (+0,27 MB). Un paese non
  scaricato tiene i consigli del `guides-en.db` pubblicato. Sui dati di set–ott 2026: 336 regioni, +5,8 MB di
  `guides-en.db` e +0,4 MB compresso. Il testo cambia per quasi tutti i paesi ogni due
  settimane e il job `guides` gira ogni giorno: per regione la tabella `travel_advice_meta` (livello di rischio, avvisi
  regionali, data di download; le app la ignorano) e `keepPublishedGuides` tiene il `guides-en.db` pubblicato quando sono
  cambiati solo i consigli, finché sono stati scaricati da meno di 7 giorni (`TRAVEL_ADVICE_MAX_AGE_DAYS`) e nessun paese
  ha cambiato livello di rischio o avvisi regionali; un cambio di rischio pubblica subito e lascia un `::warning::` nel
  job, un cambio di Wikivoyage pubblica subito con i consigli freschi.
- **Dataset SFT**: `scripts/generate_sft.py --lang it|en` genera il dataset dei due modelli linguistici con le
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
  in stile utente invece di uno schema di domanda: `scripts/travel_questions.py` prende le prime domande delle conversazioni di
  `soniawmeyer/travel-conversations-finetuning` della sola parte UltraChat (MIT; fuori Reddit e Dolly), tiene quelle
  senza nomi di luogo che toccano una sola categoria e le traduce in italiano con MarianMT per il dataset italiano;
  senza rete restano gli schemi di domanda. Di default (`--city-daily-life`) le città hanno anche la sezione "Informazioni utili" /
  "Cope" (Vita quotidiana, come in `cities.db` dell'app), con in più le città oltre `--cities` che la hanno (solo quella
  domanda, nessun rifiuto): nel v9 la categoria aveva solo le sezioni dei paesi e quasi tanti rifiuti quanti positivi. Le
  sue parole chiave in più (uffici turistici, farmacie, consolati) valgono anche per i paesi, e i rifiuti delle città
  hanno nel contesto solo sezioni che non trattano la categoria. Di
  default (`--balanced-negatives`) la categoria di un rifiuto si sceglie in proporzione ai positivi della categoria
  (paesi) o alle sezioni che la trattano (città), invece che con la stessa probabilità per tutte. Di default
  (`--clear-questions`) restano fuori le domande che una sezione di un'altra categoria soddisfa altrettanto ("Quali
  informazioni pratiche mi servono…") e le domande sui vaccini tra quelle sulla salute: nell'app portano nel contesto il
  riassunto delle vaccinazioni, quindi sono domande di Vaccinazioni. Con `--guides-db` e `--guide-sections` (default)
  entrano anche domande sulle sezioni della guida generale (`_general`: salute, documenti, visti, truffe, denaro,
  telefono). Le domande sui punti vicini usano anche sinonimi e bisogni ("Dove compro delle medicine qui vicino?"), non
  solo la parola dell'etichetta del blocco; i rifiuti delle città hanno a volte nel contesto la sezione di una categoria
  vicina che non risponde (Come arrivare per gli spostamenti in città, Dove dormire per Dove mangiare). Di default
  (`--external-questions`) entrano rifiuti sulle domande di servizio degli alloggi di WebFAQ (CC BY 4.0: check-in,
  animali, parcheggio dell'hotel, che le guide non coprono) e domande fuori tema di MKQA (CC BY 3.0) nelle due lingue,
  sempre solo le domande (`scripts/external_questions.py`, cache in `data/sft/raw/`). Con `--retrieval-rows <n>` e
  `--retrieval-cache <cartella>` aggiunge circa n righe con il contesto che la ricerca dell'app sceglie davvero
  (`scripts/sft_retrieval.py`, che usa `eval_retrieval.py` sui `guides.db` e `cities.db` pubblicati), su città fuori
  dalle regioni di test e da quelle misurate da `eval_retrieval.py`. Con `--retrieval-cache` il generatore toglie
  anche le righe con una domanda quasi uguale a una delle serie di `eval_retrieval.py` sulla stessa regione o città
  (motivo `serie-di-misura` nell'elenco delle esclusioni), così il confronto dei modelli su quelle serie dopo il
  training resta pulito. Toglie sempre le risposte che cominciano con un rimando ("Tuttavia", "Questo", "They",
  "However"…) a una frase rimasta fuori dallo stesso paragrafo (motivo `risposta-con-rimando`): da sole non stanno in
  piedi. Le note di `ATTRIBUTION` sono nella lingua del dataset. I file di output sono
  `pocket_travel_sft.<versione>.<lang>.jsonl` e `ATTRIBUTION.<versione>.<lang>.tsv`: la versione è v11 con
  `--nearby`, `--distances`, `--cities-db`, `--emergency`, le domande in stile utente, `--city-daily-life`,
  `--balanced-negatives` o `--clear-questions` (i default), v9 senza nessuna di queste,
  oppure quella data con `--version`; un v9 che esiste già (lo usano i training) si sovrascrive solo con
  `--version v9`. Le fonti e la pulizia sono cambiate dopo il v9 generato: gli stessi argomenti non ridanno quel file. I test estesi
  (`generate_eval_set.py`, `generate_eval_set_en.py`) hanno in fondo le righe `pos_near`/`neg_near` e
  `pos_dep`/`neg_dep` sugli stessi blocchi, con domande, nomi e seme diversi da quelli del training, e con
  `--cities-db` (solo le regioni di test) `pos_wiki`/`neg_wiki` su storia e clima; le righe
  precedenti restano identiche. In coda, con `"set": "v11"` e semi propri, altre righe per i gruppi piccoli: 100
  `neg_city` con domande nuove e casi difficili, 106 `pos_near` e 30 `neg_near` con sinonimi che il training non ha, e
  nel test inglese righe con la domanda in italiano (`"question_lang": "it"`). I contesti dei rifiuti delle città non
  hanno sezioni che trattano la categoria chiesta, come nel training. `scripts/translate_dataset.py` traduce
  con MarianMT (`opus-mt-tc-big`, CC BY 4.0) le sezioni assenti o molto più brevi in una lingua; scarta le
  traduzioni con numeri diversi dall'originale, toglie le parentesi rimaste vuote (MarianMT perde i caratteri di altri
  alfabeti, come i nomi cinesi o arabi tra parentesi) e conserva le frasi tradotte in una cache. Le tabelle per lingua
  stanno in `generate_sft_dataset.py` e `generate_sft_dataset_en.py`; il primo cerca anche i titoli mancanti di
  `sft-sources.tsv` e riempie la cache `data/sft/raw/` usata dai test estesi.
  Ogni generatore scrive anche `EXCLUDED[.<versione>].<lang>.tsv`: per regione, fonte e categoria, cosa è rimasto
  fuori e perché (regione di test, pagina assente, pagina senza sezioni utili, markup residuo, sezione che non tratta la
  sua categoria, nessuna risposta estratta); il riepilogo per fonte e motivo si stampa a fine generazione.
  `scripts/audit_sft.py <dataset.jsonl> [--eval <test esteso>] [--out <pulito.jsonl>] [--strict] [--retrieval-cache <cartella>]`
  controlla un dataset prima del training: righe malformate, contesti o risposte oltre i limiti, risposte positive con
  frasi che non sono nel contesto, markup residuo, sottoregioni delle regioni di test, duplicati, righe che ripetono il
  test esteso e, con `--retrieval-cache` (i dati scaricati da `eval_retrieval.py`), domande quasi uguali a quelle delle
  sue serie; stampa anche quota di rifiuti, righe per categoria e regione e domande più ripetute. Con `--out` scrive una copia senza le
  righe con errori e senza i duplicati.
  Nelle risposte estratte dalle guide, frasi di righe diverse (voci di elenco, sottosezioni) restano su righe diverse.
  `scripts/generate-sft.sh [--new | --v12 | --old] --data <cartella>` fa tutto in sequenza per le due lingue: dataset,
  test estesi e audit `--strict`, dal dump più recente in `<cartella>/dumps/` e dai `guides*.db`, `cities*.db` e
  riassunti delle vaccinazioni pubblicati nella stessa cartella (lo schema è nell'intestazione dello script). `--new` (il
  default) è la ricetta v11; `--v12` aggiunge le sezioni di Wikipedia delle guide (`--guide-wikipedia`); `--old` è
  quella del v9, scritta come `v9-rigenerato` per non sostituire il v9 dei training.
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
- **Guide arricchite dall'altra lingua**: `scripts/translate_guides.py` traduce con MarianMT (`opus-mt-tc-big-en-it`,
  CC BY 4.0, CTranslate2 int8 su CPU) le sezioni del Governo del Canada della guida inglese che pubblica la passata
  `en` (`GUIDES_FINAL_COPY`, poi `GUIDES_SOURCE_DB` della passata italiana), senza quelle per i soli canadesi
  (`#for-nationality=CA`: requisiti d'ingresso, indirizzi e numeri restano nella guida inglese); `generateGuides
  --translated` le aggiunge in fondo alla guida italiana, con titoli fissi e `translated = 1`, che le app vecchie ignorano. Le frasi inglesi più
  frequenti hanno una traduzione rivista in `translation-corrections.en-it.tsv` (una riga "inglese<TAB>italiano"), che
  prevale sul modello e non passa dal controllo automatico; le altre frasi con numeri diversi, lunghezza fuori misura o
  un carattere perso (" ⁇ ") fanno scartare la loro sezione. Il confronto delle sezioni di Wikivoyage con quelle
  dell'altra lingua (assenti, sotto 300 caratteri o lunghe meno della metà), dall'inglese all'italiano e dall'italiano
  all'inglese, guide e città, resta nel codice ma spento (`WIKIVOYAGE_SECTIONS`): in una revisione a campione il 21%
  delle frasi tradotte aveva errori critici o gravi, contro il 5,8% di quelle del Governo del Canada. In publish-regions
  i passi girano solo con la variabile del repository `TRANSLATE_GUIDES` uguale a `true`: con la variabile spenta le guide escono
  senza sezioni tradotte. Mai bloccante: se CTranslate2 o il modello mancano, il tempo finisce o una categoria viene scartata,
  restano i consigli tradotti già pubblicati (nessuno, la prima volta); la cache delle frasi tradotte tiene il lavoro
  fatto. Spegnendo di nuovo la variabile i consigli tradotti già pubblicati restano, senza aggiornamenti.
