# Problemen oplossen

## De app crasht of toont "Er is iets misgegaan"

Het crashscherm draait in een apart proces en biedt:

- **Opnieuw proberen**: start de app opnieuw. Na 3 crashes binnen 2 minuten is dit uitgeschakeld om eindeloos opnieuw proberen te voorkomen.
- **App herstellen**: wist instellingen, tijdelijke bestanden, gedownloade updates en de herstelstatus. Je database (favorieten, wishlist, beoordelingen, profiel) blijft behouden.
- **Logs bekijken**: toont het lokale crashrapport (gevoelige gegevens zijn verwijderd). Je kunt het delen of opslaan; er wordt niets automatisch verzonden.
- **Update controleren / downloaden**: een nieuwere versie kan het probleem oplossen.
- **Terug naar home**: start de app op het beginscherm.

Na een crash start de app op de laatst bekende veilige plek en meldt dat ze is hersteld.

### Beschadigde database

Kan Room de database niet openen, dan wordt het bestand bewaard als `gamegraph.db.corrupt` (in de databasemap van de app) en start de app met een lege database. Heb je een export, importeer die via Instellingen → Gegevens importeren.

### Het crashscherm testen

Instellingen → Diagnostiek → *Crashpagina testen* veroorzaakt een opzettelijke, onschadelijke fout.

## Updates

| Probleem | Oplossing |
| --- | --- |
| "Geen verbinding met GitHub" | Controleer internet. De rest van de app werkt offline gewoon. |
| "GitHub staat te veel aanvragen niet toe" | De publieke GitHub-API heeft een uurlimiet per IP-adres. Probeer later opnieuw. |
| "De releasepagina is niet gevonden" | Klopt `UPDATE_REPO_OWNER/NAME`? Is de repository openbaar? Is er minstens één niet-concept-release? |
| "De nieuwste release bevat geen APK" | Voeg de ondertekende APK toe aan de release (zie RELEASING.md). |
| "Android moet eerst toestemming geven..." | Instellingen → Apps → Speciale toegang → Onbekende apps installeren → GameGraph → toestaan. Kom terug en tik op Installeren. |
| "...andere ondertekeningssleutel" | De APK is met een andere sleutel gemaakt. Gebruik altijd dezelfde keystore. Je huidige app blijft ongewijzigd. Zie UPDATER.md. |
| "De controlesom klopt niet" | Download opnieuw. Blijft het fout, dan hoort het `.sha256`-bestand niet bij de APK; controleer de release. |
| Update wordt niet aangeboden | De `versionCode` in de release moet hoger zijn dan die van de geïnstalleerde app. Controleer `version.properties`, het manifest en de notes. |

## Bouwen

| Probleem | Oplossing |
| --- | --- |
| `SDK location not found` | Maak `local.properties` met `sdk.dir=...` (zie BUILDING.md). |
| `Release-ondertekening ontbreekt` | Maak `signing/keystore.properties` of zet de `GAMEGRAPH_*`-omgevingsvariabelen (zie RELEASING.md). Dit is bewust: er wordt geen onbruikbare APK gemaakt. |
| Platform 36 of build-tools niet gevonden | `sdkmanager "platforms;android-36" "build-tools;36.0.0"` |
| Eerste build duurt erg lang | Normaal: Gradle en alle dependencies worden gedownload en gecompileerd. Daarna gaat het veel sneller. |
| Gradle laat een proces draaien | `./gradlew --stop` |
| Robolectric-tests kunnen niet downloaden | De tests gebruiken Android 14 (SDK 34); de eerste keer is internet nodig. |

## Gegevens

- **Export/import**: Instellingen → Databeheer. Een import wordt eerst volledig gevalideerd en vervangt je gegevens pas daarna in één transactie; bij een ongeldig bestand verandert er niets.
- **Cache wissen** raakt je database niet.
- **Profiel opnieuw instellen** (Profiel-scherm) wist voorkeuren en leerhistorie, maar laat de Library staan.
