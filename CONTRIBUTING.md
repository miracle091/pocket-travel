# Contribuire a Pocket Travel

<p><a href="CONTRIBUTING.md"><strong>Italiano</strong></a> | <a href="CONTRIBUTING.en.md">English</a></p>

I contributi sono benvenuti. Chi partecipa rispetta il [codice di condotta](CODE_OF_CONDUCT.md); segnalazioni e pull request si scrivono in italiano o in inglese.

## Modi per contribuire

- 🐛 **Segnalare errori**, con i passi per riprodurli.
- 💡 **Proporre funzioni** utili in viaggio, soprattutto senza connessione.
- 🔧 **Correggere errori** aperti nelle [segnalazioni](https://github.com/miracle091/pocket-travel/issues).
- 📝 **Migliorare i testi** dell'app, il README e la [wiki](https://github.com/miracle091/pocket-travel/wiki).
- 🚌 **Aggiungere reti di mezzi pubblici** con un GTFS pubblico e una licenza aperta.
- ♿ **Migliorare l'accessibilità** e 🧪 **aggiungere test**.

Gli errori nei contenuti di guide e mappa si correggono alla fonte (Wikivoyage, OpenStreetMap, Wikidata) e arrivano nell'app con l'aggiornamento settimanale.

## Segnalazioni e proposte

Una [segnalazione](https://github.com/miracle091/pocket-travel/issues/new) indica la versione dell'app, il dispositivo con la versione di Android, la nazione e la lingua dell'app, i passi per riprodurre l'errore e il risultato atteso. Screenshot e log (`adb logcat`) non devono contenere dati personali, posizioni precise o chiavi API.

Una proposta descrive prima il caso d'uso in viaggio, poi la soluzione. Le modifiche grandi si discutono in una segnalazione prima di scrivere il codice, tenendo conto dei vincoli del progetto: l'app funziona offline, non ha un server proprio, non raccoglie dati sugli utenti ed è distribuita solo su GitHub.

## Fare una modifica

1. Crea un fork e un ramo da `main` (`fix/meteo-senza-rete`, `feat/filtro-parcheggi`).
2. Limita la modifica al suo scopo, con i test: per un errore, prima un test che fallisce, poi la correzione.
3. Aggiorna la documentazione toccata e aggiungi una voce al [CHANGELOG](CHANGELOG.md) in "Non rilasciato" (e in "Cambiamenti incompatibili" se la modifica rompe le versioni precedenti).
4. Esegui i controlli della CI:

   ```bash
   ./gradlew assembleDebug lint detekt testDebugUnitTest :core:poi:test :tools:data-pipeline:content:test
   ```

   Per la pipeline anche `python3 tools/data-pipeline/scripts/test_<nome>.py`. I test sul dispositivo si lanciano con `installDebugAndroidTest` e `adb shell am instrument`, non con `connectedDebugAndroidTest`, che cancella i file sul dispositivo come il modello GGUF.

5. Apri la pull request su `main` con cosa cambia e perché, la segnalazione collegata (`Risolve #123`), i test eseguiti e gli screenshot delle modifiche visibili.

La [guida rapida](docs/GUIDA.md) spiega come preparare l'ambiente, compilare e generare i dati.

## Regole

| Area | Regola |
|---|---|
| Moduli | ogni `feature/*` dipende solo da `core/*` |
| Stile | `detekt` e `lint` senza nuovi avvisi; commenti in italiano, al presente |
| Dipendenze | in `gradle/libs.versions.toml`, motivate per peso, licenza e funzionamento offline |
| Database | ogni modifica allo schema Room ha una migrazione in `Migrations.kt` e un test in `RegionDatabaseMigrationTest` |
| Terze parti | BRouter e llama.cpp non si modificano: le modifiche sono patch in `third-party/patches/` |
| Testi | ogni stringa in italiano (`values/`) e in inglese (`values-en/`), con i termini e le regole di [STYLE.md](docs/STYLE.md) |
| Dati | ogni nuova fonte ha una licenza aperta compatibile ed entra nella schermata Licenze (`LicenseData.kt`) e nella [pagina delle licenze](https://miracle091.github.io/pocket-travel/licenses.html) (`tools/data-pipeline/scripts/licenses_page.py`); niente file generati grandi nel repository |

## Messaggi di commit

[Conventional Commits](https://www.conventionalcommits.org/it/v1.0.0/) in italiano (o in inglese, per chi non scrive in italiano), con l'ambito del modulo:

```
fix(map): partenze delle reti scadute accanto a quelle valide
feat(poi): ponti, piazze, parchi nazionali e altri luoghi famosi
```

Tipi: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`.

## Sicurezza e licenza

Le vulnerabilità si segnalano in privato con un [avviso di sicurezza](https://github.com/miracle091/pocket-travel/security/advisories/new), mai con una segnalazione pubblica. Inviando un contributo si accetta che il codice sia distribuito con la [licenza MIT](LICENSE).
