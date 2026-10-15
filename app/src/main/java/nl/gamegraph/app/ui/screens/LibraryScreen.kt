package nl.gamegraph.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.gamegraph.app.AppState
import nl.gamegraph.app.AppViewModel
import nl.gamegraph.app.domain.Game
import nl.gamegraph.app.domain.Reaction
import nl.gamegraph.app.domain.UserGame
import nl.gamegraph.app.ui.CenteredMessage
import nl.gamegraph.app.ui.GameCover
import nl.gamegraph.app.ui.LoadingState
import nl.gamegraph.app.ui.TagRow

private enum class LibraryTab(val label: String, val empty: String, val matches: (UserGame) -> Boolean) {
    FAVORITES("Favorieten", "Nog geen favorieten. Tik op het hartje bij een game.", { it.favorite }),
    WISHLIST("Wishlist", "Je wishlist is leeg.", { it.wishlist }),
    RATED("Beoordeeld", "Je hebt nog geen games beoordeeld.", { it.rating != null }),
    SAVED("Bewaard", "Je hebt nog niets bewaard voor later.", { it.saved }),
    REJECTED("Afgewezen", "Je hebt nog geen games afgewezen.", { it.reaction == Reaction.DISLIKE }),
}

private enum class LibrarySort(val label: String) { NAME("Naam"), RECENT("Recent"), RATING("Beoordeling") }

@Composable
fun LibraryScreen(state: AppState, vm: AppViewModel, onOpenGame: (String) -> Unit) {
    if (state.loading) return LoadingState()
    var tab by remember { mutableStateOf(LibraryTab.FAVORITES) }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    var query by remember { mutableStateOf("") }

    val items = remember(state.user, state.gamesById, tab, sort, query) {
        state.user.values.filter { tab.matches(it) }
            .mapNotNull { u -> state.gamesById[u.gameId]?.let { it to u } }
            .filter { (g, _) -> query.isBlank() || g.title.contains(query, ignoreCase = true) || g.genres.any { it.contains(query, ignoreCase = true) } }
            .let { list ->
                when (sort) {
                    LibrarySort.NAME -> list.sortedBy { it.first.title }
                    LibrarySort.RECENT -> list.sortedByDescending { it.second.updatedAt }
                    LibrarySort.RATING -> list.sortedWith(compareByDescending<Pair<Game, UserGame>> { it.second.rating ?: 0 }.thenBy { it.first.title })
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.background) {
            LibraryTab.entries.forEach { t ->
                val count = state.user.values.count { t.matches(it) }
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text("${t.label} ($count)") })
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Zoek op titel of genre") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibrarySort.entries) { s -> FilterChip(sort == s, { sort = s }, { Text(s.label) }) }
        }
        if (items.isEmpty()) {
            CenteredMessage(Icons.Filled.VideogameAsset, if (query.isBlank()) "Niets te zien" else "Geen resultaten", if (query.isBlank()) tab.empty else "Probeer een andere zoekterm.")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.first.id }) { (game, user) ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenGame(game.id) }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GameCover(game, Modifier.width(96.dp), height = 72.dp, corner = 14.dp)
                        Column(Modifier.weight(1f)) {
                            Text(game.title, style = MaterialTheme.typography.titleMedium)
                            TagRow(game.genres, max = 2)
                            user.rating?.let { Text("★".repeat(it) + "☆".repeat(5 - it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        }
                        if (tab == LibraryTab.REJECTED) TextButton(onClick = { vm.react(game.id, Reaction.NONE) }) { Text("Herstel") }
                    }
                }
            }
        }
    }
}
