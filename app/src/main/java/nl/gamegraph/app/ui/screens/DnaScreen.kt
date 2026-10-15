package nl.gamegraph.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.gamegraph.app.AppState
import nl.gamegraph.app.AppViewModel
import nl.gamegraph.app.domain.Axis
import nl.gamegraph.app.domain.Recommender
import nl.gamegraph.app.ui.LoadingState
import nl.gamegraph.app.ui.formatDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun DnaScreen(state: AppState, vm: AppViewModel, onSettings: () -> Unit) {
    if (state.loading) return LoadingState()
    var confirmReset by remember { mutableStateOf(false) }
    val profile = state.profile
    val values = Axis.entries.map { profile.axisPreference(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("My GameDNA", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (profile.evidence >= Recommender.MIN_EVIDENCE_FOR_LEVEL) "Gebaseerd op ${profile.evidence} aanwijzingen"
                    else "Nog te weinig gegevens: reageer op games of stel hieronder je voorkeuren in.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Instellingen") }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            RadarChart(values, Modifier.fillMaxWidth().height(300.dp).padding(8.dp))
        }

        Text("Pas je voorkeuren aan", style = MaterialTheme.typography.titleLarge)
        Text(
            "Stel in hoe belangrijk elk kenmerk voor je is. Wat je hier instelt weegt mee naast wat de app van je reacties leert.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Axis.entries.forEach { axis ->
            AxisRow(axis, manual = state.manualAxes[axis], effective = profile.axisPreference(axis), onSet = { vm.setAxis(axis, it) })
        }

        Text("Welke feedback telt mee", style = MaterialTheme.typography.titleLarge)
        val top = profile.contributions.sortedByDescending { abs(it.signal) }.take(8)
        if (top.isEmpty()) {
            Text("Nog geen feedback. Geef bij NextGame een duim omhoog of omlaag.", style = MaterialTheme.typography.bodyMedium)
        } else {
            top.forEach {
                Text(
                    "${if (it.signal > 0) "▲" else "▼"} ${it.game.title}: " + if (it.signal > 0) "trekt aanbevelingen hiernaartoe" else "duwt vergelijkbare games weg",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (state.events.isNotEmpty()) {
            Text("Laatste reacties", style = MaterialTheme.typography.titleMedium)
            state.events.take(10).forEach { e ->
                val title = state.gamesById[e.gameId]?.title ?: e.gameId
                Text("${e.type.label} · $title · ${formatDateTime(e.createdAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedButton(onClick = { confirmReset = true }, Modifier.fillMaxWidth()) { Text("Profiel en leerhistorie opnieuw instellen") }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Profiel opnieuw instellen?") },
            text = { Text("Je handmatige voorkeuren en de leerhistorie worden gewist. Je favorieten, wishlist en beoordelingen blijven in je Library staan, maar tellen niet meer mee voor je smaakprofiel.") },
            confirmButton = { TextButton(onClick = { vm.resetProfile(); confirmReset = false }) { Text("Opnieuw instellen") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annuleren") } },
        )
    }
}

@Composable
private fun AxisRow(axis: Axis, manual: Float?, effective: Float?, onSet: (Float?) -> Unit) {
    var local by remember(manual) { mutableStateOf(manual ?: effective ?: 0.5f) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(axis.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        manual != null -> "Ingesteld door jou: ${wordFor(manual)}"
                        effective != null -> "Geleerd: ${wordFor(effective)}"
                        else -> "Nog niet bekend"
                    },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (manual != null) TextButton(onClick = { onSet(null) }) { Text("Wissen") }
        }
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onSet(local) },
            modifier = Modifier.semantics { contentDescription = "${axis.label}: ${wordFor(local)}" },
        )
    }
}

private fun wordFor(v: Float) = when {
    v >= 0.8f -> "heel belangrijk"
    v >= 0.6f -> "belangrijk"
    v >= 0.4f -> "neutraal"
    v >= 0.2f -> "minder belangrijk"
    else -> "onbelangrijk"
}

@Composable
private fun RadarChart(values: List<Float?>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.primary
    val style = remember(onSurface) { TextStyle(color = onSurface, fontSize = 11.sp) }
    val description = Axis.entries.zip(values).joinToString("; ") { (a, v) -> "${a.label}: ${v?.let { wordFor(it) } ?: "onbekend"}" }
    Canvas(modifier.semantics { contentDescription = "Radardiagram van je smaakprofiel. $description" }) {
        val n = Axis.entries.size
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * 0.68f
        fun point(i: Int, f: Float): Offset {
            val angle = -Math.PI / 2 + 2 * Math.PI * i / n
            return Offset(center.x + (cos(angle) * radius * f).toFloat(), center.y + (sin(angle) * radius * f).toFloat())
        }
        for (ring in 1..4) {
            val path = Path()
            for (i in 0 until n) { val p = point(i, ring / 4f); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            path.close()
            drawPath(path, grid.copy(alpha = 0.5f), style = Stroke(1.dp.toPx()))
        }
        for (i in 0 until n) drawLine(grid.copy(alpha = 0.5f), center, point(i, 1f), 1.dp.toPx())
        val poly = Path()
        values.forEachIndexed { i, v ->
            val p = point(i, (v ?: 0f).coerceIn(0f, 1f).coerceAtLeast(0.03f))
            if (i == 0) poly.moveTo(p.x, p.y) else poly.lineTo(p.x, p.y)
        }
        poly.close()
        drawPath(poly, fill.copy(alpha = 0.3f))
        drawPath(poly, fill, style = Stroke(2.5.dp.toPx()))
        Axis.entries.forEachIndexed { i, axis ->
            val layout = measurer.measure(axis.label, style)
            val p = point(i, 1.18f)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
    }
}
