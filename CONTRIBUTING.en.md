# Contributing to Pocket Travel

<p><a href="CONTRIBUTING.md">Italiano</a> | <a href="CONTRIBUTING.en.md"><strong>English</strong></a></p>

Contributions are welcome. Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.en.md); issues and pull requests can be written in Italian or English.

## Ways to contribute

- 🐛 **Report bugs**, with the steps to reproduce them.
- 💡 **Suggest features** useful when traveling, especially without a connection.
- 🔧 **Fix bugs** open in the [issues](https://github.com/miracle091/pocket-travel/issues).
- 📝 **Improve the texts** of the app, the README and the [wiki](https://github.com/miracle091/pocket-travel/wiki).
- 🚌 **Add public transport networks** with a public GTFS feed and an open license.
- ♿ **Improve accessibility** and 🧪 **add tests**.

Errors in the content of guides and the map are fixed at the source (Wikivoyage, OpenStreetMap, Wikidata) and reach the app with the weekly update.

## Issues and suggestions

An [issue](https://github.com/miracle091/pocket-travel/issues/new) states the app version, the device with its Android version, the country and the app language, the steps to reproduce the bug and the expected result. Screenshots and logs (`adb logcat`) must not contain personal data, precise locations or API keys.

A suggestion describes the travel use case first, then the solution. Large changes are discussed in an issue before writing code, keeping in mind the project's constraints: the app works offline, has no server of its own, collects no user data and is distributed only on GitHub.

## Making a change

1. Fork the repository and create a branch from `main` (`fix/weather-offline`, `feat/parking-filter`).
2. Keep the change to its purpose, with tests: for a bug, first a failing test, then the fix.
3. Update the affected documentation and add an entry to the [CHANGELOG](CHANGELOG.md) under "Non rilasciato" (unreleased), and under "Cambiamenti incompatibili" (breaking changes) if the change breaks earlier versions. The CHANGELOG is in Italian.
4. Run the CI checks:

   ```bash
   ./gradlew assembleDebug lint detekt testDebugUnitTest :core:poi:test :tools:data-pipeline:content:test
   ```

   For the pipeline, also `python3 tools/data-pipeline/scripts/test_<name>.py`. Device tests run with `installDebugAndroidTest` and `adb shell am instrument`, not with `connectedDebugAndroidTest`, which deletes files on the device such as the GGUF model.

5. Open the pull request against `main` with what changes and why, the linked issue (`Fixes #123`), the tests run and screenshots of visible changes.

The [quick guide](docs/GUIDA.md) (in Italian) explains how to set up the environment, build and generate the data.

## Rules

| Area | Rule |
|---|---|
| Modules | each `feature/*` depends only on `core/*` |
| Style | `detekt` and `lint` with no new warnings; comments in Italian, in the present tense |
| Dependencies | in `gradle/libs.versions.toml`, justified by size, license and offline behavior |
| Database | every Room schema change has a migration in `Migrations.kt` and a test in `RegionDatabaseMigrationTest` |
| Third parties | BRouter and llama.cpp are not edited: changes are patches in `third-party/patches/` |
| Texts | every string in Italian (`values/`) and English (`values-en/`), with the terms and rules of [STYLE.md](docs/STYLE.md) |
| Data | every new source has a compatible open license and is added to the Licenses screen (`LicenseData.kt`) and to the [licenses page](https://miracle091.github.io/pocket-travel/licenses-en.html) (`tools/data-pipeline/scripts/licenses_page.py`); no large generated files in the repository |

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) in Italian (or in English, for contributors who do not write Italian), with the module as scope:

```
fix(map): partenze delle reti scadute accanto a quelle valide
feat(poi): ponti, piazze, parchi nazionali e altri luoghi famosi
```

Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`.

## Security and license

Vulnerabilities are reported privately with a [security advisory](https://github.com/miracle091/pocket-travel/security/advisories/new), never with a public issue. Submitting a contribution means agreeing that the code is distributed under the [MIT license](LICENSE).
