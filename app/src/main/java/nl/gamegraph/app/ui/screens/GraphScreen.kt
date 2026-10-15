package nl.gamegraph.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.gamegraph.app.AppState
import nl.gamegraph.app.domain.GraphBuilder
import nl.gamegraph.app.domain.GraphData
import nl.gamegraph.app.domain.LinkKind
import nl.gamegraph.app.domain.Recommender
import nl.gamegraph.app.ui.CenteredMessage
import nl.gamegraph.app.ui.GameCover
import nl.gamegraph.app.ui.LoadingState
import nl.gamegraph.app.ui.TagRow
import nl.gamegraph.app.ui.theme.GgColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

private fun dashFor(kind: LinkKind): PathEffect? = when (kind) {
    LinkKind.GENRE -> null
    LinkKind.MECHANIC -> PathEffect.dashPathEffect(floatArrayOf(18f, 10f))
    LinkKind.MOOD -> PathEffect.dashPathEffect(floatArrayOf(4f, 10f))
    LinkKind.DEVELOPER -> null
    LinkKind.STYLE -> PathEffect.dashPathEffect(floatArrayOf(18f, 8f, 4f, 8f))
}

@Composable
fun GraphScreen(state: AppState, onOpenGame: (String) -> Unit) {
    if (state.loading) return LoadingState()
    if (state.games.isEmpty()) {
        return CenteredMessage(
            Icons.Filled.BubbleChart, "Nog geen games in de graph",
            "Zodra er games beschikbaar zijn, verschijnen ze hier als netwerk. Controleer later of herstel de app via Instellingen.",
        )
    }

    var genre by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableIntStateOf(-1) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val shown = remember(state.games, genre) { state.games.filter { genre == null || genre in it.genres } }
    val graph: GraphData? by produceState<GraphData?>(null, shown, state.profile, state.user) {
        value = withContext(Dispatchers.Default) {
            GraphBuilder.build(shown, priority = { g ->
                (if (state.user[g.id]?.hasDecision == true) 2f else 0f) + Recommender.score(g, state.profile).score
            })
        }
        selected = -1
        scale = 1f
        offset = Offset.Zero
    }
    val data = graph

    Column(Modifier.fillMaxSize()) {
        Text("Discover Graph", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = genre == null, onClick = { genre = null }, label = { Text("Alle genres") }) }
            items(state.allGenres) { g -> FilterChip(selected = genre == g, onClick = { genre = if (genre == g) null else g }, label = { Text(g) }) }
        }
        Legend()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (data == null) {
                LoadingState()
            } else if (data.games.isEmpty()) {
                CenteredMessage(Icons.Filled.BubbleChart, "Geen games voor dit filter", "Kies een ander genre.")
            } else {
                GraphCanvas(
                    data = data, selected = selected, scale = scale, offset = offset,
                    onTransform = { newScale, newOffset -> scale = newScale; offset = newOffset },
                    onSelect = { selected = it },
                )
                Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    IconButton(onClick = { scale = min(5f, scale * 1.3f) }) { Icon(Icons.Filled.Add, contentDescription = "Inzoomen") }
                    IconButton(onClick = { scale = (scale / 1.3f).coerceAtLeast(0.5f) }) { Icon(Icons.Filled.Remove, contentDescription = "Uitzoomen") }
                    IconButton(onClick = { scale = 1f; offset = Offset.Zero }) { Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Centreren") }
                }
                if (data.hiddenCount > 0) {
                    Text(
                        "Toont de ${data.games.size} meest relevante van ${data.games.size + data.hiddenCount} games. Kies een genre om andere games te zien.",
                        Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (data != null && data.games.isNotEmpty()) {
            // Dezelfde selectie zonder gebaren, voor toegankelijkheid en gemak.
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(data.games.indices.toList()) { i ->
                    FilterChip(selected = selected == i, onClick = { selected = if (selected == i) -1 else i }, label = { Text(data.games[i].title) })
                }
            }
            DetailPanel(data, selected, onSelect = { selected = it }, onOpenGame = onOpenGame)
        }
    }
}

