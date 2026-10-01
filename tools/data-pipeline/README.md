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
