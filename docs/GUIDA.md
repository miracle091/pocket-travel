# Guida rapida per chi sviluppa

Le cose da sapere per lavorare su Pocket Travel. Il **perché** delle scelte tecniche è nella [wiki](https://github.com/miracle091/pocket-travel/wiki).

## Cosa serve

- Android Studio con JDK 17 o successivo (Gradle è già incluso nel repository).
- Per la pipeline dei dati: bash, `jq`, `xz`, Python 3 (per i civici di Overture anche il pacchetto `duckdb`).
- Per addestrare i modelli dell'assistente: una GPU (AMD con ROCm o NVIDIA) e Unsloth.

## Compilare e provare

```bash
./gradlew :app:installDebug          # installa sul telefono o sull'emulatore
```

Prima di ogni commit, lancia gli stessi controlli della CI (`.github/workflows/android-ci.yml`):

```bash
./gradlew assembleDebug lint testDebugUnitTest :tools:data-pipeline:content:test --continue
python tools/data-pipeline/scripts/test_clip_rd5.py
```

I test sul dispositivo (migrazioni del database, installazione dei pacchetti) girano con un emulatore acceso:

```bash
./gradlew :core:data:connectedDebugAndroidTest :core:sync:connectedDebugAndroidTest
```

Alcuni test dei percorsi usano segmenti BRouter veri, troppo grandi per il repository: senza, vengono saltati. Per farli girare scarica da [brouter.de](https://brouter.de/brouter/segments4/) i file e indica le cartelle con due variabili d'ambiente:

- `RD5_CLIP_TEST_DIR`: una cartella con `orig/E10_N40.rd5` (il file di brouter.de) e `clipped/E10_N40.rd5` (lo stesso ritagliato su San Marino con `tools/data-pipeline/scripts/clip_rd5.py --bbox=12.40,43.89,12.52,43.99 --margin 0.1`);
- `RD5_UK_TEST_DIR`: una cartella con `W5_N50.rd5` (rotonde con guida a sinistra, Milton Keynes).

Tempo e memoria del calcolo sul telefono li misura `RouteEngineBenchmarkDeviceTest` (commento in testa al file per come copiare i segmenti sul dispositivo).

## Provare con dati tuoi

Nelle build di debug l'app può leggere un catalogo locale invece di quello pubblicato:

```bash
python -m http.server 8000 --directory <cartella con manifest.json>
./gradlew :app:installDebug -PpocketTravel.manifestUrl=http://10.0.2.2:8000/manifest.json
```

`10.0.2.2` è il PC visto dall'emulatore.

Per provare l'app in un'altra lingua senza cambiare quella del telefono:

```bash
adb shell cmd locale set-app-locales com.pockettravel.app --locales en   # o it
```

## Dove mettere le mani

| Voglio cambiare… | Guarda in |
|---|---|
| una schermata | `app/` o il modulo `feature/` corrispondente |
| il download o l'installazione dei pacchetti | `core/sync/` |
| il database | `core/data/` (ogni cambio di schema vuole una migrazione e il suo test) |
| cosa finisce nei pacchetti | `tools/data-pipeline/scripts/` e `tools/data-pipeline/content/` |
| l'elenco delle regioni | `tools/data-pipeline/scripts/pilot-regions.sh`, poi `generate-weekly-schedule.sh` |
| i numeri civici (celle da ~10 MB) | `tools/data-pipeline/scripts/build-address-cell.sh` e il seme `tools/data-pipeline/address-grid-seed.tsv` |
| le fonti Overture dei civici | `tools/data-pipeline/overture-address-sources.tsv` (una fonte nuova resta fuori finché non la si rivede) |
| i modelli dell'assistente | `feature/ai/.../LlmModelCatalog.kt` (ogni modello addestrato ha la sua lingua) e gli script `train_*.py` / `eval_*.py` |
| percorsi e navigazione | `feature/map/.../BRouterRouteEngine.kt`, `NavigationScreen.kt`, `NavigationTracker.kt`; le aggiunte a BRouter sono segnate "Pocket Travel" in `third-party/brouter-core` |
| i testi dell'app | `res/values/strings.xml` (italiano) e `res/values-en/strings.xml` (inglese) di ogni modulo |
| le guide e le città in inglese | `build-guides.sh … en`, `build-cities.sh … en`, `extract-cities-dump-en.py`; nell'app `core/sync/.../GuidesChoice.kt` |

## Rilasciare una versione

1. Aggiorna `versionCode` e `versionName` in `app/build.gradle.kts`.
2. Nel `CHANGELOG.md` rinomina "Non rilasciato" con il numero di versione.
3. Se i nuovi dati richiedono questa versione dell'app, aggiorna `tools/data-pipeline/min-app-version-code`.
4. Fai il push su `main`: `publish-apk.yml` firma l'APK e crea la release. La pipeline dei dati pubblicherà i dati nuovi solo dopo.

## Regole della casa

- Ogni modulo `feature` dipende solo dai moduli `core`.
- Testi dell'app in italiano e in inglese: ogni testo nuovo va in `values/strings.xml` **e** in `values-en/strings.xml` (lint segnala le traduzioni mancanti).
- Il prompt italiano dell'assistente (`PromptTemplates`) è quello dei dati di addestramento: non va cambiato senza riaddestrare i modelli.
- Ogni modifica visibile va nel `CHANGELOG.md`.
- Niente percorsi del proprio computer (`C:\...`, `/home/...`) nei file del repository né nei messaggi di commit.
