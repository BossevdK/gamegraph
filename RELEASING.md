# Een release maken

De app wordt **één keer handmatig geïnstalleerd**. Daarna haalt de ingebouwde updater nieuwe versies uit GitHub Releases. Android vraagt bij elke installatie zelf om bevestiging.

## 1. De ondertekeningssleutel (eenmalig)

Alle releases moeten met **dezelfde sleutel** zijn ondertekend. Een andere sleutel betekent: Android weigert de update, en je moet de app deïnstalleren (waarbij lokale gegevens verdwijnen). Bewaar de sleutel dus goed.

Maak een sleutel:

```bash
keytool -genkeypair -v -keystore signing/gamegraph-release.jks -alias gamegraph \
  -keyalg RSA -keysize 4096 -validity 10000
```

Maak `signing/keystore.properties` (de map `signing/` staat in `.gitignore`):

```
storeFile=gamegraph-release.jks
storePassword=...
keyAlias=gamegraph
keyPassword=...
```

**Veilig bewaren**

- Commit nooit `*.jks`, `*.keystore` of `keystore.properties` (staan in `.gitignore`).
- Maak een back-up van `gamegraph-release.jks` én de wachtwoorden op een tweede plek (bijvoorbeeld een wachtwoordmanager plus een versleutelde schijf of usb-stick). Zonder deze sleutel kun je nooit meer een update uitbrengen die over de bestaande installatie heen past.
- Plak wachtwoorden nergens in chats, issues of logs.

## 2. GitHub-secrets instellen (voor de automatische build)

Repository → Settings → Secrets and variables → Actions → *New repository secret*:

| Secret | Inhoud |
| --- | --- |
| `GAMEGRAPH_KEYSTORE_BASE64` | de keystore als base64: `base64 -w0 signing/gamegraph-release.jks` (Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("signing\gamegraph-release.jks"))`) |
| `GAMEGRAPH_KEYSTORE_PASSWORD` | het keystore-wachtwoord |
| `GAMEGRAPH_KEY_ALIAS` | `gamegraph` |
| `GAMEGRAPH_KEY_PASSWORD` | het sleutelwachtwoord |

Ontbreekt er een secret, dan faalt de release-job meteen met de melding welke. De keystore wordt tijdens de build buiten de werkmap geplaatst en nooit als artifact geüpload; de workflow toont geen geheimen in logs.

## 3. De updaterepository instellen

De updater leest `github.com/<eigenaar>/<repo>`. Standaard is dat `BossevdK/gamegraph`. Pas aan in `app/build.gradle.kts` (`repoOwner`, `repoName`) of bouw met `-PgameGraphRepoOwner=... -PgameGraphRepoName=...`. De repository moet **openbaar** zijn: de app gebruikt geen token.

## 4. Een nieuwe release publiceren

1. Verhoog in `version.properties` **beide** waarden, bijvoorbeeld:
   ```
   versionCode=2
   versionName=1.1.0
   ```
2. Commit en push naar `main`.
3. Maak een tag die exact `v` + `versionName` is en push die:
   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```
4. GitHub Actions bouwt, test, ondertekent en maakt automatisch de GitHub Release met:
   - `GameGraph-1.1.0.apk`
   - `GameGraph-1.1.0.apk.sha256` (SHA-256-checksum)
   - `gamegraph-release.json` (releasemanifest)
   - release notes met de regel `versionCode: 2`
5. Optioneel: bewerk daarna de release notes op GitHub (laat de regel `versionCode: N` staan; het manifest heeft voorrang).

De workflow faalt als de tag niet bij `versionName` past, zodat een release nooit een verkeerd versienummer krijgt.

Een tag met een streepje (bijvoorbeeld `v1.2.0-beta1`) wordt als **prerelease** gepubliceerd; de app toont die alleen als de gebruiker dat aanzet in Instellingen.

## Handmatig releasen (zonder Actions)

```bash
./gradlew :app:assembleRelease
sha256sum app/build/outputs/apk/release/app-release.apk
```

Maak daarna op GitHub een release met een tag `vX.Y.Z`, upload de APK (bij voorkeur als `GameGraph-X.Y.Z.apk`) en zet in de notes de regel `versionCode: N`. Het manifest en de checksum zijn optioneel maar aan te raden.

## Eerste installatie

Zet de eerste APK (uit de GitHub Release of `app/build/outputs/apk/release/`) op je telefoon en installeer die; Android vraagt om toestemming voor installatie uit de gebruikte bron. Vanaf dan gaat het via Instellingen → Updates.
