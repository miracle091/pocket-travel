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

## Città, guide e dataset di addestramento

- **Popolazione e capitale delle città**: `scripts/city_population.py` è usato da `extract-cities-dump.py` e
  `extract-cities-dump-en.py`. La popolazione viene dal campo "Abitanti" di Wikivoyage, in alternativa da Wikidata
  (P1082); la capitale della regione da Wikidata (P36). Finiscono nelle colonne `population` e `capital` di
  `cities.db`; senza dati la città resta senza popolazione.
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
- **Dataset SFT v9**: `scripts/generate_sft.py --lang it|en` genera il dataset dei due modelli linguistici con le
  stesse fonti, la stessa composizione e gli stessi tipi di domanda. Con `--vaccinations` aggiunge domande sui
  vaccini, con il riassunto che l'app inserisce nel contesto dell'assistente. `scripts/translate_sections.py` traduce
  con MarianMT (`opus-mt-tc-big`, CC BY 4.0) le sezioni assenti o molto più brevi in una lingua; scarta le
  traduzioni con numeri diversi dall'originale e conserva le frasi tradotte in una cache. Le tabelle per lingua
  stanno in `generate_sft_dataset.py` e `generate_sft_dataset_en.py`, che servono ancora per rigenerare il dataset v8.
