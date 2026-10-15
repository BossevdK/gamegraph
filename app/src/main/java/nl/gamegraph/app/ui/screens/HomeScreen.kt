package nl.gamegraph.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import nl.gamegraph.app.AppState
import nl.gamegraph.app.AppViewModel
import nl.gamegraph.app.domain.Game
import nl.gamegraph.app.domain.Recommendation
import nl.gamegraph.app.domain.Recommender
import nl.gamegraph.app.ui.ErrorState
import nl.gamegraph.app.ui.GameTile
import nl.gamegraph.app.ui.LoadingState
import nl.gamegraph.app.ui.SectionHeader

@Composable
fun HomeScreen(
    state: AppState,
    vm: AppViewModel,
    onOpenGame: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onSettings: () -> Unit,
) {
    if (state.loading) return LoadingState()
    state.loadError?.let { return ErrorState(it, onRetry = vm::retryLoad) }

    val personalised = state.profile.evidence >= Recommender.MIN_EVIDENCE_FOR_LEVEL
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GameGraph", style = MaterialTheme.typography.headlineMedium)
                    Text("Ontdek games die echt bij je passen", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Instellingen") }
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickLink("Graph", Icons.Filled.BubbleChart, Modifier.weight(1f)) { onNavigate("graph") }
                QuickLink("NextGame", Icons.Filled.AutoAwesome, Modifier.weight(1f)) { onNavigate("next") }
                QuickLink("Library", Icons.Filled.VideogameAsset, Modifier.weight(1f)) { onNavigate("library") }
                QuickLink("Profiel", Icons.Filled.Person, Modifier.weight(1f)) { onNavigate("dna") }
            }
        }
        item {
            Card(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    "Je ziet nu een kleine demo-catalogus van ${state.games.size} games. Die is niet compleet of actueel; prijzen en reviews zijn bewust leeg gelaten.",
                    Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (!personalised) {
            item {
                Card(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    onClick = { onNavigate("next") },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Leer GameGraph kennen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Geef bij NextGame een paar keer een duim omhoog of omlaag. Daarna worden de aanbevelingen persoonlijk.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth()) { SectionHeader(if (personalised) "Past bij jouw profiel" else "Om mee te beginnen", subtitle = if (personalised) null else "Gevarieerde selectie") } }
        item { GameRow(state.forYou, onOpenGame, state) }
        item { SectionHeader("Nieuwe ontdekkingen", subtitle = "Buiten je bekende voorkeuren") }
        item { GameRow(state.discoveries, onOpenGame, state) }
        if (state.recent.isNotEmpty()) {
            item { SectionHeader("Recent bekeken") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.recent, key = { it.id }) { g -> GameTile(g, onClick = { onOpenGame(g.id) }, caption = g.genres.sorted().joinToString()) }
                }
            }
        }
        if (state.forYou.isEmpty() && state.discoveries.isEmpty()) {
            item { Text("Je hebt alle games in de demo-catalogus beoordeeld. Bekijk je Library of stel je profiel opnieuw in.", Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun GameRow(recs: List<Recommendation>, onOpen: (String) -> Unit, state: AppState) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(recs, key = { it.game.id }) { rec ->
            val caption = rec.percent?.let { "$it% · ${rec.level.label}" } ?: rec.reasons.firstOrNull()
            GameTile(rec.game, onClick = { onOpen(rec.game.id) }, caption = caption)
        }
    }
}

@Composable
private fun QuickLink(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
