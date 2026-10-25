# Guida per sviluppatori

Questa guida raccoglie le informazioni per lavorare su Pocket Travel. Il **perché** delle scelte tecniche è nella [wiki](https://github.com/miracle091/pocket-travel/wiki).

## Cosa serve

- Android Studio con JDK 17 o successivo (Gradle è già incluso nel repository).
- Per la pipeline dei dati: bash, `jq`, `xz`, Python 3 (per i numeri civici di Overture anche il pacchetto `duckdb`).
- Per addestrare i modelli dell'assistente: una GPU (AMD con ROCm o NVIDIA con CUDA) e PyTorch, meglio con Unsloth (senza, si usa peft). Si lancia `tools/data-pipeline/scripts/train_auto.py`, che sceglie da solo GPU e backend. Con ROCm la grafica integrata AMD compare come device 0 e non regge i calcoli (crash 0xC0000005 su Windows): `train_auto.py` sceglie da sé la GPU dedicata, e se `HIP_VISIBLE_DEVICES` è impostata la rispetta. Con NVIDIA, la `--help` di `train_auto.py` riporta l'installazione nel venv e le note per WSL. Su WSL il desktop di Windows occupa parte della memoria della GPU: se supera il 10%, si aggiunge `--max-vram-held 25`. Il dataset di addestramento (v10: di default con una parte di domande in stile utente, più `--nearby` per gli esempi sui punti di interesse vicini e sulle partenze, `--distances` per le domande sulla distanza tra due città, `--cities-db` per storia e clima delle città, `--emergency` per i numeri di emergenza; in italiano o in inglese) si genera con `generate_sft.py --lang it|en`; le sezioni mancanti in una lingua si traducono dall'altra con `translate_dataset.py` (MarianMT, anche su GPU).
- Sulla GPU gira un lavoro alla volta: training, valutazioni e conversioni prendono il lucchetto della GPU (`gpu_lock.py`) e, se è occupato, aspettano che si liberi. Un altro comando che usa la GPU (llama.cpp, uno script di prova) si lancia con `python tools/data-pipeline/scripts/gpu_lock.py run -- <comando>`; `gpu_lock.py status` dice chi la tiene. Il lucchetto è uno solo per PC, anche con WSL: un lavoro lanciato in WSL prende anche quello di Windows (con `powershell.exe`, o con `pwsh.exe` di PowerShell 7 se manca), quindi i lavori di WSL e di Windows sulla stessa GPU si aspettano a vicenda. Gli stessi script scrivono ogni minuto nel log una riga `[hw]` con temperature (anche punto più caldo, memoria e VRM della GPU), consumi, ventole, VRAM e RAM, e un avviso `[hw] ATTENZIONE` quando la temperatura della GPU o dei suoi VRM è troppo alta, il driver rallenta la GPU per la temperatura, una ventola della GPU è ferma mentre le altre girano, la VRAM trabocca nella RAM o due processi occupano ciascuno più di 1 GB di VRAM (un lavoro partito senza lucchetto); i campioni ogni 10 secondi vanno in `~/.cache/pocket-travel/hw/`. `python tools/data-pipeline/scripts/hw_monitor.py` mostra gli stessi valori dal vivo. Le GPU NVIDIA si leggono con `nvidia-smi`, installato con il driver: temperatura, consumo, attività, VRAM, frequenza, ventola in percentuale e rallentamenti per temperatura o consumo. Su Windows, e anche per i lavori lanciati in WSL, [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) con il web server acceso dà la temperatura della CPU (Windows non la dà agli altri programmi), le ventole della CPU e del case, e i valori della GPU che il driver non dà: con le NVIDIA punto più caldo, memoria e giri di ogni ventola, con le AMD i VRM. Indirizzo in `POCKET_TRAVEL_LHM_URL` (default `http://127.0.0.1:8085`), utente e password, se impostati nel server, in `POCKET_TRAVEL_LHM_USER` e `POCKET_TRAVEL_LHM_PASSWORD`. Da WSL il firewall di Windows blocca le connessioni verso Windows: lo script legge LHM con `curl.exe` di Windows, e le tre variabili, se mancano in WSL, le prende da quelle impostate su Windows (dell'utente o di sistema). La riga `[hw]` è compatta, con i gruppi separati da `|`: `GPU 38/49 °C 9 W 2% fan 1207 RPM 30% | VRAM 0,6/6,0 GB | CPU 58 °C fan 722 RPM | case fan 813 RPM | RAM 0,6/11,7 GB`, dove `38/49 °C` sono la temperatura della GPU e quella del suo punto più caldo; memoria e VRM della GPU compaiono come `mem 62 °C` e `VRM 78 °C`, la VRAM traboccata come `+0,3 in RAM`.

## Compilare e provare

```bash
git -c core.longpaths=true submodule update --init --depth 1   # una volta, se il clone non ha i submodule
./gradlew :app:installDebug          # installa sul dispositivo o sull'emulatore
```

Prima di ogni commit, lancia gli stessi controlli della CI (`.github/workflows/android-ci.yml`):

```bash
./gradlew assembleDebug lint detekt testDebugUnitTest :core:poi:test :tools:data-pipeline:content:test --continue
for t in tools/data-pipeline/scripts/test_*.py .github/scripts/test_check_dependencies.py; do python "$t" || break; done
```

La CI controlla anche le vulnerabilità note (OSV) delle dipendenze di app e pipeline, e segnala come nota le versioni più nuove del catalogo delle versioni (`gradle/libs.versions.toml`):

```bash
./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath > app.txt
./gradlew -q :tools:data-pipeline:content:dependencies --configuration runtimeClasspath > pipeline.txt
python .github/scripts/check-dependencies.py app.txt pipeline.txt
```

detekt segnala solo i problemi nuovi: quelli già presenti stanno nel `detekt-baseline.xml` di ogni modulo. La baseline di un modulo si rigenera (`./gradlew :feature:map:detektBaseline`) solo quando si sposta codice che contiene già un problema registrato, non per far passare codice nuovo.

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

Tempo e memoria del calcolo sul dispositivo li misura `RouteEngineBenchmarkDeviceTest` (il commento in testa al file spiega come copiare i segmenti sul dispositivo).

La libreria nativa dell'assistente (llama.cpp) la prova `LlamaEngineDeviceTest` con un modello GGUF vero (Qwen3.5 0.8B, ~530 MB): senza il file i test vengono saltati. Copialo come spiega il commento in testa al file, poi usa `installDebugAndroidTest` e `am instrument` invece di `connectedDebugAndroidTest`, che disinstalla l'app di test e cancella il modello:

```bash
./gradlew :feature:ai:installDebugAndroidTest
adb shell am instrument -w -e class com.pockettravel.feature.ai.LlamaEngineDeviceTest com.pockettravel.feature.ai.test/androidx.test.runner.AndroidJUnitRunner
```

Sull'emulatore (x86_64) i 4 test durano circa 9 minuti.

Le conversazioni a più turni (chiarimento, cambio di destinazione, domanda interrotta, navigazione prima di una domanda, prompt oltre il contesto) le prova `MultiTurnDeviceTest`, con il primo modello italiano del catalogo dei modelli copiato nella stessa cartella (per esempio `qwen3.5-0.8b-travel-it-Q4_K_M.gguf`). Stessi comandi, con la classe `com.pockettravel.feature.ai.MultiTurnDeviceTest`; le risposte si leggono con `adb logcat -s MultiTurn`. Sull'emulatore durano meno di un minuto. Lo stesso test prova anche un chiarimento dopo la ripresa di una conversazione (`chiarimentoDopoUnaRipresa`).

Il formato dei prompt lo prova `ChatTemplateDeviceTest`: per la stessa domanda il testo che riceve il modello deve essere il ChatML del training, con il blocco `<think>` vuoto per i Qwen3.5 v10 e Ufficiali e senza per i modelli addestrati dalla copia solo testo, mentre un modello non ChatML (Gemma 3 270M, `gemma-3-270m-it-Q8_0.gguf` di `ggml-org/gemma-3-270m-it-GGUF`) deve passare dal suo template jinja, anche con un prompt oltre il contesto. I ripieghi per i template che jinja non legge o non formatta li provano quattro copie di Gemma con il template sostituito da `gguf_new_metadata.py` di llama.cpp (`--chat-template-file`; i template sono nei commenti dei test). Ogni test cerca il suo GGUF (nomi in testa al file) nella stessa cartella e si salta se manca. Stessi comandi, con la classe `com.pockettravel.feature.ai.ChatTemplateDeviceTest`; formato e testo si leggono con `adb logcat -s ChatTemplate`.

La conversione del testo tra l'app e il modello la prova `Utf8DeviceTest`, con `qwen3.5-0.8b-travel-it-Q4_K_M.gguf` nella stessa cartella: un testo con emoji, simboli fuori dal piano base, caratteri cinesi, coreani e cirillici e accenti composti deve tornare identico dopo la tokenizzazione, anche un token alla volta come una risposta, senza U+FFFD né caratteri tagliati quando la risposta si ferma a metà di un carattere. Va eseguito con CheckJNI attivo (`adb shell setprop debug.checkjni 1`; l'app di test è debuggable, quindi lo ha già). Stessi comandi, con la classe `com.pockettravel.feature.ai.Utf8DeviceTest`; sull'emulatore dura pochi secondi.

La cronologia cifrata delle conversazioni la misura `ConversationHistoryDeviceTest` in `:core:data`, con 500 conversazioni generate: spazio occupato dal database e tempi di apertura. Gira con `:core:data:connectedDebugAndroidTest` e i risultati si leggono con `adb logcat -s ConversationHistory`. `SecureDeleteDeviceTest`, nella stessa cartella, controlla che i byte di una conversazione cancellata non restino nel file del database.

Il riconoscimento vocale sul dispositivo lo misura `SpeechRecognitionDeviceTest`, con file audio al posto del microfono (da Android 13). In `Android/data/com.pockettravel.feature.ai.test/files/speech/` vanno un WAV PCM 16 bit mono a 16 kHz per frase e `cases.tsv`, con una riga `nome<TAB>lingua<TAB>testo atteso` per frase (lingua `it` o `en`). Con la rete, `scaricaLeLingue` chiede al telefono i pacchetti di italiano e inglese (per l'italiano compare una finestra di sistema da confermare); poi, in modalità aereo, `riconosceLeFrasiSulDispositivo` scrive testo riconosciuto, parole sbagliate e tempo in `adb logcat -s SpeechProbe`. File copiati con `adb push` possono restare illeggibili dal test: in quel caso vanno assegnati all'utente dell'app di test (`chown` da `adb root`).

### Misurare l'assistente su un dispositivo fisico, senza adb

Nelle build di debug la scheda IA, con il motore sul dispositivo, ha nel menu (⋯) il **Task manager dei modelli**. Mostra memoria, CPU e, per l'ultima risposta, il tempo al primo token, la velocità di generazione e la velocità di lettura del prompt (token del prompt diviso per il tempo al primo token). "Esporta i log" salva un file di testo con le stesse misure in CSV e il logcat dell'app, da condividere senza collegare il dispositivo al computer.

La sezione **Variante CPU di llama.cpp** serve a confrontare le varianti della libreria su dispositivi diversi. llama.cpp è compilato in più varianti, una per famiglia di CPU (`libggml-cpu-*.so`: per arm64 da `android_armv8.0_1` a `android_armv9.2_2`). All'avvio ggml carica quella con il punteggio più alto per le estensioni della CPU, che non è sempre la più veloce: sui core ARMv9 con vettori SVE a 128 bit la variante SVE2 può leggere il prompt fino a due volte più lentamente di `android_armv8.6_1` (llama.cpp, issue #29884). Per questo, sulle CPU con vettori SVE a 128 bit e senza SME, l'app prova prima `android_armv8.6_1`, poi `android_armv8.2_2` e `android_armv8.2_1`, e lascia la scelta a ggml solo se la CPU le rifiuta tutte. La sezione mostra:

- le estensioni della CPU che decidono la scelta (SVE2, SME, lunghezza dei vettori SVE);
- la variante caricata, scelta in automatico o a mano;
- l'elenco delle varianti presenti nell'APK installato, da usare al prossimo avvio. "Riavvia ora" riavvia l'app, perché ggml carica la variante solo all'avvio del processo.

Una variante che la CPU non supporta viene rifiutata da ggml: l'app carica quella automatica e lo scrive accanto alla variante caricata. Per confrontare due varianti fai la stessa domanda, in una conversazione nuova, almeno tre volte con ciascuna, con il dispositivo nelle stesse condizioni (schermo acceso, risparmio energetico spento, una pausa tra una variante e l'altra perché non si scaldi), poi esporta i log. L'intestazione del file riporta la variante (riga `cpu_backend`), la sezione `generations` le velocità di ogni risposta (`tokens_per_s` e `prompt_tokens_per_s`). Le build di release ignorano la scelta e usano sempre quella automatica.

## Provare con dati tuoi

Nelle build di debug l'app può leggere un catalogo di debug invece di quello ufficiale (le firme non si controllano):

```bash
python -m http.server 8000 --directory <cartella con manifest.json>
./gradlew :app:installDebug -PpocketTravel.manifestUrl=http://10.0.2.2:8000/manifest.json
```

`10.0.2.2` è il PC visto dall'emulatore.

Per provare il tempo reale dei mezzi pubblici con una rete appena costruita: `build-transit.sh <id> <versione> http://10.0.2.2:8000 <cartella>` su una riga di `transit-feeds.tsv` con `url_rt`, poi nella cartella del catalogo di debug il `transit.json` pubblicato con la voce della rete presa da `transit-entries.jsonl`, e in `manifest.json` la voce `transit` con indirizzo, peso e sha256 del `transit.json` nuovo. Con la rete, il riquadro di una fermata di quella rete mostra ritardi e soppressioni accanto all'orario e "In tempo reale, aggiornato alle …"; in modalità aereo (`adb shell cmd connectivity airplane-mode enable`) le partenze tornano quelle dell'orario. I test del lettore del feed usano i feed registrati in `core/data/src/test/resources/gtfs-rt/`.

Per provare l'app in un'altra lingua senza cambiare quella del dispositivo:

```bash
adb shell cmd locale set-app-locales com.pockettravel.app --locales en   # o it
```

## llama.cpp e BRouter

llama.cpp (`third-party/llama-cpp`) e BRouter (`third-party/brouter`) sono submodule git, ognuno sul commit di una release upstream. Il clone che li scarica è `git -c core.longpaths=true clone --recurse-submodules`. Su Windows alcuni percorsi di llama.cpp superano i 260 caratteri: conviene `git config --global core.longpaths true`, una volta per PC, così anche `git status` nel submodule funziona. Il build chiama `git`, che deve stare nel PATH. Dopo un `git pull` che sposta un submodule su un altro commit, `git submodule update --depth 1` lo allinea; senza, il build compila il commit vecchio.

- I submodule restano uguali all'upstream. Le modifiche del progetto sono patch in `third-party/patches/llama-cpp/` e `third-party/patches/brouter/`, con i percorsi dalla root del submodule e una descrizione prima del diff.
- Il build applica le patch, in ordine di nome, a una copia: il `CMakeLists.txt` di `feature/ai` in `feature/ai/.cache/cxx/llama-cpp-src`, il task `brouterSources` di `core/routing-engine` in `core/routing-engine/build/generated/brouter`. Una patch che non si applica fa fallire il build con il suo nome.
- Il modulo `:core:routing-engine` compila cinque moduli di BRouter (`brouter-codec`, `brouter-core`, `brouter-expressions`, `brouter-mapaccess`, `brouter-util`) e il file del progetto `TurnInstructions.java`.
- Una patch nuova si scrive nel submodule, sopra quelle esistenti: si applicano le patch (`cat third-party/patches/brouter/*.patch | git -C third-party/brouter apply --index`), si modificano i file e si salva solo la differenza nuova (`git -C third-party/brouter diff > third-party/patches/brouter/0004-nome.patch`; per un file nuovo prima `git add -N`), poi si scrive la descrizione in testa. Il build compila la copia, non il submodule: la modifica si prova con la patch salvata. Alla fine il submodule torna pulito con `git -C third-party/brouter reset --hard` e, per i file nuovi, `git -C third-party/brouter clean -fd`.
- Un aggiornamento porta il submodule sul tag della release (`git -C third-party/llama-cpp fetch --depth 1 origin tag <tag>`, poi `git -C third-party/llama-cpp checkout <tag>`). Se il build segnala una patch che non si applica, la patch si rifà sul codice nuovo, oppure si toglie quando l'upstream contiene già la modifica. Il commit registra il nuovo commit del submodule insieme alle patch.

## Dove si trova il codice

| Cosa cambiare | Guarda in |
|---|---|
| una schermata | `app/` o il modulo `feature/` corrispondente |
| il download o l'installazione dei pacchetti | `core/sync/` |
| il database | `core/data/` (ogni cambio di schema vuole una migrazione e il suo test in `RegionDatabaseMigrationTest`: una migrazione per ogni versione del database uscita in una release, i passi successivi uniti in una sola al rilascio, l'ultima separata finché non esce) |
| cosa finisce nei pacchetti | `tools/data-pipeline/scripts/` e `tools/data-pipeline/content/` |
| l'elenco delle regioni | `tools/data-pipeline/scripts/regions.sh`, poi `generate-weekly-schedule.sh` |
| i numeri civici (celle da ~10 MB) | `tools/data-pipeline/scripts/build-address-cell.sh` e il seme `tools/data-pipeline/address-grid-seed.tsv` |
| le fonti Overture dei numeri civici | `tools/data-pipeline/overture-address-sources.tsv` (una fonte nuova resta fuori finché non la si rivede) |
| i modelli dell'assistente | `feature/ai/.../LlmModelCatalog.kt` (ogni modello addestrato ha la sua lingua) e gli script `train_*.py` / `eval_*.py`; un GGUF ricaricato con lo stesso nome si pubblica con `upload_hf.py --push --update-app-status`, che aggiorna le impronte in `app-status.json` senza rilasciare l'app. Nelle build di debug il menu (⋯) della scheda IA ha il benchmark e il task manager dei modelli (memoria, CPU, contesto, velocità, log esportabili) |
| il tempo reale dei mezzi pubblici | colonna `url_rt` di `tools/data-pipeline/transit-feeds.tsv` (solo con la licenza del tempo reale letta sulla pagina dell'ente); lettura e aggancio in `core/data/.../GtfsRealtimeDecoder.kt` e `TransitRealtime.kt`, download in `feature/map/.../TransitRealtimeLoader.kt` |
| percorsi e navigazione | `feature/map/.../BRouterRouteEngine.kt`, `NavigationScreen.kt`, `NavigationTracker.kt`; le modifiche a BRouter sono le patch in `third-party/patches/brouter` |
| i testi dell'app | `res/values/strings.xml` (italiano) e `res/values-en/strings.xml` (inglese) di ogni modulo |
| le vaccinazioni | dati in `tools/data-pipeline/content/src/main/resources/vaccinations/` (TSV curati, bozza con `vaccinations_draft.py`), regole in `core/data/.../vaccination/`, schermata in `feature/guide/.../VaccinationScreen.kt` |
| popolazione, capitale e coordinate delle città | `tools/data-pipeline/scripts/city_population.py`, usato da `extract-cities-dump.py` e `extract-cities-dump-en.py` |
| il dataset di addestramento | `tools/data-pipeline/scripts/generate_sft.py` e `translate_dataset.py` (le tabelle per lingua stanno in `generate_sft_dataset.py` e `generate_sft_dataset_en.py`) |
| le guide e le città in inglese | `build-guides.sh … en`, `build-cities.sh … en`, `extract-cities-dump-en.py`; nell'app `core/sync/.../GuidesChoice.kt` |

## Rilasciare una versione

1. Aggiornare `versionCode` e `versionName` in `app/build.gradle.kts`.
2. Nel `CHANGELOG.md` spostare le voci di "Non rilasciato" sotto `## [X.Y.Z] - AAAA-MM-GG` (data del rilascio), lasciare in cima un "Non rilasciato" vuoto e aggiornare i link di confronto in fondo al file.
3. Se i nuovi dati richiedono questa versione dell'app, aggiornare `tools/data-pipeline/min-app-version-code`.
4. Controllare le ultime release stabili di [llama.cpp](https://github.com/ggml-org/llama.cpp/releases) (tag `vX.Y.Z`, non le build `bNNNN`) e di [BRouter](https://github.com/abrensch/brouter/releases) rispetto ai commit dei submodule (`git submodule status`). Con una release più nuova si aggiorna il submodule come spiega "llama.cpp e BRouter", con `LLAMA_BUILD` di `tools/data-pipeline/scripts/get_llama_tools.py` sulla build `bNNNN` dello stesso commit di llama.cpp, e si provano assistente e Navigatore sull'emulatore prima del rilascio.
5. Fare il push su `main`: `publish-apk.yml` firma l'APK e crea la release. La pipeline dei dati pubblicherà i dati nuovi solo dopo.

## Regole del progetto

- Ogni modulo `feature` dipende solo dai moduli `core`.
- Testi dell'app in italiano e in inglese: ogni testo nuovo va in `values/strings.xml` **e** in `values-en/strings.xml` (lint segnala le traduzioni mancanti).
- Il prompt italiano dell'assistente (`PromptTemplates`) è quello dei dati di addestramento: non va cambiato senza riaddestrare i modelli.
- Ogni modifica visibile va nel `CHANGELOG.md`.
- Niente percorsi del proprio computer (`C:\...`, `/home/...`) nei file del repository né nei messaggi di commit.
