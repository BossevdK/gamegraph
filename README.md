# GameGraph

Een persoonlijke game-discovery-app voor Android. GameGraph combineert drie dingen:

1. **Discover Graph**: een interactieve netwerkkaart waarin games nodes zijn en verbindingen concrete overeenkomsten tonen (genre, gameplay, sfeer, ontwikkelaar, speelstijl).
2. **NextGame**: één gamekaart tegelijk; swipe of gebruik de knoppen om te liken, af te wijzen, te bewaren of de details te bekijken.
3. **My GameDNA**: een smaakprofiel (radardiagram) dat leert van je reacties en dat je handmatig kunt bijstellen.

Alles werkt **lokaal en zonder account**. Er is geen backend, geen Steam API-key, geen advertentie en geen verborgen telemetrie.

> **Let op:** de ingebouwde catalogus is een kleine **demo-catalogus** (ongeveer 40 games). Hij is niet compleet of actueel. Prijzen, reviews en screenshots ontbreken bewust; de app toont die nooit verzonnen.

## Schermen

| Scherm | Wat het doet |
| --- | --- |
| Home | Aanbevolen, nieuwe ontdekkingen, recent bekeken |
| Graph | Pan, zoom, tik op een node; detailpaneel met reden van de verbinding; genre-filter; maximaal 60 nodes zodat de kaart leesbaar blijft |
| NextGame | Swipe + knoppen, matchscore alleen bij genoeg gegevens, uitleg waarom, filters op genre/platform/prijs/speelstijl |
| Details | Info, kenmerken, bronvermelding, winkellink, gerelateerde games, beoordeling, favoriet/wishlist |
| Profiel (GameDNA) | Radardiagram, sliders per smaakas, welke feedback meetelt, profiel opnieuw instellen |
| Library | Favorieten, wishlist, beoordeeld, bewaard, afgewezen; zoeken en sorteren |
| Instellingen | Thema, updates, export/import, cache wissen, privacy, diagnostiek, versie-info |

## Architectuur

```
app/src/main/java/nl/gamegraph/app/
  domain/    Pure Kotlin, zonder Android: modellen, overeenkomst, smaakprofiel, aanbevelingen, graph-layout
  data/      Room (database), DataStore (instellingen), GameRepository, SeedCatalog, export/import
  update/    GitHub-updater: releaseparser, download + verificatie, PackageInstaller
  crash/     Globale crashhandler, rapporten, herstelstatus, CrashActivity (eigen proces)
  ui/        Compose: thema, componenten, navigatie, schermen
  AppContainer.kt   Handmatige dependency injection
  AppViewModel.kt   Eén ViewModel dat catalogus, gebruikersdata en profiel combineert
```

Principes:

- **Domeinlogica is deterministisch en testbaar** (`domain/` kent geen Android). Dezelfde invoer geeft altijd dezelfde aanbevelingen en dezelfde graph.
- **Het profiel is afgeleid, niet opgeslagen.** Het wordt berekend uit de huidige staat van elke game (liked, favoriet, rating...) en de handmatige voorkeuren. Daardoor is het idempotent: vaker tikken telt niet dubbel, en terugdraaien werkt. `TasteProfile.apply` werkt incrementeel bij; `TasteProfile.from` bouwt opnieuw op met hetzelfde resultaat.
- **Schrijfacties zijn transacties** (`GameRepository`), zodat een mislukte actie nooit een half resultaat achterlaat.
- **Fouten blijven lokaal.** Gewone fouten (netwerk, opslaan) worden een korte melding of foutstatus in het scherm. Alleen onverwachte, fatale fouten leiden naar de crashpagina.

## Aanbevelingsmodel (versie 1)

Gewogen en uitlegbaar, zonder externe AI. Per game:

| Onderdeel | Gewicht | Betekenis |
| --- | --- | --- |
| Affiniteit | 0,30 | Hoe positief de genres/mechanieken/sferen van de game in je profiel wegen |
| Smaakassen | 0,25 | Afstand tot je voorkeuren (exploratie, gevecht, verhaal, vrijheid, strategie, co-op, moeilijkheid, builds) |
| Lijkt op favorieten | 0,25 | Overeenkomst met games die je leuk vond |
| Ontdekking | 0,20 | Aandeel genres dat je nog niet kent |
| Lijkt op afgewezen | -0,35 | Straf voor games die lijken op wat je afwees |

Eerlijkheid: met minder dan 2 aanwijzingen toont de app *"Nog te weinig gegevens"*; met minder dan 5 een kwalitatief oordeel zonder percentage. Het NextGame-deck is gediversifieerd (geen reeks van hetzelfde genre) en elke vierde kaart is een bewuste ontdekking.

## Documentatie

- [BUILDING.md](BUILDING.md): lokaal bouwen en testen
- [RELEASING.md](RELEASING.md): signing key, GitHub Release publiceren
- [UPDATER.md](UPDATER.md): hoe de ingebouwde updater werkt
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md): problemen oplossen

## Een databron toevoegen

De datalaag is vervangbaar via de interface `GameSource` (`data/GameRepository.kt`):

```kotlin
class MijnBron : GameSource {
    override val name = "Mijn bron"
    override suspend fun load(): List<Game> = /* lees uit een toegestane bron */
}
```

1. Implementeer `GameSource` en geef elke `Game` een `Provenance(source = "...", checkedAtMillis = <tijdstip van controle>)`.
2. Registreer de bron in `AppContainer.repository` (`listOf(SeedGameSource(), MijnBron())`).
3. Of sla opgehaalde games op met `GameRepository.saveCachedGames(games, source)`; die worden in de database bewaard (met herkomst en controletijdstip) en overschrijven games met dezelfde `id`.

Regels: gebruik alleen bronnen waarvan de voorwaarden dat toestaan, geen Steam API-key, geen scraping zonder rekening te houden met de gebruiksvoorwaarden, en zet onbekende velden op `null` in plaats van een gok (`priceCents = null` is *onbekend*, `0` is *gratis*). De kernfunctionaliteit mag nooit van netwerktoegang afhangen.

## Beperkingen

- De catalogus is een demo; er zijn geen echte covers of screenshots (er wordt een kleurvlak met initialen getoond).
- Er is geen live prijs-, review- of releasegegevensbron gekoppeld.
- Updates kunnen alleen worden geïnstalleerd als de nieuwe APK met dezelfde ondertekeningssleutel is gemaakt (zie UPDATER.md).
- Een crash kan niet altijd automatisch worden hersteld; de crashpagina biedt dan veilige keuzes.
