package nl.gamegraph.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.gamegraph.app.AppState
import nl.gamegraph.app.AppViewModel
import nl.gamegraph.app.domain.Axis
import nl.gamegraph.app.domain.DeckFilter
import nl.gamegraph.app.domain.PlayStyle
import nl.gamegraph.app.domain.PriceFilter
import nl.gamegraph.app.domain.Reaction
import nl.gamegraph.app.domain.Recommendation
import nl.gamegraph.app.ui.CenteredMessage
import nl.gamegraph.app.ui.ErrorState
import nl.gamegraph.app.ui.GameCover
import nl.gamegraph.app.ui.LoadingState
import nl.gamegraph.app.ui.MatchBadge
import nl.gamegraph.app.ui.TagRow
import nl.gamegraph.app.ui.priceText

@Composable
fun NextGameScreen(state: AppState, vm: AppViewModel, onOpenGame: (String) -> Unit) {
    if (state.loading) return LoadingState()
    state.loadError?.let { return ErrorState(it, onRetry = vm::retryLoad) }
    var showFilters by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NextGame", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (state.filter.isActive) "Filters actief" else "Swipe of gebruik de knoppen",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showFilters = true }) { Icon(Icons.Filled.FilterList, contentDescription = "Filters") }
        }
        val rec = state.deck.firstOrNull()
        if (rec == null) {
            if (state.filter.isActive) {
                CenteredMessage(Icons.Filled.AutoAwesome, "Geen games voor deze filters", "Pas de filters aan om meer games te zien.") {
                    TextButton(onClick = { vm.setFilter(DeckFilter()) }) { Text("Filters wissen") }
                }
            } else {
                CenteredMessage(
                    Icons.Filled.AutoAwesome, "Je hebt alles gezien",
                    "Er zijn geen nieuwe games meer in de demo-catalogus. Bekijk je Library of stel je profiel opnieuw in via Profiel.",
                )
            }
        } else {
            DeckCard(
                rec, state,
                onLike = { vm.react(rec.game.id, Reaction.LIKE) },
                onDislike = { vm.react(rec.game.id, Reaction.DISLIKE) },
                onSave = { vm.setSaved(rec.game.id, true) },
                onDetails = { onOpenGame(rec.game.id) },
            )
        }
    }
    if (showFilters) FilterDialog(state, onApply = { vm.setFilter(it); showFilters = false }, onDismiss = { showFilters = false })
}

@Composable
private fun DeckCard(rec: Recommendation, state: AppState, onLike: () -> Unit, onDislike: () -> Unit, onSave: () -> Unit, onDetails: () -> Unit) {
    val game = rec.game
    val scope = rememberCoroutineScope()
    val offsetX = remember(game.id) { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 110.dp.toPx() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Card(
                Modifier.fillMaxSize()
                    .graphicsLayer { translationX = offsetX.value; rotationZ = offsetX.value / 40f }
                    .pointerInput(game.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    when {
                                        offsetX.value > threshold -> { offsetX.animateTo(1600f, tween(180)); onLike() }
                                        offsetX.value < -threshold -> { offsetX.animateTo(-1600f, tween(180)); onDislike() }
                                        else -> offsetX.animateTo(0f, tween(150))
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(150)) } },
                        ) { change, drag ->
                            change.consume()
                            scope.launch { offsetX.snapTo(offsetX.value + drag) }
                        }
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Box {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GameCover(game, Modifier.fillMaxWidth(), height = 200.dp, corner = 20.dp)
                        Text(game.title, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            listOfNotNull(game.developer, game.releaseYear?.toString()).joinToString(" · ") + "  ·  " + priceText(game),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MatchBadge(rec)
                        TagRow(game.genres + game.moods)
                        Text(game.description, style = MaterialTheme.typography.bodyLarge)
                        val traits = Axis.entries.filter { (game.axes[it] ?: 0f) >= 0.7f }
                        if (traits.isNotEmpty()) Text("Kenmerken: " + traits.joinToString { it.label }, style = MaterialTheme.typography.bodyMedium)
                        if (game.modes.isNotEmpty()) Text("Spelmodi: " + game.modes.joinToString(), style = MaterialTheme.typography.bodyMedium)
                        Text("Waarom deze game?", style = MaterialTheme.typography.titleMedium)
                        rec.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                    val progress = (offsetX.value / threshold).coerceIn(-1f, 1f)
                    if (progress > 0.15f) Text("LEUK", Modifier.align(Alignment.TopStart).padding(20.dp).graphicsLayer { alpha = progress }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
                    if (progress < -0.15f) Text("NIET", Modifier.align(Alignment.TopEnd).padding(20.dp).graphicsLayer { alpha = -progress }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        // Dezelfde acties als knoppen: werkt zonder swipen (toegankelijkheid).
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionButton(Icons.Filled.Close, "Niet interessant") { scope.launch { offsetX.animateTo(-1600f, tween(150)); onDislike() } }
            ActionButton(Icons.Filled.Bookmark, "Bewaren") { onSave() }
            ActionButton(Icons.Filled.Info, "Details", onDetails)
            ActionButton(Icons.Filled.ThumbUp, "Leuk") { scope.launch { offsetX.animateTo(1600f, tween(150)); onLike() } }
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.padding(4.dp)) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDialog(state: AppState, onApply: (DeckFilter) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(state.filter) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filters") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Genre", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(draft.genre == null, { draft = draft.copy(genre = null) }, { Text("Alle") })
                    state.allGenres.forEach { g -> FilterChip(draft.genre == g, { draft = draft.copy(genre = g) }, { Text(g) }) }
                }
                Text("Platform", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(draft.platform == null, { draft = draft.copy(platform = null) }, { Text("Alle") })
                    state.allPlatforms.forEach { p -> FilterChip(draft.platform == p, { draft = draft.copy(platform = p) }, { Text(p) }) }
                }
                Text("Prijs", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PriceFilter.entries.forEach { p -> FilterChip(draft.price == p, { draft = draft.copy(price = p) }, { Text(p.label) }) }
                }
                Text("Speelstijl", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayStyle.entries.forEach { s -> FilterChip(draft.playStyle == s, { draft = draft.copy(playStyle = s) }, { Text(s.label) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("Toepassen") } },
        dismissButton = { TextButton(onClick = { onApply(DeckFilter()) }) { Text("Wissen") } },
    )
}
