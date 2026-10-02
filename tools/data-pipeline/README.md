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
