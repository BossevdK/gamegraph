# De ingebouwde updater

GameGraph kan zichzelf bijwerken vanuit **GitHub Releases**, zonder dat je de APK via een pc hoeft over te zetten. De app installeert nooit stilzwijgend: Android toont altijd zijn eigen bevestigingsscherm. Er is geen root, geen Accessibility Service en geen omweg langs Android-beveiliging.

## Stroom

1. **Controleren**: bij het openen van de app (maximaal 1× per 24 uur, uit te zetten) of handmatig via Instellingen → *Controleer op updates*. De app vraagt `https://api.github.com/repos/<eigenaar>/<repo>/releases` op (publiek, zonder token).
2. **Kiezen**: concepten (drafts) worden genegeerd, prereleases alleen als de gebruiker dat aanzet. Releases met een ongeldige tag (geen `vX.Y.Z`) worden overgeslagen.
3. **Vergelijken**: de `versionCode` van de release wordt vergeleken met de geïnstalleerde `versionCode`. Bron van de `versionCode`, in volgorde:
   1. `gamegraph-release.json` (het manifest) in de release,
   2. een regel `versionCode: N` in de release notes,
   3. als geen van beide bestaat: de tag (`v1.2.0`) tegen de geïnstalleerde `versionName`.
4. **Downloaden**: alleen de APK uit de release, alleen via `https://github.com/<eigenaar>/<repo>/releases/download/...`. Elke andere host of repository wordt geweigerd. Voortgang, foutmelding en *Opnieuw proberen* zijn zichtbaar. De APK komt in app-eigen opslag (`Android/data/nl.gamegraph.app/files/updates`).
5. **Verifiëren**: HTTP-status, bestandsgrootte en SHA-256 (indien de release een checksum publiceert via manifest, GitHub-`digest` of een `.sha256`-bestand). Bij een afwijking wordt het bestand verwijderd. Een checksum is een controle op downloadfouten, **niet** een vervanging van Android's handtekeningcontrole.
6. **Voorcontrole**: de app leest de gedownloade APK en controleert of de package-naam klopt, de versie hoger is en de **ondertekeningssleutel gelijk is** aan die van de geïnstalleerde app.
7. **Installeren**: via Android's `PackageInstaller`. Als Android toestemming nodig heeft om apps uit deze bron te installeren, verwijst de app naar de juiste instelling met uitleg. Daarna opent Android het bevestigingsscherm.
8. **Na de installatie**: bij de volgende start herkent de app dat de update is geïnstalleerd en meldt dat. Wordt de installatie geannuleerd of mislukt, dan blijft de huidige app ongewijzigd bruikbaar.

## Wat een release moet bevatten

Minimaal: een tag `vX.Y.Z` en één `.apk`-bestand. Aanbevolen (de GitHub Actions-workflow maakt dit automatisch):

| Bestand | Doel |
| --- | --- |
| `GameGraph-X.Y.Z.apk` | de ondertekende release-APK |
| `GameGraph-X.Y.Z.apk.sha256` | checksum, formaat `<hash>  <bestandsnaam>` |
| `gamegraph-release.json` | manifest |

Manifest:

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apk": "GameGraph-1.1.0.apk",
  "sha256": "<64 hexadecimale tekens>",
  "size": 12345678,
  "package": "nl.gamegraph.app",
  "minSdk": 26
}
```

Alle velden zijn optioneel. Een kapot manifest wordt genegeerd.

## Foutafhandeling

| Situatie | Wat de gebruiker ziet |
| --- | --- |
| Geen internet / time-out | "Geen verbinding met GitHub..." (app blijft volledig bruikbaar) |
| Rate limit (HTTP 403/429) | "GitHub staat te veel aanvragen niet toe. Probeer het over een uur opnieuw." |
| Repository of releases niet gevonden (404) | melding om de repository-instelling te controleren |
| Onleesbare of onverwachte JSON | "De releasegegevens zijn onleesbaar." |
| Release zonder APK | "De nieuwste release bevat geen APK." (geen update aangeboden) |
| Meerdere APK's, onduidelijk welke | melding, niets wordt geïnstalleerd |
| Downloadlink buiten de officiële repository | geweigerd |
| Verkeerde grootte of checksum | bestand verwijderd, opnieuw proberen |
| Andere ondertekeningssleutel | uitleg dat Android dit niet toestaat; de huidige installatie wordt **niet** verwijderd of vervangen |
| Installatie geannuleerd | "Installatie geannuleerd. De huidige app blijft bruikbaar." |

## Handtekening moet gelijk blijven

Android installeert een update alleen over een bestaande app als beide met dezelfde sleutel zijn ondertekend. De app probeert dit vooraf vast te stellen en legt het uit; ze verwijdert of vervangt zelf nooit iets. Wil je toch overstappen op een nieuwe sleutel, dan moet je de app handmatig deïnstalleren (export eerst je gegevens via Instellingen → Gegevens exporteren).

## Distributie

Er is één soort app: `nl.gamegraph.app`, altijd met dezelfde ondertekeningssleutel. De updater accepteert alleen releases met die package-naam (het manifest kan dit afdwingen) en controleert de sleutel vóór installatie.

## Privacy

De enige verbinding is een GET naar `api.github.com` (en de download van de APK). Er worden geen persoonsgegevens verstuurd. Het adres van je toestel is voor GitHub zichtbaar zoals bij elke webaanvraag.

## Configuratie

`BuildConfig.UPDATE_REPO_OWNER` en `UPDATE_REPO_NAME` komen uit `app/build.gradle.kts` (zie RELEASING.md). De logica staat in `update/ReleaseParser.kt` (puur, getest) en `update/UpdateManager.kt` (Android).
