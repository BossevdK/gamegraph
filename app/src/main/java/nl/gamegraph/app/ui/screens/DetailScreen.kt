package nl.gamegraph.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import nl.gamegraph.app.AppState
import nl.gamegraph.app.AppViewModel
import nl.gamegraph.app.domain.Axis
import nl.gamegraph.app.domain.GameSimilarity
import nl.gamegraph.app.domain.Reaction
import nl.gamegraph.app.ui.CenteredMessage
import nl.gamegraph.app.ui.GameCover
import nl.gamegraph.app.ui.GameTile
import nl.gamegraph.app.ui.LoadingState
import nl.gamegraph.app.ui.SectionHeader
import nl.gamegraph.app.ui.TagRow
import nl.gamegraph.app.ui.formatDateTime
import nl.gamegraph.app.ui.priceText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: String, state: AppState, vm: AppViewModel, onBack: () -> Unit, onOpenGame: (String) -> Unit) {
    if (state.loading) return LoadingState()
    val game = state.gamesById[id]
    val context = LocalContext.current
    LaunchedEffect(id) { vm.markViewed(id) }

    if (game == null) {
        CenteredMessage(Icons.Filled.StarBorder, "Game niet gevonden", "Deze game staat niet meer in de catalogus.") {
            OutlinedButton(onClick = onBack) { Text("Terug") }
        }
        return
    }
    val user = state.userOf(id)
    val related = remember(game, state.games) {
        state.games.filter { it.id != game.id }
            .map { it to GameSimilarity.compare(game, it) }
            .sortedWith(compareByDescending<Pair<nl.gamegraph.app.domain.Game, nl.gamegraph.app.domain.Similarity>> { it.second.score }.thenBy { it.first.title })
            .take(5)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(game.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
            actions = {
                IconButton(onClick = { vm.setFavorite(id, !user.favorite) }) {
                    Icon(if (user.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (user.favorite) "Verwijder uit favorieten" else "Toevoegen aan favorieten")
                }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GameCover(game, Modifier.fillMaxWidth(), height = 220.dp)
            Text(game.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                listOfNotNull(game.developer, game.releaseYear?.let { "Uitgebracht in $it" }).joinToString(" · ").ifEmpty { "Ontwikkelaar en releasedatum onbekend" },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(priceText(game) + if (game.priceCents == null) " — bekijk de winkelpagina voor de actuele prijs" else "", style = MaterialTheme.typography.titleMedium)
            TagRow(game.genres + game.mechanics + game.moods)
            Text(game.description, style = MaterialTheme.typography.bodyLarge)

            Text("Gameplaykenmerken", style = MaterialTheme.typography.titleMedium)
            Axis.entries.filter { game.axes[it] != null }.forEach { axis ->
                val v = game.axes.getValue(axis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(axis.label, style = MaterialTheme.typography.bodyMedium)
                    Text(levelWord(v), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (game.modes.isNotEmpty()) Text("Spelmodi: " + game.modes.joinToString(), style = MaterialTheme.typography.bodyMedium)
            if (game.platforms.isNotEmpty()) Text("Platforms: " + game.platforms.joinToString(), style = MaterialTheme.typography.bodyMedium)

            Text("Reviews", style = MaterialTheme.typography.titleMedium)
            Text(
                game.reviewSummary?.let { it + (game.reviewSource?.let { s -> " (bron: $s)" } ?: "") }
                    ?: "Geen reviewgegevens beschikbaar. De demo-catalogus bevat bewust geen verzonnen scores.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Screenshots", style = MaterialTheme.typography.titleMedium)
            Text("Geen screenshots beschikbaar in de demo-catalogus. Bekijk ze op de officiële winkelpagina.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Bron: ${game.provenance.source}" + (game.provenance.checkedAtMillis?.let { ", gecontroleerd op ${formatDateTime(it)}" } ?: ", laatste controle onbekend"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            game.storeUrl?.let { url ->
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, Modifier.fillMaxWidth()) {
                    Text("Zoek in de Steam-winkel")
                }
            }

            Text("Jouw mening", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(user.reaction == Reaction.LIKE, { vm.react(id, if (user.reaction == Reaction.LIKE) Reaction.NONE else Reaction.LIKE) },
                    { Text("Leuk") }, leadingIcon = { Icon(Icons.Filled.ThumbUp, null) })
                FilterChip(user.reaction == Reaction.DISLIKE, { vm.react(id, if (user.reaction == Reaction.DISLIKE) Reaction.NONE else Reaction.DISLIKE) },
                    { Text("Niet interessant") }, leadingIcon = { Icon(Icons.Filled.ThumbDown, null) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(user.wishlist, { vm.setWishlist(id, !user.wishlist) }, { Text("Wishlist") })
                FilterChip(user.saved, { vm.setSaved(id, !user.saved) }, { Text("Bewaard") })
            }
            Row {
                for (star in 1..5) {
                    val filled = (user.rating ?: 0) >= star
                    IconButton(onClick = { vm.setRating(id, if (user.rating == star) null else star) }) {
                        Icon(if (filled) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "Geef $star ${if (star == 1) "ster" else "sterren"}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            SectionHeader("Gerelateerde games", Modifier.padding(horizontal = 0.dp), subtitle = "Met de reden van de overeenkomst")
            LazyRow(contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(related, key = { it.first.id }) { (g, sim) ->
                    GameTile(g, onClick = { onOpenGame(g.id) }, caption = sim.reasons.firstOrNull()?.text() ?: "Beperkte overeenkomst")
                }
            }
        }
    }
}

private fun levelWord(v: Float) = when {
    v >= 0.8f -> "Zeer sterk"
    v >= 0.6f -> "Sterk"
    v >= 0.4f -> "Gemiddeld"
    v >= 0.2f -> "Beperkt"
    else -> "Nauwelijks"
}
