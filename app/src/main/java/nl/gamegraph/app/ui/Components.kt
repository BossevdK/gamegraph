package nl.gamegraph.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.gamegraph.app.domain.Game
import nl.gamegraph.app.domain.MatchLevel
import nl.gamegraph.app.domain.Recommendation
import kotlin.math.abs

/**
 * Cover van een game. De demo-catalogus bevat bewust geen afbeeldingen (geen rechten of netwerk nodig);
 * daarom wordt een kleurvlak met initialen en genre getoond.
 */
@Composable
fun GameCover(game: Game, modifier: Modifier = Modifier, height: Dp = 140.dp, corner: Dp = 20.dp) {
    val hue = (abs(game.id.hashCode()) % 360).toFloat()
    val brush = Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.55f), Color.hsv((hue + 50f) % 360f, 0.65f, 0.28f)))
    val initials = game.title.split(' ', ':', '-').filter { it.firstOrNull()?.isLetterOrDigit() == true }.take(2)
        .joinToString("") { it.first().uppercase() }
    Box(
        modifier.height(height).clip(RoundedCornerShape(corner)).background(brush)
            .semantics { contentDescription = "Cover van ${game.title} (kleurvlak, geen afbeelding)" },
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White.copy(alpha = 0.92f), fontSize = (height.value / 3).sp, fontWeight = FontWeight.Bold)
        Text(
            game.genres.sorted().firstOrNull().orEmpty(),
            color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(tags: Collection<String>, modifier: Modifier = Modifier, max: Int = Int.MAX_VALUE) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.take(max).forEach { SuggestionChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelMedium) }, enabled = false) }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Toont de overeenkomst. Zonder genoeg gegevens alleen tekst, nooit een verzonnen percentage. */
@Composable
fun MatchBadge(rec: Recommendation, modifier: Modifier = Modifier) {
    val color = when (rec.level) {
        MatchLevel.STRONG -> MaterialTheme.colorScheme.primary
        MatchLevel.GOOD -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    val text = rec.percent?.let { "$it% match · ${rec.level.label}" } ?: rec.level.label
    Text(
        text, color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun CenteredMessage(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(16.dp)); it() }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Laden" })
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    CenteredMessage(Icons.Filled.ErrorOutline, "Er ging iets mis", message, modifier) {
        Button(onClick = onRetry) { Text("Opnieuw proberen") }
    }
}

@Composable
fun OfflineNote(modifier: Modifier = Modifier, text: String = "Geen verbinding. GameGraph werkt gewoon offline verder; alleen updatecontrole en winkelpagina's hebben internet nodig.") {
    Row(modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Kleine kaart voor lijsten met games. */
@Composable
fun GameTile(game: Game, onClick: () -> Unit, modifier: Modifier = Modifier, caption: String? = null) {
    Column(modifier.width(150.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GameCover(game, Modifier.fillMaxWidth(), height = 110.dp)
        Text(game.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        caption?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun LabeledAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true) {
    if (filled) Button(onClick, modifier, enabled = enabled) { Text(label) }
    else OutlinedButton(onClick, modifier, enabled = enabled) { Text(label) }
}

@Composable
fun InfoChip(text: String) {
    AssistChip(onClick = {}, label = { Text(text) }, enabled = false)
}
