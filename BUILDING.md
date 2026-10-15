# Bouwen

## Benodigdheden

- JDK 17 (`JAVA_HOME`)
- Android SDK met platform **36** en build-tools **36.0.0** (`ANDROID_HOME`)
- Internet bij de eerste build (Gradle en dependencies worden binnengehaald)

De Gradle-versie staat vast in `gradle/wrapper/gradle-wrapper.properties`; gebruik daarom altijd `./gradlew` (Windows: `gradlew.bat`). Alle bibliotheekversies staan vast in `gradle/libs.versions.toml`.

## Eénmalig instellen

Maak `local.properties` in de projectmap (staat in `.gitignore`):

```
sdk.dir=C\:/pad/naar/android/sdk
```

## Tests, lint en controles

```bash
./gradlew :app:testDebugUnitTest   # unit tests (inclusief Room via Robolectric)
./gradlew :app:lintDebug           # lint, incl. ontbrekende resources
./gradlew :app:compileDebugKotlin  # alleen compileren
```

Rapporten: `app/build/reports/tests/` en `app/build/reports/lint-results-debug.html`.

De unit tests dekken onder meer: game-overeenkomsten, aanbevelingsscores, feedbackverwerking, databaseopslag (Room), export/import, versievergelijking, ongeldige releasegegevens, ontbrekende APK-assets, verkeerde checksums, crashstatus en recovery, en offlinegedrag.

## Release-APK maken

Zie [RELEASING.md](RELEASING.md). Zonder ondertekeningssleutel weigert `assembleRelease` met een duidelijke melding; er wordt dus nooit een onbruikbare APK gemaakt.

```bash
./gradlew :app:assembleRelease
```

## Versie ophogen

Pas `version.properties` aan. **Verhoog `versionCode` bij elke nieuwe APK**: Android installeert alleen een hogere code als update, en de updater vergelijkt ook op `versionCode`.

## Bouwen met een lokale installatie van de tools (Windows)

```bash
export JAVA_HOME=/c/pad/naar/jdk17
export ANDROID_HOME=/c/pad/naar/sdk
./gradlew --stop   # na afloop: laat geen Gradle-daemon achter
```
