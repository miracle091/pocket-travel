# 🧭 Pocket Travel

Pocket Travel è un'app per chi viaggia senza voler dipendere dalla connessione: guida turistica offline, mappa, assistente IA e vault per i documenti. Le guide di tutte le nazioni sono un unico pacchetto leggero; per ogni regione mappa, percorsi, punti di interesse (più quelli extra, facoltativi) e numeri civici si scaricano e aggiornano separatamente.

Versione corrente: **0.7.0** ([Semantic Versioning](https://semver.org/lang/it/)) — vedi [CHANGELOG.md](CHANGELOG.md).

## Stato

MVP funzionalmente completo: guida turistica offline con ricerca full-text, mappa vettoriale offline (il motore dei percorsi, BRouter, c'è già ma non ha ancora una schermata), assistente IA con modalità locale (llama.cpp, modelli GGUF scelti in base alla RAM, divisi tra "ufficiali" e "addestrati da noi" sulle guide, questi ultimi in arrivo) e online (chiave utente personale), vault passaporti cifrato, gestione dei pacchetti (guide, mappa, percorsi, punti di interesse, punti di interesse extra, numeri civici: download, aggiornamento e rimozione uno per uno), legenda della mappa con filtri salvati, modalità d'uso ("Come ti sposti") che scelgono i punti di interesse mostrati di default e registro fonti ufficiali esterne. Interfaccia Material Design 3 con layout adattivo per telefono e tablet.

Il catalogo delle regioni (355 nel lotto pilota, con Stati Uniti, Canada, Russia europea, Cina e Francia divisi in regioni, `tools/data-pipeline/scripts/pilot-regions.sh`) è generato da `tools/data-pipeline` e pubblicato ogni settimana da `.github/workflows/publish-regions.yml`: `manifest.json` su GitHub Pages, i pacchetti sulle release `region-data*` del repository.

## Struttura

```
app/                        shell UI, navigazione, DI (Hilt)
core/data/                  Room DB (region.db), pacchetti installati, archiviazione su disco
core/content/               parsing dei contenuti Wikivoyage (usato dalla pipeline per le guide)
core/sync/                  WorkManager: sync del manifest, download e installazione dei pacchetti
core/poi/                   categorie dei POI e regole su cosa mostrare e pubblicare (Kotlin puro, usato anche dalla pipeline)
core/ui/                    design system condiviso (tema M3, icone, componenti Compose comuni)
feature/guide/              UI guida turistica
feature/map/                mappa MapLibre, POI, routing, mappa del mondo
feature/ai/                 orchestrazione prompt, motore locale (llama.cpp via JNI) e online
feature/sources/            registro fonti ufficiali esterne
feature/vault/              UI vault passaporti cifrato
third-party/                sorgenti vendorizzati: brouter-core, brouter-map-creator, llama-cpp
tools/data-pipeline/        generazione e pubblicazione dei pacchetti
  content/                  guides.db, poi.db e poi-extra.db, civici, manifest (merge e validazione), mappa del mondo
  maptiles/, routing/       generatori locali di map.pmtiles (Planetiler) e .rd5 (BRouter), non
                            usati in produzione: mappa da Protomaps, segmenti da brouter.de
  scripts/                  build-guides.sh, build-region.sh, assemble-site.sh, calendario
                            settimanale, script Python per dataset/training/valutazione/
                            conversione GGUF con imatrix (riga di stato comune in status.py)
  data/sft/                 dataset di fine-tuning dell'assistente (non versionato)
scripts/                    generate_ai_models_manifest.py (catalogo modelli IA per app-status.json)
.github/workflows/          android-ci.yml, publish-apk.yml, publish-regions.yml
```

Ogni modulo `feature` dipende solo dai moduli `core`, mai da altri `feature`.

## Aprire il progetto

Richiede Android Studio con JDK 17 o successivo.

1. Apri la cartella in Android Studio: usa il Gradle wrapper già presente nel repository (`gradlew`, Gradle 9.7.1).
2. Le versioni delle dipendenze sono in `gradle/libs.versions.toml`.
3. Per provare l'app con un catalogo locale invece di quello pubblicato (solo build di debug):
   `./gradlew :app:installDebug -PpocketTravel.manifestUrl=http://10.0.2.2:8000/manifest.json`
   (`10.0.2.2` è il PC visto dall'emulatore; l'HTTP in chiaro è consentito solo verso quell'host).

## Licenza

[MIT](LICENSE)
