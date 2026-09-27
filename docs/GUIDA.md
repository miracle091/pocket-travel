# Guida rapida per chi sviluppa

Le cose da sapere per lavorare su Pocket Travel. Il **perché** delle scelte tecniche è nella [wiki](https://github.com/miracle091/pocket-travel/wiki).

## Cosa serve

- Android Studio con JDK 17 o successivo (Gradle è già incluso nel repository).
- Per la pipeline dei dati: bash, `jq`, `xz`, Python 3.
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

## Provare con dati tuoi

Nelle build di debug l'app può leggere un catalogo locale invece di quello pubblicato:

```bash
python -m http.server 8000 --directory <cartella con manifest.json>
./gradlew :app:installDebug -PpocketTravel.manifestUrl=http://10.0.2.2:8000/manifest.json
```

`10.0.2.2` è il PC visto dall'emulatore.

## Dove mettere le mani

| Voglio cambiare… | Guarda in |
|---|---|
| una schermata | `app/` o il modulo `feature/` corrispondente |
| il download o l'installazione dei pacchetti | `core/sync/` |
| il database | `core/data/` (ogni cambio di schema vuole una migrazione e il suo test) |
| cosa finisce nei pacchetti | `tools/data-pipeline/scripts/` e `tools/data-pipeline/content/` |
| l'elenco delle regioni | `tools/data-pipeline/scripts/pilot-regions.sh`, poi `generate-weekly-schedule.sh` |
| i modelli dell'assistente | `feature/ai/.../LlmModelCatalog.kt` e gli script `train_*.py` / `eval_*.py` |

## Rilasciare una versione

1. Aggiorna `versionCode` e `versionName` in `app/build.gradle.kts`.
2. Nel `CHANGELOG.md` rinomina "Non rilasciato" con il numero di versione.
3. Se i nuovi dati richiedono questa versione dell'app, aggiorna `tools/data-pipeline/min-app-version-code`.
4. Fai il push su `main`: `publish-apk.yml` firma l'APK e crea la release. La pipeline dei dati pubblicherà i dati nuovi solo dopo.

## Regole della casa

- Ogni modulo `feature` dipende solo dai moduli `core`.
- Testi dell'app in italiano, nei file `strings.xml`.
- Ogni modifica visibile va nel `CHANGELOG.md`.