@Composable
private fun Legend() {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(LinkKind.entries) { kind ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(GgColors.link(kind)))
                Spacer(Modifier.width(6.dp))
                Text(kind.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun GraphCanvas(
    data: GraphData,
    selected: Int,
    scale: Float,
    offset: Offset,
    onTransform: (Float, Offset) -> Unit,
    onSelect: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val labelStyle = remember(onSurface) { TextStyle(color = onSurface, fontSize = 11.sp) }
    val neighbors = remember(data, selected) {
        if (selected < 0) emptySet() else data.neighbors(selected).map { data.other(it, selected) }.toSet()
    }
    val currentScale by rememberUpdatedStateCompat(scale)
    val currentOffset by rememberUpdatedStateCompat(offset)

    fun project(i: Int, w: Int, h: Int, s: Float, o: Offset): Offset {
        val base = min(w, h) / 2f * 0.82f * s
        return Offset(w / 2f + o.x + data.x[i] * base, h / 2f + o.y + data.y[i] * base)
    }

    Canvas(
        Modifier.fillMaxSize()
            .semantics { contentDescription = "Netwerkkaart met ${data.games.size} games en ${data.edges.size} verbindingen. Gebruik de lijst onder de kaart om een game te kiezen." }
            .pointerInput(data) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (currentScale * zoom).coerceIn(0.5f, 5f)
                    val real = newScale / currentScale
                    val c = Offset(centroid.x - size.width / 2f, centroid.y - size.height / 2f)
                    onTransform(newScale, c - (c - currentOffset) * real + pan)
                }
            }
            .pointerInput(data) {
                detectTapGestures { tap ->
                    val reach = with(density) { 28.dp.toPx() }
                    var best = -1
                    var bestDist = reach
                    for (i in data.games.indices) {
                        val p = project(i, size.width, size.height, currentScale, currentOffset)
                        val d = hypot(p.x - tap.x, p.y - tap.y)
                        if (d < bestDist) { bestDist = d; best = i }
                    }
                    onSelect(best)
                }
            },
    ) {
        val w = size.width.toInt()
        val h = size.height.toInt()
        for (e in data.edges) {
            val a = project(e.a, w, h, scale, offset)
            val b = project(e.b, w, h, scale, offset)
            val touches = selected >= 0 && (e.a == selected || e.b == selected)
            val dim = selected >= 0 && !touches
            drawLine(
                color = GgColors.link(e.kind).copy(alpha = if (dim) 0.12f else (0.35f + e.weight).coerceAtMost(0.95f)),
                start = a, end = b,
                strokeWidth = (if (touches) 3.5f else 1.5f + e.weight * 2f) * (if (e.kind == LinkKind.DEVELOPER) 1.8f else 1f),
                pathEffect = dashFor(e.kind),
            )
        }
        for (i in data.games.indices) {
            val p = project(i, w, h, scale, offset)
            val hue = (abs(data.games[i].genres.sorted().firstOrNull().hashCode()) % 360).toFloat()
            val isSel = i == selected
            val dim = selected >= 0 && !isSel && i !in neighbors
            val r = (if (isSel) 13.dp else 9.dp).toPx()
            drawCircle(Color.hsv(hue, 0.55f, 0.9f).copy(alpha = if (dim) 0.35f else 1f), radius = r, center = p)
            if (isSel) drawCircle(Color.White, radius = r + 3.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
            if (isSel || i in neighbors || scale >= 1.4f) {
                val layout = measurer.measure(data.games[i].title, labelStyle, maxLines = 1)
                drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y + r + 2.dp.toPx()))
            }
        }
    }
}

// rememberUpdatedState onder een korte naam, zodat de gebarenlambda's altijd de nieuwste waarde zien.
@Composable
private fun <T> rememberUpdatedStateCompat(value: T) = androidx.compose.runtime.rememberUpdatedState(value)

@Composable
private fun DetailPanel(data: GraphData, selected: Int, onSelect: (Int) -> Unit, onOpenGame: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
    ) {
        if (selected < 0) {
            Text("Tik op een game in de kaart of kies er een in de lijst om te zien waarom games verbonden zijn.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            return@Card
        }
        val game = data.games[selected]
        val links = data.neighbors(selected)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GameCover(game, Modifier.width(88.dp), height = 88.dp, corner = 16.dp)
                Column(Modifier.weight(1f)) {
                    Text(game.title, style = MaterialTheme.typography.titleLarge)
                    TagRow(game.genres + game.moods, max = 5)
                }
            }
            if (links.isEmpty()) {
                Text("Geen sterke verbindingen met andere games in deze weergave.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Waarom verbonden:", style = MaterialTheme.typography.labelLarge)
                links.take(3).forEach { e ->
                    val other = data.other(e, selected)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(other) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(GgColors.link(e.kind)))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(data.games[other].title, style = MaterialTheme.typography.titleMedium)
                            Text(e.similarity.explain(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { onOpenGame(game.id) }) { Text("Details bekijken") }
        }
    }
}
