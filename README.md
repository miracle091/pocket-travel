# 🧭 Pocket Travel

Pocket Travel è un'app per chi viaggia senza voler dipendere dalla connessione: guida turistica offline, mappa con routing pedonale, assistente IA e vault per i documenti, tutto scaricabile a pacchetti per regione.

Versione corrente: **0.4.0** ([Semantic Versioning](https://semver.org/lang/it/)) — vedi [CHANGELOG.md](CHANGELOG.md).

## Stato

MVP funzionalmente completo: guida turistica offline con ricerca full-text, mappa vettoriale con routing pedonale offline (BRouter), assistente IA con modalità locale (on-device, con controllo RAM) e online (chiave utente personale), vault passaporti cifrato, gestione pacchetti regionali (download/aggiornamento/rimozione) e registro fonti ufficiali esterne. Pipeline dati e orchestratore per generare e pubblicare pacchetti regionali sono pronti, inclusa la pubblicazione automatica settimanale a bucket con controllo di necessità (`tools/data-pipeline`, `.github/workflows/publish-regions.yml`); manca ancora un repository GitHub reale collegato per pubblicarli.

## Struttura

```
app/                    shell UI, navigazione, DI (Hilt)
core/data/              Room DB, RegionPackage, gestione pacchetti regionali
core/content/           import/parsing contenuti guida (Wikivoyage → schema interno)
core/sync/              WorkManager: sync manifest, download pacchetti
core/ui/                design system condiviso (tema, componenti Compose comuni)
feature/guide/          UI guida turistica
feature/map/            mappa MapLibre, POI, routing
feature/ai/             orchestrazione prompt, motore locale/online
feature/sources/        registro fonti ufficiali esterne
feature/vault/          UI vault passaporti cifrato
tools/data-pipeline/    generazione e pubblicazione dei pacchetti regionali
```

Ogni modulo `feature` dipende solo dai moduli `core`, mai da altri `feature`.

## Aprire il progetto

Richiede Android Studio (porta con sé JDK 17+ e Gradle).

1. Apri la cartella in Android Studio.
2. Lascia che generi il Gradle wrapper e sincronizzi le dipendenze al primo avvio.
3. Le versioni in `gradle/libs.versions.toml` sono già verificate contro una build reale (vedi il log di sviluppo). Accetta comunque i suggerimenti di aggiornamento AGP/Kotlin/Compose che Android Studio propone al sync, se presenti.

## Licenza

[MIT](LICENSE)
