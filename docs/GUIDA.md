# Guida rapida per chi sviluppa

Le cose da sapere per lavorare su Pocket Travel. Il **perché** delle scelte tecniche è nella [wiki](https://github.com/miracle091/pocket-travel/wiki).

## Cosa serve

- Android Studio con JDK 17 o successivo (Gradle è già incluso nel repository).
- Per la pipeline dei dati: bash, `jq`, `xz`, Python 3 (per i civici di Overture anche il pacchetto `duckdb`).
- Per addestrare i modelli dell'assistente: una GPU (AMD con ROCm o NVIDIA con CUDA) e PyTorch, meglio con Unsloth (senza, si usa peft). Si lancia `tools/data-pipeline/scripts/train_auto.py`, che sceglie da solo GPU e backend; su Linux/WSL con NVIDIA c'è anche `run_train_nvidia.sh`. Su WSL il desktop di Windows occupa parte della memoria della GPU: si aggiunge `--max-vram-held 25`. Il dataset di addestramento (v10: di default con una parte di domande di viaggio reali, più `--nearby` per gli esempi sui punti di interesse vicini e sulle partenze, `--distances` per le domande sulla distanza tra due città, `--cities-db` per storia e clima delle città, `--emergency` per i numeri di emergenza; in italiano o in inglese) si genera con `generate_sft.py --lang it|en`; le sezioni mancanti in una lingua si traducono dall'altra con `translate_dataset.py` (MarianMT, anche su GPU).

## Compilare e provare

```bash
./gradlew :app:installDebug          # installa sul telefono o sull'emulatore
```

Prima di ogni commit, lancia gli stessi controlli della CI (`.github/workflows/android-ci.yml`):

```bash
./gradlew assembleDebug lint detekt testDebugUnitTest :core:poi:test :tools:data-pipeline:content:test --continue
for t in tools/data-pipeline/scripts/test_*.py; do python "$t" || break; done
```

detekt segnala solo i problemi nuovi: quelli già presenti stanno nel `detekt-baseline.xml` di ogni modulo. Rigenera il baseline di un modulo (`./gradlew :feature:map:detektBaseline`) solo quando sposti codice che contiene già un problema registrato, non per far passare codice nuovo.

I test di interfaccia (`src/androidTest`, con `createComposeRule`) girano sull'emulatore: installa l'APK dei test del modulo e lancia la classe, per esempio:

```bash
./gradlew :feature:guide:installDebugAndroidTest --no-parallel
adb shell am instrument -w -e class com.pockettravel.feature.guide.GuideContentDeviceTest com.pockettravel.feature.guide.test/androidx.test.runner.AndroidJUnitRunner
```

`--no-parallel` evita che più APK di test, compilati insieme, esauriscano la memoria di Gradle. In CI il job `device-tests` di `android-ci.yml` li lancia tutti su un emulatore (API 34, x86_64), in parallelo alla build; i test che chiedono il modello GGUF o i segmenti `.rd5` si saltano da soli.

I test sul dispositivo (migrazioni del database, installazione dei pacchetti) girano con un emulatore acceso:

```bash
./gradlew :core:data:connectedDebugAndroidTest :core:sync:connectedDebugAndroidTest
```

Alcuni test dei percorsi usano segmenti BRouter veri, troppo grandi per il repository: senza, vengono saltati. Per farli girare scarica da [brouter.de](https://brouter.de/brouter/segments4/) i file e indica le cartelle con due variabili d'ambiente:

- `RD5_CLIP_TEST_DIR`: una cartella con `orig/E10_N40.rd5` (il file di brouter.de) e `clipped/E10_N40.rd5` (lo stesso ritagliato su San Marino con `tools/data-pipeline/scripts/clip_rd5.py --bbox=12.40,43.89,12.52,43.99 --margin 0.1`);
- `RD5_UK_TEST_DIR`: una cartella con `W5_N50.rd5` (rotonde con guida a sinistra, Milton Keynes).

Tempo e memoria del calcolo sul telefono li misura `RouteEngineBenchmarkDeviceTest` (commento in testa al file per come copiare i segmenti sul dispositivo).

La libreria nativa dell'assistente (llama.cpp) la prova `LlamaEngineDeviceTest` con un modello GGUF vero (Qwen3.5 0.8B, ~530 MB): senza il file i test vengono saltati. Copialo come spiega il commento in testa al file, poi usa `installDebugAndroidTest` e `am instrument` invece di `connectedDebugAndroidTest`, che disinstalla l'app di test e cancella il modello:

```bash
./gradlew :feature:ai:installDebugAndroidTest
adb shell am instrument -w -e class com.pockettravel.feature.ai.LlamaEngineDeviceTest com.pockettravel.feature.ai.test/androidx.test.runner.AndroidJUnitRunner
```

Sull'emulatore (x86_64) i 4 test durano circa 9 minuti.

Le conversazioni a più turni (chiarimento, cambio di destinazione, domanda interrotta, navigazione prima di una domanda, prompt oltre il contesto) le prova `MultiTurnDeviceTest`, con il primo modello italiano del catalogo copiato nella stessa cartella (per esempio `qwen3.5-0.8b-travel-it-Q4_K_M.gguf`). Stessi comandi, con la classe `com.pockettravel.feature.ai.MultiTurnDeviceTest`; le risposte si leggono con `adb logcat -s MultiTurn`. Sull'emulatore durano meno di un minuto.

### Misurare l'assistente su un telefono, senza adb

Nelle build di debug la scheda IA, con il motore sul dispositivo, ha nel menu (⋯) il **Task manager dei modelli**. Mostra memoria, CPU e, per l'ultima risposta, il tempo al primo token, la velocità di generazione e la velocità di lettura del prompt (token del prompt diviso il tempo al primo token). "Esporta i log" salva un file di testo con le stesse misure in CSV e il logcat dell'app, da condividere senza collegare il telefono al computer.

La sezione **Variante CPU di llama.cpp** serve a confrontare le varianti della libreria su telefoni diversi. llama.cpp è compilato in più varianti, una per famiglia di CPU (`libggml-cpu-*.so`: per arm64 da `android_armv8.0_1` a `android_armv9.2_2`). All'avvio ggml carica quella con il punteggio più alto per le estensioni della CPU, che non è sempre la più veloce: sui core ARMv9 con vettori SVE a 128 bit la variante SVE2 può leggere il prompt fino a due volte più lentamente di `android_armv8.6_1` (llama.cpp, issue #29884). Per questo, sulle CPU con vettori SVE a 128 bit e senza SME, l'app prova prima `android_armv8.6_1`, poi `android_armv8.2_2` e `android_armv8.2_1`, e lascia la scelta a ggml solo se la CPU le rifiuta tutte. La sezione mostra:

- le estensioni della CPU che decidono la scelta (SVE2, SME, lunghezza dei vettori SVE);
- la variante caricata, scelta in automatico o a mano;
- l'elenco delle varianti presenti nell'APK installato, da usare al prossimo avvio. "Riavvia ora" riavvia l'app, perché ggml carica la variante solo all'avvio del processo.

Una variante che la CPU non supporta viene rifiutata da ggml: l'app carica quella automatica e lo scrive accanto alla variante caricata. Per confrontare due varianti fai la stessa domanda, in una conversazione nuova, almeno tre volte con ciascuna, con il telefono nelle stesse condizioni (schermo acceso, risparmio energetico spento, una pausa tra una variante e l'altra perché non si scaldi), poi esporta i log. L'intestazione del file riporta la variante (riga `cpu_backend`), la sezione `generations` le velocità di ogni risposta (`tokens_per_s` e `prompt_tokens_per_s`). Le build di release ignorano la scelta e usano sempre quella automatica.

## Provare con dati tuoi

Nelle build di debug l'app può leggere un catalogo di debug invece di quello ufficiale (le firme non si controllano):

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
| il database | `core/data/` (ogni cambio di schema vuole una migrazione e il suo test in `RegionDatabaseMigrationTest`: una migrazione per ogni versione del database uscita in una release, i passi successivi uniti in una sola al rilascio, l'ultima separata finché non esce) |
| cosa finisce nei pacchetti | `tools/data-pipeline/scripts/` e `tools/data-pipeline/content/` |
| l'elenco delle regioni | `tools/data-pipeline/scripts/regions.sh`, poi `generate-weekly-schedule.sh` |
| i numeri civici (celle da ~10 MB) | `tools/data-pipeline/scripts/build-address-cell.sh` e il seme `tools/data-pipeline/address-grid-seed.tsv` |
| le fonti Overture dei civici | `tools/data-pipeline/overture-address-sources.tsv` (una fonte nuova resta fuori finché non la si rivede) |
| i modelli dell'assistente | `feature/ai/.../LlmModelCatalog.kt` (ogni modello addestrato ha la sua lingua) e gli script `train_*.py` / `eval_*.py`; un GGUF ricaricato con lo stesso nome si pubblica con `upload_hf.py --push --update-app-status`, che aggiorna le impronte in `app-status.json` senza rilasciare l'app. Nelle build di debug il menu ⋮ della scheda IA ha il benchmark e il task manager dei modelli (memoria, CPU, contesto, velocità, log esportabili) |
| percorsi e navigazione | `feature/map/.../BRouterRouteEngine.kt`, `NavigationScreen.kt`, `NavigationTracker.kt`; le aggiunte a BRouter sono segnate "Pocket Travel" in `third-party/brouter-core` |
| i testi dell'app | `res/values/strings.xml` (italiano) e `res/values-en/strings.xml` (inglese) di ogni modulo |
| le vaccinazioni | dati in `tools/data-pipeline/content/src/main/resources/vaccinations/` (TSV curati, bozza con `vaccinations_draft.py`), regole in `core/data/.../vaccination/`, schermata in `feature/guide/.../VaccinationScreen.kt` |
| popolazione, capitale e coordinate delle città | `tools/data-pipeline/scripts/city_population.py`, usato da `extract-cities-dump.py` e `extract-cities-dump-en.py` |
| il dataset di addestramento | `tools/data-pipeline/scripts/generate_sft.py` e `translate_dataset.py` (le tabelle per lingua stanno in `generate_sft_dataset.py` e `generate_sft_dataset_en.py`) |
| le guide e le città in inglese | `build-guides.sh … en`, `build-cities.sh … en`, `extract-cities-dump-en.py`; nell'app `core/sync/.../GuidesChoice.kt` |

## Rilasciare una versione

1. Aggiornare `versionCode` e `versionName` in `app/build.gradle.kts`.
2. Nel `CHANGELOG.md` spostare le voci di "Non rilasciato" sotto `## [X.Y.Z] - AAAA-MM-GG` (data del rilascio), lasciare in cima un "Non rilasciato" vuoto e aggiornare i link di confronto in fondo al file.
3. Se i nuovi dati richiedono questa versione dell'app, aggiornare `tools/data-pipeline/min-app-version-code`.
4. Fare il push su `main`: `publish-apk.yml` firma l'APK e crea la release. La pipeline dei dati pubblicherà i dati nuovi solo dopo.

## Regole della casa

- Ogni modulo `feature` dipende solo dai moduli `core`.
- Testi dell'app in italiano e in inglese: ogni testo nuovo va in `values/strings.xml` **e** in `values-en/strings.xml` (lint segnala le traduzioni mancanti).
- Il prompt italiano dell'assistente (`PromptTemplates`) è quello dei dati di addestramento: non va cambiato senza riaddestrare i modelli.
- Ogni modifica visibile va nel `CHANGELOG.md`.
- Niente percorsi del proprio computer (`C:\...`, `/home/...`) nei file del repository né nei messaggi di commit.
